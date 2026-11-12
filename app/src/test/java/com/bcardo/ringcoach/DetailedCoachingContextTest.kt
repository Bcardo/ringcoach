package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DetailedCoachingContextTest {
    private val date = LocalDate.of(2026, 11, 12)
    private val start = Instant.parse("2026-11-12T01:00:00Z")
    private val window = ExportWindow(start.minusSeconds(7 * 86400), start.plusSeconds(86400))
    private fun sleep(source: String = "source-a", offset: String? = null) = JSONObject()
        .put("start", start.toString()).put("end", start.plusSeconds(3600).toString())
        .put("start_offset", offset ?: JSONObject.NULL).put("end_offset", offset ?: JSONObject.NULL)
        .put("source_app", source).put("source_device_type", JSONObject.NULL).put("source_record_id", source)
        .put("stages", JSONArray().put(JSONObject().put("start", start.toString()).put("end", start.plusSeconds(600).toString()).put("stage", "light"))
            .put(JSONObject().put("start", start.plusSeconds(600).toString()).put("end", start.plusSeconds(3600).toString()).put("stage", "deep")))
    private fun heart() = JSONObject().put("source_app", "hr-source").put("source_record_id", "hr-1")
        .put("start", start.minusSeconds(60).toString()).put("end", start.plusSeconds(7200).toString())
        .put("samples", JSONArray(listOf(-1L, 0L, 600L, 3599L, 3600L).map { seconds ->
            JSONObject().put("time", start.plusSeconds(seconds).toString()).put("beats_per_minute", 60 + seconds % 3)
        }))
    private fun night(sessions: List<JSONObject> = listOf(sleep()), heartStatus: ProbeStatus = ProbeStatus.AVAILABLE) = CurrentNightEvidence.build(
        date, window, listOf(MetricResult(MetricKind.SLEEP, null, ProbeStatus.AVAILABLE, exportRecords = sessions),
            MetricResult(MetricKind.HEART_RATE, null, heartStatus, exportRecords = if (heartStatus == ProbeStatus.AVAILABLE) listOf(heart()) else emptyList())), ZoneId.of("UTC"))
    private fun context(night: JSONObject = night()) = JSONObject().put("request_date", date.toString())
        .put("request_timezone", "UTC").put("history_start", date.minusDays(6).toString())
        .put("routine", "保存的训练计划").put("diet_plan", "保存的饮食计划").put("instructions", "保存的约束")
        .put("progress_submission", JSONObject().put("id", "trigger").put("text", "已吃午餐"))
        .put("daily_nutrition", JSONArray()).put("journal_history", JSONArray())
        .put("telemetry_status", "checked").put("telemetry", JSONObject().put("current_night", night))
        .put("nightly_recovery", JSONObject().put("records", JSONArray())
            .put("screen_reader_configured", false).put("screen_reader_connected", false))

    @Test fun originalStagesAndExactInSessionSampleTimesReachSerializedModelRequest() {
        val original = context()
        val packaged = CoachingRequestPackager.prepare(original, "Coach")
        val sent = JSONObject(packaged.request.getJSONArray("messages").getJSONObject(1).getString("content"))
        val evidence = sent.getJSONObject("telemetry").getJSONObject("current_night")
        assertEquals(sleep().getJSONArray("stages").toString(), evidence.getJSONArray("sessions").getJSONObject(0).getJSONArray("stages").toString())
        val samples = evidence.getJSONArray("heart_rate_series").getJSONObject(0).getJSONArray("samples")
        assertEquals(3, samples.length())
        assertEquals(start.toString(), samples.getJSONObject(0).getString("time"))
        assertEquals(start.plusSeconds(3599).toString(), samples.getJSONObject(2).getString("time"))
        assertTrue(original.getJSONObject("telemetry").getJSONObject("current_night").getJSONArray("heart_rate_series").getJSONObject(0).has("samples"))
        assertEquals(packaged.request.toString().toByteArray(Charsets.UTF_8).size,
            sent.getJSONObject("request_manifest").getInt("serialized_request_bytes"))
    }

    @Test fun snapshotsKeepIntervalsStatisticsAndDigestButNeverHrSampleArrays() {
        val packaged = CoachingRequestPackager.prepare(context(), "Coach")
        val retained = packaged.retainedContext.getJSONObject("telemetry").getJSONObject("current_night")
        val series = retained.getJSONArray("heart_rate_series").getJSONObject(0)
        assertFalse(series.has("samples"))
        assertEquals(3, series.getInt("sample_count"))
        assertTrue(series.has("beats_per_minute"))
        assertEquals(2, retained.getJSONArray("sessions").getJSONObject(0).getJSONArray("stages").length())
        val expected = MessageDigest.getInstance("SHA-256").digest(packaged.request.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals(expected, packaged.retainedContext.getJSONObject("request_manifest").getString("model_request_sha256"))
        assertEquals(packaged.retainedContext.toString(), CoachingSnapshot.retained(packaged.retainedContext).toString())
    }

    @Test fun overlapsRetainTwoSessionsAndOneSampleWithBothReferences() {
        val evidence = night(listOf(sleep(), sleep("source-b")))
        assertEquals(2, evidence.getJSONArray("sessions").length())
        val series = evidence.getJSONArray("heart_rate_series")
        assertEquals(1, series.length())
        assertEquals(2, series.getJSONObject(0).getJSONArray("session_refs").length())
        assertEquals("shared_by_all_samples_in_this_series", series.getJSONObject(0).getString("session_reference_encoding"))
        assertFalse(series.getJSONObject(0).getBoolean("is_vendor_resting_heart_rate"))
    }

    @Test fun wakeDateUsesSourceEndOffsetAndMissingSourcesStayMissing() {
        assertEquals(0, night(listOf(sleep(offset = "-05:00"))).getJSONArray("sessions").length())
        assertEquals(0, night(heartStatus = ProbeStatus.NO_PERMISSION).getJSONArray("heart_rate_series").length())
        assertEquals("no_permission", night(heartStatus = ProbeStatus.NO_PERMISSION).getString("heart_rate_read_status"))
        assertEquals(0, night(emptyList()).getJSONArray("heart_rate_series").length())
    }

    @Test fun partialSessionOverlapsKeepPerSampleMembershipWithoutDuplicatingSamples() {
        val a = sleep().put("end", start.plusSeconds(1800).toString())
        val b = sleep("source-b").put("start", start.plusSeconds(600).toString())
        val series = night(listOf(a, b)).getJSONArray("heart_rate_series").getJSONObject(0)
        assertEquals("per_sample", series.getString("session_reference_encoding"))
        val samples = series.getJSONArray("samples")
        assertEquals(3, samples.length())
        assertEquals(1, samples.getJSONObject(0).getJSONArray("session_refs").length())
        assertEquals(2, samples.getJSONObject(1).getJSONArray("session_refs").length())
        assertEquals(1, samples.getJSONObject(2).getJSONArray("session_refs").length())
    }

    @Test fun explicitBudgetRemovesOptionalAdviceAndRawCopiesWithoutTruncatingCurrentEvidence() {
        val input = context().put("previous_advice", JSONObject().put("notes", "旧建议".repeat(30000)))
            .put("journal_history", JSONArray().put(JSONObject().put("date", date.minusDays(1).toString()).put("additional", "旧原文".repeat(10000)))
                .put(JSONObject().put("date", date.toString()).put("additional", "当日原文".repeat(30000))))
        val originalTrace = input.getJSONObject("telemetry").toString()
        val packaged = CoachingRequestPackager.prepare(input, "Coach", 16000)
        val sent = JSONObject(packaged.request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertFalse(sent.has("previous_advice"))
        assertEquals(0, sent.getJSONArray("journal_history").length())
        assertEquals(originalTrace, sent.getJSONObject("telemetry").toString())
        assertEquals("已吃午餐", sent.getJSONObject("progress_submission").getString("text"))
        assertEquals(3, sent.getJSONObject("request_manifest").getJSONArray("omissions").length())
    }

    @Test fun oversizedRequiredTraceFailsExplicitlyRatherThanSendingPartialEvidence() {
        val input = context()
        try { CoachingRequestPackager.prepare(input, "Coach", 1000); fail("Oversized evidence sent") }
        catch (e: CoachingPayloadTooLarge) {
            assertEquals("required_evidence_too_large_not_sent", e.manifest.getString("budget_status"))
            assertEquals(3, e.manifest.getJSONObject("current_night").getInt("heart_rate_sample_count"))
        }
    }

    @Test fun missingTargetDayHrvAndDisconnectedReaderAreVisibleEvenWhenOldSyncSaysAvailable() {
        val input = context()
        input.getJSONObject("nightly_recovery").put("records", JSONArray().put(JSONObject().put("wake_date", date.minusDays(1).toString())
            .put("metric", "sleeping_hrv").put("value", 40)))
            .put("sync", JSONArray().put(JSONObject().put("source", "samsung_health_screen").put("status", "available").put("checked_at", "2026-11-11T12:00:00Z")))
        val freshness = CoachingRequestPackager.prepare(input, "Coach").retainedContext.getJSONObject("request_manifest").getJSONObject("freshness")
        assertFalse(freshness.getBoolean("target_date_recovery_complete"))
        assertEquals(date.minusDays(1).toString(), freshness.getString("latest_sleeping_hrv_date"))
        assertFalse(freshness.getBoolean("screen_reader_configured"))
        assertTrue(freshness.getJSONArray("missing_target_date_vendor_metrics").toString().contains("sleeping_hrv"))
    }

    @Test fun historyPartitionsSourcesAndKeepsGapsWithoutInventingBaseline() {
        val days = JSONArray()
        repeat(3) { index -> days.put(JSONObject().put("wake_date", date.minusDays((3 - index).toLong()).toString()).apply {
            for (metric in DAILY_RECOVERY_METRICS) put(metric, JSONObject().put("status", if (index == 2) "not_checked" else "available")
                .put("value", if (index == 2) JSONObject.NULL else 40 + index).put("source", if (index == 0) "sdk" else "screen")
                .put("metric_definition", metric).put("unit", "ms"))
        }) }
        val history = CoachingRecoveryHistory.compact(JSONObject().put("history_start", date.minusDays(3).toString()).put("history_end", date.minusDays(1).toString())
            .put("policy_version", "test-policy").put("query_scope", "local").put("sync", JSONArray()).put("days", days), date)
        assertEquals(3, history.getJSONArray("days").length())
        assertEquals(4, history.getJSONArray("series_coverage").length())
        assertEquals(1, history.getJSONArray("series_coverage").getJSONObject(0).getInt("valid_night_count"))
        assertTrue(history.getBoolean("target_night_excluded"))
        assertTrue(history.getString("baseline_status").contains("not_computed"))
    }

    @Test fun currentFoodAssumptionsAndWorkoutsStayCompleteWhileHistoryUsesDailyFacts() {
        val input = context()
        val food = JSONObject().put("id", "food-1").put("source_submission_ids", JSONArray(listOf("trigger"))).put("portion", "reported")
        val values = JSONObject().put("food_items", JSONArray().put(food)).put("assumptions", JSONArray(listOf("粗略估算")))
            .put("intake_kcal", JSONObject().put("value", 500).put("kind", "estimated").put("evidence", "原始证据"))
            .put("coverage", "partial_day")
        val target = JSONObject().put("date", date.toString()).put("nutrition", values)
        val old = JSONObject(target.toString()).put("date", date.minusDays(1).toString())
        input.put("daily_nutrition", JSONArray().put(target).put(old))
            .put("latest_workout_records", JSONArray().put(JSONObject().put("exercise", "深蹲").put("status", "completed")))
        val packaged = CoachingRequestPackager.prepare(input, "Coach")
        val sent = JSONObject(packaged.request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(values.toString(), sent.getJSONArray("daily_nutrition").getJSONObject(0).getJSONObject("nutrition").toString())
        val historical = sent.getJSONArray("daily_nutrition").getJSONObject(1).getJSONObject("nutrition")
        assertFalse(historical.has("food_items"))
        assertEquals("partial_day", historical.getString("coverage"))
        assertEquals(500, historical.getJSONObject("intake_kcal").getInt("value"))
        assertEquals("trigger", historical.getJSONArray("source_submission_ids").getString(0))
        assertEquals(input.getJSONArray("latest_workout_records").toString(), sent.getJSONArray("latest_workout_records").toString())
    }
}
