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
internal data class PendingWorkout(val date: LocalDate, val revision: String, val training: String)
internal data class WorkoutRecord(val date: LocalDate, val observation: WorkoutObservation) {
    fun toJson(): JSONObject = JSONObject()
        .put("date", date.toString()).put("exercise", observation.exercise)
        .put("performance", observation.performance).put("evidence", observation.evidence)
        .put("status", observation.status).put("source", "saved_training_input")
}

internal object WorkoutExtractor {
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

    fun decode(content: String, training: String, knownExercises: Set<String>): WorkoutExtraction {
        val json = JSONObject(content)
        require(json.keys().asSequence().toSet() == setOf("records", "unresolved"))
        val rows = json.getJSONArray("records")
        val records = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            fun text(key: String): String {
                val value = row.get(key)
                require(value is String && value.isNotBlank())
                return value
            }
            val observation = WorkoutObservation(text("exercise"), text("performance"), text("evidence"), text("status"))
            require(observation.status in setOf("completed", "partial", "skipped", "planned"))
            require(training.contains(observation.evidence) && observation.evidence.contains(observation.performance))
            require(observation.exercise in knownExercises || observation.evidence.contains(observation.exercise))
            observation
        }.filter { it.status != "planned" }.distinct()
        val unresolved = json.getJSONArray("unresolved")
        val fragments = (0 until unresolved.length()).map { index ->
            val value = unresolved.get(index)
            require(value is String && value.isNotBlank() && training.contains(value))
            value
        }.distinct()
        return WorkoutExtraction(records, fragments)
    }
}
