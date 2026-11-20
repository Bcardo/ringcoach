package com.bcardo.ringcoach

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A read-only presentation of saved advice, never a new measurement or a plan mutation. */
internal data class DailyDecision(
    val card: SavedCoachCard?,
    val focus: String?,
    val reasons: List<String>,
    val action: String?,
    val awaitingLatest: Boolean,
)

internal fun dailyDecision(date: LocalDate, feedback: DailyCoaching?): DailyDecision {
    val current = feedback?.takeIf { it.date == date }
    val card = current?.card?.takeIf { it.date == date }
    val notes = card?.advice?.notes.orEmpty().filter(String::isNotBlank)
    return DailyDecision(card, notes.firstOrNull(), notes.drop(1).take(2),
        card?.advice?.training?.firstOrNull { it.isNotBlank() }, current?.pending != null)
}

internal data class NightRecord(
    val start: Instant, val end: Instant,
    val heartRateMean: Double?, val heartRateCount: Int,
    val hrvWindows: Int,
)

/** A session's span is not sleep duration. Never sum overlapping sources or infer calibrated HRV. */
internal fun nightRecords(snapshot: JSONObject, date: LocalDate, zone: ZoneId, now: Instant): List<NightRecord> {
    if (snapshot.optString("request_date") != date.toString() || snapshot.optString("timezone") != zone.id) return emptyList()
    val sessions = snapshot.optJSONArray("sessions") ?: return emptyList()
    return (0 until sessions.length()).mapNotNull { index -> runCatching {
        val row = sessions.getJSONObject(index)
        val start = Instant.parse(row.getString("start")); val end = Instant.parse(row.getString("end"))
        if (row.optString("wake_date") != date.toString() || end.atZone(zone).toLocalDate() != date || start >= end || end > now) return@runCatching null
        val metrics = row.optJSONObject("metrics")
        // Prefer the minute series; a separate protocol summary is never averaged with it.
        fun validHeartRate(key: String): JSONObject? = metrics?.optJSONObject(key)?.takeIf {
            it.optString("unit") == "bpm" && it.optInt("sample_count") > 0 && it.optDouble("mean").let { value -> value.isFinite() && value in 20.0..250.0 }
        }
        val hr = validHeartRate("heart_rate") ?: validHeartRate("heart_rate_summary")
        val windows = metrics?.optJSONObject("hrv_rmssd")?.optInt("sample_count", 0)?.coerceAtLeast(0) ?: 0
        NightRecord(start, end, hr?.optDouble("mean"), hr?.optInt("sample_count") ?: 0, windows)
    }.getOrNull() }
}
