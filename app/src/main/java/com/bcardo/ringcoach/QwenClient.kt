package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal const val QWEN_MODEL = "qwen3.8-flash"
internal const val COACH_PROMPT_VERSION = "coach-v20-today-first-training"

internal data class CoachAdvice(val training: List<String>, val diet: List<String>, val notes: List<String>, val exerciseNotes: List<ExerciseNote> = emptyList()) {
    fun toJson(): JSONObject = JSONObject()
        .put("training", JSONArray(training))
        .put("diet", JSONArray(diet))
        .put("notes", JSONArray(notes))
        .apply { if (exerciseNotes.isNotEmpty()) put("exercise_notes", JSONArray(exerciseNotes.map { it.toJson() })) }

    companion object {
        fun decode(content: String): CoachAdvice {
            val json = JSONObject(content)
            val keys = json.keys().asSequence().toSet()
            require(keys.containsAll(setOf("training", "diet", "notes")) && keys.all { it in setOf("training", "diet", "notes", "exercise_notes") })
            fun section(key: String): List<String> {
                val values = json.getJSONArray(key)
                require(values.length() in 1..4)
                return (0 until values.length()).map { index ->
                    val value = values.get(index)
                    require(value is String && value.isNotBlank() && value.length <= 2000)
                    value.trim()
                }
            }
            return CoachAdvice(section("training"), section("diet"), section("notes"), ExerciseNote.decode(json.opt("exercise_notes")))
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
        return request(context, systemPrompt, context.optJSONObject("request_manifest")?.optString("wire_policy") == CoachingWireContext.VERSION)
    }

    fun request(context: JSONObject, systemPrompt: String, compact: Boolean): JSONObject {
        if (context.optString("context_encoding") == CoachingWireContext.VERSION)
            return buildRequest(context, systemPrompt + "\n" + CoachingWireContext.INSTRUCTIONS)
        val annotated = ExerciseFollowups.decorate(context)
        val plain = buildRequest(annotated, systemPrompt)
        if (!compact) return plain
        val encoded = buildRequest(CoachingWireContext.encode(annotated), systemPrompt + "\n" + CoachingWireContext.INSTRUCTIONS)
        // Tiny/unusual inputs keep the ordinary schema when encoding instructions would cost more.
        return if (encoded.toString().toByteArray(Charsets.UTF_8).size < plain.toString().toByteArray(Charsets.UTF_8).size) encoded else plain
    }

    private fun buildRequest(context: JSONObject, systemPrompt: String): JSONObject {
        val fields = JSONObject()
        for (name in listOf("training", "diet", "notes")) {
            fields.put(name, JSONObject()
                .put("type", "array").put("items", JSONObject().put("type", "string"))
                .put("minItems", 1).put("maxItems", 4))
        }
        val required = JSONArray(listOf("training", "diet", "notes"))
        val baselines = context.optJSONArray("exercise_baselines") ?: JSONArray()
        val refs = (0 until baselines.length()).mapNotNull { baselines.optJSONObject(it)?.optString("followup_ref")?.takeIf { ref -> ref.matches(Regex("e[1-9][0-9]{0,3}")) } }.distinct()
        if (refs.isNotEmpty()) {
            val item = JSONObject().put("type", "object").put("properties", JSONObject()
                .put("exercise_ref", JSONObject().put("type", "string").put("enum", JSONArray(refs)))
                .put("text", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 240)))
                .put("required", JSONArray(listOf("exercise_ref", "text"))).put("additionalProperties", false)
            fields.put("exercise_notes", JSONObject().put("type", "array").put("items", item).put("minItems", 0).put("maxItems", 4))
            required.put("exercise_notes")
        }
        val schema = JSONObject().put("type", "object").put("properties", fields)
            .put("required", required).put("additionalProperties", false)
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
