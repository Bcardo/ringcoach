package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal const val QWEN_MODEL = "qwen3.8-flash"
internal const val COACH_PROMPT_VERSION = "coach-v10-supplement-feedback"

internal data class CoachAdvice(val training: List<String>, val diet: List<String>, val notes: List<String>) {
    fun toJson(): JSONObject = JSONObject()
        .put("training", JSONArray(training))
        .put("diet", JSONArray(diet))
        .put("notes", JSONArray(notes))

    companion object {
        fun decode(content: String): CoachAdvice {
            val json = JSONObject(content)
            require(json.keys().asSequence().toSet() == setOf("training", "diet", "notes"))
            fun section(key: String): List<String> {
                val values = json.getJSONArray(key)
                require(values.length() in 1..4)
                return (0 until values.length()).map { index ->
                    val value = values.get(index)
                    require(value is String && value.isNotBlank() && value.length <= 2000)
                    value.trim()
                }
            }
            return CoachAdvice(section("training"), section("diet"), section("notes"))
        }
    }
}

internal class QwenHttpException(val status: Int) : Exception("Qwen HTTP request failed")

internal object QwenClient {
    fun endpoint(apiKey: String): String {
        val host = if (apiKey.startsWith("sk-sp-")) "token-plan.maas.qianwenaiapi.com" else "maas.qianwenaiapi.com"
        return "https://$host/compatible-mode/v1/chat/completions"
    }

    fun request(context: JSONObject, systemPrompt: String): JSONObject {
        val fields = JSONObject()
        for (name in listOf("training", "diet", "notes")) {
            fields.put(name, JSONObject()
                .put("type", "array").put("items", JSONObject().put("type", "string"))
                .put("minItems", 1).put("maxItems", 4))
        }
        val schema = JSONObject().put("type", "object").put("properties", fields)
            .put("required", JSONArray(listOf("training", "diet", "notes"))).put("additionalProperties", false)
        val facts = JSONObject(context.toString())
        val instructions = facts.remove("instructions") as? String ?: ""
        facts.remove("progress_submission_id")
        facts.remove("nutrition_turn_ids")
        return JSONObject()
            .put("model", QWEN_MODEL)
            .put("enable_thinking", false)
            .put("max_tokens", 2000)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt + "\n\n" + instructions))
                .put(JSONObject().put("role", "user").put("content", facts.toString())))
            .put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "ringcoach_card").put("strict", true).put("schema", schema)))
    }

    fun generate(context: JSONObject, systemPrompt: String, apiKey: String): CoachAdvice {
        return response(post(request(context, systemPrompt), apiKey))
    }

    fun post(request: JSONObject, apiKey: String): String {
        val connection = URL(endpoint(apiKey)).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            val body = request.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) throw QwenHttpException(status)
            val bytes = connection.inputStream.use { it.readNBytes(65_537) }
            require(bytes.size <= 65_536)
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    fun response(content: String): CoachAdvice {
        return CoachAdvice.decode(responseText(content))
    }

    fun responseText(content: String): String {
        val choice = JSONObject(content).getJSONArray("choices").getJSONObject(0)
        require(choice.getString("finish_reason") == "stop")
        val message = choice.getJSONObject("message")
        require(!message.has("tool_calls") || message.getJSONArray("tool_calls").length() == 0)
        val answer = message.get("content")
        require(answer is String)
        return answer
    }
}
