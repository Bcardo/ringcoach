package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Original sleep intervals and HR samples are request evidence, not an inferred vendor sleep score. */
internal object CurrentNightEvidence {
    fun build(date: LocalDate, window: ExportWindow, results: List<MetricResult>, zone: ZoneId): JSONObject {
        val sleep = results.firstOrNull { it.kind == MetricKind.SLEEP }
        val heart = results.firstOrNull { it.kind == MetricKind.HEART_RATE }
        val sessions = sleep?.exportRecords.orEmpty().filter { record ->
            val end = Instant.parse(record.getString("end"))
            val offset = record.optString("end_offset").takeIf { it.isNotBlank() && it != "null" }?.let(ZoneOffset::of)
            val wake = if (offset != null) end.atOffset(offset).toLocalDate() else end.atZone(zone).toLocalDate()
            wake == date && end <= window.end && Instant.parse(record.getString("start")) < end
        }.sortedWith(compareBy<JSONObject> { it.getString("start") }.thenBy { it.optString("source_app") }
            .thenBy { it.optString("source_record_id") })
        val intervals = sessions.mapIndexed { index, original ->
            JSONObject(original.toString()).put("session_ref", "wake-$date-session-${index + 1}")
                .put("wake_date", date.toString())
                .put("timezone_basis", if (!original.isNull("end_offset") && original.has("end_offset")) "record_end_offset" else "request_timezone")
                .put("session_minutes", Duration.between(Instant.parse(original.getString("start")),
                    Instant.parse(original.getString("end"))).toMillis() / 60_000.0)
        }
        val heartSeries = JSONArray()
        for (record in heart?.exportRecords.orEmpty()) {
            val samples = record.getJSONArray("samples")
            val matched = (0 until samples.length()).mapNotNull { index ->
                val sample = samples.getJSONObject(index)
                val time = Instant.parse(sample.getString("time"))
                if (time < window.start || time >= window.end) return@mapNotNull null
                val refs = intervals.filter { session ->
                    time >= Instant.parse(session.getString("start")) && time < Instant.parse(session.getString("end"))
                }.map { it.getString("session_ref") }
                if (refs.isEmpty()) null else JSONObject(sample.toString()).put("session_refs", JSONArray(refs))
            }.sortedWith(compareBy<JSONObject> { it.getString("time") }.thenBy { it.getDouble("beats_per_minute") })
            if (matched.isEmpty()) continue
            val values = matched.map { it.getDouble("beats_per_minute") }
            val references = matched.map { it.getJSONArray("session_refs").toString() }.distinct()
            val series = JSONObject(record.toString()).apply { remove("samples") }
                .put("samples", JSONArray(matched)).put("sample_count", matched.size)
                .put("first_sample_at", matched.first().getString("time")).put("last_sample_at", matched.last().getString("time"))
                .put("beats_per_minute", JSONObject().put("min", values.min()).put("max", values.max()).put("mean", values.average()))
                .put("is_vendor_resting_heart_rate", false).put("hardware_attribution", "not_verified")
            // Most records belong to one shared set of sessions. Store that association once, losslessly.
            if (references.size == 1) {
                series.put("session_refs", JSONArray(references.single()))
                    .put("session_reference_encoding", "shared_by_all_samples_in_this_series")
                matched.forEach { it.remove("session_refs") }
            } else series.put("session_reference_encoding", "per_sample")
            heartSeries.put(series)
        }
        return JSONObject().put("wake_date", date.toString()).put("timezone", zone.id)
            .put("selection", "all_completed_sessions_ending_on_target_date_including_possible_naps")
            .put("sleep_read_status", sleep?.status?.name?.lowercase() ?: "unavailable")
            .put("heart_rate_read_status", heart?.status?.name?.lowercase() ?: "unavailable")
            .put("sessions", JSONArray(intervals)).put("heart_rate_series", heartSeries)
            .put("heart_rate_selection", "samples_in_union_of_sleep_session_intervals_half_open_not_source_attribution")
            .put("session_overlap_policy", "sessions_and_sources_remain_separate_no_merged_sleep_duration")
            .put("trace_retention", "transient_request_only_reread_source_on_retry")
            .put("coverage", JSONObject().put("session_count", intervals.size)
                .put("stage_interval_count", intervals.sumOf { it.getJSONArray("stages").length() })
                .put("heart_rate_record_count", heartSeries.length())
                .put("heart_rate_sample_count", (0 until heartSeries.length()).sumOf { heartSeries.getJSONObject(it).getInt("sample_count") })
                .put("sampling_completeness", "unknown_source_samples_not_a_continuous_trace"))
    }
}
