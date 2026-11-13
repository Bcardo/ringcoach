package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

/** Disposable synthetic databases; never reads owner facts or calls a live provider. */
@RunWith(AndroidJUnit4::class)
class BackgroundProgressTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 10, 2)
    private fun isolated(block: (String) -> Unit) {
        val name = "background-test-${UUID.randomUUID()}.db"
        try { block(name) } finally { ProgressHealth.forget(name); context.deleteDatabase(name) }
    }
    private fun seed(name: String, capture: Boolean = true) = JournalStore(context, name).use { store ->
        store.appendProgress("one", date, "今天休息", emptyList(), true)
        if (capture) ProgressWorkStore(store.writableDatabase).putHealth(date, telemetry())
    }
    private fun telemetry() = JSONObject().put("captured_at", "2026-10-02T09:00:00Z")
        .put("current_night", JSONObject().put("coverage", JSONObject().put("session_count", 1)
            .put("stage_interval_count", 2).put("heart_rate_sample_count", 2))
            .put("heart_rate_series", JSONArray().put(JSONObject().put("samples", JSONArray(listOf(60, 62))))))
    private fun envelope(content: JSONObject): String = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", content.toString())))).toString()
    private fun reply(request: JSONObject): String {
        val schema = request.getJSONObject("response_format").getJSONObject("json_schema").getString("name")
        val content = when (schema) {
            "daily_nutrition" -> NutritionExtraction(NUTRITION_FIELDS.associateWith { NutritionValue(null, "missing", "") },
                "unknown", "", emptyList()).toJson()
            "workout_actuals" -> JSONObject().put("records", JSONArray()).put("unresolved", JSONArray())
            else -> CoachAdvice(listOf("按计划休息"), listOf("按原摄入计划"), listOf("测试数据" )).toJson()
        }
        return envelope(content)
    }
    private fun processor(name: String, post: (JSONObject, String) -> String = { request, _ -> reply(request) }) =
        ProgressProcessor(context, name, { "test-key" }, post)

    @Test fun completesFromPersistedCaptureAndDoesNotReplayCommittedStages() = isolated { name -> runBlocking {
        seed(name)
        val calls = mutableListOf<JSONObject>()
        assertFalse(processor(name) { request, _ -> calls.add(request); reply(request) }.run())
        assertEquals(3, calls.size)
        JournalStore(context, name).use { store ->
            assertNull(store.nextPendingCoaching()); assertNull(store.nextPendingNutrition()); assertNull(store.nextPendingWorkout())
            val card = JSONObject(store.latestCoachCard()!!.contextJson)
            val manifest = card.getJSONObject("request_manifest")
            assertEquals(0, manifest.getJSONObject("current_night").getInt("heart_rate_sample_count"))
            assertEquals(2, manifest.getJSONObject("current_night").getInt("heart_rate_statistic_sample_count"))
            assertEquals("retained_statistics", manifest.getString("health_capture_mode"))
            assertEquals("2026-10-02T09:00:00Z", manifest.getString("health_captured_at"))
            assertTrue(manifest.getJSONArray("omissions").toString().contains("current_night_raw_hr"))
            assertFalse(card.getJSONObject("telemetry").toString().contains("\"samples\""))
        }
        assertFalse(processor(name) { _, _ -> error("Completed stages replayed") }.run())
    } }

    @Test fun networkRetryIsBoundedAndManualRetryPreservesSubmission() = isolated { name -> runBlocking {
        seed(name)
        repeat(3) { attempt ->
            assertEquals(attempt < 2, processor(name) { _, _ -> throw IOException("synthetic offline") }.run())
        }
        assertFalse(processor(name) { _, _ -> error("Retry budget ignored") }.run())
        JournalStore(context, name).use { store ->
            assertEquals("blocked", ProgressWorkStore(store.readableDatabase).state().getString("stage"))
            assertNotNull(store.progressSubmission("one")); store.retryFailedNutrition()
            ProgressWorkStore(store.writableDatabase).clearRetries()
        }
        assertFalse(processor(name).run())
        JournalStore(context, name).use { assertEquals("one", it.latestCoachCard()!!.id) }
    } }

    @Test fun healthWaitResumesWithoutRepeatingRecognition() = isolated { name -> runBlocking {
        seed(name, false)
        var calls = 0
        assertFalse(processor(name) { request, _ -> calls++; reply(request) }.run())
        assertEquals(2, calls)
        JournalStore(context, name).use { store ->
            assertEquals("waiting_health", ProgressWorkStore(store.readableDatabase).state().getString("stage"))
            assertNull(store.latestCoachCard())
            ProgressWorkStore(store.writableDatabase).putHealth(date, telemetry())
        }
        assertFalse(processor(name) { request, _ -> calls++; reply(request) }.run())
        assertEquals(3, calls)
    } }

    @Test fun coachNetworkFailureDoesNotRepeatRecognition() = isolated { name -> runBlocking {
        seed(name)
        assertTrue(processor(name) { request, _ ->
            if (request.getJSONObject("response_format").getJSONObject("json_schema").getString("name") == "ringcoach_card") throw IOException("offline")
            reply(request)
        }.run())
        var calls = 0
        assertFalse(processor(name) { request, _ -> calls++; reply(request) }.run())
        assertEquals(1, calls)
    } }

    @Test fun cancellationLeavesRecognitionPending() = isolated { name -> runBlocking {
        seed(name)
        try { processor(name) { _, _ -> throw CancellationException("stopped job") }.run(); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
        JournalStore(context, name).use { store -> assertEquals("pending", store.nextPendingNutrition()!!.turn!!.status) }
        assertFalse(processor(name).run())
    } }

    @Test fun transientTraceIsSentOnceButNeverPersisted() = isolated { name -> runBlocking {
        seed(name)
        ProgressHealth.remember(name, date, telemetry().put("capture_mode", "foreground_live"))
        var traceSent = false
        assertFalse(processor(name) { request, _ ->
            if (request.getJSONObject("response_format").getJSONObject("json_schema").getString("name") == "ringcoach_card") {
                val data = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
                traceSent = data.getJSONObject("telemetry").toString().contains("\"samples\"")
            }
            reply(request)
        }.run())
        assertTrue(traceSent)
        JournalStore(context, name).use { store -> assertFalse(JSONObject(store.latestCoachCard()!!.contextJson)
            .getJSONObject("telemetry").toString().contains("\"samples\"")) }
    } }

    @Test fun schemaTwelveMigrationPreservesQueueAndCompletedRecognition() = isolated { name -> runBlocking {
        seed(name, false)
        processor(name).run()
        JournalStore(context, name).use { store ->
            listOf("progress_health_snapshots", "progress_work_state", "progress_work_retries").forEach { store.writableDatabase.execSQL("DROP TABLE $it") }
            store.writableDatabase.version = 12
        }
        JournalStore(context, name).use { store ->
            assertEquals(13, store.readableDatabase.version)
            assertEquals("one", store.nextPendingCoaching()!!.id)
            assertNull(store.nextPendingNutrition()); assertNull(store.nextPendingWorkout())
            ProgressWorkStore(store.writableDatabase).putHealth(date, telemetry())
        }
        var calls = 0
        assertFalse(processor(name) { request, _ -> calls++; reply(request) }.run())
        assertEquals(1, calls)
    } }

    @Test fun accessErrorsWaitForManualRetry() = isolated { name -> runBlocking {
        seed(name)
        assertFalse(processor(name) { _, _ -> throw QwenHttpException(401) }.run())
        assertFalse(processor(name) { _, _ -> error("Access error retried automatically") }.run())
        JournalStore(context, name).use { store -> assertEquals("provider_access", ProgressWorkStore(store.readableDatabase).state().getString("error_code")) }
    } }

    @Test fun submissionArrivingDuringCoachingIsDrainedByCoalescedPass() = isolated { name -> runBlocking {
        seed(name)
        var submitted = false
        var calls = 0
        var passes = 0
        val worker = processor(name) { request, _ ->
            calls++
            if (!submitted && request.getJSONObject("response_format").getJSONObject("json_schema").getString("name") == "ringcoach_card") {
                submitted = true
                JournalStore(context, name).use { it.appendProgress("two", date, "仍然休息", emptyList(), true) }
                repeat(3) { assertTrue(ProgressRuntime.requestIfRunning(name)) }
            }
            reply(request)
        }
        ProgressRuntime.mutex.withLock {
            ProgressRuntime.started(name)
            try {
                do { passes++; assertFalse(worker.run()) } while (ProgressRuntime.consumeRequested(name))
            } finally { ProgressRuntime.finished(name) { assertFalse(it) } }
        }
        assertEquals(2, passes); assertEquals(6, calls)
        JournalStore(context, name).use { store ->
            assertNull(store.nextPendingCoaching()); assertNull(store.nextPendingNutrition()); assertNull(store.nextPendingWorkout())
            assertEquals("two", store.latestCoachCard()!!.id)
            store.readableDatabase.rawQuery("SELECT count(*) FROM coach_cards", null).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
        }
        assertFalse(ProgressRuntime.running.value)
    } }
}
