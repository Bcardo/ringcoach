package com.bcardo.ringcoach

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StandingBreakfastTest {
    private val date = LocalDate.of(2026, 1, 5)
    private val rule = StandingBreakfast(date, "合成测试早餐", mapOf("protein_grams" to 20.0, "carbohydrate_grams" to 30.0, "fat_grams" to 5.0, "intake_kcal" to 250.0))
    private fun food(id: String = "old:1", kcal: Double? = 100.0) = FoodFact(id, "一份测试午餐", listOf(id.substringBefore(':')),
        MealEstimate("测试午餐", mapOf("protein_grams" to 10.0, "carbohydrate_grams" to 12.0, "fat_grams" to 3.0, "intake_kcal" to kcal), emptyList(), if (kcal == null) listOf("未知摄入") else emptyList()))
    private fun raw(foods: List<FoodFact> = emptyList(), resolution: BreakfastResolution? = null) = NutritionExtraction(
        NUTRITION_FIELDS.associateWith { field ->
            val values = foods.map { it.estimate.values[field] }
            val v = if (field in MEAL_FIELDS && values.isNotEmpty() && values.all { it != null }) values.sumOf { it!! } else null
            NutritionValue(v, if (v == null) "missing" else "estimated", if (v == null) "" else "已吃")
        }, "partial_day", "", emptyList(), foodItems = foods, breakfast = resolution)
    private fun project(result: NutritionExtraction? = null, day: LocalDate = date) = StandingBreakfastProjection.apply(NutritionDay(day, if (result == null) "missing" else "complete", result), rule)
    private fun defaultResolution() = BreakfastResolution("default", "", emptyList(), emptyList())

    @Test fun explicitRuleParsesAndDoesNotEnableFromRecipeReference() {
        assertNull(StandingBreakfast.parse("固定早餐配方仅供参考"))
        assertEquals(rule, StandingBreakfast.parse("Instructions\n" + rule.block()))
        assertEquals(rule, StandingBreakfast.parse(StandingBreakfast.replace("Old instructions", rule)))
        assertNull(StandingBreakfast.parse(StandingBreakfast.replace(rule.block(), null)))
        assertThrows(IllegalArgumentException::class.java) { StandingBreakfast.parse(rule.block() + "\n" + rule.block()) }
        assertThrows(IllegalArgumentException::class.java) { StandingBreakfast.parse(rule.block().replace("250.0", "NaN")) }
    }
    @Test fun noDailyReportCountsDefaultOnceAndDoesNotBackfill() {
        val day = project()
        assertEquals(250.0, day.result!!.values.getValue("intake_kcal").value!!, 0.0)
        assertEquals("estimated", day.result.values.getValue("intake_kcal").kind)
        assertEquals("default", day.breakfast!!.getString("mode"))
        assertEquals("partial_day", day.result.coverage)
        assertEquals(day, StandingBreakfastProjection.apply(day, rule))
        assertNull(project(day = date.minusDays(1)).result)
        assertNull(StandingBreakfastProjection.apply(NutritionDay(date, "missing"), null).result)
    }
    @Test fun confirmedDefaultAddsToReportedLunchButPreservesStoredFacts() {
        val resolution = BreakfastResolution("confirmed_default", "早餐照旧", listOf("next"), emptyList())
        val original = raw(listOf(food()), resolution)
        val day = project(original)
        assertEquals(350.0, day.result!!.values.getValue("intake_kcal").value!!, 0.0)
        assertEquals(2, day.result.foodItems.size)
        assertEquals(1, original.foodItems.size)
        assertEquals(100.0, original.values.getValue("intake_kcal").value!!, 0.0)
        assertEquals("confirmed_default", day.toJson().getJSONObject("standing_breakfast").getString("mode"))
    }
    @Test fun skipsReplacementAndWholeDayTotalsNeverAddDefault() {
        for (mode in listOf("skipped", "replaced", "adjusted", "already_recorded", "unresolved")) {
            val ids = if (mode in setOf("replaced", "adjusted", "already_recorded")) listOf("old:1") else emptyList()
            val day = project(raw(listOf(food()), BreakfastResolution(mode, "早餐说明", listOf("old"), ids)))
            assertEquals(100.0, day.result!!.values.getValue("intake_kcal").value!!, 0.0)
            assertFalse(day.breakfast!!.getBoolean("included"))
        }
        val total = raw(listOf(food())).copy(coverage = "daily_total", scopeEvidence = "全天总量")
        assertEquals("included_in_reported_daily_total", project(total).breakfast!!.getString("mode"))
    }
    @Test fun unknownOtherNutritionAndUnreconciledExistingFoodStayUnknown() {
        assertEquals("needs_reconciliation", project(raw(listOf(food()))).breakfast!!.getString("mode"))
        val unknown = project(raw(listOf(food(kcal = null)), defaultResolution()))
        assertNull(unknown.result!!.values.getValue("intake_kcal").value)
        assertEquals(250.0, unknown.breakfast!!.getJSONObject("contribution").getDouble("intake_kcal"), 0.0)
        assertEquals("waiting_for_recognition", StandingBreakfastProjection.apply(NutritionDay(date, "pending"), rule).breakfast!!.getString("mode"))
    }
    @Test fun overridePersistsAcrossUnrelatedTurnsAndRequiresNewEvidenceToChange() {
        val prior = raw(resolution = BreakfastResolution("skipped", "早餐没吃", listOf("old"), emptyList()))
        val job = PendingNutrition(date, "next", "", "午餐已吃", true, NutritionTurn("next", "午餐已吃", emptyList(), 2, "old", prior, "pending"), breakfastRule = rule)
        BreakfastResolution.validate(job, raw(resolution = prior.breakfast))
        assertThrows(IllegalArgumentException::class.java) { BreakfastResolution.validate(job, raw(resolution = defaultResolution())) }
        assertThrows(IllegalArgumentException::class.java) { BreakfastResolution.validate(job, raw(resolution = BreakfastResolution("confirmed_default", "早餐照旧", listOf("old", "next"), emptyList()))) }
        val confirmed = job.copy(turn = job.turn!!.copy(input = "早餐照旧"))
        BreakfastResolution.validate(confirmed, raw(resolution = BreakfastResolution("confirmed_default", "早餐照旧", listOf("old", "next"), emptyList())))
    }
    @Test fun extractorReceivesRecipeAndRuleButPreviousStateHasNoProjectedFoods() {
        val previous = raw(listOf(food()), defaultResolution())
        val job = PendingNutrition(date, "next", "", "午餐已吃", true, NutritionTurn("next", "午餐已吃", emptyList(), 2, "old", previous, "pending"), "Saved recipe reference", rule)
        val request = NutritionExtractor.request(job, "Extract")
        val input = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(rule.toJson().toString(), input.getJSONObject("standing_breakfast_rule").toString())
        assertEquals("Saved recipe reference", input.getString("saved_diet_plan"))
        assertEquals(1, input.getJSONObject("previous_daily_state").getJSONArray("food_items").length())
        assertTrue(request.getJSONObject("response_format").getJSONObject("json_schema").getJSONObject("schema").getJSONArray("required").toString().contains("breakfast_override"))
        assertEquals(previous, NutritionExtractor.decode(previous.toJson().toString(), "已吃"))
    }
    @Test fun addingFoodAfterReportedWholeDayTotalDoesNotReviveDefault() {
        val resolution = BreakfastResolution("already_recorded", "全天总量", listOf("old"), listOf("old:1"))
        val previous = raw(listOf(food()), resolution).copy(coverage = "daily_total", scopeEvidence = "全天总量")
        val next = raw(listOf(food(), food("next:1")), resolution)
        val job = PendingNutrition(date, "next", "", "又吃一份", true,
            NutritionTurn("next", "又吃一份", emptyList(), 2, "old", previous, "pending"), breakfastRule = rule)
        BreakfastResolution.validate(job, next)
        assertEquals(200.0, project(next).result!!.values.getValue("intake_kcal").value!!, 0.0)
        assertThrows(IllegalArgumentException::class.java) { BreakfastResolution.validate(job, next.copy(breakfast = defaultResolution())) }
    }
}
