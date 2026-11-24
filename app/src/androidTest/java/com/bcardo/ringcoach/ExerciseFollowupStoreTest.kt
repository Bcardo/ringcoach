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

/** Disposable synthetic database only; no provider calls or owner facts. */
@RunWith(AndroidJUnit4::class)
class ExerciseFollowupStoreTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val day = LocalDate.of(2026, 6, 12)
    private fun facts() = JSONObject().put("request_date", day.toString()).put("routine", "Saved synthetic routine")
        .put("instructions", "Maintain unless explicitly changed")
        .put("exercise_baselines", JSONArray().put(ExerciseBaseline("Day A", "Synthetic press", "20 kg", day.minusDays(1)).toJson()))
        .put("latest_workout_records", JSONArray().put(JSONObject().put("date", day.toString()).put("exercise", "Synthetic press")
            .put("status", "completed").put("performance", "20 kg x 8")))
    private fun card(id: String, facts: JSONObject, seconds: Long, notes: List<ExerciseNote> = emptyList()) =
        SavedCoachCard(id, LocalDate.parse(facts.getString("request_date")), Instant.ofEpochSecond(seconds), "synthetic", COACH_PROMPT_VERSION,
            facts.toString(), CoachAdvice(listOf("Feedback"), listOf("Diet"), listOf("Notes"), notes))
    private fun isolated(block: (String, JournalStore) -> Unit) {
        val name = "exercise-followups-${UUID.randomUUID()}.db"
        try { JournalStore(app, name).use { block(name, it) } } finally { app.deleteDatabase(name) }
    }

    @Test fun compatibleNoteSurvivesDietCardAndDatabaseReopenWithoutWritingFacts() = isolated { name, store ->
        val context = facts()
        val note = ExerciseNote("e1", "下次先完成原目标，再按已保存规则评估。")
        store.saveCoachCard(card("workout", context, 1, listOf(note)))
        store.saveCoachCard(card("diet", context, 2))
        assertEquals(note.text, store.exerciseFollowups(context, day)["Synthetic press"]?.text)
        JournalStore(app, name).use { reopened ->
            assertEquals(note.text, reopened.exerciseFollowups(context, day)["Synthetic press"]?.text)
            assertTrue(reopened.latestCoachCard()!!.advice.exerciseNotes.isEmpty())
            for (table in listOf("journal_entries", "exercise_logs", "exercise_baselines")) {
                reopened.readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use { rows -> rows.moveToFirst(); assertEquals(0, rows.getInt(0)) }
            }
        }
    }

    @Test fun correctionPlanChangeFutureAndExpiredNotesAreHidden() = isolated { _, store ->
        val context = facts()
        store.saveCoachCard(card("workout", context, 10, listOf(ExerciseNote("e1", "下次先维持原目标。"))))
        assertTrue(store.exerciseFollowups(context, day, Instant.ofEpochSecond(9)).isEmpty())
        val corrected = JSONObject(context.toString())
        corrected.getJSONArray("latest_workout_records").getJSONObject(0).put("performance", "18 kg x 8")
        assertTrue(store.exerciseFollowups(corrected, day).isEmpty())
        assertTrue(store.exerciseFollowups(JSONObject(context.toString()).put("routine", "Changed plan"), day).isEmpty())
        val delayed = JSONObject(context.toString()).put("request_date", day.plusDays(14).toString())
        assertTrue(store.exerciseFollowups(delayed, day.plusDays(14)).isEmpty())
    }

    @Test fun unknownReferencesCannotBlockMainCardAndLegacyShapeStaysReadable() = isolated { _, store ->
        val context = facts()
        store.saveCoachCard(card("unknown", context, 1, listOf(ExerciseNote("e99", "下次未知动作。"))))
        assertEquals(listOf("Feedback"), store.latestCoachCard()!!.advice.training)
        assertTrue(store.exerciseFollowups(context, day).isEmpty())
        assertEquals(setOf("training", "diet", "notes"), store.latestCoachCard()!!.advice.toJson().keys().asSequence().toSet())
    }
}
