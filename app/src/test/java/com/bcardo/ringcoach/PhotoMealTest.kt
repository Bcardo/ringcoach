package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PhotoMealTest {
    private val date = LocalDate.of(2025, 1, 10)
    private fun estimate(kcal: Double = 100.0) = MealEstimate("Rice and egg", MEAL_FIELDS.associateWith { kcal }, listOf("Oil estimated"), emptyList())
    private fun meal(id: String = "one", kcal: Double = 100.0) = PhotoMeal(id, date, "rev", "Ate half; sauce excluded", true,
        "complete", estimate(kcal), Instant.EPOCH, QWEN_MODEL, PHOTO_PROMPT_VERSION)
    private fun textDay(coverage: String = "partial_day") = NutritionDay(date, "complete", NutritionExtraction(
        NUTRITION_FIELDS.associateWith {
            if (it in MEAL_FIELDS) NutritionValue(500.0, "reported", "Total 500") else NutritionValue(null, "missing", "")
        }, coverage, "Total 500", emptyList()))

    @Test
    fun imageRequestIncludesOnlyPhotoAndCorrectionsWithCurrentVisionModel() {
        val request = PhotoMealClient.request(byteArrayOf(1, 2, 3), "Ate half; no sauce", "Estimate")
        assertEquals(QWEN_MODEL, request.getString("model"))
        val content = request.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertTrue(content.getJSONObject(0).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
        assertTrue(content.getJSONObject(1).getString("text").contains("Ate half; no sauce"))
        assertFalse(request.toString().contains("journal_history"))
    }

    @Test
    fun textOnlyMealDoesNotInventAnImageAttachment() {
        val request = PhotoMealClient.request(byteArrayOf(), "Ate 100g rice", "Estimate")
        val content = request.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
    }

    @Test
    fun sumsOnlyConfirmedCompletedMealsAndPreservesUnknownMacros() {
        val partial = meal("two", 200.0).copy(estimate = estimate(200.0).copy(
            values = estimate(200.0).values + ("protein_grams" to null), questions = listOf("Which filling?")))
        val meals = listOf(meal(), partial, meal("draft", 999.0).copy(status = "draft"),
            meal("not_eaten", 999.0).copy(consumed = false), meal("failed", 999.0).copy(status = "failed"))
        val day = MealNutrition.merge(NutritionDay(date, "missing"), meals, null)
        assertEquals(300.0, day.result!!.values.getValue("intake_kcal").value!!, 0.0)
        assertEquals(null, day.result.values.getValue("protein_grams").value)
        assertEquals("partial_day", day.result.coverage)
        assertEquals(null, day.result.balance)
        assertFalse(day.toJson().toString().contains("base64"))
    }

    @Test
    fun fullDayTotalOverridesPhotoMealsAndPartialTextRequiresExplicitReconciliation() {
        val meals = listOf(meal())
        val daily = MealNutrition.merge(textDay("daily_total"), meals, "separate")
        assertEquals(500.0, daily.result!!.values.getValue("intake_kcal").value!!, 0.0)
        val ambiguous = MealNutrition.merge(textDay(), meals, null)
        assertTrue(ambiguous.photoConflict)
        assertEquals(500.0, ambiguous.result!!.values.getValue("intake_kcal").value!!, 0.0)
        val included = MealNutrition.merge(textDay(), meals, "included")
        assertFalse(included.photoConflict)
        assertEquals(500.0, included.result!!.values.getValue("intake_kcal").value!!, 0.0)
        val separate = MealNutrition.merge(textDay(), meals, "separate")
        assertEquals(600.0, separate.result!!.values.getValue("intake_kcal").value!!, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeNutrition() {
        MealEstimate.decode(estimate().toJson().put("intake_kcal", -1).toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownPortionsNeedAQuestionAndNeverBecomeZero() {
        val json = estimate().toJson().put("intake_kcal", JSONObject.NULL).put("questions", JSONArray())
        MealEstimate.decode(json.toString())
    }
}
