package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** Explicit live parser check: synthetic facts in a disposable DB; never inserts owner submissions. */
@RunWith(AndroidJUnit4::class)
class IncrementalWorkoutLiveTest {
    @Test fun questionCorrectionAdditionalSetFuturePlanAndRetraction() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_workout_delta") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = QwenCredentials(context).read() ?: error("Saved provider key required")
        val name = "workout-delta-live-${UUID.randomUUID()}.db"
        val date = LocalDate.of(2026, 10, 2)
        try { JournalStore(context, name).use { store ->
            val original = "Bench: 20 lb x 8."
            store.save(JournalDraft(date, training = original))
            store.applyWorkoutExtraction(store.nextPendingWorkout()!!,
                WorkoutExtraction(listOf(WorkoutObservation("Bench", "20 lb x 8", original, "completed")), emptyList()))
            val prompt = context.assets.open("workout-delta.txt").bufferedReader().use { it.readText() }
            val baseline = listOf(ExerciseBaseline("Push", "Bench", "Reference only", date))
            val turns = IncrementalWorkoutStore(store.writableDatabase)
            fun run(id: String, text: String): WorkoutDayState {
                store.appendProgress(id, date, text, emptyList(), true)
                assertNull("Legacy full-day recognition was queued", store.nextPendingWorkout())
                val turn = turns.next()!!
                val result = WorkoutDeltaExtractor.extract(turn, baseline, prompt, key, post = { request, credential ->
                    val data = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
                    assertEquals(text, data.getJSONObject("new_input").getString("text"))
                    assertFalse(data.has("training_input")); assertFalse(data.has("journal_history"))
                    QwenClient.post(request, credential)
                })
                assertTrue(turns.apply(turn, result)); return result
            }
            val first = run("question", "Should I do Bench tomorrow if I feel tired?")
            assertEquals(listOf("20 lb x 8"), first.records.map { it.observation.performance })
            assertTrue(first.unresolved.isEmpty())
            val corrected = run("correction", "Correction: Bench was 15 lb x 8, not 20 lb x 8.")
            assertEquals(listOf("15 lb x 8"), corrected.records.map { it.observation.performance })
            val extra = run("extra", "Another Bench set completed: 15 lb x 6.")
            assertEquals(setOf("15 lb x 8", "15 lb x 6"), extra.records.map { it.observation.performance }.toSet())
            val plan = run("plan", "Tomorrow I plan Bench: 30 lb x 8.")
            assertEquals(extra, plan)
            val removed = run("retraction", "Correction: the extra Bench set was not performed.")
            assertEquals(listOf("15 lb x 8"), removed.records.map { it.observation.performance })
            assertNull(turns.next())
        } } finally { context.deleteDatabase(name) }
    }
}
