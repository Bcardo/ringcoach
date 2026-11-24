package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal data class ConfirmedWorkout(val id: String, val observation: WorkoutObservation,
    val sources: List<String>, val updateEvidence: String = observation.evidence) {
    fun toJson() = JSONObject().put("id", id).put("exercise", observation.exercise)
        .put("performance", observation.performance).put("evidence", observation.evidence).put("status", observation.status)
        .put("source_submission_ids", JSONArray(sources)).put("update_evidence", updateEvidence)
    companion object {
        fun decode(j: JSONObject) = ConfirmedWorkout(j.getString("id"), WorkoutObservation(j.getString("exercise"),
            j.getString("performance"), j.getString("evidence"), j.getString("status")),
            j.getJSONArray("source_submission_ids").strings(), j.optString("update_evidence", j.getString("evidence")))
    }
}
internal data class WorkoutClarification(val id: String, val evidence: String, val source: String) {
    fun toJson() = JSONObject().put("id", id).put("evidence", evidence).put("source_submission_id", source)
}
internal data class WorkoutDayState(val records: List<ConfirmedWorkout> = emptyList(),
    val unresolved: List<WorkoutClarification> = emptyList()) {
    fun toJson() = JSONObject().put("version", 1).put("records", JSONArray(records.map { it.toJson() }))
        .put("unresolved", JSONArray(unresolved.map { it.toJson() }))
    companion object {
        fun decode(j: JSONObject): WorkoutDayState {
            require(j.getInt("version") == 1)
            return WorkoutDayState(j.getJSONArray("records").objects().map(ConfirmedWorkout::decode),
                j.getJSONArray("unresolved").objects().map { WorkoutClarification(it.getString("id"), it.getString("evidence"), it.getString("source_submission_id")) })
        }
    }
}
internal data class PendingWorkoutTurn(val id: String, val date: LocalDate, val rowId: Long, val input: String,
    val previousId: String?, val previous: WorkoutDayState, val status: String)
internal data class WorkoutChange(val operation: String, val targets: List<String>, val exercise: String,
    val performance: String, val evidence: String, val status: String)
internal data class WorkoutDelta(val changes: List<WorkoutChange>, val unresolved: List<String>)

