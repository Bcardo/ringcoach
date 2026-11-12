package com.bcardo.ringcoach

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.PersistableBundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Two phases: seed synthetic durable work, terminate process externally, run job, verify and remove. */
@RunWith(AndroidJUnit4::class)
class BackgroundProcessRestartTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "background-process-restart.db"
    private val date = LocalDate.of(2026, 10, 2)

    @Test fun prepareDurableJob() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restart_phase") == "prepare")
        context.deleteDatabase(name)
        JournalStore(context, name).use { store ->
            store.saveSettings(CoachingSettings("今天休息", "原摄入计划", "仅依据给定事实"))
            store.appendProgress("restart-one", date, "今天休息", emptyList(), true)
            assertTrue(store.applyNutritionExtraction(store.nextPendingNutrition()!!,
                NutritionExtraction(NUTRITION_FIELDS.associateWith { NutritionValue(null, "missing", "") }, "unknown", "", emptyList())))
            assertTrue(store.applyWorkoutExtraction(store.nextPendingWorkout()!!, WorkoutExtraction(emptyList(), emptyList())))
            val telemetry = JSONObject().put("captured_at", "2026-10-02T09:00:00Z")
                .put("current_night", JSONObject().put("coverage", JSONObject().put("heart_rate_sample_count", 2))
                    .put("heart_rate_series", JSONArray().put(JSONObject().put("samples", JSONArray(listOf(60, 62))))))
            ProgressHealth.remember(name, date, telemetry)
            ProgressWorkStore(store.writableDatabase).putHealth(date, telemetry)
        }
        val extras = PersistableBundle().apply { putString("database", name) }
        assertEquals(JobScheduler.RESULT_SUCCESS, context.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(9273, ComponentName(context, ProgressJob::class.java)).setExtras(extras)
                .setMinimumLatency(86_400_000).setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()))
    }

    @Test fun verifyCompletedAfterProcessRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restart_phase") == "verify")
        try {
            JournalStore(context, name).use { store ->
                assertNull(store.nextPendingCoaching())
                val card = store.latestCoachCard() ?: error("Background restart has not produced feedback")
                assertEquals("restart-one", card.id)
                val manifest = JSONObject(card.contextJson).getJSONObject("request_manifest")
                assertEquals("retained_statistics", manifest.getString("health_capture_mode"))
                assertEquals(0, manifest.getJSONObject("current_night").getInt("heart_rate_sample_count"))
                assertEquals(2, manifest.getJSONObject("current_night").getInt("heart_rate_statistic_sample_count"))
                assertNull(store.nextPendingNutrition()); assertNull(store.nextPendingWorkout())
            }
        } finally { context.getSystemService(JobScheduler::class.java).cancel(9273); context.deleteDatabase(name) }
    }
}
