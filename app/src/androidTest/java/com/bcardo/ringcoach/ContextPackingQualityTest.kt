package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Explicit live-provider opt-ins. Synthetic fixtures only; no owner facts/cards are read or saved. */
@RunWith(AndroidJUnit4::class)
class ContextPackingQualityTest {
    private fun fixture(protein: Int?, load: Int, correction: Boolean): JSONObject {
        val day = LocalDate.of(2026, 6, 12)
        val rules = "Only report confirmed intake and actual training; missing values stay unknown. ".repeat(20)
        val plan = JSONObject().put("routine", "Keep the saved plan and exercise exclusions. ".repeat(20))
            .put("diet_plan", "Use the saved diet; missing intake is not zero. ".repeat(20)).put("instructions", rules)
        return JSONObject(plan.toString()).put("request_date", day.toString()).put("request_timezone", "UTC")
            .put("request_manifest", JSONObject().put("wire_policy", CoachingWireContext.VERSION))
            .put("progress_submission", JSONObject().put("text", "请核对当天已记录的蛋白质克数和最近训练的实际负重、次数。没有记录的摄入请明确说未知，不要补造。"))
            .put("latest_workout_records", JSONArray().put(JSONObject().put("date", day.toString()).put("exercise", "Synthetic press")
                .put("performance", "$load kg x 8").put("status", "completed").put("record_kind", "actual_performance")
                .put("evidence", "Synthetic press $load kg x 8").put("source_submission_ids", JSONArray(listOf("synthetic-turn")))))
            .put("previous_advice", JSONObject().put("training", JSONArray(listOf(if (correction) "Old suggestion: 20 kg x 8" else "Keep saved plan"))))
            .put("daily_nutrition", JSONArray().put(JSONObject().put("date", day.toString()).put("status", "complete")
                .put("nutrition", JSONObject().put("coverage", "partial_day").put("protein_grams", JSONObject()
                    .put("value", protein ?: JSONObject.NULL).put("kind", if (protein == null) "missing" else "reported")))))
            .put("supplement_history", JSONObject().put("standing_plan", plan).put("days", JSONArray()))
            .put("recovery_history", JSONObject().put("days", JSONArray((1..30).map { offset ->
                JSONObject().put("wake_date", day.minusDays(offset.toLong()).toString()).put("sleeping_hrv", JSONObject()
                    .put("status", "not_checked").put("value", JSONObject.NULL).put("unit", "ms").put("metric_definition", "vendor_daily_sleeping_hrv")
                    .put("source", JSONObject.NULL).put("revision", JSONObject.NULL).put("last_verified_at", JSONObject.NULL).put("source_record_id", JSONObject.NULL))
            })))
            .put("ring_bluetooth", JSONObject().put("sessions", JSONArray()).put("interpretation", "Experimental windows do not replace vendor daily HRV; units pending reference validation."))
    }

