package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Disposable fixtures/fake provider only. Completed recognition must survive retry and restart. */
@RunWith(AndroidJUnit4::class)
class ContextBudgetIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 6, 12)
    private fun envelope(content: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("content", content.toString())))).toString()
    private fun telemetry(): JSONObject = JSONObject().put("captured_at", "2026-06-12T10:00:00Z")
        .put("current_night", JSONObject().put("coverage", JSONObject().put("session_count", 1).put("heart_rate_sample_count", 1600))
            .put("heart_rate_series", JSONArray().put(JSONObject().put("samples", JSONArray((0 until 1600).map { i ->
                JSONObject().put("time", Instant.parse("2026-06-12T01:00:00Z").plusSeconds(i.toLong()).toString())
                    .put("beats_per_minute", 60 + i % 6).put("session_refs", JSONArray(listOf("overlap-a", "overlap-b")))
            })))))
    private fun facts(store: JournalStore): List<String> = listOf("daily_food_state", "daily_workout_state", "nutrition_turns", "workout_turns", "exercise_logs").map { table ->
        store.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { rows ->
            val all = JSONArray()
            while (rows.moveToNext()) all.put(JSONArray((0 until rows.columnCount).map { rows.getString(it) ?: JSONObject.NULL }))
            all.toString()
        }
    }

    @Test fun committedMixedRecognitionRetriesOnlyFeedbackAfterRestartAndKeepsTraceTransient() = runBlocking {
        val name = "budget-retry-${UUID.randomUUID()}.db"
        try {
            JournalStore(context, name).use { store ->
                store.saveSettings(CoachingSettings("Saved plan constraint. ".repeat(60), "Saved diet constraint. ".repeat(60), "Preserve confirmed facts. ".repeat(60)))
                store.appendProgress("mixed", date, "Press 20 kg x 8. Intake unknown.", emptyList(), true)
                ProgressWorkStore(store.writableDatabase).putHealth(date, telemetry())
            }
            val recognitionCalls = mutableListOf<String>()
            var coachCalls = 0
            val first = ProgressProcessor(context, name, { "synthetic-key" }, { request, _ ->
                val schema = request.getJSONObject("response_format").getJSONObject("json_schema").getString("name")
                when (schema) {
                    "daily_nutrition" -> {
                        recognitionCalls.add(schema)
                        envelope(NutritionExtraction(NUTRITION_FIELDS.associateWith { NutritionValue(null, "missing", "") }, "unknown", "", emptyList()).toJson())
                    }
                    "workout_delta" -> {
                        recognitionCalls.add(schema)
                        envelope(JSONObject().put("changes", JSONArray().put(JSONObject().put("operation", "add").put("target_ids", JSONArray())
                            .put("exercise", "Press").put("performance", "20 kg x 8").put("evidence", "Press 20 kg x 8").put("status", "completed")))
                            .put("unresolved", JSONArray()))
                    }
                    else -> {
                        coachCalls++
                        assertTrue(request.toString().toByteArray(Charsets.UTF_8).size <= COACH_REQUEST_BUDGET)
                        throw IOException("Synthetic provider interruption")
                    }
                }
            }, health = { _, _ -> telemetry() })
            assertTrue(first.run())
            assertEquals(listOf("daily_nutrition", "workout_delta"), recognitionCalls)
            val committed = JournalStore(context, name).use { store ->
                assertEquals(1, store.latestWorkoutRecords(date).size)
                assertNotNull(store.nextPendingCoaching())
                assertNull(store.nextPendingNutrition()); assertNull(IncrementalWorkoutStore(store.readableDatabase).next())
                store.retryDailyCoaching(date)
                facts(store)
            }
            ProgressHealth.forget(name) // Simulate process loss; reread only retained statistics next.
            val second = ProgressProcessor(context, name, { "synthetic-key" }, { request, _ ->
                assertEquals("ringcoach_card", request.getJSONObject("response_format").getJSONObject("json_schema").getString("name"))
                coachCalls++
                envelope(CoachAdvice(listOf("Keep the saved plan"), listOf("Intake is unknown"), listOf("Synthetic evidence only")).toJson())
            })
            assertFalse(second.run())
            JournalStore(context, name).use { store ->
                assertTrue("Completed facts must stay unchanged", committed == facts(store))
                assertNull(store.nextPendingCoaching())
                val card = JSONObject(store.coachCard("mixed")!!.contextJson)
                assertEquals("retained_statistics", card.getJSONObject("request_manifest").getString("health_capture_mode"))
                assertFalse(card.getJSONObject("telemetry").toString().contains("\"samples\""))
                assertEquals(2, coachCalls)
            }
            assertFalse(ProgressProcessor(context, name, { "synthetic-key" }, { _, _ -> error("Completed work replayed") }).run())
        } finally { ProgressHealth.forget(name); context.deleteDatabase(name) }
    }

    @Test fun boundedSizeLedgerStoresOnlySafeCountsIncludingBlockedPreparation() {
        val name = "size-ledger-${UUID.randomUUID()}.db"
        try {
            CoachingRequestSizeStore(context, name).use { ledger ->
                val input = JSONObject().put("request_date", date.toString()).put("private_identifier", "SECRET_CONTENT")
                val size = try { CoachingRequestPackager.prepare(input, "SECRET_INSTRUCTION".repeat(300), 1000); error("Budget ignored") }
                    catch (e: CoachingPayloadTooLarge) { e.size }
                repeat(110) { ledger.record(size) }
                ledger.readableDatabase.rawQuery("SELECT count(*) FROM sizes", null).use { assertTrue(it.moveToFirst()); assertEquals(100, it.getInt(0)) }
                ledger.readableDatabase.rawQuery("SELECT report_json FROM sizes", null).use { rows -> while (rows.moveToNext()) {
                    val text = rows.getString(0)
                    assertFalse(text.contains("SECRET")); assertFalse(text.contains("private_identifier"))
                    assertTrue(JSONObject(text).getBoolean("blocked"))
                } }
            }
        } finally { context.deleteDatabase(name) }
    }
}
