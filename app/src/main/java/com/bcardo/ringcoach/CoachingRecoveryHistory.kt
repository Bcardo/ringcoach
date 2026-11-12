package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Reuse the chart's canonical daily/source policy; do not invent personal baselines here. */
internal object CoachingRecoveryHistory {
    fun compact(history: JSONObject, targetDate: LocalDate): JSONObject {
        require(LocalDate.parse(history.getString("history_end")) < targetDate) { "Comparison must exclude the target date" }
        val days = history.getJSONArray("days")
        require(days.length() <= 30)
        val rows = (0 until days.length()).map { index ->
            val day = days.getJSONObject(index)
            JSONObject().put("wake_date", day.getString("wake_date")).apply {
                for (metric in DAILY_RECOVERY_METRICS) {
                    val source = day.getJSONObject(metric)
                    put(metric, JSONObject().apply {
                        for (key in listOf("status", "value", "unit", "metric_definition", "source", "last_verified_at", "revision", "source_record_id"))
                            put(key, source.opt(key) ?: JSONObject.NULL)
                    })
                }
            }
        }
        val series = JSONArray()
        for (metric in DAILY_RECOVERY_METRICS) {
            val valid = rows.map { it.getJSONObject(metric) }.filter { it.optString("status") == "available" && !it.isNull("value") }
            for ((key, values) in valid.groupBy { it.getString("source") to it.getString("metric_definition") }) {
                series.put(JSONObject().put("metric", metric).put("source", key.first).put("metric_definition", key.second)
                    .put("valid_night_count", values.size).put("missing_or_other_series_nights", rows.size - values.size)
                    .put("hardware_attribution", "not_verified").put("baseline_status", "not_computed"))
            }
        }
        return JSONObject().put("history_start", history.getString("history_start")).put("history_end", history.getString("history_end"))
            .put("target_wake_date", targetDate.toString())
            .put("target_night_excluded", true).put("policy_version", history.getString("policy_version"))
            .put("query_scope", history.getString("query_scope")).put("days", JSONArray(rows)).put("series_coverage", series)
            .put("sync", history.getJSONArray("sync"))
            .put("sleep_timing_history_status", "not_yet_durably_archived")
            .put("baseline_status", "not_computed_no_zscore_or_composite_score")
    }
}
