package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Opt-in requests send synthetic inputs only; credentials stay in Keystore, owner facts are never loaded. */
@RunWith(AndroidJUnit4::class)
class SupplementFeedbackLiveTest {
    @Test fun supplementOnlyFoodLinkCorrectionAndFreshFeedback() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_supplements") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "supplement-live-${UUID.randomUUID()}.db"
        var store = JournalStore(context, name)
        try {
            val key = QwenCredentials(context).read() ?: error("Saved key required")
            val prompt = context.assets.open("nutrition-extraction.txt").bufferedReader().use { it.readText() }
            val coachPrompt = context.assets.open("coach-system.txt").bufferedReader().use { it.readText() }
            val workoutPrompt = context.assets.open("workout-extraction.txt").bufferedReader().use { it.readText() }
            val date = LocalDate.of(2026, 10, 2)
            store.saveSettings(CoachingSettings("休息日，无训练安排", "休息日摄入以记录为准。补剂尚无固定计划。", "只依据给定事实"))
            fun turn(id: String, text: String): NutritionExtraction {
                store.appendProgress(id, date, text, emptyList(), true)
                val job = store.nextPendingNutrition()!!
                var answer = ""
                val result = try {
                    NutritionExtractor.extract(job, prompt, emptyList(), key) { request, credential ->
                        QwenClient.post(request, credential).also { answer = it }
                    }
                } catch (e: Exception) {
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                        putString("stream", "Synthetic supplement turn $id failed: ${runCatching { QwenClient.responseText(answer) }.getOrDefault("no response")}\n")
                    })
                    throw e
                }
                assertTrue(store.applyNutritionExtraction(job, result))
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "Synthetic turn $id: events=${result.supplements.map { it.name + ":" + it.status }} foods=${result.foodItems.size}\n")
                })
                val workout = store.nextPendingWorkout()!!
                val workoutResult = if (id == "protein") WorkoutExtractor.decode(QwenClient.responseText(QwenClient.post(
                    WorkoutExtractor.request(workout, emptyList(), workoutPrompt), key)), workout.training, emptySet())
                    else WorkoutExtraction(emptyList(), emptyList())
                if (id == "protein") assertTrue(workoutResult.records.any { it.status == "completed" && it.exercise.contains("深蹲") })
                store.applyWorkoutExtraction(workout, workoutResult)
                assertEquals(id, store.nextPendingCoaching()!!.id)
                return result
            }
            fun feedback(id: String) {
                val json = store.readyCoachingContext(date, "").toJson().put("progress_submission_id", id)
                    .put("progress_submission", store.progressSubmission(id))
                val outgoing = JSONObject(QwenClient.request(json, coachPrompt).getJSONArray("messages").getJSONObject(1).getString("content"))
                assertTrue(outgoing.getJSONObject("supplement_history").getJSONArray("days").getJSONObject(0).getJSONArray("events").length() > 0)
                val advice = QwenClient.generate(json, coachPrompt, key)
                store.saveCoachCard(SavedCoachCard(id, date, Instant.now(), QWEN_MODEL, COACH_PROMPT_VERSION, json.toString(), advice))
                assertEquals(id, store.latestCoachCard()!!.id)
                assertNull(store.nextPendingCoaching())
            }
            val first = turn("actual", "早餐后已服用肌酸3g。晚上计划服用维生素D，剂量还没决定，尚未服用。")
            val creatine = first.supplements.single { it.name == "肌酸" }
            assertEquals("taken", creatine.status)
            assertEquals(3.0, creatine.amount!!, 0.0)
            assertEquals("早餐后", creatine.intakeTime)
            assertEquals("planned", first.supplements.single { it.name == "维生素D" }.status)
            assertNull(first.supplements.single { it.name == "维生素D" }.amount)
            assertTrue(first.foodItems.isEmpty())
            feedback("actual")
            store.close(); store = JournalStore(context, name)
            val repeated = turn("repeat", "刚才说的早餐后肌酸3g还是同一次，没有再服用。维生素D仍只是计划。")
            assertEquals(first.supplements, repeated.supplements)
            feedback("repeat")
            val food = turn("protein", "已喝蛋白粉30g，时间未记录。这份蛋白粉摄入120kcal、蛋白质24g、碳水3g、脂肪1.3g。另已完成自重深蹲2组，每组10次。")
            val protein = food.supplements.single { it.name == "蛋白粉" }
            assertEquals(food.foodItems.single().id, protein.foodId)
            assertEquals(120.0, food.values["intake_kcal"]!!.value!!, 0.1)
            feedback("protein")
            val corrected = turn("correct", "更正：肌酸早餐后实际只服用2g，之前的3g记错了；维生素D今晚明确跳过，没有服用。蛋白粉不变。")
            val revised = corrected.supplements.single { it.id == creatine.id }
            assertEquals(2.0, revised.amount!!, 0.0)
            assertTrue("correct" in revised.submissionIds)
            assertEquals("skipped", corrected.supplements.single { it.name == "维生素D" }.status)
            assertEquals(food.foodItems, corrected.foodItems)
            assertEquals(food.values.mapValues { it.value.value }, corrected.values.mapValues { it.value.value })
            feedback("correct")
            val additional = turn("vitamin", "午后已服用维生素C，剂量没有记录。蛋白粉不变。")
            assertEquals(food.foodItems, additional.foodItems)
            assertNull(additional.supplements.single { it.name == "维生素C" }.amount)
            feedback("vitamin")
            val retracted = turn("retract", "撤销蛋白粉30g的摄入记录，实际没有喝，之前的记录有误。肌酸和维生素C的实际记录不变。")
            assertEquals("retracted", retracted.supplements.single { it.id == protein.id }.status)
            assertTrue(retracted.foodItems.isEmpty())
            assertNull(retracted.values["intake_kcal"]!!.value)
            feedback("retract")
            assertEquals(6, store.coachCardSummaries().size)
            println("SUPPLEMENT_LIVE turns=6 feedback_cards=6 actual_plan_dedup_correction_retraction=true mixed_workout=true food_counted_once=true")
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
