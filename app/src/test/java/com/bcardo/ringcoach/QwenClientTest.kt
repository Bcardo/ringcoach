package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenClientTest {
    @Test
    fun coachingKeepsConvertedFactsAndPriorAdviceButNotInternalAcknowledgmentIds() {
        val food = JSONObject().put("id", "meal:1").put("intake_kcal", 300)
        val context = JSONObject().put("instructions", "偏好")
            .put("nutrition_turn_ids", JSONArray(listOf("internal-turn")))
            .put("progress_submission_id", "internal-coach-update")
            .put("daily_nutrition", JSONArray().put(JSONObject().put("food_items", JSONArray().put(food))))
            .put("previous_advice", JSONObject().put("diet", JSONArray(listOf("此前建议，不等于已吃"))))
        val request = QwenClient.request(context, "通用要求")
        val facts = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertFalse(facts.has("nutrition_turn_ids"))
        assertFalse(facts.has("progress_submission_id"))
        assertEquals("meal:1", facts.getJSONArray("daily_nutrition").getJSONObject(0).getJSONArray("food_items").getJSONObject(0).getString("id"))
        assertTrue(facts.has("previous_advice"))
        assertTrue(context.has("nutrition_turn_ids"))
    }

    @Test
    fun buildsStructuredRequestWithoutMutatingReviewedContext() {
        val context = JSONObject().put("instructions", "自定义偏好").put("request_date", "2025-01-10")
        val request = QwenClient.request(context, "通用要求")
        assertEquals(QWEN_MODEL, request.getString("model"))
        assertFalse(request.getBoolean("enable_thinking"))
        assertEquals("自定义偏好", context.getString("instructions"))
        val messages = request.getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getString("content").contains("自定义偏好"))
        val data = JSONObject(messages.getJSONObject(1).getString("content"))
        assertFalse(data.has("instructions"))
        assertEquals("2025-01-10", data.getString("request_date"))
        assertEquals("json_schema", request.getJSONObject("response_format").getString("type"))
        assertEquals("https://token-plan.maas.qianwenaiapi.com/compatible-mode/v1/chat/completions",
            QwenClient.endpoint("sk-sp-fake"))
        assertEquals("https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions",
            QwenClient.endpoint("sk-ws-fake"))
    }

    @Test
    fun acceptsCompleteCoachCard() {
        val expected = CoachAdvice(listOf("训练建议"), listOf("饮食建议"), listOf("数据缺口"))
        assertEquals(expected, QwenClient.response(response(expected, "stop")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedReply() {
        val expected = CoachAdvice(listOf("训练建议"), listOf("饮食建议"), listOf("数据缺口"))
        QwenClient.response(response(expected, "length"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingAdviceSections() {
        CoachAdvice.decode("{\"training\":[\"内容\"]}")
    }

    private fun response(advice: CoachAdvice, reason: String): String = JSONObject().put("choices", JSONArray()
        .put(JSONObject().put("finish_reason", reason)
            .put("message", JSONObject().put("content", advice.toJson().toString())))).toString()
}
