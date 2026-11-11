package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class WorkoutExtractionTest {
    private val training = "Bench: per side 20 lb, 8/8/7. Tomorrow plan 21 lb. Row unclear."

    private fun row(performance: String, evidence: String, status: String) = JSONObject()
        .put("exercise", "Bench").put("performance", performance)
        .put("evidence", evidence).put("status", status)

    @Test
    fun preservesUnitsAndEvidenceAndExcludesPlans() {
        val json = JSONObject().put("records", JSONArray()
            .put(row("per side 20 lb, 8/8/7", "Bench: per side 20 lb, 8/8/7.", "completed"))
            .put(row("21 lb", "Tomorrow plan 21 lb.", "planned")))
            .put("unresolved", JSONArray(listOf("Row unclear.")))
        val result = WorkoutExtractor.decode(json.toString(), training, setOf("Bench"))
        assertEquals("per side 20 lb, 8/8/7", result.records.single().performance)
        assertEquals(listOf("Row unclear."), result.unresolved)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInventedPerformance() {
        val json = JSONObject().put("records", JSONArray()
            .put(row("40 lb x 8", "Bench: per side 20 lb, 8/8/7.", "completed")))
            .put("unresolved", JSONArray())
        WorkoutExtractor.decode(json.toString(), training, setOf("Bench"))
    }

    @Test
    fun sendsOnlyBaselineNamesWithoutCopyingCapacities() {
        val date = LocalDate.of(2025, 1, 10)
        val request = WorkoutExtractor.request(PendingWorkout(date, "revision", training),
            listOf(ExerciseBaseline("Push", "Bench", "PRIVATE BASELINE 999 lb", date)), "Extract")
        assertFalse(request.toString().contains("999"))
        val input = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(training, input.getString("training_input"))
        val known = input.getJSONArray("known_exercises").getJSONObject(0)
        assertEquals("Bench", known.getString("exercise"))
        assertEquals("Push", known.getString("training_day"))
        assertEquals(1, known.getInt("exercise_number"))
    }
}
