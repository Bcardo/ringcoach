package com.bcardo.ringcoach

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.TemperatureDelta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class TelemetryExportTest {
    private val start = Instant.parse("2025-01-02T02:00:00Z")
    private val end = start.plusSeconds(3600)
    private val offset = ZoneOffset.of("-05:00")
    private val metadata = Metadata.manualEntry(clientRecordId = "omit-client-id")
    private val window = ExportWindow(Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-01-08T00:00:00Z"))

    @Test
    fun `export matches the synthetic fixture without leaking notes or identifiers`() {
        val sleep = SleepSessionRecord(start, offset, end, offset, metadata,
            title = "omit-title", notes = "omit-notes",
            stages = listOf(SleepSessionRecord.Stage(start, end, SleepSessionRecord.STAGE_TYPE_DEEP)))
        val heartRate = HeartRateRecord(start, offset, end, null,
            listOf(HeartRateRecord.Sample(start.plusSeconds(60), 62)), metadata)
        val temperature = SkinTemperatureRecord(start, null, end, null, metadata,
            deltas = listOf(SkinTemperatureRecord.Delta(start.plusSeconds(60), TemperatureDelta.celsius(-0.4))))

        val export = TelemetryExport.encode(window, listOf(
            available(MetricKind.SLEEP, TelemetryExport.sleep(sleep)),
            available(MetricKind.HEART_RATE, TelemetryExport.heartRate(heartRate)),
            MetricResult(MetricKind.HRV, null, ProbeStatus.EMPTY),
            available(MetricKind.SKIN_TEMPERATURE, TelemetryExport.skinTemperature(temperature)),
        ))
        val fixture = checkNotNull(javaClass.getResourceAsStream("/synthetic-telemetry.json"))
            .bufferedReader().use { it.readText() }
        assertEquals(JSONObject(fixture).toString(), JSONObject(export).toString())
        assertFalse(export.contains("omit-"))
    }

    @Test
    fun `export includes only selected metrics and distinguishes denied access`() {
        val hrv = HeartRateVariabilityRmssdRecord(start, offset, 41.5, metadata)
        val export = TelemetryExport.encode(window, listOf(
            available(MetricKind.HRV, TelemetryExport.hrv(hrv)),
            MetricResult(MetricKind.HEART_RATE, null, ProbeStatus.NO_PERMISSION),
        ))
        val metrics = JSONObject(export).getJSONObject("metrics")
        assertEquals(setOf("heart_rate", "hrv_rmssd"), metrics.keys().asSequence().toSet())
        assertEquals("no_permission", metrics.getJSONObject("heart_rate").getString("status"))
        val record = metrics.getJSONObject("hrv_rmssd").getJSONArray("records").getJSONObject(0)
        assertEquals(41.5, record.getDouble("rmssd_milliseconds"), 0.0)
        assertEquals("-05:00", record.getString("offset"))
    }

    private fun available(kind: MetricKind, record: JSONObject) =
        MetricResult(kind, null, ProbeStatus.AVAILABLE, exportRecords = listOf(record))
}