/** Only new input is recognized. Previously accepted facts are referenced by ID, never regenerated. */
internal object WorkoutDeltaExtractor {
    const val VERSION = "workout-v4-numbered-reports"
    fun request(turn: PendingWorkoutTurn, baselines: List<ExerciseBaseline>, instructions: String, feedback: JSONObject? = null): JSONObject {
        val properties = JSONObject()
        for (key in listOf("operation", "exercise", "performance", "evidence", "status"))
            properties.put(key, JSONObject().put("type", "string"))
        properties.getJSONObject("operation").put("enum", JSONArray(listOf("add", "replace", "remove", "resolve", "dismiss")))
        properties.getJSONObject("status").put("enum", JSONArray(listOf("completed", "partial", "skipped")))
        properties.put("target_ids", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
        val change = JSONObject().put("type", "object").put("properties", properties)
            .put("required", JSONArray(listOf("operation", "target_ids", "exercise", "performance", "evidence", "status"))).put("additionalProperties", false)
        val schema = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("changes", JSONObject().put("type", "array").put("items", change))
            .put("unresolved", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))))
            .put("required", JSONArray(listOf("changes", "unresolved"))).put("additionalProperties", false)
        val data = JSONObject().put("date", turn.date.toString())
            .put("new_input", JSONObject().put("submission_id", turn.id).put("text", turn.input))
            .put("confirmed_workout_state", turn.previous.toJson())
            .put("known_exercises", JSONArray(baselines.map { JSONObject().put("training_day", it.trainingDay)
                .put("exercise_number", it.exerciseNumber).put("exercise", it.exercise) }))
        feedback?.let { data.put("validation_feedback", it) }
        return JSONObject().put("model", QWEN_MODEL).put("enable_thinking", false).put("max_tokens", 3000)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", instructions))
                .put(JSONObject().put("role", "user").put("content", data.toString())))
            .put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "workout_delta").put("strict", true).put("schema", schema)))
    }

    fun extract(turn: PendingWorkoutTurn, baselines: List<ExerciseBaseline>, instructions: String, key: String,
        post: (JSONObject, String) -> String = QwenClient::post,
        onValidation: (String, Int, String) -> Unit = { _, _, _ -> }): WorkoutDayState {
        if (turn.input.isBlank()) return turn.previous
        var feedback: JSONObject? = null
        repeat(2) { attempt ->
            val response = post(request(turn, baselines, instructions, feedback), key)
            var answer: String? = null
            try {
                answer = try { QwenClient.responseText(response) }
                    catch (_: IllegalArgumentException) { throw WorkoutValidationException("response_envelope") }
                    catch (_: org.json.JSONException) { throw WorkoutValidationException("response_envelope") }
                val state = apply(turn, decode(answer), baselines.map { it.exercise }.toSet())
                if (attempt > 0) onValidation(feedback!!.getString("code"), attempt + 1, "recovered")
                return state
            } catch (e: WorkoutValidationException) {
                onValidation(e.code, attempt + 1, if (attempt == 0) "corrective_retry" else "blocked")
                if (attempt == 1) throw e
                feedback = JSONObject().put("code", e.code).put("rejected_delta_not_evidence", answer?.take(16000))
                    .put("instruction", "修正未通过验证的增量。只识别new_input，不重发旧事实；纯提问/未来计划返回空changes。所有操作evidence逐字引用本次输入，target_ids只能引用已提供的记录。")
            }
        }
        error("Unreachable workout delta state")
    }

    fun decode(raw: String): WorkoutDelta = try {
        val json = JSONObject(raw)
        valid(json.keys().asSequence().toSet() == setOf("changes", "unresolved"), "json_shape")
        val changes = json.getJSONArray("changes").objects().map { row ->
            valid(row.keys().asSequence().toSet() == setOf("operation", "target_ids", "exercise", "performance", "evidence", "status"), "json_shape")
            fun text(key: String): String {
                val value = row.get(key); valid(value is String, "missing_$key"); return value as String
            }
            WorkoutChange(text("operation"), row.getJSONArray("target_ids").strings(), text("exercise"), text("performance"), text("evidence"), text("status"))
        }
        WorkoutDelta(changes, json.getJSONArray("unresolved").strings())
    } catch (_: org.json.JSONException) { throw WorkoutValidationException("json_shape") }

    fun apply(turn: PendingWorkoutTurn, delta: WorkoutDelta, known: Set<String>): WorkoutDayState {
        val records = turn.previous.records.toMutableList()
        val clarifications = turn.previous.unresolved.toMutableList()
        val originalFacts = records.associateBy { it.id }
        val originalQuestions = clarifications.associateBy { it.id }
        val consumed = mutableSetOf<String>()
        for ((index, change) in delta.changes.withIndex()) {
            valid(change.operation in setOf("add", "replace", "remove", "resolve", "dismiss"), "invalid_operation")
            valid(change.status in setOf("completed", "partial", "skipped"), "invalid_status")
            valid(change.evidence.isNotBlank() && turn.input.contains(change.evidence), "evidence_not_in_new_input")
            valid(change.targets.distinct().size == change.targets.size && change.targets.none { it in consumed }, "duplicate_target")
            val facts = change.targets.mapNotNull(originalFacts::get)
            val questions = change.targets.mapNotNull(originalQuestions::get)
            when (change.operation) {
                "add" -> valid(change.targets.isEmpty(), "add_has_targets")
                "replace", "remove" -> valid(change.targets.isNotEmpty() && facts.size == change.targets.size, "unknown_fact_target")
                "resolve", "dismiss" -> valid(change.targets.isNotEmpty() && questions.size == change.targets.size, "unknown_clarification_target")
            }
            if (change.operation in setOf("remove", "dismiss")) {
                valid(change.status == "skipped", "removal_status")
                records.removeAll { it.id in change.targets }; clarifications.removeAll { it.id in change.targets }
                consumed.addAll(change.targets)
                continue
            }
            valid(change.exercise.isNotBlank() && (change.exercise in known || change.evidence.contains(change.exercise) ||
                questions.any { it.evidence.contains(change.exercise) }), "exercise_not_supported")
            val performance = if (change.status == "skipped" && change.performance.isBlank()) change.evidence else change.performance
            valid(performance.isNotBlank(), "missing_performance")
            val supportingFact = facts.firstOrNull { it.observation.performance == performance }
            val supportingQuestion = questions.firstOrNull { it.evidence.contains(performance) }
            val sourceEvidence = when {
                change.evidence.contains(performance) -> change.evidence
                supportingFact != null -> supportingFact.observation.evidence
                supportingQuestion != null -> supportingQuestion.evidence
                else -> throw WorkoutValidationException("performance_not_supported")
            }
            val sources = (listOf(turn.id) + facts.flatMap { it.sources } + questions.map { it.source }).distinct()
            val next = ConfirmedWorkout("workout:${turn.id}:$index", WorkoutObservation(change.exercise, performance, sourceEvidence, change.status), sources, change.evidence)
            records.removeAll { it.id in change.targets }; clarifications.removeAll { it.id in change.targets }
            // A repeated exact report is not a second completed set/session.
            if (records.none { it.observation == next.observation }) records.add(next)
            consumed.addAll(change.targets)
        }
        for ((index, quote) in delta.unresolved.withIndex()) {
            valid(quote.isNotBlank() && turn.input.contains(quote), "clarification_not_in_new_input")
            if (clarifications.none { it.evidence == quote }) clarifications.add(WorkoutClarification("clarification:${turn.id}:$index", quote, turn.id))
        }
        return WorkoutDayState(records, clarifications)
    }
    private fun valid(condition: Boolean, code: String) { if (!condition) throw WorkoutValidationException(code) }
}

private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
private fun JSONArray.strings(): List<String> = (0 until length()).map {
    val value = get(it)
    if (value !is String) throw WorkoutValidationException("json_shape")
    value
}
