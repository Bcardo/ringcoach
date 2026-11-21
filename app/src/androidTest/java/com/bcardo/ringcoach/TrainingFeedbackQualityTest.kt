package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in synthetic prompt comparison; no owner facts/cards or response text are read/saved/logged. */
@RunWith(AndroidJUnit4::class)
class TrainingFeedbackQualityTest {
    private data class Case(val name: String, val input: JSONObject)
    private val oldTraining = "training是训练页展示的当前可执行安排，而非泛泛评论。根据已保存routine、动作编号、最近完成情况与恢复状态，说明当前适用训练日、动作和已知组次数/负重及必要调整；可在每条中用换行列出多个动作。训练日或负重无法确定时明确待确认内容，不虚构。只在有明确依据时调整安排，保留用户基础计划作为长期约束。"
    private val oldReferences = "训练基准中的 training_day 和 exercise_number 是用户原始计划中的稳定动作引用。提及动作顺序时保留这些编号，不按动作名称重新排序。用户用 Day 与编号汇报时，按对应基准匹配；训练日不明确且存在多个候选时请求澄清。"

    private fun previousPrompt(current: String): String = current.lineSequence().joinToString("\n") {
        when {
            it.startsWith("training应给训练反馈") -> oldTraining
            it.startsWith("训练基准中的 training_day") -> oldReferences
            else -> it
        }
    }

    private fun fixture(text: String, partial: Boolean = false, progression: Boolean = false): JSONObject {
        val routine = if (progression) "Day A：合成推举3组，每组8次。仅当上次3组全部完成且用户明确报告RPE不超过7，下次同动作在20 kg基础上加2 kg；否则保持原负重。用户自行选择训练日，休息和游泳均由用户安排。"
            else "保存的Day A包含合成推举、合成划船、合成腿推；Day B是另一组动作。用户灵活选择训练日，无固定周安排，也没有已授权的数值加重规则。"
        val records = if (progression) listOf(Triple("合成推举", "20 kg，3组×8次，RPE6", "completed"))
            else if (partial) listOf(Triple("合成推举", "20 kg，完成2组×8次，第三组未做", "partial"))
            else listOf(Triple("合成推举", "20 kg，3组×8次", "completed"), Triple("合成划船", "30 kg，3组×10次", "completed"), Triple("合成腿推", "40 kg，3组×12次", "completed"))
        return JSONObject().put("request_date", "2026-06-12").put("request_timezone", "UTC")
            .put("routine", routine).put("diet_plan", "保留饮食计划；未提供数字目标。")
            .put("instructions", "只依据明确事实，不将未来计划当已完成，不虚构体感、动作质量、RPE或睡眠恢复评分。")
            .put("progress_submission", JSONObject().put("text", text))
            .put("latest_workout_records", JSONArray(records.map { (name, performance, status) -> JSONObject()
                .put("date", "2026-06-12").put("exercise", name).put("performance", performance)
                .put("status", status).put("record_kind", "actual_performance").put("evidence", "$name $performance") }))
            .put("nightly_recovery", JSONObject().put("records", JSONArray()))
            .put("ring_bluetooth", JSONObject().put("sessions", JSONArray()))
            .put("daily_nutrition", JSONArray().put(JSONObject().put("date", "2026-06-12").put("status", "complete")
                .put("nutrition", JSONObject().put("coverage", "partial_day").put("protein_grams", JSONObject().put("value", 47).put("kind", "estimated")))))
    }

    private fun cases() = listOf(
        Case("completed_update", fixture("汇报完成Day A：合成推举20 kg 3组×8，合成划船30 kg 3组×10，合成腿推40 kg 3组×12。")),
        Case("explicit_progression", fixture("今天合成推举20 kg，3组8次全部完成，RPE6。按我保存的规则，下次做同动作该用多少重量？", progression = true)),
        Case("partial_tired", fixture("合成推举20 kg只完成2组8次，第三组没做，今天很疲劳。下次同动作怎么安排？", partial = true)),
        Case("diet_only", fixture("仅补充午餐：已记录蛋白质估算47克；没有新的训练汇报，也没有训练问题。")),
        Case("explicit_recall", fixture("请仅核对已记录的合成推举负重和每组次数，不需要新的训练安排。")),
    )

    private fun passes(name: String, advice: CoachAdvice): Boolean {
        val training = advice.training.joinToString(" ")
        val next = Regex("下次|下一次|保持|维持|建议|观察|留意|先|暂不|无需|不必").containsMatchIn(training)
        return when (name) {
            "completed_update" -> next && listOf(20, 30, 40).count { Regex("$it\\s*(?:kg|公斤|千克)", RegexOption.IGNORE_CASE).containsMatchIn(training) } < 3
            "explicit_progression" -> training.contains("22") && Regex("下次|下一次").containsMatchIn(training)
            "partial_tired" -> next && !training.contains("22")
            "diet_only" -> training.length <= 160 && listOf("合成推举", "合成划船", "合成腿推").none(training::contains)
            "explicit_recall" -> training.contains("20") && training.contains("8")
            else -> false
        }
    }

    @Test fun liveSyntheticComparisonKeepsFeedbackActionableAndFactsHonest() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_training_feedback") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val key = requireNotNull(QwenCredentials(context).read()) { "Saved provider credential required" }
        // Read the candidate from the test APK so it can be evaluated before replacing the owner app.
        val current = instrumentation.context.assets.open("coach-system.txt").bufferedReader().use { it.readText() }
        require(current.lineSequence().count { it.startsWith("training应给训练反馈") } == 1)
        val previous = previousPrompt(current)
        var calls = 0; var currentFailures = 0; var transportFailures = 0; var previousPasses = 0
        for (case in cases()) {
            // Alternate arm order to avoid giving every new-prompt call the same sequence position.
            val arms = listOf("previous" to previous, "current" to current).let { if (calls % 4 == 0) it else it.reversed() }
            for ((arm, prompt) in arms) {
                val request = QwenClient.request(case.input, prompt, true)
                val bytes = request.toString().toByteArray(Charsets.UTF_8).size
                val started = System.nanoTime()
                var status = "ok"; var passed = false
                try {
                    require(bytes <= 131072); calls++
                    val advice = QwenClient.response(QwenClient.post(request, key))
                    passed = passes(case.name, advice)
                    if (arm == "current" && !passed) currentFailures++
                    if (arm == "previous" && passed) previousPasses++
                } catch (e: Exception) {
                    transportFailures++
                    status = when (e) { is QwenHttpException -> "http_${e.status}"; is java.net.SocketTimeoutException -> "timeout"; else -> "transport_or_decode" }
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "TRAINING_FEEDBACK case=${case.name} arm=$arm passed=$passed status=$status request_bytes=$bytes latency_ms=${(System.nanoTime() - started) / 1_000_000}\n")
                })
            }
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "TRAINING_FEEDBACK cases=5 calls=$calls current_failures=$currentFailures previous_passes=$previousPasses transport_failures=$transportFailures owner_data_sent=false cards_saved=0\n")
        })
        assertEquals("Provider comparison incomplete", 10, calls)
        assertEquals("Provider/response failure; inspect fixed diagnostics", 0, transportFailures)
        assertEquals("Current synthetic behavior check failed", 0, currentFailures)
    }
}
