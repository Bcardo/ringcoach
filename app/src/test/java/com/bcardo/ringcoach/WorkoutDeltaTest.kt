package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WorkoutDeltaTest {
    private val old = ConfirmedWorkout("old", WorkoutObservation("Bench", "20 lb x 8", "Bench: 20 lb x 8.", "completed"), listOf("original"))
    private val state = WorkoutDayState(listOf(old))
    private fun turn(text: String, previous: WorkoutDayState = state) = PendingWorkoutTurn("new", LocalDate.of(2026, 10, 2), 2, text, "original", previous, "pending")
    private fun change(operation: String, evidence: String, performance: String = "", targets: List<String> = emptyList(), exercise: String = "Bench", status: String = "completed") =
        WorkoutChange(operation, targets, exercise, performance, evidence, status)
    private fun rejected(code: String, block: () -> Unit) {
        try { block(); fail("Invalid delta accepted") } catch (e: WorkoutValidationException) { assertEquals(code, e.code) }
        assertEquals("20 lb x 8", old.observation.performance)
    }

    @Test fun questionNoOpPreservesConfirmedFactsAndTheirIdentity() {
        assertEquals(state, WorkoutDeltaExtractor.apply(turn("Should I rest tomorrow?"), WorkoutDelta(emptyList(), emptyList()), setOf("Bench")))
    }

    @Test fun requestRecognizesOnlyNewTextAndDoesNotCopyBaselineCapacities() {
        val t = turn("Should I rest tomorrow?")
        val request = WorkoutDeltaExtractor.request(t, listOf(ExerciseBaseline("Push", "Bench", "SECRET_BASELINE_999", t.date)), "Extract")
        val data = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(t.input, data.getJSONObject("new_input").getString("text"))
        assertFalse(data.has("training_input")); assertFalse(data.has("journal_history"))
        assertFalse(request.toString().contains("SECRET_BASELINE_999"))
        assertEquals("old", data.getJSONObject("confirmed_workout_state").getJSONArray("records").getJSONObject(0).getString("id"))
    }

    @Test fun additionalSetPreservesEarlierRecordWhileCorrectionReplacesOnlyTarget() {
        val input = "Another Bench set: 20 lb x 6."
        val added = WorkoutDeltaExtractor.apply(turn(input), WorkoutDelta(listOf(change("add", input, "20 lb x 6")), emptyList()), setOf("Bench"))
        assertEquals(2, added.records.size); assertEquals(old, added.records.first())
        val correction = "Correction: that first Bench set was 15 lb x 8."
        val updated = WorkoutDeltaExtractor.apply(turn(correction, added), WorkoutDelta(listOf(change("replace", correction, "15 lb x 8", listOf("old"))), emptyList()), setOf("Bench"))
        assertEquals(2, updated.records.size)
        assertTrue(updated.records.any { it.observation.performance == "20 lb x 6" })
        assertFalse(updated.records.any { it.observation.performance == "20 lb x 8" })
    }

    @Test fun oldEvidenceCannotBeReportedAsNewInput() = rejected("evidence_not_in_new_input") {
        WorkoutDeltaExtractor.apply(turn("Should I rest tomorrow?"), WorkoutDelta(listOf(change("add", old.observation.evidence, old.observation.performance)), emptyList()), setOf("Bench"))
    }

    @Test fun unknownOrDuplicateTargetsAreRejectedAtomically() {
        val input = "Correction: Bench 15 lb x 8."
        rejected("unknown_fact_target") { WorkoutDeltaExtractor.apply(turn(input), WorkoutDelta(listOf(change("replace", input, "15 lb x 8", listOf("invented"))), emptyList()), setOf("Bench")) }
        rejected("duplicate_target") { WorkoutDeltaExtractor.apply(turn(input), WorkoutDelta(List(2) { change("replace", input, "15 lb x 8", listOf("old")) }, emptyList()), setOf("Bench")) }
    }

    @Test fun explicitRetractionRemovesFactWithoutMutatingPriorState() {
        val input = "Correction: Bench was not performed."
        val updated = WorkoutDeltaExtractor.apply(turn(input), WorkoutDelta(listOf(change("remove", input, targets = listOf("old"), status = "skipped")), emptyList()), setOf("Bench"))
        assertTrue(updated.records.isEmpty()); assertEquals(listOf(old), state.records)
    }

    @Test fun clarificationCanResolvePriorQuotedPerformanceUsingNewEvidence() {
        val question = WorkoutClarification("ambiguous", "Second exercise: 20 lb x 8.", "earlier")
        val input = "That second exercise was Bench."
        val updated = WorkoutDeltaExtractor.apply(turn(input, WorkoutDayState(unresolved = listOf(question))),
            WorkoutDelta(listOf(change("resolve", input, "20 lb x 8", listOf("ambiguous"))), emptyList()), setOf("Bench"))
        assertEquals("20 lb x 8", updated.records.single().observation.performance)
        assertEquals(question.evidence, updated.records.single().observation.evidence)
        assertEquals(input, updated.records.single().updateEvidence)
        assertEquals(setOf("new", "earlier"), updated.records.single().sources.toSet())
        assertTrue(updated.unresolved.isEmpty())
    }

    @Test fun repeatedExactReportDoesNotAddAnotherCompletedSet() {
        val updated = WorkoutDeltaExtractor.apply(turn(old.observation.evidence), WorkoutDelta(listOf(change("add", old.observation.evidence, old.observation.performance)), emptyList()), setOf("Bench"))
        assertEquals(state, updated)
    }

    @Test fun inventedPerformanceAndUnsupportedClarificationsFailClosed() {
        rejected("performance_not_supported") { WorkoutDeltaExtractor.apply(turn("Bench was corrected."), WorkoutDelta(listOf(change("replace", "Bench was corrected.", "999 lb", listOf("old"))), emptyList()), setOf("Bench")) }
        rejected("clarification_not_in_new_input") { WorkoutDeltaExtractor.apply(turn("A question"), WorkoutDelta(emptyList(), listOf("paraphrased invented fact")), setOf("Bench")) }
    }

    @Test fun invalidDeltaGetsOnlyOneRepairAndNeverRegeneratesPriorFacts() {
        var calls = 0
        val t = turn("Should I rest tomorrow?")
        val result = WorkoutDeltaExtractor.extract(t, emptyList(), "Extract", "fake", post = { request, _ ->
            calls++
            val data = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals(t.input, data.getJSONObject("new_input").getString("text"))
            val answer = JSONObject().put("changes", JSONArray()).put("unresolved", JSONArray(if (calls == 1) listOf("invented") else emptyList<String>()))
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", answer.toString())))).toString()
        })
        assertEquals(2, calls); assertEquals(state, result)
    }
}
