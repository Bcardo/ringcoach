package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NutritionExtractionTest {
    @Test fun aggregateEvidenceCanCiteSeveralSavedUpdatesWithoutInventingAQuote() {
        val json = blank().put("protein_grams", JSONObject().put("value", 35).put("kind", "estimated")
            .put("evidence", "早餐蛋白质10g\n午餐蛋白质25g"))
            .put("food_summary", "早餐和午餐")
            .put("assumptions", JSONArray(listOf("两次记录合计")))
            .put("unresolved", JSONArray(listOf("蛋白粉是什么品牌？")))
        val result = NutritionExtractor.decode(json.toString(), "早餐蛋白质10g\n[更新 lunch]\n午餐蛋白质25g")
        assertEquals(35.0, result.values.getValue("protein_grams").value!!, 0.0)
        assertEquals("早餐和午餐", result.foodSummary)
        assertEquals(listOf("蛋白粉是什么品牌？"), result.unresolved)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnInventedFragmentAmongValidEvidenceLines() {
        val json = blank().put("intake_kcal", JSONObject().put("value", 300).put("kind", "estimated")
            .put("evidence", "早餐鸡蛋\n午餐牛肉"))
        NutritionExtractor.decode(json.toString(), "早餐鸡蛋\n午餐米饭")
    }

    @Test fun multimodalRequestPairsEveryImageWithItsDatedSourceMarker() {
        val date = LocalDate.of(2026, 11, 11)
        val job = PendingNutrition(date, "update", "", "午餐和照片是同一餐\n[照片 lunch]", true)
        val request = NutritionExtractor.request(job, "Extract", listOf("lunch" to byteArrayOf(1, 2, 3)))
        val parts = request.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertTrue(parts.getJSONObject(0).getString("text").contains(date.toString()))
        assertEquals("[照片 lunch]", parts.getJSONObject(1).getString("text"))
        assertEquals("data:image/jpeg;base64,AQID", parts.getJSONObject(2).getJSONObject("image_url").getString("url"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsImageThatIsNotInTheJobSnapshot() {
        NutritionExtractor.request(PendingNutrition(LocalDate.of(2026, 11, 11), "update", "", "[照片 lunch]", true),
            "Extract", listOf("different-date-or-update" to byteArrayOf(1)))
    }

    private fun blank(): JSONObject = JSONObject().apply {
        for (field in NUTRITION_FIELDS) put(field, JSONObject().put("value", JSONObject.NULL)
            .put("kind", "missing").put("evidence", ""))
        put("coverage", "unknown").put("scope_evidence", "").put("unresolved", JSONArray())
    }

    @Test
    fun preservesMissingnessAndCalculatesOnlyKnownBalance() {
        val json = blank()
        val missing = NutritionExtractor.decode(json.toString(), "No nutrition")
        assertEquals(null, missing.balance)
        json.put("intake_kcal", JSONObject().put("value", 1700).put("kind", "reported").put("evidence", "Total 1700 kcal"))
        assertEquals(null, NutritionExtractor.decode(json.toString(), "Total 1700 kcal, spent 1900 kcal").balance)
        json.put("expenditure_kcal", JSONObject().put("value", 1900).put("kind", "reported").put("evidence", "spent 1900 kcal"))
        val result = NutritionExtractor.decode(json.toString(), "Total 1700 kcal, spent 1900 kcal")
        assertEquals(-200.0, result.balance!!, 0.0)
        assertEquals(null, result.values.getValue("protein_grams").value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReportedValueWithoutItsNumericEvidence() {
        val json = blank().put("protein_grams", JSONObject().put("value", 135)
            .put("kind", "reported").put("evidence", "Two eggs"))
        NutritionExtractor.decode(json.toString(), "Two eggs")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEstimatedBodyWeight() {
        val json = blank().put("weight_kg", JSONObject().put("value", 70)
            .put("kind", "estimated").put("evidence", "Breakfast"))
        NutritionExtractor.decode(json.toString(), "Breakfast")
    }

    @Test
    fun labelsFoodEstimatesAndSendsOnlyDietAndOtherNotes() {
        val source = "Ate 100g yogurt"
        val json = blank().put("protein_grams", JSONObject().put("value", 8)
            .put("kind", "estimated").put("evidence", source)).put("coverage", "partial_day")
        val result = NutritionExtractor.decode(json.toString(), source)
        assertEquals("estimated", result.values.getValue("protein_grams").kind)
        assertEquals("partial_day", result.coverage)
        val request = NutritionExtractor.request(PendingNutrition(LocalDate.of(2025, 1, 10), "rev", source, "Weight 70 kg"), "Extract")
        assertFalse(request.toString().contains("known_exercises"))
        val data = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(setOf("date", "journal_input"), data.keys().asSequence().toSet())
    }
}
