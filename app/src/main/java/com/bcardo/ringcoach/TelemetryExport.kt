package com.bcardo.ringcoach

import androidx.annotation.StringRes
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

internal enum class MetricKind(val key: String, @field:StringRes val title: Int) {
    SLEEP("sleep", R.string.metric_sleep),
    HEART_RATE("heart_rate", R.string.metric_heart_rate),
    HRV("hrv_rmssd", R.string.metric_hrv),
    SKIN_TEMPERATURE("skin_temperature", R.string.metric_skin_temperature),
}

internal data class ExportWindow(val start: Instant, val end: Instant)

internal object TelemetryExport {
    fun encode(window: ExportWindow, results: List<MetricResult>): String {
        val metrics = JSONObject()
        for (result in results) {
            metrics.put(result.kind.key, JSONObject()
                .put("status", result.status.name.lowercase())
                .put("records", JSONArray(result.exportRecords)))
        }
        return JSONObject()
            .put("schema_version", 1)
            .put("window", JSONObject().put("start", window.start.toString()).put("end", window.end.toString()))
            .put("metrics", metrics)
            .toString(2) + "\n"
    }

    fun sleep(record: SleepSessionRecord): JSONObject = interval(
        record.metadata, record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset,
    ).put("stages", JSONArray(record.stages.map { stage ->
        JSONObject()
            .put("start", stage.startTime.toString())
            .put("end", stage.endTime.toString())
            .put("stage", when (stage.stage) {
                SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
                SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
                SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
                SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
                SleepSessionRecord.STAGE_TYPE_REM -> "rem"
                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
                else -> "unknown"
            })
    }))

    fun heartRate(record: HeartRateRecord): JSONObject = interval(
        record.metadata, record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset,
    ).put("samples", JSONArray(record.samples.map { sample ->
        JSONObject().put("time", sample.time.toString()).put("beats_per_minute", sample.beatsPerMinute)
    }))

    fun hrv(record: HeartRateVariabilityRmssdRecord): JSONObject = source(record.metadata)
        .put("time", record.time.toString())
        .put("offset", record.zoneOffset?.toString() ?: JSONObject.NULL)
        .put("rmssd_milliseconds", record.heartRateVariabilityMillis)

    fun skinTemperature(record: SkinTemperatureRecord): JSONObject = interval(
        record.metadata, record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset,
    ).put("baseline_celsius", record.baseline?.inCelsius ?: JSONObject.NULL)
        .put("deltas", JSONArray(record.deltas.map { sample ->
            JSONObject().put("time", sample.time.toString()).put("delta_celsius", sample.delta.inCelsius)
        }))

    private fun interval(
        metadata: Metadata,
        start: Instant,
        startOffset: ZoneOffset?,
        end: Instant,
        endOffset: ZoneOffset?,
    ): JSONObject = source(metadata)
        .put("start", start.toString())
        .put("start_offset", startOffset?.toString() ?: JSONObject.NULL)
        .put("end", end.toString())
        .put("end_offset", endOffset?.toString() ?: JSONObject.NULL)

    private fun source(metadata: Metadata): JSONObject = JSONObject()
        .put("source_app", metadata.dataOrigin.packageName.ifBlank { null } ?: JSONObject.NULL)
        .put("source_device_type", metadata.device?.type?.takeUnless { it == Device.TYPE_UNKNOWN } ?: JSONObject.NULL)
}
