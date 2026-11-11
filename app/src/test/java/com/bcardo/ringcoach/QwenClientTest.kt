package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenClientTest {
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
