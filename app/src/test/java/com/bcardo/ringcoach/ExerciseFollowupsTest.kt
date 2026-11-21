package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExerciseFollowupsTest {
    private fun context() = JSONObject().put("request_date", "2026-06-12")
        .put("routine", "Synthetic saved routine").put("instructions", "No automatic changes")
        .put("exercise_baselines", JSONArray().put(JSONObject().put("training_day", "Day A").put("exercise", "Synthetic press")
            .put("exercise_number", 1).put("reference_notes", "Saved reference").put("snapshot_date", "2026-06-01")))
        .put("latest_workout_records", JSONArray().put(JSONObject().put("date", "2026-06-12").put("exercise", "Synthetic press")
            .put("performance", "20 kg x 8").put("evidence", "Synthetic press 20 kg x 8").put("status", "completed")))

    @Test fun requestReferencesAreCompactAndReviewedInputIsNotMutated() {
        val original = context()
        val request = QwenClient.request(original, "Synthetic system", false)
        assertFalse(original.getJSONArray("exercise_baselines").getJSONObject(0).has("followup_ref"))
        val facts = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("e1", facts.getJSONArray("exercise_baselines").getJSONObject(0).getString("followup_ref"))
        val fields = request.getJSONObject("response_format").getJSONObject("json_schema").getJSONObject("schema").getJSONObject("properties")
        assertEquals("e1", fields.getJSONObject("exercise_notes").getJSONObject("items").getJSONObject("properties")
            .getJSONObject("exercise_ref").getJSONArray("enum").getString(0))
        assertEquals(2000, request.getInt("max_tokens"))
        assertFalse(QwenClient.request(JSONObject().put("request_date", "2026-06-12"), "Synthetic system", false)
            .getJSONObject("response_format").getJSONObject("json_schema").getJSONObject("schema").getJSONObject("properties").has("exercise_notes"))
    }

    @Test fun changedPlanRuleReferenceOrConfirmedPerformanceInvalidatesTheBasis() {
        val original = context(); val basis = ExerciseFollowups.targets(original).single().basis
        for (key in listOf("routine", "instructions")) {
            assertNotEquals(basis, ExerciseFollowups.targets(JSONObject(original.toString()).put(key, "Changed")).single().basis)
        }
        val reference = JSONObject(original.toString())
        reference.getJSONArray("exercise_baselines").getJSONObject(0).put("reference_notes", "New reference")
        assertNotEquals(basis, ExerciseFollowups.targets(reference).single().basis)
        for ((key, value) in listOf("performance" to "18 kg x 8", "status" to "partial", "date" to "2026-06-11")) {
            val changed = JSONObject(original.toString()); changed.getJSONArray("latest_workout_records").getJSONObject(0).put(key, value)
            assertNotEquals(basis, ExerciseFollowups.targets(changed).single().basis)
        }
    }

    @Test fun dietUpdatesAndNextDayDoNotChangeTheSameWorkoutBasis() {
        val original = context(); val basis = ExerciseFollowups.targets(original).single().basis
        val diet = JSONObject(original.toString()).put("request_date", "2026-06-13").put("diet_plan", "Changed food target")
            .put("progress_submission", JSONObject().put("text", "Synthetic lunch"))
        assertEquals(basis, ExerciseFollowups.targets(diet).single().basis)
        assertEquals(basis, ExerciseFollowups.targets(ExerciseFollowups.decorate(original)).single().basis)
    }

    @Test fun ambiguousOldFutureAndPlannedFactsDoNotReceiveReferences() {
        val ambiguous = context(); val baseline = ambiguous.getJSONArray("exercise_baselines")
        baseline.put(JSONObject(baseline.getJSONObject(0).toString()).put("training_day", "Day B"))
        assertTrue(ExerciseFollowups.targets(ambiguous).isEmpty())
        for ((key, value) in listOf("date" to "2026-05-01", "date" to "2026-06-13", "status" to "planned")) {
            val row = context(); row.getJSONArray("latest_workout_records").getJSONObject(0).put(key, value)
            assertTrue(ExerciseFollowups.targets(row).isEmpty())
        }
    }

    @Test fun malformedOptionalNotesCannotBlockCoreFeedbackOrTurnIntoFacts() {
        val core = JSONObject().put("training", JSONArray(listOf("Advice"))).put("diet", JSONArray(listOf("Diet"))).put("notes", JSONArray(listOf("Notes")))
        assertTrue(CoachAdvice.decode(core.toString()).exerciseNotes.isEmpty())
        assertTrue(CoachAdvice.decode(JSONObject(core.toString()).put("exercise_notes", "wrong type").toString()).exerciseNotes.isEmpty())
        val duplicate = JSONArray().put(ExerciseNote("e1", "下次按已保存规则处理。").toJson()).put(ExerciseNote("e1", "下次这是重复引用。").toJson())
        assertTrue(CoachAdvice.decode(JSONObject(core.toString()).put("exercise_notes", duplicate).toString()).exerciseNotes.isEmpty())
        val advice = CoachAdvice(listOf("Advice"), listOf("Diet"), listOf("Notes"), listOf(ExerciseNote("e1", "下次先完成原目标。"), ExerciseNote("e99", "下次未知动作。")))
        val accepted = ExerciseFollowups.accepted(advice, context())
        assertEquals(1, accepted.exerciseNotes.size)
        assertEquals(ExerciseFollowups.targets(context()).single().basis, accepted.exerciseNotes.single().ref)
        assertEquals(advice.training, accepted.training)
        assertEquals(accepted, CoachAdvice.decode(accepted.toJson().toString()))
    }
}
