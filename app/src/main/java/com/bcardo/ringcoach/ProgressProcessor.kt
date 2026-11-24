package com.bcardo.ringcoach

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Runs exclusively under ProgressRuntime.mutex. Every committed stage can be resumed independently. */
internal class ProgressProcessor(
    private val context: Context,
    private val database: String = "ringcoach.db",
    private val credential: () -> String? = { QwenCredentials(context).read() },
    private val post: (JSONObject, String) -> String = QwenClient::post,
    private val health: (suspend (LocalDate, JournalStore) -> JSONObject?)? = null,
) {
    suspend fun run(): Boolean = JournalStore(context, database).use { store ->
        val work = ProgressWorkStore(store.writableDatabase)
        var task = ""
        var date: LocalDate? = null
        try {
            if (!work.hasWork()) { work.stage("idle"); return@use false }
            val key = credential() ?: run { work.stage("blocked", error = "credential_missing"); return@use false }
            work.resetTransientFailures()
            while (true) {
                currentCoroutineContext().ensureActive()
                val job = store.nextPendingNutrition() ?: break
                task = "nutrition:${job.turn?.id ?: job.revision}"; date = job.date
                if (job.turn?.status == "failed") { work.stage("blocked", date, error = work.error(task) ?: "nutrition_failed"); return@use false }
                work.stage("recognizing_intake", date)
                try {
                    val result = NutritionExtractor.extract(job, asset("nutrition-extraction.txt"), store.nutritionPhotos(job), key, post)
                    currentCoroutineContext().ensureActive()
                    if (store.applyNutritionExtraction(job, result) && job.turn == null) store.releaseConfirmedLegacyPhotos()
                    work.clearRetry(task); ProgressRuntime.changed()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { store.failNutritionExtraction(job); throw e }
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val turns = IncrementalWorkoutStore(store.writableDatabase)
                val turn = turns.next() ?: break
                task = "workout:${turn.id}"; date = turn.date
                if (turn.status == "failed") {
                    work.stage("blocked", date, error = work.error(task) ?: "workout_failed"); return@use false
                }
                work.stage("recognizing_workout", date)
                try {
                    val baselines = store.readCoachingContext(turn.date, "").exercises
                    val result = WorkoutDeltaExtractor.extract(turn, baselines, asset("workout-delta.txt"), key, post) { code, attempt, outcome ->
                        if (database == "ringcoach.db") recordFailure("workout", code, attempt, outcome, WorkoutDeltaExtractor.VERSION)
                    }
                    currentCoroutineContext().ensureActive()
                    turns.apply(turn, result); work.clearRetry(task); ProgressRuntime.changed()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { turns.fail(turn); throw e }
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val job = store.nextPendingWorkout(includeFailed = true) ?: break
                task = "workout:${job.revision}"; date = job.date
                if (job.status == "failed") {
                    work.stage("blocked", date, error = work.error(task) ?: "workout_failed"); return@use false
                }
                work.stage("recognizing_workout", date)
                try {
                    val baselines = store.readCoachingContext(job.date, "").exercises
                    val result = WorkoutExtractor.extract(job, baselines, asset("workout-extraction.txt"), key, post) { code, attempt, outcome ->
                        if (database == "ringcoach.db") recordFailure("workout", code, attempt, outcome)
                    }
                    currentCoroutineContext().ensureActive()
                    store.applyWorkoutExtraction(job, result); work.clearRetry(task); ProgressRuntime.changed()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { store.failWorkoutExtraction(job); throw e }
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val pending = store.nextPendingCoaching() ?: break
                task = "coach:${pending.id}"; date = pending.date
                if (work.error(task) != null && !work.canRetry(task)) {
                    work.stage("blocked", date, pending.id, work.error(task)); return@use false
                }
                // Incomplete/failed extraction must never be reported as a successful coach update.
                val data = try { store.readyCoachingContext(pending.date, asset("coaching-policy.md")).toJson() }
                catch (_: IllegalArgumentException) { work.stage("blocked", date, pending.id, "extraction_incomplete"); return@use false }
                var telemetry = if (health != null) health.invoke(pending.date, store)
                    else ProgressHealth.forJob(context, database, pending.date, store)
                if (telemetry == null && database == "ringcoach.db") {
                    val ringAvailable = RingBluetoothStore(context).use { it.snapshot(pending.date).optInt("target_date_session_count") > 0 }
                    if (ringAvailable) telemetry = JSONObject().put("status", "unavailable").put("capture_mode", "direct_ring_statistics_only")
                }
                if (telemetry == null) { work.stage("waiting_health", date, pending.id); return@use false }
                work.stage("coaching", date, pending.id)
                data.put("request_timezone", ZoneId.systemDefault().id)
                    .put("progress_submission_id", pending.id)
                    .put("progress_submission", requireNotNull(store.progressSubmission(pending.id)))
                    .put("nutrition_turn_ids", JSONArray(store.incrementalSubmissionIds(pending.date, completedOnly = true)))
                    .put("telemetry_status", when {
                        telemetry.optString("status") == "unavailable" -> "unavailable"
                        telemetry.optString("capture_mode") == "retained_statistics" -> "retained_capture"
                        else -> "checked"
                    })
                    .put("telemetry", telemetry)
                store.latestCoachCard(pending.date)?.let { data.put("previous_advice", it.advice.toJson()) }
                // Test databases never read the owner's Samsung data or recovery archive.
                if (database == "ringcoach.db") {
                    RingBluetoothStore(context).use { data.put("ring_bluetooth", it.snapshot(pending.date)) }
                    data.put("nightly_recovery", SamsungNightlyReader.refresh(context, pending.date))
                    NightlyRecoveryStore(context).use { history -> data.put("recovery_history",
                        CoachingRecoveryHistory.compact(history.history(pending.date.minusDays(30), pending.date.minusDays(1)), pending.date)) }
                }
                val packaged = CoachingRequestPackager.prepare(data, asset("coach-system.txt"), onSize = { size -> if (database == "ringcoach.db") CoachingRequestSizeStore.record(context, size) })
                currentCoroutineContext().ensureActive()
                val advice = QwenClient.response(post(packaged.request, key))
                currentCoroutineContext().ensureActive()
                store.saveCoachCard(SavedCoachCard(pending.id, pending.date, Instant.now(), QWEN_MODEL,
                    COACH_PROMPT_VERSION, packaged.retainedContext.toString(), advice))
                if (database == "ringcoach.db") RecoveryReliability.coached(context, pending.date, packaged.retainedContext)
                work.clearRetry(task); ProgressRuntime.changed()
            }
            work.stage("complete")
            if (database == "ringcoach.db") RingMorningJob.schedule(context)
            false
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val code = when (e) {
                is QwenHttpException -> when (e.status) { 401, 403 -> "provider_access"; 429 -> "provider_quota"; in 500..599 -> "provider_temporary"; else -> "provider_request" }
                is IOException -> "network"
                is CoachingPayloadTooLarge -> "context_too_large"
                is WorkoutValidationException -> "workout_${e.code}"
                is NutritionValidationException -> "nutrition_${e.code}"
                else -> "invalid_response"
            }
            val attempts = work.failure(task, code)
            val retry = attempts < 3 && code in setOf("network", "provider_quota", "provider_temporary")
            work.stage(if (retry) "retry_scheduled" else "blocked", date, error = code)
            if (database == "ringcoach.db" && e !is WorkoutValidationException)
                recordFailure(task.substringBefore(':').takeIf { it in setOf("nutrition", "workout", "coach") } ?: "setup",
                    code, attempts, if (retry) "retry_scheduled" else "blocked")
            retry
        } finally { ProgressHealth.forget(database) }
    }

    private fun asset(name: String): String = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun recordFailure(stage: String, code: String, attempt: Int, outcome: String, prompt: String? = null) {
        try { ProcessingFailureStore(context).use { it.record(stage, code, attempt, outcome, prompt) } }
        catch (_: Exception) { /* Diagnostics must never block or replace the processing result. */ }
    }
}
