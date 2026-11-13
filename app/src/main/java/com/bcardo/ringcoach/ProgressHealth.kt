package com.bcardo.ringcoach

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/** Only permitted summaries survive a restart; raw samples are short-lived processing memory. */
internal object ProgressHealth {
    private data class Capture(val at: Instant, val telemetry: JSONObject)
    private val memory = mutableMapOf<String, Capture>()
    private fun key(database: String, date: LocalDate) = "$database:$date"

    @Synchronized fun remember(database: String, date: LocalDate, telemetry: JSONObject) {
        memory.entries.removeAll { it.value.at < Instant.now().minusSeconds(300) }
        memory[key(database, date)] = Capture(Instant.now(), JSONObject(telemetry.toString()))
    }
    @Synchronized fun forget(database: String) { memory.keys.removeAll { it.startsWith("$database:") } }
    @Synchronized private fun take(database: String, date: LocalDate): JSONObject? {
        val capture = memory.remove(key(database, date)) ?: return null
        return capture.telemetry.takeIf { capture.at >= Instant.now().minusSeconds(300) }
    }

    fun retained(telemetry: JSONObject): JSONObject {
        val copy = CoachingSnapshot.retained(telemetry)
        if (!copy.has("captured_at")) copy.put("captured_at", Instant.now().toString())
        copy.put("capture_mode", "retained_statistics")
        copy.optJSONObject("current_night")?.let { night ->
            val counts = night.optJSONObject("coverage")
            val available = counts?.optInt("heart_rate_statistic_sample_count", counts.optInt("heart_rate_sample_count")) ?: 0
            counts?.put("heart_rate_statistic_sample_count", available)?.put("heart_rate_sample_count", 0)
            night.put("trace_mode", "retained_statistics_only")
        }
        return copy
    }

    suspend fun forJob(context: Context, database: String, date: LocalDate, store: JournalStore): JSONObject? {
        take(database, date)?.let { return it }
        if (database == "ringcoach.db" && HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) try {
            val probe = HealthConnectProbe(HealthConnectClient.getOrCreate(context))
            if (probe.hasBackgroundReadAccess()) {
                val telemetry = probe.coachingSnapshot(date).put("captured_at", Instant.now().toString()).put("capture_mode", "background_live")
                ProgressWorkStore(store.writableDatabase).putHealth(date, telemetry)
                return telemetry
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Permission/provider changes fall back to the dated, redacted capture. */ }
        return ProgressWorkStore(store.readableDatabase).health(date)
    }
}
