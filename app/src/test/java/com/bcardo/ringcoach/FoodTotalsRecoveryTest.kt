package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FoodTotalsRecoveryTest {
    private val date = LocalDate.of(2026, 11, 12)
    private val text = "已吃测试加餐和蛋白粉30g"
    private fun food(id: String, name: String, p: Double?, c: Double = 2.0, f: Double = 1.0, kcal: Double = 120.0) =
        FoodFact(id, "30g", listOf(id.substringBefore(':')), MealEstimate(name,
            mapOf("protein_grams" to p, "carbohydrate_grams" to c, "fat_grams" to f, "intake_kcal" to kcal), emptyList(),
            if (p == null) listOf("合成测试：蛋白质成分未知") else emptyList()))
    private fun state(foods: List<FoodFact>, amount: Double = 999.0) = NutritionExtraction(NUTRITION_FIELDS.associateWith {
        if (it in MEAL_FIELDS) NutritionValue(amount, "estimated", text) else NutritionValue(null, "missing", "")
    }, "partial_day", "", emptyList(), foodItems = foods)
    private val previous = state(listOf(food("old:1", "测试午餐", 20.0, 30.0, 5.0, 245.0)), 20.0)
    private fun job() = PendingNutrition(date, "next", "", text, true,
        NutritionTurn("next", text, emptyList(), 2, "old", previous, "failed"))
    private fun protein(foodId: String?) = SupplementEvent("next:supplement:1", date, "蛋白粉", 30.0, "g", null,
        "taken", text, listOf("next"), foodId)
    private fun wire(result: NutritionExtraction) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("content", result.toJson().toString())))).toString()

    @Test fun wrongModelTotalsAreReplacedByExactFoodSumsWithoutAnotherModelCall() {
        val raw = state(previous.foodItems + food("next:1", "测试加餐", 10.0))
        var calls = 0
        val result = NutritionExtractor.extract(job(), "Extract", emptyList(), "synthetic-key") { _, _ -> calls++; wire(raw) }
        assertEquals(1, calls)
        assertEquals(30.0, result.values["protein_grams"]!!.value!!, 0.0)
        assertEquals(365.0, result.values["intake_kcal"]!!.value!!, 0.0)
        assertEquals(previous.foodItems.single(), result.foodItems.first())
        assertEquals("local_food_fact_sum_v1", result.totalsSource)
        assertEquals(result, NutritionExtractor.decode(result.toJson().toString(), job().source))
    }

    @Test fun wrongSupplementFoodLinkMustBeRepairedBeforeAnyTotalsAreAccepted() {
        val snack = food("next:snack", "测试加餐", 10.0)
        val wrong = state(previous.foodItems + snack).copy(supplements = listOf(protein(snack.id)))
        val powder = food("next:powder", "蛋白粉", 24.0)
        val repaired = state(previous.foodItems + snack + powder).copy(supplements = listOf(protein(powder.id)))
        var calls = 0
        val result = NutritionExtractor.extract(job(), "Extract", emptyList(), "synthetic-key") { _, _ ->
            calls++; wire(if (calls == 1) wrong else repaired)
        }
        assertEquals(2, calls)
        assertEquals(54.0, result.values["protein_grams"]!!.value!!, 0.0)
        assertEquals(485.0, result.values["intake_kcal"]!!.value!!, 0.0)
        assertEquals(powder.id, result.supplements.single().foodId)
    }

    @Test fun missingCaloricSupplementFactCannotDisappearThroughArithmeticNormalization() {
        val incomplete = state(previous.foodItems).copy(supplements = listOf(protein(null)))
        try { DailyFoodState.calculated(job(), incomplete); fail("Missing protein powder accepted") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("linked food")) }
    }

    @Test fun unknownNutrientStaysUnknownWhileOtherKnownNutrientsStillSum() {
        val raw = state(previous.foodItems + food("next:1", "未知成分测试食物", null))
        val result = DailyFoodState.calculated(job(), raw)
        assertNull(result.values["protein_grams"]!!.value)
        assertEquals("missing", result.values["protein_grams"]!!.kind)
        assertEquals(365.0, result.values["intake_kcal"]!!.value!!, 0.0)
    }

    @Test fun arithmeticFixDoesNotAllowUnrequestedChangesToOldFoodOrLostSources() {
        val changed = previous.foodItems.single().copy(portion = "无依据新份量")
        try { DailyFoodState.calculated(job(), state(listOf(changed))); fail("Old fact drift accepted") }
        catch (_: IllegalArgumentException) { }
        val wrongSource = food("next:1", "测试加餐", 10.0).copy(submissionIds = listOf("other"))
        try { DailyFoodState.calculated(job(), state(previous.foodItems + wrongSource)); fail("Invalid source accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun unchangedLegacyFoodLinkWithAnOlderAliasRemainsUsable() {
        val oldFood = food("old:powder", "此前已转换的产品", 24.0)
        val oldEvent = protein(oldFood.id).copy(id = "old:supplement:1", submissionIds = listOf("old"))
        val oldState = state(listOf(oldFood)).copy(supplements = listOf(oldEvent))
        val next = job().copy(turn = job().turn!!.copy(previous = oldState))
        val result = DailyFoodState.calculated(next, oldState)
        assertEquals(oldState.foodItems, result.foodItems)
        assertEquals(oldState.supplements, result.supplements)
    }
}
