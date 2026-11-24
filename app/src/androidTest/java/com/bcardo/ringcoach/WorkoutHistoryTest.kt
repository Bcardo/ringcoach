package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkoutHistoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val day = LocalDate.of(2026, 6, 12)
    private fun isolated(block: (JournalStore) -> Unit) {
        val name = "workout-history-${UUID.randomUUID()}.db"
        try { JournalStore(context, name).use(block) } finally { context.deleteDatabase(name) }
    }
    private fun seed(store: JournalStore, date: LocalDate, records: List<WorkoutObservation>) {
        store.save(JournalDraft(date, training = records.joinToString("\n") { it.evidence }))
        assertTrue(store.applyWorkoutExtraction(store.nextPendingWorkout()!!, WorkoutExtraction(records, emptyList())))
    }
    private fun record(set: Int = 1, status: String = "completed", name: String = "Synthetic press") =
        WorkoutObservation(name, "set $set: 20 kg x 8", "$name set $set: 20 kg x 8", status)

    @Test fun boundedDatesPreserveAllSetsExcludeOtherExercisesAndFutureRecords() = isolated { store ->
        for (offset in 5 downTo 0) seed(store, day.minusDays(offset.toLong()), listOf(record(1), record(2, "partial"), record(name = "Synthetic row")))
        val result = store.workoutHistory("Synthetic press", day.minusDays(1))
        assertEquals((1L..4L).map { day.minusDays(it) }, result.map { it.date }.distinct())
        assertEquals(8, result.size)
        assertTrue(result.all { it.observation.exercise == "Synthetic press" })
        assertEquals(listOf("completed", "partial"), result.take(2).map { it.observation.status })
    }

    @Test fun staleRevisionAndPlannedRowsCannotBecomeHistoricalActuals() = isolated { store ->
        seed(store, day.minusDays(1), listOf(record()))
        seed(store, day, listOf(record()))
        store.writableDatabase.execSQL("UPDATE journal_entries SET training_revision='changed',training='Unrelated replacement' WHERE date=?", arrayOf(day.toString()))
        assertEquals(listOf(day.minusDays(1)), store.workoutHistory("Synthetic press", day).map { it.date })
        store.writableDatabase.execSQL("UPDATE exercise_logs SET status='planned' WHERE date=?", arrayOf(day.minusDays(1).toString()))
        assertTrue(store.workoutHistory("Synthetic press", day).isEmpty())
    }

    @Test fun appendedQuestionPreservesConfirmedHistoryAndAcceptedCorrectionReplacesIt() = isolated { store ->
        seed(store, day, listOf(record()))
        store.appendProgress("synthetic-question", day, "Should I rest next time?", emptyList(), true)
        assertEquals(1, store.workoutHistory("Synthetic press", day).size)
        store.save(JournalDraft(day, training = "Synthetic press corrected 18 kg x 8"))
        assertTrue(store.workoutHistory("Synthetic press", day).isEmpty())
        assertTrue(store.applyWorkoutExtraction(store.nextPendingWorkout()!!, WorkoutExtraction(listOf(
            WorkoutObservation("Synthetic press", "18 kg x 8", "Synthetic press corrected 18 kg x 8", "completed")), emptyList())))
        assertEquals(listOf("18 kg x 8"), store.workoutHistory("Synthetic press", day).map { it.observation.performance })
    }
}