    @Test fun syntheticFactsRemainCorrectInPlainAndPackedRequests() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_context_quality") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val key = requireNotNull(QwenCredentials(context).read()) { "Existing saved credential required" }
        val system = context.assets.open("coach-system.txt").bufferedReader().use { it.readText() }
        var calls = 0
        for ((protein, load, correction) in listOf(Triple(123, 20, false), Triple(111, 18, true), Triple(null, 16, false))) {
            val facts = fixture(protein, load, correction)
            val plain = QwenClient.request(facts, system, false)
            val packed = QwenClient.request(facts, system, true)
            assertTrue("Synthetic fixture must exercise packing", packed.toString().toByteArray(Charsets.UTF_8).size < plain.toString().toByteArray(Charsets.UTF_8).size)
            for (request in listOf(plain, packed)) {
                val advice = QwenClient.response(QwenClient.post(request, key)); calls++
                val training = advice.training.joinToString(" ")
                val diet = advice.diet.joinToString(" ")
                assertTrue("Confirmed training load missing", training.contains(load.toString()))
                assertTrue("Confirmed training repetitions missing", training.contains("8"))
                if (protein != null) assertTrue("Confirmed intake missing", diet.contains(protein.toString()))
                else assertTrue("Unknown intake must stay explicit", Regex("未知|不明|未记录|缺少|缺失|没有|无法").containsMatchIn(diet))
            }
        }
        assertEquals(6, calls)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "CONTEXT_QUALITY synthetic_cases=3 formats=2 calls=6 passed=true owner_data_sent=false cards_saved=0\n") })
    }

    @Test fun estimatesPlansAndRecoveryUncertaintySurviveBothFormats() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_context_semantics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val key = requireNotNull(QwenCredentials(context).read()) { "Existing saved credential required" }
        val system = context.assets.open("coach-system.txt").bufferedReader().use { it.readText() }
        val day = LocalDate.of(2026, 6, 12)
        fun ask(facts: JSONObject, question: String): JSONObject = facts.apply {
            getJSONObject("progress_submission").put("text", question)
        }
        fun vendor(value: Int, ref: String) = JSONObject().put("wake_date", day.toString()).put("metric", "sleeping_hrv")
            .put("value", value).put("unit", "ms").put("metric_definition", "samsung_vendor_daily_sleeping_hrv")
            .put("source", "samsung_health_screen").put("source_record_id", ref)
        val estimated = ask(fixture(47, 20, false), "请核对已记录蛋白质克数，说明这是精确实测还是估计、能否代表全天，以及能否算出全天热量缺口。不要补造总热量或消耗。")
        estimated.getJSONArray("daily_nutrition").getJSONObject(0).getJSONObject("nutrition").apply {
            getJSONObject("protein_grams").put("kind", "estimated")
            put("assumptions", JSONArray(listOf("Synthetic meal portion is estimated; only part of the day is logged.")))
        }
        val planned = ask(fixture(123, 20, false), "今天实际完成 Synthetic press 20 kg x 8。明天计划试40 kg x 5，还没做。请仅核对已经完成的负重和次数，不把明天计划写成已完成。")
        val missing = ask(fixture(null, 20, false), "今天三星每日睡眠HRV能确认吗？能否用蓝牙窗口均值代填日值或计算Z-score？请说明判断限制，不要输出虚构的日值或Z分数。")
        missing.getJSONObject("recovery_history").getJSONArray("days").getJSONObject(0).getJSONObject("sleeping_hrv")
            .put("status", "available").put("value", 54).put("source", "samsung_health_screen")
        missing.put("nightly_recovery", JSONObject().put("records", JSONArray()))
        missing.getJSONObject("ring_bluetooth").put("sessions", JSONArray().put(JSONObject().put("wake_date", day.toString())
            .put("session_ref", "synthetic-session").put("validation", "experimental_protocol_decode_not_vendor_daily_summary")
            .put("metrics", JSONObject().put("hrv_rmssd", JSONObject().put("mean", 61.25).put("sample_count", 100)
                .put("unit", "protocol_units_pending_reference_validation").put("same_night_reference_validation", "pending")))))
        val conflicting = ask(fixture(123, 20, false), "请说明今天厂商每日睡眠HRV能否确认唯一值；若记录矛盾请明确说明，不要取平均当实测日值。")
        conflicting.put("nightly_recovery", JSONObject().put("records", JSONArray()
            .put(vendor(54, "synthetic-first")).put(vendor(76, "synthetic-second"))))
        var calls = 0
        for ((name, facts) in listOf("estimated_partial_day" to estimated, "planned_not_completed" to planned,
            "missing_vendor_experimental_ring" to missing, "conflicting_vendor" to conflicting)) {
            val selected = CoachingRequestPackager.prepare(facts, system).retainedContext
            selected.getJSONObject("request_manifest").apply { remove("model_request_sha256"); remove("snapshot_kind") }
            val plain = QwenClient.request(selected, system, false)
            val packed = QwenClient.request(selected, system, true)
            assertTrue("Fixture must exercise the packed schema", packed.toString().toByteArray(Charsets.UTF_8).size < plain.toString().toByteArray(Charsets.UTF_8).size)
            for ((format, request) in listOf("plain" to plain, "packed" to packed)) {
                val advice = try { QwenClient.response(QwenClient.post(request, key)) }
                    catch (e: Exception) { throw AssertionError("Synthetic semantic provider failure: ${e.javaClass.simpleName}") }
                calls++
                val training = advice.training.joinToString(" ")
                val diet = advice.diet.joinToString(" ")
                val all = (advice.notes + advice.training + advice.diet).joinToString(" ")
                when (name) {
                    "estimated_partial_day" -> {
                        assertTrue("Estimated intake amount missing", diet.contains("47"))
                        assertTrue("Estimate must remain qualified", Regex("估算|估计|大约|约|并非.*实测|不是.*实测|非.*实测").containsMatchIn(diet + all))
                        assertTrue("Partial coverage/unknown expenditure must remain explicit", Regex("无法|不能|不代表|不完整|不足|未记录|缺少|部分|仅").containsMatchIn(all))
                    }
                    "planned_not_completed" -> {
                        assertTrue("Actual performance missing", training.contains("20") && training.contains("8"))
                        assertFalse("Future plan presented as completed", Regex("(?:已完成|完成了)\\s*40|40\\s*(?:kg|公斤|千克)[^。；\\n]{0,8}(?:已完成|完成了)", RegexOption.IGNORE_CASE).containsMatchIn(training))
                    }
                    "missing_vendor_experimental_ring" -> {
                        assertTrue("Missing/unvalidated evidence must stay explicit", Regex("不能|无法|不足|未确认|未知|缺失|缺少|没有|尚无|有限|不确定").containsMatchIn(all))
                        assertFalse("Invented Z-score", Regex("Z\\s*[-－]?\\s*(?:score|分数|评分)?\\s*[=:：]\\s*[+-]?\\d", RegexOption.IGNORE_CASE).containsMatchIn(all))
                        assertFalse("Experimental window substituted as today's vendor value", Regex("今日(?:三星|厂商)?(?:每日)?睡眠\\s*HRV\\s*[:：为是=]\\s*61\\.25", RegexOption.IGNORE_CASE).containsMatchIn(all))
                    }
                    "conflicting_vendor" -> assertTrue("Conflicting records must remain unresolved", Regex("不一致|冲突|矛盾|无法确定|不能确定|不确定|不能确认|无法确认|唯一值.*无法|唯一值.*不能").containsMatchIn(all))
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "CONTEXT_SEMANTICS case=$name format=$format passed=true\n") })
            }
        }
        assertEquals(8, calls)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "CONTEXT_SEMANTICS cases=4 formats=2 calls=8 passed=true owner_data_sent=false cards_saved=0\n") })
    }
}
