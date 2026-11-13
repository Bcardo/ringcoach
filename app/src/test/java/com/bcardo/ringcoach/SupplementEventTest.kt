package com.bcardo.ringcoach

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SupplementEventTest {
    private val date = LocalDate.of(2026, 11, 10)
    private fun event(id: String = "one:supplement:1", status: String = "taken") = SupplementEvent(
        id, date, "肌酸", 3.0, "g", "早餐后", status, "早餐后服用肌酸3g", listOf("one"))
    private fun state(events: List<SupplementEvent>) = NutritionExtraction(NUTRITION_FIELDS.associateWith {
        NutritionValue(null, "missing", "") }, "unknown", "", emptyList(), supplements = events)
    private fun job(previous: NutritionExtraction? = null, input: String = "早餐后服用肌酸3g", id: String = "one") =
        PendingNutrition(date, id, "", input, true, NutritionTurn(id, input, emptyList(), 1, null, previous, "pending"))

    @Test fun actualPlannedSkippedAndUnknownDoseRoundTripWithoutInventedNutrition() {
        for (status in listOf("taken", "planned", "skipped", "retracted")) {
            val event = event(status = status).copy(amount = null, unit = null, intakeTime = null)
            assertEquals(event, SupplementEvent.decode(event.toJson()))
            DailyFoodState.validate(job(), state(listOf(event)))
            assertTrue(state(listOf(event)).values.values.all { it.value == null })
        }
    }

    @Test fun repeatedMentionPreservesIdAndCorrectionRetractionRetainsSources() {
        val original = event()
        val previous = state(listOf(original))
        DailyFoodState.validate(job(previous, "肌酸还是之前那次，没有再吃", "two"), previous.copy(revisedSupplementIds = emptyList()))
        for (status in listOf("taken", "skipped", "retracted")) {
            val corrected = original.copy(amount = 2.0, status = status,
                evidence = "更正肌酸2g", submissionIds = listOf("one", "two"))
            DailyFoodState.validate(job(previous, "更正肌酸2g", "two"), state(listOf(corrected)).copy(revisedSupplementIds = listOf(original.id)))
        }
    }

    @Test fun rejectsUnquotedDoseTimeWrongDateAndLostProvenance() {
        fun reject(next: SupplementEvent) {
            try { DailyFoodState.validate(job(), state(listOf(next))); fail("Accepted unsupported fact") }
            catch (_: IllegalArgumentException) { }
        }
        reject(event().copy(amount = 5.0))
        reject(event().copy(intakeTime = "20:00"))
        reject(event().copy(date = date.plusDays(1)))
        reject(event().copy(submissionIds = listOf("unrelated")))
        try { DailyFoodState.validate(job(state(listOf(event())), "撤销肌酸", "two"), state(emptyList())); fail("Deleted provenance") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun repeatedReportCannotRewriteOriginalEvidenceOrSourceIds() {
        val original = event()
        val previous = state(listOf(original))
        val repeated = original.copy(evidence = "还是之前的肌酸3g", submissionIds = listOf("one", "two"))
        val raw = state(listOf(repeated)).copy(revisedSupplementIds = listOf(original.id)).toJson().toString()
        val parsed = NutritionExtractor.decodeForJob(raw, job(previous, "还是之前的肌酸3g", "two"))
        assertEquals(listOf(original), parsed.supplements)
        assertTrue(parsed.revisedSupplementIds.isEmpty())
        DailyFoodState.validate(job(previous, "还是之前的肌酸3g", "two"), parsed)
    }

    @Test fun caloricProductLinksToExactlyOneFoodAndCannotLinkPlannedIntake() {
        val food = FoodFact("one:food:1", "30g", listOf("one"), MealEstimate("蛋白粉", MEAL_FIELDS.associateWith { 30.0 }, emptyList(), emptyList()))
        val protein = event().copy(name = "蛋白粉", amount = 30.0, intakeTime = null, evidence = "服用蛋白粉30g", foodId = food.id)
        val result = state(listOf(protein)).copy(foodItems = listOf(food), values = NUTRITION_FIELDS.associateWith {
            if (it in MEAL_FIELDS) NutritionValue(30.0, "estimated", "服用蛋白粉30g") else NutritionValue(null, "missing", "") })
        DailyFoodState.validate(job(input = protein.evidence), result)
        assertEquals(30.0, result.values["protein_grams"]!!.value!!, 0.0)
        try { DailyFoodState.validate(job(input = protein.evidence), result.copy(supplements = listOf(protein.copy(status = "planned")))); fail("Planned food link") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun supplementOnlyRequestAndCoachingPayloadCarrySourceLinkedFactsAndSavedPlan() {
        val event = event()
        val previous = state(listOf(event))
        val request = NutritionExtractor.request(job(previous, "今天没有再吃肌酸", "two"), "Extract")
        val input = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(event.id, input.getJSONObject("previous_daily_state").getJSONArray("supplement_events").getJSONObject(0).getString("id"))
        val context = CoachingContext(date, CoachingSettings(diet = "已确认补剂计划：按用户说明", instructions = "约束"),
            emptyList(), emptyList(), emptyList(), nutritionDays = listOf(NutritionDay(date, "complete", previous))).toJson()
        val history = context.getJSONObject("supplement_history")
        assertEquals(date.minusDays(6).toString(), history.getString("start_date"))
        assertEquals("one", history.getJSONArray("days").getJSONObject(0).getJSONArray("events").getJSONObject(0)
            .getJSONArray("source_submission_ids").getString(0))
        assertEquals("已确认补剂计划：按用户说明", history.getJSONObject("standing_plan").getString("diet_plan"))
        val sent = JSONObject(QwenClient.request(context, "Coach").getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(history.toString(), sent.getJSONObject("supplement_history").toString())
    }
}
