package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock

/** Explicit owner recovery: reuse one saved job; never append a second submission. */
@RunWith(AndroidJUnit4::class)
class PendingNutritionRecoveryTest {
    @Test fun replayCapturedResponseWithoutChangingOwnerData() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("replay_captured_progress") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.filesDir, "progress-recovery")
        val request = JSONObject(File(output, "request-1.json").readText(Charsets.UTF_8))
        val input = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        val source = input.getString("journal_input")
        val next = input.getJSONObject("new_input")
        val previous = NutritionExtractor.decode(input.getJSONObject("previous_daily_state").toString(), source)
        val photos = next.getJSONArray("photo_ids")
        val job = PendingNutrition(java.time.LocalDate.parse(input.getString("date")), next.getString("submission_id"),
            "", source, true, NutritionTurn(next.getString("submission_id"), next.getString("text"),
                (0 until photos.length()).map { photos.getString(it) }, 1, null, previous, "pending"))
        val response = File(output, "response-2.json").readText(Charsets.UTF_8)
        val result = DailyFoodState.calculated(job, NutritionExtractor.decodeForJob(QwenClient.responseText(response), job))
        assertEquals(previous.foodItems.size + 2, result.foodItems.size)
        assertEquals(2, result.supplements.size)
        previous.foodItems.forEach { old -> assertEquals(old, result.foodItems.single { it.id == old.id }) }
    }

    @Test fun resumeSavedQueueAfterValidationFix() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("recover_saved_progress") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProgressRuntime.mutex.withLock {
            val count = JournalStore(context).use { store ->
                val count = store.readableDatabase.rawQuery("SELECT count(*) FROM progress_submissions", null).use {
                    it.moveToFirst(); it.getInt(0)
                }
                store.retryFailedNutrition(); store.retryFailedWorkouts()
                ProgressWorkStore(store.writableDatabase).clearRetries()
                count
            }
            assertFalse("Recovery needs a transient network retry", ProgressProcessor(context).run())
            JournalStore(context).use { store ->
                assertNull("Nutrition still pending", store.nextPendingNutrition())
                assertNull("Workout still pending", store.nextPendingWorkout())
                assertNull("Coaching still pending", store.nextPendingCoaching())
                store.readableDatabase.rawQuery("SELECT count(*) FROM progress_submissions", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(count, it.getInt(0))
                }
            }
        }
    }

    @Test fun inspectOrRecoverExistingFailedTurn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("diagnose_progress") == "true")
        val context = instrumentation.targetContext
        val output = File(context.filesDir, "progress-recovery").apply { mkdirs() }
        JournalStore(context).use { store ->
            val job = store.nextPendingNutrition() ?: error("No pending saved nutrition job")
            val prompt = context.assets.open("nutrition-extraction.txt").bufferedReader().use { it.readText() }
            var calls = 0
            val report = JSONObject().put("date", job.date.toString()).put("old_status", job.turn?.status)
                .put("appended_submission", false)
            try {
                val replay = args.getString("replay_saved_response") == "true"
                val result = if (replay) {
                    val response = File(output, "response-2.json").readText(Charsets.UTF_8)
                    DailyFoodState.calculated(job, NutritionExtractor.decodeForJob(QwenClient.responseText(response), job))
                } else {
                    val key = QwenCredentials(context).read() ?: error("Saved key required")
                    NutritionExtractor.extract(job, prompt, store.nutritionPhotos(job), key) { request, credential ->
                        calls++
                        val diagnostic = JSONObject(request.toString())
                        val messages = diagnostic.getJSONArray("messages")
                        for (i in 0 until messages.length()) {
                            val parts = messages.getJSONObject(i).optJSONArray("content") ?: continue
                            for (j in 0 until parts.length()) if (parts.getJSONObject(j).optString("type") == "image_url") {
                                parts.getJSONObject(j).remove("image_url")
                                parts.getJSONObject(j).put("type", "text").put("text", "[transient photo omitted from diagnostics]")
                            }
                        }
                        File(output, "request-$calls.json").writeText(diagnostic.toString(), Charsets.UTF_8)
                        QwenClient.post(request, credential).also {
                            File(output, "response-$calls.json").writeText(it, Charsets.UTF_8)
                        }
                    }
                }
                File(output, "validated-result.json").writeText(result.toJson().toString(), Charsets.UTF_8)
                val apply = args.getString("apply_progress") == "true"
                if (apply) assertTrue(store.applyNutritionExtraction(job, result))
                report.put("validated", true).put("applied", apply).put("food_count", result.foodItems.size)
                    .put("supplement_count", result.supplements.size).put("replayed_saved_response", replay)
            } catch (e: Exception) {
                report.put("validated", false).put("applied", false).put("exception", e.javaClass.simpleName)
                    .put("error", e.message ?: "")
                File(output, "exception.txt").writeText(e.stackTraceToString(), Charsets.UTF_8)
                throw e
            } finally {
                report.put("model_calls", calls)
                File(output, "report.json").writeText(report.toString(), Charsets.UTF_8)
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "PROGRESS_DIAGNOSTIC $report\n") })
            }
        }
    }
}
