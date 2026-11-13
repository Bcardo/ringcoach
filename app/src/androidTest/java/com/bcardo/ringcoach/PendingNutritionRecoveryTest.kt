package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit owner recovery: reuse one saved job; never append a second submission. */
@RunWith(AndroidJUnit4::class)
class PendingNutritionRecoveryTest {
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
