package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** Disposable synthetic databases; no owner facts, permissions or live requests. */
@RunWith(AndroidJUnit4::class)
class IncrementalWorkoutStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 10, 2)
    private val source = "Bench: 20 lb x 8."
    private val record = WorkoutObservation("Bench", "20 lb x 8", source, "completed")
    private fun isolated(block: (JournalStore) -> Unit) {
        val name = "workout-turns-${UUID.randomUUID()}.db"
        try { JournalStore(context, name).use(block) } finally { context.deleteDatabase(name) }
    }
    private fun seed(store: JournalStore) {
        store.save(JournalDraft(date, training = source))
        store.applyWorkoutExtraction(store.nextPendingWorkout()!!, WorkoutExtraction(listOf(record), emptyList()))
    }

    @Test fun questionBootstrapsOncePreservesFactsAndDoesNotQueueLegacyRecognition() = isolated { store ->
        seed(store)
        store.appendProgress("question", date, "Should I rest tomorrow?", emptyList(), true)
        assertNull(store.nextPendingWorkout())
        val turns = IncrementalWorkoutStore(store.writableDatabase)
        val pending = turns.next()!!
        assertEquals("Should I rest tomorrow?", pending.input)
        assertEquals(listOf(record), pending.previous.records.map { it.observation })
        assertTrue(turns.apply(pending, pending.previous))
        assertFalse(turns.apply(pending, pending.previous))
        assertNull(turns.next()); assertEquals(listOf(record), store.latestWorkoutRecords(date).map { it.observation })
        store.close()
        JournalStore(context, store.databaseName).use { reopened ->
            assertNull(IncrementalWorkoutStore(reopened.writableDatabase).next())
            assertEquals(listOf(record), reopened.latestWorkoutRecords(date).map { it.observation })
        }
    }

    @Test fun rapidTurnsCommitInOrderAndStaleResultCannotOverwriteNewState() = isolated { store ->
        seed(store)
        store.appendProgress("one", date, "A question", emptyList(), true)
        store.appendProgress("two", date, "Another question", emptyList(), true)
        val turns = IncrementalWorkoutStore(store.writableDatabase)
        val first = turns.next()!!
        assertFalse(turns.apply(first.copy(id = "two"), first.previous))
        assertTrue(turns.apply(first, first.previous))
        val second = turns.next()!!
        assertEquals("two", second.id); assertEquals("one", second.previousId)
        assertFalse(turns.apply(second.copy(previousId = null), second.previous))
        assertTrue(turns.apply(second, second.previous)); assertNull(turns.next())
        assertEquals(1, store.latestWorkoutRecords(date).size)
    }

    @Test fun replacingOriginalSourceSupersedesInFlightTurnWithoutApplyingOldFacts() = isolated { store ->
        seed(store)
        store.appendProgress("question", date, "A question", emptyList(), true)
        val turns = IncrementalWorkoutStore(store.writableDatabase)
        val pending = turns.next()!!
        store.save(JournalDraft(date, training = "No workout was actually performed."))
        assertFalse(turns.apply(pending, pending.previous)); assertNull(turns.next())
        assertTrue(store.latestWorkoutRecords(date).isEmpty())
        assertNotNull(store.nextPendingWorkout())
    }

    @Test fun failedTurnKeepsConfirmedProgressAndDatedRetryResetsIt() = isolated { store ->
        seed(store)
        store.appendProgress("question", date, "A question", emptyList(), true)
        val turns = IncrementalWorkoutStore(store.writableDatabase)
        val pending = turns.next()!!
        turns.fail(pending)
        ProgressWorkStore(store.writableDatabase).failure("workout:question", "workout_invalid_operation")
        assertEquals("failed", turns.next()!!.status)
        assertEquals(listOf(record), store.latestWorkoutRecords(date).map { it.observation })
        store.retryDailyCoaching(date)
        assertEquals("pending", turns.next()!!.status)
        assertNull(ProgressWorkStore(store.readableDatabase).error("workout:question"))
        assertTrue(turns.apply(turns.next()!!, pending.previous))
    }

    @Test fun schemaThirteenMigrationPreservesHistoryAndOnlyAddsQueueTables() = isolated { store ->
        seed(store)
        val journals = store.readAll()
        store.writableDatabase.execSQL("DROP TABLE workout_turns")
        store.writableDatabase.execSQL("DROP TABLE daily_workout_state")
        store.writableDatabase.version = 13
        store.close()
        JournalStore(context, store.databaseName).use { reopened ->
            assertEquals(14, reopened.readableDatabase.version)
            assertEquals(journals, reopened.readAll())
            assertEquals(listOf(record), reopened.latestWorkoutRecords(date).map { it.observation })
            assertNull(IncrementalWorkoutStore(reopened.writableDatabase).next())
            reopened.appendProgress("new", date, "A question", emptyList(), true)
            assertEquals("new", IncrementalWorkoutStore(reopened.writableDatabase).next()!!.id)
            assertNull(reopened.nextPendingWorkout())
        }
    }

    @Test fun unresolvedLegacyFailureIsNotMarkedCompleteOrBootstrapped() = isolated { store ->
        store.save(JournalDraft(date, training = source))
        store.failWorkoutExtraction(store.nextPendingWorkout()!!)
        store.appendProgress("new", date, "A question", emptyList(), true)
        assertNull(IncrementalWorkoutStore(store.writableDatabase).next())
        assertNotNull(store.nextPendingWorkout())
        assertEquals("pending", store.workoutStatuses(date, date).single().getString("status"))
    }
}
