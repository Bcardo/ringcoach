package com.bcardo.ringcoach

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CoachingTelemetryTest {
    @Test
    fun summarizesActualSamplesAndPreservesMissingHrvAndTemperatureDeltas() {
        val fixture = javaClass.classLoader!!.getResourceAsStream("synthetic-telemetry.json")!!
            .bufferedReader().use { JSONObject(it.readText()) }
        val metrics = fixture.getJSONObject("metrics")
        val results = MetricKind.entries.map { kind ->
            val source = metrics.getJSONObject(kind.key)
            val records = source.getJSONArray("records")
            MetricResult(kind, null, ProbeStatus.valueOf(source.getString("status").uppercase()),
                exportRecords = (0 until records.length()).map { records.getJSONObject(it) })
        }
        val range = fixture.getJSONObject("window")
        val window = ExportWindow(Instant.parse(range.getString("start")), Instant.parse(range.getString("end")))
        val summary = CoachingTelemetry.summarize(window, results, ZoneId.of("UTC")).getJSONObject("metrics")
        val sleep = summary.getJSONObject("sleep").getJSONArray("sessions").getJSONObject(0)
        assertEquals(60.0, sleep.getDouble("session_minutes"), 0.0)
        assertEquals(60.0, sleep.getJSONObject("stage_minutes").getDouble("deep"), 0.0)
        assertEquals(62.0, summary.getJSONObject("heart_rate").getJSONObject("beats_per_minute").getDouble("mean"), 0.0)
        assertFalse(summary.getJSONObject("heart_rate").getBoolean("is_vendor_resting_heart_rate"))
        val hrv = summary.getJSONObject("hrv_rmssd")
        assertEquals("empty", hrv.getString("status"))
        assertFalse(hrv.has("rmssd_milliseconds"))
        assertEquals(-0.4, summary.getJSONObject("skin_temperature").getJSONObject("delta_celsius").getDouble("mean"), 0.0)
    }
}
