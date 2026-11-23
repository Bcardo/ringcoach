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

/** Synthetic provider regression. No owner input or model output is logged or stored. */
@RunWith(AndroidJUnit4::class)
class NumberedTrainingLiveTest {
    @Test fun numberedUnitlessReportAndFuturePlanRemainDistinct() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_numbered_training") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val key = requireNotNull(QwenCredentials(context).read())
        val prompt = instrumentation.context.assets.open("workout-delta.txt").bufferedReader().use { it.readText() }
        val date = LocalDate.of(2026, 6, 11)
        val baselines = (1..5).map { ExerciseBaseline("Day 3 (腿)", "Synthetic leg $it", "Not actual performance", date, it) }
        val report = "Day3\n1. 42x10x3\n2. 27x12x3\n3. 18x15\n4. 33x10x2\n5. 没做"
        fun extract(text: String) = WorkoutDeltaExtractor.extract(
            PendingWorkoutTurn("synthetic", date, 1, text, null, WorkoutDayState(), "pending"), baselines, prompt, key)
        val actual = extract(report)
        assertEquals(5, actual.records.size)
        assertTrue(actual.unresolved.isEmpty())
        for (number in 1..4) {
            val record = actual.records.single { it.observation.exercise == "Synthetic leg $number" }.observation
            assertEquals("completed", record.status)
            assertEquals(report.lines()[number].substringAfter(". "), record.performance)
        }
        assertEquals("skipped", actual.records.single { it.observation.exercise == "Synthetic leg 5" }.observation.status)
        val plan = extract("明天计划：$report")
        assertTrue(plan.records.isEmpty()); assertTrue(plan.unresolved.isEmpty())
    }

    @Test fun nextDayAdviceUsesExplicitSwimRestSelection() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_numbered_training") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val key = requireNotNull(QwenCredentials(instrumentation.targetContext).read())
        val prompt = instrumentation.context.assets.open("coach-system.txt").bufferedReader().use { it.readText() }
        val data = JSONObject().put("request_date", "2026-06-12")
            .put("routine", "Day 3为腿部训练；用户灵活选择，完成后可游泳或休息。")
            .put("instructions", "不虚构恢复数据，不重复安排已经完成的训练。")
            .put("progress_submission", JSONObject().put("text", "昨天完成Day3腿部训练，今天是游泳/休息。请给今天的安排。"))
            .put("journal_history", JSONArray().put(JSONObject().put("date", "2026-06-11").put("training", "Day3\n1. 42x10x3")))
            .put("latest_workout_records", JSONArray().put(JSONObject().put("date", "2026-06-11")
                .put("exercise", "Synthetic leg 1").put("performance", "42x10x3").put("status", "completed")))
            .put("daily_nutrition", JSONArray()).put("exercise_baselines", JSONArray())
        val advice = QwenClient.response(QwenClient.post(QwenClient.request(data, prompt, true), key))
        val text = advice.training.joinToString(" ")
        assertTrue("Today's selected activity must be addressed", Regex("游泳|休息").containsMatchIn(text))
        assertFalse("Do not prescribe synthetic leg load for today", text.contains("42"))
    }
}
