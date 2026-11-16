package com.bcardo.ringcoach

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One persisted morning advice request per new wake date; never invents a journal submission. */
internal class RingMorningProcessor(
    private val context: Context,
    private val ringDatabase: String = RingBluetoothStore.DATABASE,
    private val journalDatabase: String = "ringcoach.db",
    private val credential: () -> String? = { QwenCredentials(context).read() },
    private val post: (JSONObject, String) -> String = QwenClient::post,
) {
    suspend fun run(today: LocalDate = LocalDate.now()): Boolean = RingBluetoothStore(context, ringDatabase).use { ring ->
        if (!ring.enabled()) return@use false
        val date = ring.nextMorning(today) ?: return@use false
        val id = "ring-morning-$date"
        JournalStore(context, journalDatabase).use { journal ->
            if (journal.coachCard(id) != null) { ring.morningStatus(date, "complete"); return@use false }
            val key = credential() ?: run { ring.morningStatus(date, "credential_missing"); return@use false }
            val data = try { journal.readyCoachingContext(date, asset("coaching-policy.md")).toJson() }
            catch (_: IllegalArgumentException) { ring.morningStatus(date, "waiting_progress"); return@use true }
            try {
                val bluetooth = ring.snapshot(date)
                if (bluetooth.optInt("target_date_session_count") == 0) {
                    ring.morningStatus(date, "blocked", "target_sleep_missing"); return@use false
                }
                data.put("request_timezone", ZoneId.systemDefault().id)
                    .put("request_at", Instant.now().toString())
                    .put("coaching_trigger", if (bluetooth.getJSONArray("sessions").let { sessions ->
                        (0 until sessions.length()).map { sessions.getJSONObject(it) }.filter { it.optString("wake_date") == date.toString() }
                            .all { !it.getJSONArray("capture_origins").toString().contains("live_bluetooth") }
                    }) "preserved_ring_sleep_capture" else "new_ring_sleep_transfer")
                    .put("ring_bluetooth", bluetooth)
                    .put("telemetry_status", "unavailable")
                // No UI navigation or unlock is needed for morning advice.
                if (journalDatabase == "ringcoach.db") {
                    val telemetry = ProgressHealth.forJob(context, journalDatabase, date, journal)
                    if (telemetry != null) data.put("telemetry", telemetry).put("telemetry_status", "checked")
                    data.put("nightly_recovery", SamsungNightlyReader.refresh(context, date)
                        .put("screen_reader_configured", SamsungHealthScreenReader.configured(context))
                        .put("screen_reader_connected", SamsungHealthScreenReader.enabled))
                    NightlyRecoveryStore(context).use { history -> data.put("recovery_history",
                        CoachingRecoveryHistory.compact(history.history(date.minusDays(30), date.minusDays(1)), date)) }
                }
                journal.latestCoachCard(date)?.let { data.put("previous_advice", it.advice.toJson()) }
                val packaged = CoachingRequestPackager.prepare(data, asset("coach-system.txt"))
                currentCoroutineContext().ensureActive()
                ring.morningStatus(date, "coaching")
                val advice = QwenClient.response(post(packaged.request, key))
                currentCoroutineContext().ensureActive()
                journal.saveCoachCard(SavedCoachCard(id, date, Instant.now(), QWEN_MODEL, COACH_PROMPT_VERSION,
                    packaged.retainedContext.toString(), advice))
                ring.morningStatus(date, "complete"); ProgressRuntime.changed()
                false
            } catch (e: CancellationException) { ring.morningStatus(date, "pending"); throw e }
            catch (e: Exception) {
                val retry = e is IOException || e is QwenHttpException && (e.status == 429 || e.status >= 500)
                val code = when (e) {
                    is IOException -> "network"
                    is QwenHttpException -> "provider_${e.status}"
                    is CoachingPayloadTooLarge -> "context_too_large"
                    else -> "invalid_response_or_storage"
                }
                ring.morningStatus(date, if (retry) "retry" else "blocked", code, increment = true)
                val remaining = ring.morningState(date).optInt("attempts") < 3
                if (!remaining) ring.morningStatus(date, "blocked", code)
                retry && remaining
            }
        }
    }
    private fun asset(name: String) = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}

class RingMorningJob : JobService() {
    private var scope: CoroutineScope? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val task = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = task
        task.launch {
            var retry = false
            try { ProgressRuntime.mutex.withLock { retry = RingMorningProcessor(applicationContext).run() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { retry = true }
            finally { if (isActive) jobFinished(params, retry); task.cancel() }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { scope?.cancel(); return true }
    override fun onDestroy() { scope?.cancel(); super.onDestroy() }
    companion object {
        const val JOB_ID = 9302
        fun schedule(context: Context) {
            val pending = RingBluetoothStore(context).use { it.enabled() && it.nextMorning() != null }
            if (!pending) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(JobInfo.Builder(JOB_ID,
                ComponentName(context, RingMorningJob::class.java)).setMinimumLatency(120000)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(900000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true).build())
        }
    }
}
