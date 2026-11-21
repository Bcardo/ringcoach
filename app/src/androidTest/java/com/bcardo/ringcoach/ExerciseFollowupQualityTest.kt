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

/** Opt-in finite provider checks: synthetic facts, no saved cards or response-text diagnostics. */
@RunWith(AndroidJUnit4::class)
class ExerciseFollowupQualityTest {
    @Test fun sameCallProducesLinkedConditionalProposalsAndLeavesDietUpdatesEmpty() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_exercise_followups") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val key = requireNotNull(QwenCredentials(instrumentation.targetContext).read())
        val prompt = instrumentation.context.assets.open("coach-system.txt").bufferedReader().use { it.readText() }
        var failures = 0
        for (case in listOf("progression", "partial", "maintain", "diet")) {
            val partial = case == "partial"
            val context = JSONObject().put("request_date", "2026-06-12")
                .put("routine", "Day A: Synthetic press, 3 sets of 8 at 20 kg total. Only after all sets completed AND explicitly reported RPE <=7, next occurrence may increase by 2 kg total. Otherwise maintain. User chooses workout days; no fixed rotation.")
                .put("instructions", "只依据明确证据，不虚构RPE、恢复或完成情况。给中文建议。")
                .put("exercise_baselines", JSONArray().put(ExerciseBaseline("Day A", "Synthetic press", "20 kg total, 3 x 8", LocalDate.of(2026, 6, 1)).toJson()))
                .put("latest_workout_records", JSONArray().put(JSONObject().put("date", "2026-06-12").put("exercise", "Synthetic press")
                    .put("performance", if (partial) "20 kg total; 2 x 8 completed; third set not done; RPE unknown" else "20 kg total; 3 x 8 completed; RPE6")
                    .put("status", if (partial) "partial" else "completed")))
                .put("progress_submission", JSONObject().put("text", when (case) {
                    "progression" -> "刚完成上述训练，按保存规则，下次同动作如何安排？"
                    "partial" -> "刚完成上述部分训练，下次同动作怎么做？没有报告RPE。"
                    "maintain" -> "刚完成上述训练，但我明确不想加重，下次保持20kg。请更新此动作的下次参考。"
                    else -> "只补充午餐，没有新增训练，也没有训练问题。"
                }))
            var status = "ok"; var passed = false
            val started = System.nanoTime()
            val packaged = CoachingRequestPackager.prepare(context, prompt)
            try {
                val advice = QwenClient.response(QwenClient.post(packaged.request, key))
                val notes = ExerciseFollowups.accepted(advice, context).exerciseNotes
                val text = notes.joinToString(" ") { it.text }
                passed = when (case) {
                    "progression" -> notes.size == 1 && text.contains("22")
                    "partial" -> notes.size == 1 && !text.contains("22")
                    "maintain" -> notes.size == 1 && text.contains("20") && !text.contains("22")
                    else -> notes.isEmpty()
                }
            } catch (e: Exception) {
                status = when(e) { is QwenHttpException -> "http_${e.status}"; is java.net.SocketTimeoutException -> "timeout"; else -> "transport_or_decode" }
            }
            if (!passed) failures++
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", "EXERCISE_FOLLOWUP case=$case passed=$passed status=$status request_bytes=${packaged.size.requestBytes} latency_ms=${(System.nanoTime()-started)/1_000_000}\n")
            })
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "EXERCISE_FOLLOWUP calls=4 failures=$failures owner_data_sent=false cards_saved=0\n") })
        assertEquals("Finite provider behavior checks failed; inspect fixed diagnostics", 0, failures)
    }
}
