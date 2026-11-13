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
                val job = store.nextPendingWorkout() ?: break
                task = "workout:${job.revision}"; date = job.date
                work.stage("recognizing_workout", date)
                try {
                    val baselines = store.readCoachingContext(job.date, "").exercises
                    val response = post(WorkoutExtractor.request(job, baselines, asset("workout-extraction.txt")), key)
                    val result = WorkoutExtractor.decode(QwenClient.responseText(response), job.training, baselines.map { it.exercise }.toSet())
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
                val telemetry = if (health != null) health.invoke(pending.date, store)
                    else ProgressHealth.forJob(context, database, pending.date, store)
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
                    SamsungHealthScreenReader.awaitActiveCapture()
                    data.put("nightly_recovery", SamsungNightlyReader.refresh(context, pending.date)
                        .put("screen_reader_configured", SamsungHealthScreenReader.configured(context))
                        .put("screen_reader_connected", SamsungHealthScreenReader.enabled))
                    NightlyRecoveryStore(context).use { history -> data.put("recovery_history",
                        CoachingRecoveryHistory.compact(history.history(pending.date.minusDays(30), pending.date.minusDays(1)), pending.date)) }
                }
                val packaged = CoachingRequestPackager.prepare(data, asset("coach-system.txt"))
                currentCoroutineContext().ensureActive()
                val advice = QwenClient.response(post(packaged.request, key))
                currentCoroutineContext().ensureActive()
                store.saveCoachCard(SavedCoachCard(pending.id, pending.date, Instant.now(), QWEN_MODEL,
                    COACH_PROMPT_VERSION, packaged.retainedContext.toString(), advice))
                if (database == "ringcoach.db") RecoveryReliability.coached(context, pending.date, packaged.retainedContext)
                work.clearRetry(task); ProgressRuntime.changed()
            }
            work.stage("complete")
            false
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val code = when (e) {
                is QwenHttpException -> when (e.status) { 401, 403 -> "provider_access"; 429 -> "provider_quota"; in 500..599 -> "provider_temporary"; else -> "provider_request" }
                is IOException -> "network"
                is CoachingPayloadTooLarge -> "context_too_large"
                else -> "invalid_response"
            }
            val attempts = work.failure(task, code)
            val retry = attempts < 3 && code in setOf("network", "provider_quota", "provider_temporary")
            work.stage(if (retry) "retry_scheduled" else "blocked", date, error = code)
            retry
        } finally { ProgressHealth.forget(database) }
    }

    private fun asset(name: String): String = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
