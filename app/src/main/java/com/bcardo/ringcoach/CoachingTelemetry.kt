package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.round

internal object CoachingTelemetry {
    fun summarize(window: ExportWindow, results: List<MetricResult>, zone: ZoneId): JSONObject {
        val metrics = JSONObject()
        for (result in results) {
            val metric = JSONObject().put("status", result.status.name.lowercase())
                .put("record_count", result.exportRecords.size)
            val sources = result.coverage?.sources?.map { (source, count) ->
                JSONObject().put("source_app", source.packageName)
                    .put("source_device_type", source.device?.type ?: JSONObject.NULL)
                    .put("record_count", count)
            } ?: emptyList()
            metric.put("sources", JSONArray(sources))
            if (result.status == ProbeStatus.AVAILABLE) {
                when (result.kind) {
                    MetricKind.SLEEP -> metric.put("sessions", JSONArray(result.exportRecords.map { session ->
                        val start = Instant.parse(session.getString("start"))
                        val end = Instant.parse(session.getString("end"))
                        val minutes = linkedMapOf<String, Double>()
                        val stages = session.getJSONArray("stages")
                        for (index in 0 until stages.length()) {
                            val stage = stages.getJSONObject(index)
                            val duration = Duration.between(
                                Instant.parse(stage.getString("start")), Instant.parse(stage.getString("end")),
                            ).toMillis() / 60_000.0
                            val name = stage.getString("stage")
                            minutes[name] = (minutes[name] ?: 0.0) + duration
                        }
                        JSONObject()
                            .put("wake_date", end.atZone(zone).toLocalDate().toString())
                            .put("start", start.toString()).put("end", end.toString())
                            .put("session_minutes", rounded(Duration.between(start, end).toMillis() / 60_000.0))
                            .put("stage_minutes", JSONObject(minutes.mapValues { rounded(it.value) }))
                            .put("source_app", session.get("source_app"))
                            .put("source_device_type", session.get("source_device_type"))
                    }))
                    MetricKind.HEART_RATE -> {
                        metric.put("beats_per_minute", stats(series(result.exportRecords, "samples", "beats_per_minute")))
                            .put("scope", "returned_samples_across_window")
                            .put("is_vendor_resting_heart_rate", false)
                    }
                    MetricKind.HRV -> {
                        metric.put("rmssd_milliseconds", stats(result.exportRecords.map { it.getDouble("rmssd_milliseconds") }))
                            .put("baseline_status", "not_computed").put("zscore", JSONObject.NULL)
                    }
                    MetricKind.SKIN_TEMPERATURE -> {
                        metric.put("delta_celsius", stats(series(result.exportRecords, "deltas", "delta_celsius")))
                            .put("records_with_baseline", result.exportRecords.count { !it.isNull("baseline_celsius") })
                    }
                }
            }
            metrics.put(result.kind.key, metric)
        }
        return JSONObject().put("window_start", window.start.toString()).put("window_end", window.end.toString())
            .put("timezone", zone.id).put("metrics", metrics)
    }

    private fun series(records: List<JSONObject>, collection: String, field: String): List<Double> {
        return records.flatMap { record ->
            val samples = record.getJSONArray(collection)
            (0 until samples.length()).map { samples.getJSONObject(it).getDouble(field) }
        }
    }

    private fun stats(values: List<Double>): JSONObject {
        val result = JSONObject().put("sample_count", values.size)
        if (values.isNotEmpty()) {
            result.put("min", rounded(values.min())).put("max", rounded(values.max())).put("mean", rounded(values.average()))
        }
        return result
    }

    private fun rounded(value: Double): Double = round(value * 100) / 100
}
