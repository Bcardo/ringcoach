package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkoutProgressRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 10, 2)
    private val source = "Bench: 20 lb x 8."
    private val record = WorkoutObservation("Bench", "20 lb x 8", source, "completed")
    private fun isolated(block: (JournalStore) -> Unit) {
        val name = "workout-progress-${UUID.randomUUID()}.db"
        try { JournalStore(context, name).use(block) } finally { context.deleteDatabase(name) }
    }
    private fun seed(store: JournalStore) {
        store.save(JournalDraft(date, training = source))
        assertTrue(store.applyWorkoutExtraction(store.nextPendingWorkout()!!, WorkoutExtraction(listOf(record), emptyList())))
    }

    @Test fun appendedQuestionAndFailedRecognitionKeepLastConfirmedPerformance() = isolated { store ->
        seed(store)
        store.appendProgress("question", date, "Should I rest tomorrow?", emptyList(), true)
        val turns = IncrementalWorkoutStore(store.writableDatabase)
        val pending = requireNotNull(turns.next())
        assertEquals(record, store.latestWorkoutRecords(date).single().observation)
        turns.fail(pending)
        assertEquals(record, store.latestWorkoutRecords(date).single().observation)
        assertNotNull(store.progressSubmission("question"))
    }

    @Test fun removingPriorSourceInvalidatesOldPerformance() = isolated { store ->
        seed(store)
        store.save(JournalDraft(date, training = "No workout was completed."))
        assertTrue(store.latestWorkoutRecords(date).isEmpty())
    }

    @Test fun acceptedCorrectionReplacesPriorRowsWithoutDoubleCounting() = isolated { store ->
        seed(store)
        val correction = "Correction: Bench was 15 lb x 8."
        store.appendProgress("correction", date, correction, emptyList(), true)
        val updated = WorkoutObservation("Bench", "15 lb x 8", correction, "completed")
        assertEquals(record, store.latestWorkoutRecords(date).single().observation)
        val turns = IncrementalWorkoutStore(store.writableDatabase)
        val pending = turns.next()!!
        val delta = WorkoutDelta(listOf(WorkoutChange("replace", listOf(pending.previous.records.single().id), "Bench", "15 lb x 8", correction, "completed")), emptyList())
        assertTrue(turns.apply(pending, WorkoutDeltaExtractor.apply(pending, delta, setOf("Bench"))))
        assertEquals(listOf(updated), store.latestWorkoutRecords(date).map { it.observation })
        val context = store.latestWorkoutRecords(date).single().toJson()
        assertTrue(context.getJSONArray("source_submission_ids").toString().contains("correction"))
        assertEquals(correction, context.getString("update_evidence"))
    }
}
