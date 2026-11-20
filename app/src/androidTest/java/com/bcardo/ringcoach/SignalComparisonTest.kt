package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.OffsetDateTime
import java.time.Duration
import kotlin.random.Random

/** #50 screening only. Never reads owner facts, saves cards, or persists requests/responses/traces. */
@RunWith(AndroidJUnit4::class)
class SignalComparisonTest {
    private val summaryVersion = "signal-summary-screen-v1"
    private val day = "2026-06-12"
    private data class Case(val name: String, val signal: JSONObject, val timing: String, val missing: Boolean = false, val corrected: Boolean = false)

    private fun observation(ref: String, metric: String, value: Any, source: String = "synthetic_health_connect", date: String = day) =
        JSONObject().put("ref", ref).put("date", date).put("source", source).put("metric", metric)
            .put("value", value).put("unit", if (metric == "sleep_minutes") "minutes" else "ms")
            .put("status", if (value == JSONObject.NULL) "missing" else "available")

    private fun signal() = JSONObject().put("target_date", day)
        .put("observations", JSONArray().put(observation("duration", "sleep_minutes", 420))
            .put(observation("vendor", "vendor_daily_sleeping_hrv", 54, "synthetic_vendor")))
        .put("hr_statistics", JSONObject().put("unit", "bpm").put("valid_samples", 6).put("missing_samples", 0).put("mean", 65))
        .put("hr_samples", JSONArray((0..5).map { JSONObject().put("time", "${day}T0$it:00:00Z").put("bpm", 65) }))
        .put("sleep_intervals", JSONArray().put(JSONObject().put("start", "2026-06-11T23:00:00Z").put("end", "2026-06-12T06:00:00Z").put("stage", "sleeping")))
        .put("limitations", JSONArray(listOf("Synthetic platform samples, not raw PPG or beat-to-beat signals. No clinical diagnosis or derived recovery score.")))

    private fun cases(): List<Case> {
        val missing = signal().apply {
            getJSONArray("observations").put(1, observation("vendor", "vendor_daily_sleeping_hrv", JSONObject.NULL, "synthetic_vendor"))
            getJSONArray("observations").put(observation("prior_vendor", "vendor_daily_sleeping_hrv", 76, "synthetic_vendor", "2026-06-11"))
        }
        val sparse = signal().apply { getJSONArray("hr_samples").apply { while (length() > 2) remove(length() - 1) }; getJSONObject("hr_statistics").put("valid_samples", 2).put("missing_samples", 4) }
        val overlap = signal().apply { getJSONArray("observations").put(observation("alternate_vendor", "vendor_daily_sleeping_hrv", 76, "synthetic_other_vendor")); getJSONArray("limitations").put("Vendor records disagree; keep sources separate and do not average.") }
        val fragments = signal().apply { put("sleep_intervals", JSONArray((0..3).map { JSONObject().put("start", "${day}T0$it:00:00Z").put("end", "${day}T0$it:30:00Z").put("stage", if (it % 2 == 0) "sleeping" else "awake") })); getJSONArray("limitations").put("Intervals have gaps; duration is a separate source record, not sum of these partial stages.") }
        val offset = signal().apply { getJSONArray("sleep_intervals").getJSONObject(0).put("start", "2026-06-11T23:00:00-04:00").put("end", "2026-06-12T06:00:00-04:00"); getJSONArray("limitations").put("Local wake date uses explicit -04:00 offset; do not shift the vendor date.") }
        fun timed(late: Boolean) = signal().apply {
            put("hr_samples", JSONArray((0..5).map { JSONObject().put("time", "${day}T0$it:00:00Z").put("bpm", if ((it >= 3) == late) 75 else 55) }))
        }
        return listOf(Case("ordinary", signal(), "flat"), Case("missing_stale", missing, "flat", missing = true),
            Case("sparse", sparse, "not_evaluated"), Case("overlapping_sources", overlap, "flat"),
            Case("fragmented", fragments, "flat"), Case("offset", offset, "flat"),
            Case("early_elevation", timed(false), "early_elevated"), Case("late_elevation", timed(true), "late_elevated"),
            Case("corrected_workout", signal(), "flat", corrected = true))
    }

    private fun daily(case: Case) = JSONObject().put("request_date", day).put("request_timezone", "UTC")
        .put("routine", "Synthetic press only. RPE cap 6. Stop exercise if dizzy. Do not add excluded squats.")
        .put("diet_plan", "Protein target 140 g; logged intake covers only part of the day.")
        .put("instructions", "Keep the saved constraints. Explain recovery concerns proportionately; do not routinely reduce the plan without a user request. Missing evidence remains unknown.")
        .put("progress_submission", JSONObject().put("text", "请结合恢复证据解释今天训练的注意事项。明确已记录蛋白质111克、实际推举负重及8次；明确RPE上限6和头晕停止的规则。不要把计划当完成，不要把旧HRV当今天。如果今天厂商HRV缺失请明确说缺失。"))
        .put("daily_nutrition", JSONArray().put(JSONObject().put("date", day).put("protein_grams", 111).put("coverage", "partial_day")))
        .put("latest_workout_records", JSONArray().put(JSONObject().put("date", day).put("exercise", "Synthetic press").put("load_kg", if (case.corrected) 18 else 20).put("reps", 8).put("status", "completed")))
        .put("previous_advice", JSONObject().put("training", JSONArray(listOf("Old suggestion: press 20 kg x 8. This is advice, not consumption or completed performance."))))

