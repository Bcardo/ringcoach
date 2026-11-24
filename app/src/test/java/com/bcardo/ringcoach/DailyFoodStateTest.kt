package com.bcardo.ringcoach

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DailyFoodStateTest {
    private val date = LocalDate.of(2026, 11, 11)
    private fun food(id: String, kcal: Double = 165.0, refs: List<String> = listOf(id.substringBefore(':'))) = FoodFact(
        id, "100g", refs, MealEstimate("已吃的测试食物", mapOf("protein_grams" to 10.0, "carbohydrate_grams" to 20.0,
            "fat_grams" to 5.0, "intake_kcal" to kcal), listOf("合成测试估算"), emptyList()))
    private fun result(foods: List<FoodFact>, revised: List<String> = emptyList()) = NutritionExtraction(
        NUTRITION_FIELDS.associateWith { field ->
            val values = foods.map { it.estimate.values[field] }
            val value = if (field in MEAL_FIELDS && values.isNotEmpty() && values.all { it != null }) values.sumOf { it!! } else null
            NutritionValue(value, if (value == null) "missing" else "estimated", if (value == null) "" else "已吃")
        }, "partial_day", "", emptyList(), "测试食品", emptyList(), foods, revised)
    private fun job(previous: NutritionExtraction? = null, photos: List<String> = emptyList()) = PendingNutrition(date,
        "next", "", "已吃\n" + photos.joinToString("\n") { "[照片 $it]" }, true,
        NutritionTurn("next", "新输入", photos, 2, "old", previous, "pending"))

    @Test fun additionPreservesConvertedFactsAndCorrectionUsesSameId() {
        val old = food("old:1")
        DailyFoodState.validate(job(result(listOf(old))), result(listOf(old, food("next:1"))))
        val corrected = old.copy(submissionIds = listOf("old", "next"), estimate = old.estimate.copy(
            values = old.estimate.values.mapValues { it.value?.div(2) }, description = "只吃了一半"))
        DailyFoodState.validate(job(result(listOf(old))), result(listOf(corrected), listOf(old.id)))
    }

    @Test fun changedMassCannotKeepAllOldNutrientsButReestimateCanPass() {
        val old = food("old:1").copy(portion = "350 g")
        val changed = old.copy(portion = "700 g", submissionIds = listOf("old", "next"))
        try {
            DailyFoodState.validate(job(result(listOf(old))), result(listOf(changed), listOf(old.id)))
            fail("A changed mass with stale nutrients was accepted")
        } catch (e: NutritionValidationException) { assertEquals("portion_not_recalculated", e.code) }
        val recalculated = changed.copy(estimate = changed.estimate.copy(values = changed.estimate.values.mapValues { it.value?.times(2) }))
        DailyFoodState.validate(job(result(listOf(old))), result(listOf(recalculated), listOf(old.id)))
        val equivalentUnits = old.copy(portion = "0.35 kg", submissionIds = listOf("old", "next"))
        DailyFoodState.validate(job(result(listOf(old))), result(listOf(equivalentUnits), listOf(old.id)))
    }

    @Test fun staleMassCorrectionGetsOneRepairAndPreservesOtherFoods() {
        val old = food("old:1").copy(portion = "350g")
        val other = food("old:2")
        val next = job(result(listOf(old, other)))
        val stale = old.copy(portion = "700g", submissionIds = listOf("old", "next"))
        val corrected = stale.copy(estimate = stale.estimate.copy(values = stale.estimate.values.mapValues { it.value?.times(2) }))
        var calls = 0
        val parsed = NutritionExtractor.extract(next, "Extract", emptyList(), "synthetic-key") { _, _ ->
            calls++
            val answer = result(listOf(if (calls == 1) stale else corrected, other), listOf(old.id))
            JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", answer.toJson().toString())))).toString()
        }
        assertEquals(2, calls)
        assertEquals(corrected, parsed.foodItems.first())
        assertEquals(other, parsed.foodItems.last())
        assertEquals(60.0, parsed.values.getValue("carbohydrate_grams").value!!, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnrequestedDriftInOldFood() {
        DailyFoodState.validate(job(result(listOf(food("old:1")))), result(listOf(food("old:1", 180.0))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDailyTotalsThatDisagreeWithReusableFacts() {
        val valid = result(listOf(food("next:1")))
        DailyFoodState.validate(job(), valid.copy(values = valid.values + ("intake_kcal" to NutritionValue(999.0, "estimated", "已吃"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNewFoodWithAnotherSubmissionId() {
        DailyFoodState.validate(job(), result(listOf(food("unrelated:1"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCorrectionWithoutTheNewSourceReference() {
        DailyFoodState.validate(job(result(listOf(food("old:1")))), result(listOf(food("old:1", 180.0)), listOf("old:1")))
    }

    @Test fun factsRoundTripAndLegacyTotalsStayOneExplicitAggregate() {
        val original = result(listOf(food("old:1")))
        assertEquals(original, NutritionExtractor.decode(original.toJson().toString(), "已吃"))
        val legacy = DailyFoodState.bootstrap(date, original.copy(foodItems = emptyList()))
        assertEquals(1, legacy.foodItems.size)
        assertEquals("legacy:$date", legacy.foodItems.single().id)
        assertEquals(original.values, legacy.values)
    }

    @Test fun nextRequestContainsConvertedStateAndOnlyNewPhotoBytes() {
        val next = job(result(listOf(food("old:1"))), listOf("new-photo"))
        val request = NutritionExtractor.request(next, "Extract", listOf("new-photo" to byteArrayOf(9, 8, 7)))
        val parts = request.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        val data = JSONObject(parts.getJSONObject(0).getString("text"))
        assertEquals("old:1", data.getJSONObject("previous_daily_state").getJSONArray("food_items").getJSONObject(0).getString("id"))
        assertEquals("next", data.getJSONObject("new_input").getString("submission_id"))
        assertEquals(1, (0 until parts.length()).count { parts.getJSONObject(it).getString("type") == "image_url" })
        assertTrue(parts.getJSONObject(2).getJSONObject("image_url").getString("url").endsWith("CQgH"))
        val textOnly = NutritionExtractor.request(job(result(listOf(food("old:1")))), "Extract")
        assertFalse(textOnly.toString().contains("data:image/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReattachingAnOldImageEvenWhenItsMarkerRemainsInTextHistory() {
        val next = job(result(listOf(food("old:1"))), listOf("new-photo"))
            .copy(additional = "已吃\n[照片 old-photo]\n[照片 new-photo]")
        NutritionExtractor.request(next, "Extract", listOf("old-photo" to byteArrayOf(1)))
    }

    @Test fun imageLabelNumbersRemainEstimatesRatherThanTypedReports() {
        val job = job(photos = listOf("picture"))
        val raw = result(listOf(food("next:1"))).toJson()
        for (field in MEAL_FIELDS) raw.getJSONObject(field).put("kind", "reported").put("evidence", "[照片 picture]")
        val parsed = NutritionExtractor.decodeForJob(raw.toString(), job)
        MEAL_FIELDS.forEach { assertEquals("estimated", parsed.values.getValue(it).kind) }
        assertEquals(165.0, parsed.values.getValue("intake_kcal").value!!, 0.0)
    }

    @Test fun unchangedFoodExplanationsRetainTheirOriginalWordingAndSource() {
        val old = food("old:1")
        val previous = result(listOf(old))
        val changedWording = old.copy(estimate = old.estimate.copy(assumptions = listOf("改写估算说明"), questions = listOf("重复问题")))
        val raw = result(listOf(changedWording, food("next:1")))
        val parsed = NutritionExtractor.decodeForJob(raw.toJson().toString(), job(previous))
        assertEquals(old, parsed.foodItems.first())
        DailyFoodState.validate(job(previous), parsed)
        val corrected = changedWording.copy(submissionIds = listOf("old", "next"))
        val revised = result(listOf(corrected), listOf(old.id))
        assertEquals(corrected, NutritionExtractor.decodeForJob(revised.toJson().toString(), job(previous)).foodItems.single())
        val badNutrients = changedWording.copy(estimate = changedWording.estimate.copy(values = changedWording.estimate.values + ("intake_kcal" to 999.0)))
        val drift = NutritionExtractor.decodeForJob(result(listOf(badNutrients)).toJson().toString(), job(previous))
        try { DailyFoodState.validate(job(previous), drift); fail("Unrequested nutrient drift accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun invalidOldFoodDriftGetsOneRepairWithoutAnyOldImage() {
        val old = food("old:1")
        val job = job(result(listOf(old)))
        val requests = mutableListOf<JSONObject>()
        val repaired = result(listOf(old, food("next:1")))
        val invalid = result(listOf(old.copy(estimate = old.estimate.copy(description = "无依据改写")), food("next:1")))
        val parsed = NutritionExtractor.extract(job, "Extract", emptyList(), "synthetic-key") { request, _ ->
            requests.add(JSONObject(request.toString()))
            val answer = if (requests.size == 1) invalid else repaired
            JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", answer.toJson().toString())))).toString()
        }
        assertEquals(repaired.copy(totalsSource = "local_food_fact_sum_v1"), parsed)
        assertEquals(2, requests.size)
        assertTrue(requests.all { !it.toString().contains("data:image/") })
    }
}
