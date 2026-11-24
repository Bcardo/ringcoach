package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    private fun envelope(answer: JSONObject) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", answer.toString())))).toString()

    @Test fun rejectedWorkoutGetsOneCorrectiveAttemptWithoutAcceptingInventedFacts() {
        val invalid = JSONObject().put("records", JSONArray().put(row("40 lb x 8", "Bench: per side 20 lb, 8/8/7.", "completed")))
            .put("unresolved", JSONArray())
        val valid = JSONObject().put("records", JSONArray().put(row("per side 20 lb, 8/8/7", "Bench: per side 20 lb, 8/8/7.", "completed")))
            .put("unresolved", JSONArray())
        var calls = 0
        val events = mutableListOf<String>()
        val result = WorkoutExtractor.extract(PendingWorkout(LocalDate.of(2026, 10, 1), "test", training), emptyList(), "Extract", "fake-key",
            post = { request, _ ->
                calls++
                if (calls == 2) {
                    val data = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
                    assertEquals(training, data.getString("training_input"))
                    assertEquals("performance_not_in_evidence", data.getJSONObject("validation_feedback").getString("code"))
                }
                envelope(if (calls == 1) invalid else valid)
            }, onValidation = { code, attempt, outcome -> events.add("$code:$attempt:$outcome") })
        assertEquals(2, calls)
        assertEquals("per side 20 lb, 8/8/7", result.records.single().performance)
        assertEquals(listOf("performance_not_in_evidence:1:corrective_retry", "performance_not_in_evidence:2:recovered"), events)
    }

    @Test fun repeatedInvalidResponseStopsAfterTwoCallsAndKeepsSpecificReason() {
        var calls = 0
        try {
            WorkoutExtractor.extract(PendingWorkout(LocalDate.of(2026, 10, 1), "test", training), emptyList(), "Extract", "fake-key",
                post = { _, _ -> calls++; envelope(JSONObject().put("records", JSONArray()).put("unresolved", JSONArray(listOf("invented paraphrase")))) })
            fail("Invalid workout accepted")
        } catch (e: WorkoutValidationException) { assertEquals("clarification_not_in_source", e.code) }
        assertEquals(2, calls)
    }

    @Test fun networkFailureDoesNotTriggerValidationRepair() {
        var calls = 0
        try {
            WorkoutExtractor.extract(PendingWorkout(LocalDate.of(2026, 10, 1), "test", training), emptyList(), "Extract", "fake-key",
                post = { _, _ -> calls++; throw java.io.IOException("synthetic offline") })
            fail("Network failure swallowed")
        } catch (_: java.io.IOException) { }
        assertEquals(1, calls)
    }

    @Test fun questionMayReturnNoWorkoutFactsWithoutBecomingUnresolved() {
        val result = WorkoutExtractor.decode(JSONObject().put("records", JSONArray()).put("unresolved", JSONArray()).toString(),
            "Should I rest tomorrow?", emptySet())
        assertTrue(result.records.isEmpty()); assertTrue(result.unresolved.isEmpty())
    }

    @Test fun questionCannotSilentlyClearConfirmedWorkout() {
        val confirmed = WorkoutObservation("Bench", "per side 20 lb, 8/8/7", "Bench: per side 20 lb, 8/8/7.", "completed")
        var calls = 0
        val result = WorkoutExtractor.extract(PendingWorkout(LocalDate.of(2026, 10, 1), "test", training,
            confirmedRecords = listOf(confirmed)), emptyList(), "Extract", "fake-key", post = { _, _ ->
            calls++
            envelope(JSONObject().put("records", if (calls == 1) JSONArray() else JSONArray().put(row(confirmed.performance, confirmed.evidence, confirmed.status)))
                .put("unresolved", JSONArray()))
        })
        assertEquals(2, calls); assertEquals(listOf(confirmed), result.records)
    }

    @Test fun skippedExerciseNeedsNoInventedCompletedCapacityAndPlansAreIgnored() {
        val input = "Skipped Bench today. Is Bench tomorrow appropriate?"
        val skipped = JSONObject().put("exercise", "Bench").put("performance", "")
            .put("evidence", "Skipped Bench today.").put("status", "skipped")
        val planned = JSONObject().put("exercise", "Bench").put("performance", "")
            .put("evidence", "").put("status", "planned")
        val result = WorkoutExtractor.decode(JSONObject().put("records", JSONArray().put(skipped).put(planned))
            .put("unresolved", JSONArray()).toString(), input, setOf("Bench"))
        assertEquals(1, result.records.size)
        assertEquals("skipped", result.records.single().status)
        assertEquals("Skipped Bench today.", result.records.single().performance)
    }

    @Test fun completedExerciseStillRequiresSourceBackedPerformance() {
        try {
            WorkoutExtractor.decode(JSONObject().put("records", JSONArray().put(row("", "Bench: per side 20 lb, 8/8/7.", "completed")))
                .put("unresolved", JSONArray()).toString(), training, setOf("Bench"))
            fail("Completed capacity missing but accepted")
        } catch (e: WorkoutValidationException) { assertEquals("missing_performance", e.code) }
    }
}