    private fun sleepSummary(signal: JSONObject): JSONObject {
        val intervals = signal.getJSONArray("sleep_intervals")
        val rows = (0 until intervals.length()).map { intervals.getJSONObject(it) }
        return JSONObject().put("interval_count", rows.size).put("awake_intervals", rows.count { it.getString("stage") == "awake" })
            .put("observed_interval_minutes", rows.sumOf { Duration.between(OffsetDateTime.parse(it.getString("start")), OffsetDateTime.parse(it.getString("end"))).toMinutes() })
            .put("period_start", rows.first().getString("start")).put("period_end", rows.last().getString("end"))
    }

    private fun summaryRequest(signal: JSONObject): JSONObject {
        val schema = JSONObject("""{"type":"object","properties":{"target_date":{"type":"string"},"observations":{"type":"array","maxItems":12,"items":{"type":"object","properties":{"ref":{"type":"string"},"date":{"type":"string"},"source":{"type":"string"},"metric":{"type":"string"},"value":{"type":["number","null"]},"unit":{"type":"string"},"status":{"type":"string"}},"required":["ref","date","source","metric","value","unit","status"],"additionalProperties":false}},"hr_statistics":{"type":"object","properties":{"unit":{"type":"string"},"valid_samples":{"type":"integer"},"missing_samples":{"type":"integer"},"mean":{"type":"number"}},"required":["unit","valid_samples","missing_samples","mean"],"additionalProperties":false},"timing":{"type":"string","enum":["flat","early_elevated","late_elevated","not_evaluated"]},"limitations":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"string"}}},"required":["target_date","observations","hr_statistics","timing","limitations"],"additionalProperties":false}""")
        schema.getJSONObject("properties").put("sleep_summary", JSONObject("""{"type":"object","properties":{"interval_count":{"type":"integer"},"awake_intervals":{"type":"integer"},"observed_interval_minutes":{"type":"integer"},"period_start":{"type":"string"},"period_end":{"type":"string"}},"required":["interval_count","awake_intervals","observed_interval_minutes","period_start","period_end"],"additionalProperties":false}"""))
        schema.getJSONArray("required").put("sleep_summary")
        return JSONObject().put("model", QWEN_MODEL).put("enable_thinking", false).put("max_tokens", 2000)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content",
                "Signal-only summarizer $summaryVersion. Copy all observations with exact references, values/nulls, units, source and date, and copy hr_statistics and limitations verbatim. Do not reconcile conflicting sources or invent scores. sleep_summary counts all supplied intervals, counts awake intervals, sums their durations in minutes (not elapsed span or vendor duration), and copies first start and last end including offsets. Preserve gaps and partial stage coverage. timing compares first versus second half of chronological HR samples: flat if equal, early_elevated if first higher, late_elevated if second higher, not_evaluated if fewer than 4 valid samples. Do not output sample arrays, intervals, training decisions or nutrition advice. Produce bounded structured facts and uncertainty only."))
                .put(JSONObject().put("role", "user").put("content", signal.toString())))
            .put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject().put("name", "signal_summary").put("strict", true).put("schema", schema)))
    }

    private fun faithful(case: Case, summary: JSONObject): Boolean = runCatching {
        require(summary.keys().asSequence().toSet() == setOf("target_date", "observations", "hr_statistics", "timing", "limitations", "sleep_summary"))
        require(summary.getString("target_date") == day && summary.getString("timing") == case.timing)
        val expected = case.signal.getJSONArray("observations")
        val actual = summary.getJSONArray("observations")
        require(actual.length() == expected.length())
        val byRef = (0 until actual.length()).map { actual.getJSONObject(it) }.associateBy { it.getString("ref") }
        require(byRef.size == actual.length())
        for (i in 0 until expected.length()) {
            val row = expected.getJSONObject(i); val other = byRef.getValue(row.getString("ref"))
            require(other.keys().asSequence().toSet() == row.keys().asSequence().toSet())
            for (key in row.keys()) require(row.get(key).toString() == other.get(key).toString())
        }
        val stats = case.signal.getJSONObject("hr_statistics"); val out = summary.getJSONObject("hr_statistics")
        for (key in stats.keys()) require(stats.get(key).toString() == out.get(key).toString())
        val sleep = sleepSummary(case.signal); val sleepOut = summary.getJSONObject("sleep_summary")
        require(sleep.keys().asSequence().toSet() == sleepOut.keys().asSequence().toSet())
        for (key in sleep.keys()) require(sleep.get(key).toString() == sleepOut.get(key).toString())
        require(summary.getJSONArray("limitations").length() in 1..8)
        val limitations = summary.getJSONArray("limitations")
        val expectedLimitations = case.signal.getJSONArray("limitations")
        require((0 until limitations.length()).map { limitations.getString(it) }.toSet() ==
            (0 until expectedLimitations.length()).map { expectedLimitations.getString(it) }.toSet())
        require(summary.toString().toByteArray().size <= 16384)
        true
    }.getOrDefault(false)

    @Test fun fixturesAndFidelityGate() {
        val cases = cases(); assertEquals(9, cases.size)
        for (case in cases) {
            val oracle = JSONObject().put("target_date", day).put("observations", case.signal.getJSONArray("observations"))
                .put("hr_statistics", case.signal.getJSONObject("hr_statistics")).put("timing", case.timing)
                .put("limitations", case.signal.getJSONArray("limitations")).put("sleep_summary", sleepSummary(case.signal))
            assertTrue(faithful(case, oracle))
            assertFalse(faithful(case, JSONObject(oracle.toString()).put("target_date", "2026-06-11")))
            val altered = JSONObject(oracle.toString()); altered.getJSONArray("observations").getJSONObject(0).put("value", 999)
            assertFalse(faithful(case, altered))
            assertFalse(faithful(case, JSONObject(oracle.toString()).put("hr_samples", JSONArray())))
            assertFalse(faithful(case, JSONObject(oracle.toString()).put("timing", "invented")))
            assertEquals(0, summaryRequest(case.signal).getJSONArray("messages").toString().let { if (it.contains("daily_nutrition") || it.contains("routine")) 1 else 0 })
        }
    }

    @Test fun liveSyntheticScreen() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_signal_screen") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val key = requireNotNull(QwenCredentials(context).read()) { "Saved credential required" }
        val system = context.assets.open("coach-system.txt").bufferedReader().use { it.readText() }
        fun emit(report: JSONObject) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "SIGNAL_SCREEN $report\n") })
        var failures = 0; var calls = 0
        fun call(case: Case, stage: String, request: JSONObject): String? {
            val bytes = request.toString().toByteArray(Charsets.UTF_8).size
            val report = JSONObject().put("case", case.name).put("stage", stage).put("request_bytes", bytes)
            val start = System.nanoTime()
            return try {
                require(bytes <= 131072)
                calls++
                val raw = QwenClient.post(request, key)
                val usage = JSONObject(raw).optJSONObject("usage")
                for (field in listOf("prompt_tokens", "completion_tokens", "total_tokens")) {
                    val count = usage?.opt(field)
                    report.put(field, if (count is Number && count.toLong() >= 0) count.toLong() else JSONObject.NULL)
                }
                val text = QwenClient.responseText(raw)
                report.put("status", "ok"); text
            } catch (e: Exception) {
                failures++; report.put("status", when (e) { is QwenHttpException -> "http_${e.status}"; is java.net.SocketTimeoutException -> "timeout"; else -> "transport_or_decode" }); null
            } finally { report.put("latency_ms", (System.nanoTime() - start) / 1_000_000); emit(report) }
        }
        val random = Random(50)
        for (case in cases()) {
            for (arm in listOf("direct", "summary_first").shuffled(random)) {
                val start = System.nanoTime()
                val facts = daily(case)
                if (arm == "direct") facts.put("signal_evidence", case.signal)
                else {
                    val text = call(case, "summarizer", summaryRequest(case.signal)) ?: continue
                    val summary = runCatching { JSONObject(text) }.getOrNull()
                    val passed = summary != null && faithful(case, summary)
                    emit(JSONObject().put("case", case.name).put("stage", "summary_fidelity").put("passed", passed))
                    if (!passed) { failures++; continue }
                    facts.put("signal_evidence", summary)
                }
                val text = call(case, arm, QwenClient.request(facts, system, true)) ?: continue
                val advice = runCatching { CoachAdvice.decode(text) }.getOrNull()
                val training = advice?.training?.joinToString(" ") ?: ""
                val all = advice?.toJson()?.toString() ?: ""
                val passed = advice != null && advice.diet.joinToString(" ").contains("111") && training.contains(if (case.corrected) "18" else "20") && training.contains("8") &&
                    Regex("RPE.{0,12}6", RegexOption.IGNORE_CASE).containsMatchIn(training) && Regex("头晕|眩晕|dizz", RegexOption.IGNORE_CASE).containsMatchIn(training) &&
                    (!case.missing || Regex("缺失|缺少|未知|没有|未提供|未记录|暂无|missing|unknown", RegexOption.IGNORE_CASE).containsMatchIn(all))
                if (!passed) failures++
                emit(JSONObject().put("case", case.name).put("stage", "${arm}_spotcheck").put("passed", passed).put("pipeline_latency_ms", (System.nanoTime() - start) / 1_000_000))
            }
        }
        emit(JSONObject().put("stage", "complete").put("cases", cases().size).put("calls", calls).put("failures", failures)
            .put("summary_version", summaryVersion).put("coach_version", COACH_PROMPT_VERSION).put("model", QWEN_MODEL)
            .put("owner_data_sent", false).put("cards_saved", 0).put("retries", 0).put("cache_reuse", 0))
        assertEquals("Synthetic screening gates failed; inspect fixed-category reports, not raw responses", 0, failures)
    }
}
