package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Explicit recovery of an existing queued item, never a new Journal submission or recognition replay. */
@RunWith(AndroidJUnit4::class)
class ContextBudgetOwnerRecoveryTest {
    @Test fun recoverExistingDateWithoutRerecognizingCommittedFacts() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("recover_existing_context") == "true")
        val date = LocalDate.parse(requireNotNull(args.getString("recovery_date")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun snapshot(store: JournalStore): List<String> = listOf("journal_entries", "progress_submissions", "coaching_settings", "daily_food_state",
            "nutrition_turns", "exercise_logs", "workout_turns", "daily_workout_state", "photo_meals").map { table ->
            store.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { rows ->
                val result = org.json.JSONArray()
                while (rows.moveToNext()) result.put(org.json.JSONArray((0 until rows.columnCount).map { i ->
                    if (rows.isNull(i)) JSONObject.NULL else if (rows.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) rows.getBlob(i).contentHashCode().toString() else rows.getString(i)
                }))
                result.toString()
            }
        }
        ProgressRuntime.mutex.withLock {
            val pendingId: String
            val before: List<String>
            JournalStore(context).use { store ->
                val pending = requireNotNull(store.nextPendingCoaching()) { "No pending request; no recovery performed" }
                assertEquals("Only the explicitly selected date may be recovered", date, pending.date)
                pendingId = pending.id
                assertNull(store.nextPendingNutrition()); assertNull(IncrementalWorkoutStore(store.readableDatabase).next()); assertNull(store.nextPendingWorkout())
                before = snapshot(store)
                store.retryDailyCoaching(date)
            }
            var calls = 0
            ProgressProcessor(context, post = { request, key ->
                assertEquals("Recognition replay is not allowed", "ringcoach_card", request.getJSONObject("response_format").getJSONObject("json_schema").getString("name"))
                val wire = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
                assertEquals("Only the selected date may reach the model", date.toString(), wire.getString("request_date"))
                calls++
                QwenClient.post(request, key)
            }).run()
            JournalStore(context).use { store ->
                assertTrue("Original confirmed owner facts must remain unchanged", before == snapshot(store))
                val card = requireNotNull(store.coachCard(pendingId)) { "Existing request is still blocked; inspect safe size metadata" }
                assertEquals(COACH_PROMPT_VERSION, card.promptVersion)
                assertTrue(JSONObject(card.contextJson).getJSONObject("request_manifest").getInt("serialized_request_bytes") <= COACH_REQUEST_BUDGET)
                assertEquals(1, calls)
            }
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "CONTEXT_RECOVERY saved_card=true recognition_replayed=false original_facts_preserved=true\n") })
    }
}
