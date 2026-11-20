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

/** Explicit live-provider opt-in. Six calls contain synthetic fixtures only; no cards/facts are saved. */
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
}
