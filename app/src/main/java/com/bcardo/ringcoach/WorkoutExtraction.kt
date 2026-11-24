package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal data class WorkoutObservation(
    val exercise: String,
    val performance: String,
    val evidence: String,
    val status: String,
)

internal data class WorkoutExtraction(val records: List<WorkoutObservation>, val unresolved: List<String>)
internal const val WORKOUT_EXTRACTOR_VERSION = "workout-v2-validation-repair"
internal class WorkoutValidationException(val code: String) : IllegalArgumentException(code)
internal data class PendingWorkout(val date: LocalDate, val revision: String, val training: String, val status: String = "pending",
    val confirmedRecords: List<WorkoutObservation> = emptyList())
internal data class WorkoutRecord(val date: LocalDate, val observation: WorkoutObservation,
    val sourceSubmissionIds: List<String> = emptyList(), val updateEvidence: String? = null) {
    fun toJson(): JSONObject = JSONObject()
        .put("date", date.toString()).put("exercise", observation.exercise)
        .put("performance", observation.performance).put("evidence", observation.evidence)
        .put("status", observation.status).put("source", "saved_training_input")
        .apply {
            if (sourceSubmissionIds.isNotEmpty()) put("source_submission_ids", JSONArray(sourceSubmissionIds))
            updateEvidence?.let { put("update_evidence", it) }
        }
}

internal object WorkoutExtractor {
    /** One corrective response is allowed; rejected facts never reach storage. */
    fun extract(input: PendingWorkout, baselines: List<ExerciseBaseline>, instructions: String, apiKey: String,
        post: (JSONObject, String) -> String = QwenClient::post,
        onValidation: (String, Int, String) -> Unit = { _, _, _ -> }): WorkoutExtraction {
        var rejected: String? = null
        var reason: String? = null
        repeat(2) { index ->
            val request = request(input, baselines, instructions)
            if (reason != null) {
                val data = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
                    .put("validation_feedback", JSONObject().put("code", reason).put("rejected_result_not_source_evidence", rejected?.take(16000))
                        .put("instruction", "上次结果未通过验证，未保存。只以 training_input 为原文：performance、evidence、unresolved必须是连续逐字引用。不要规范化标点或组次，不把提问当成实际训练。必须保留全日已有训练事实，不能因为末条是提问就返回空数组；仅当全文没有实际训练事实时允许空数组。"))
                request.getJSONArray("messages").getJSONObject(1).put("content", data.toString())
                request.put("max_tokens", 4400)
            }
            val raw = post(request, apiKey)
            try {
                val answer = try { QwenClient.responseText(raw) }
                    catch (_: IllegalArgumentException) { throw WorkoutValidationException("response_envelope") }
                    catch (_: org.json.JSONException) { throw WorkoutValidationException("response_envelope") }
                rejected = answer
                val result = decode(answer, input.training, baselines.map { it.exercise }.toSet())
                check(input.confirmedRecords.all { previous -> result.records.any { it.exercise == previous.exercise } }, "confirmed_workout_omitted")
                if (index > 0) onValidation(requireNotNull(reason), index + 1, "recovered")
                return result
            } catch (e: WorkoutValidationException) {
                reason = e.code
                onValidation(e.code, index + 1, if (index == 0) "corrective_retry" else "blocked")
                if (index == 1) throw e
            }
        }
        error("Unreachable workout response state")
    }

    fun request(input: PendingWorkout, baselines: List<ExerciseBaseline>, instructions: String): JSONObject {
        val fields = JSONObject()
        for (name in listOf("exercise", "performance", "evidence")) fields.put(name, JSONObject().put("type", "string"))
        fields.put("status", JSONObject().put("type", "string")
            .put("enum", JSONArray(listOf("completed", "partial", "skipped", "planned"))))
        val recordSchema = JSONObject().put("type", "object").put("properties", fields)
            .put("required", JSONArray(listOf("exercise", "performance", "evidence", "status")))
            .put("additionalProperties", false)
        val schema = JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("records", JSONObject().put("type", "array").put("items", recordSchema))
                .put("unresolved", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))))
            .put("required", JSONArray(listOf("records", "unresolved"))).put("additionalProperties", false)
        val data = JSONObject().put("date", input.date.toString()).put("training_input", input.training)
            .put("confirmed_records", JSONArray(input.confirmedRecords.map { row -> JSONObject()
                .put("exercise", row.exercise).put("performance", row.performance).put("evidence", row.evidence).put("status", row.status) }))
            .put("known_exercises", JSONArray(baselines.map {
                JSONObject().put("training_day", it.trainingDay)
                    .put("exercise_number", it.exerciseNumber).put("exercise", it.exercise)
            }))
        return JSONObject().put("model", QWEN_MODEL).put("enable_thinking", false).put("max_tokens", 2200)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", instructions))
                .put(JSONObject().put("role", "user").put("content", data.toString())))
            .put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "workout_actuals").put("strict", true).put("schema", schema)))
    }

    fun decode(content: String, training: String, knownExercises: Set<String>): WorkoutExtraction = try {
        decodeValidated(content, training, knownExercises)
    } catch (_: org.json.JSONException) { throw WorkoutValidationException("json_shape") }

    private fun check(condition: Boolean, code: String) { if (!condition) throw WorkoutValidationException(code) }

    private fun decodeValidated(content: String, training: String, knownExercises: Set<String>): WorkoutExtraction {
        val json = JSONObject(content)
        check(json.keys().asSequence().toSet() == setOf("records", "unresolved"), "json_shape")
        val rows = json.getJSONArray("records")
        val records = (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            fun text(key: String): String {
                val value = row.opt(key)
                check(value is String && value.isNotBlank(), "missing_$key")
                return value as String
            }
            val status = text("status")
            check(status in setOf("completed", "partial", "skipped", "planned"), "invalid_status")
            // Future plans never become actual facts, regardless of their missing capacity fields.
            if (status == "planned") return@mapNotNull null
            val evidence = text("evidence")
            val rawPerformance = row.opt("performance")
            // A skipped exercise has no completed capacity; its exact source quote is the display value.
            val performance = if (status == "skipped" && (rawPerformance == null || rawPerformance == JSONObject.NULL || rawPerformance is String && rawPerformance.isBlank()))
                evidence else text("performance")
            val observation = WorkoutObservation(text("exercise"), performance, evidence, status)
            check(training.contains(observation.evidence), "evidence_not_in_source")
            check(observation.evidence.contains(observation.performance), "performance_not_in_evidence")
            check(observation.exercise in knownExercises || observation.evidence.contains(observation.exercise), "exercise_not_supported")
            observation
        }.distinct()
        val unresolved = json.getJSONArray("unresolved")
        val fragments = (0 until unresolved.length()).map { index ->
            val value = unresolved.get(index)
            check(value is String && value.isNotBlank() && training.contains(value), "clarification_not_in_source")
            value as String
        }.distinct()
        return WorkoutExtraction(records, fragments)
    }
}
