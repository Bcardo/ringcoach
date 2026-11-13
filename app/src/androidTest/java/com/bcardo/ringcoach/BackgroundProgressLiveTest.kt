package com.bcardo.ringcoach

import android.app.job.JobScheduler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Opt-in live provider calls from the real Android JobService, with no open Activity or owner facts. */
@RunWith(AndroidJUnit4::class)
class BackgroundProgressLiveTest {
    @Test fun androidJobCompletesWhileNoActivityIsOpen() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_background") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "background-live-${UUID.randomUUID()}.db"
        val date = LocalDate.of(2026, 10, 2)
        try {
            assertNotNull("Saved provider key required", QwenCredentials(context).read())
            instrumentation.runOnMainSync {
                assertTrue("Test must run without a resumed RingCoach Activity",
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).isEmpty())
            }
            JournalStore(context, name).use { store ->
                store.saveSettings(CoachingSettings("今天休息，无训练计划。", "摄入维持原计划。", "仅依据给定事实。"))
                store.appendProgress("synthetic-one", date, "今天休息，无训练。已服用肌酸3g。", emptyList(), true)
                ProgressWorkStore(store.writableDatabase).putHealth(date, JSONObject()
                    .put("status", "synthetic_no_health_records").put("captured_at", Instant.now().toString()))
            }
            assertEquals(JobScheduler.RESULT_SUCCESS, ProgressJob.schedule(context, name))
            val command = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand("cmd jobscheduler run -f ${context.packageName} 9273")
            command.use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            val deadline = System.currentTimeMillis() + 180_000
            var complete = false
            while (!complete && System.currentTimeMillis() < deadline) {
                JournalStore(context, name).use { store ->
                    complete = store.latestCoachCard()?.id == "synthetic-one"
                    val state = ProgressWorkStore(store.readableDatabase).state()
                    assertNotEquals("Background job blocked: $state", "blocked", state.optString("stage"))
                }
                if (!complete) Thread.sleep(500)
            }
            assertTrue("Android job did not finish before test deadline", complete)
            JournalStore(context, name).use { store ->
                assertNull(store.nextPendingCoaching()); assertNull(store.nextPendingNutrition()); assertNull(store.nextPendingWorkout())
                val card = store.latestCoachCard()!!
                val saved = JSONObject(card.contextJson)
                assertFalse(saved.has("nightly_recovery"))
                assertFalse(saved.has("recovery_history"))
                assertEquals("retained_statistics", saved.getJSONObject("request_manifest").getString("health_capture_mode"))
                assertEquals("taken", store.readCoachingContext(date, "").nutritionDays.first().result!!.supplements.single().status)
            }
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", "BACKGROUND_JOB activity_open=false recognition_saved=true coach_saved=true owner_facts_read=false\n")
            })
        } finally {
            context.getSystemService(JobScheduler::class.java).cancel(9273)
            val stoppedBy = System.currentTimeMillis() + 60_000
            while (ProgressRuntime.running.value && System.currentTimeMillis() < stoppedBy) Thread.sleep(100)
            ProgressHealth.forget(name); context.deleteDatabase(name)
        }
    }
}
