package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Only disposable synthetic databases; no owner journal writes or live API calls. */
@RunWith(AndroidJUnit4::class)
class SupplementFeedbackStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 10, 1)
    private fun isolated(block: (JournalStore) -> Unit) {
        val name = "supplements-${UUID.randomUUID()}.db"
        try { JournalStore(context, name).use(block) } finally { context.deleteDatabase(name) }
    }
    private fun complete(store: JournalStore, expected: String) {
        val job = store.nextPendingNutrition()!!
        assertEquals(expected, job.revision)
        val event = SupplementEvent("$expected:supplement:1", job.date, "肌酸", null, null, null,
            "taken", "服用肌酸", listOf(expected))
        val previous = job.turn!!.previous
        val result = NutritionExtraction(NUTRITION_FIELDS.associateWith { NutritionValue(null, "missing", "") },
            "unknown", "", emptyList(), supplements = previous?.supplements.orEmpty() + event)
        assertTrue(store.applyNutritionExtraction(job, result))
        val workout = store.nextPendingWorkout()!!
        assertTrue(store.applyWorkoutExtraction(workout, WorkoutExtractor.decode(
            """{"records":[],"unresolved":[]}""", workout.training, emptySet())))
    }
    private fun save(store: JournalStore, id: String, date: LocalDate) {
        val json = store.readyCoachingContext(date, "").toJson().put("progress_submission_id", id)
            .put("progress_submission", store.progressSubmission(id))
            .put("nutrition_turn_ids", JSONArray(store.incrementalSubmissionIds(date, true)))
        store.saveCoachCard(SavedCoachCard(id, date, Instant.now(), QWEN_MODEL, COACH_PROMPT_VERSION,
            json.toString(), CoachAdvice(listOf("训练建议"), listOf("本次饮食建议"), listOf("本次依据"))))
    }

    @Test fun eachSubmissionGetsItsOwnCardAndHistoricalQueueSurvivesReopen() = isolated { store ->
        store.appendProgress("one", date, "服用肌酸", emptyList(), true)
        store.appendProgress("two", date.plusDays(1), "服用肌酸", emptyList(), true)
        assertEquals(PendingCoaching("one", date), store.nextPendingCoaching())
        complete(store, "one")
        complete(store, "two")
        store.close()
        JournalStore(context, store.databaseName).use { reopened ->
            assertEquals(PendingCoaching("one", date), reopened.nextPendingCoaching())
            save(reopened, "one", date)
            assertEquals(PendingCoaching("two", date.plusDays(1)), reopened.nextPendingCoaching())
            save(reopened, "two", date.plusDays(1))
            assertNull(reopened.nextPendingCoaching())
            assertEquals(2, reopened.coachCardSummaries().size)
            val sent = JSONObject(reopened.coachCard("two")!!.contextJson)
            assertEquals("two", sent.getString("progress_submission_id"))
            assertEquals(2, sent.getJSONObject("supplement_history").getJSONArray("days").let { days ->
                (0 until days.length()).sumOf { days.getJSONObject(it).getJSONArray("events").length() } })
            assertEquals("服用肌酸", sent.getJSONObject("progress_submission").getString("text"))
        }
    }

    @Test fun extractionFailureBlocksFeedbackWithoutLosingPendingSubmission() = isolated { store ->
        store.appendProgress("one", date, "服用肌酸", emptyList(), true)
        store.failNutritionExtraction(store.nextPendingNutrition()!!)
        try { store.readyCoachingContext(date, ""); fail("Pending facts reached coaching") }
        catch (_: IllegalArgumentException) { }
        assertEquals("one", store.nextPendingCoaching()!!.id)
        store.retryFailedNutrition()
        complete(store, "one")
        assertEquals(1, store.readyCoachingContext(date, "").nutritionDays.first().result!!.supplements.size)
        // Model/network failure has no database acknowledgement; reopening/retry sees the same ID.
        assertEquals("one", store.nextPendingCoaching()!!.id)
        save(store, "one", date)
        assertNull(store.nextPendingCoaching())
    }

    @Test fun appendAndCoachingQueueRollBackTogetherAndRetriesDoNotDuplicate() = isolated { store ->
        store.writableDatabase.execSQL("CREATE TRIGGER block_queue BEFORE INSERT ON progress_coaching BEGIN SELECT RAISE(ABORT,'test rollback'); END")
        try { store.appendProgress("one", date, "服用肌酸", emptyList(), true); fail("Expected rollback") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertNull(store.progressSubmission("one"))
        assertNull(store.nextPendingNutrition())
        store.writableDatabase.execSQL("DROP TRIGGER block_queue")
        repeat(2) { store.appendProgress("one", date, "服用肌酸", emptyList(), true) }
        assertEquals("one", store.nextPendingCoaching()!!.id)
        complete(store, "one")
        save(store, "one", date)
        assertNull(store.nextPendingCoaching())
    }

    @Test fun schemaElevenMigrationOnlyRequeuesUncoveredSubmissions() = isolated { store ->
        store.appendProgress("covered", date, "服用肌酸", emptyList(), true)
        complete(store, "covered")
        store.saveCoachCard(SavedCoachCard("old-random-id", date, Instant.now(), QWEN_MODEL, "old-prompt",
            JSONObject().put("nutrition_turn_ids", JSONArray(listOf("covered"))).toString(),
            CoachAdvice(listOf("训练"), listOf("饮食"), listOf("依据"))))
        store.appendProgress("pending", date, "服用肌酸", emptyList(), true)
        store.writableDatabase.execSQL("DROP TABLE progress_coaching")
        store.writableDatabase.version = 11
        store.close()
        JournalStore(context, store.databaseName).use { reopened ->
            assertEquals("pending", reopened.nextPendingCoaching()!!.id)
            assertNotNull(reopened.coachCard("old-random-id"))
            assertEquals("covered:supplement:1", reopened.nextPendingNutrition()!!.turn!!.previous!!.supplements.single().id)
        }
    }
}
