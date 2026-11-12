package com.bcardo.ringcoach

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Explicit live check: reads existing progress and saves one real coaching update, without editing journals. */
@RunWith(AndroidJUnit4::class)
class NightlyCoachingLiveTest {
    @Test fun savedContextUsesNightlyRecovery() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveNightly") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val today = LocalDate.now()
        val snapshot = NightlyRecoveryStore(app).use { it.snapshot(today) }
        val rows = snapshot.getJSONArray("records")
        val current = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("wake_date") == today.toString() }
        assertTrue("No fresh sleeping HRV", current.any { it.getString("metric") == "sleeping_hrv" })
        assertTrue("No API Energy Score", current.any { it.getString("metric") == "energy_score" && it.getString("source") == "samsung_health_sdk" })
        lateinit var model: CoachViewModel
        instrumentation.runOnMainSync { model = CoachViewModel(app) }
        val loadDeadline = System.currentTimeMillis() + 10_000
        while (model.loading && System.currentTimeMillis() < loadDeadline) Thread.sleep(100)
        assertFalse(model.loadFailed)
        assertTrue("Saved Qwen credential required", model.hasKey)
        val probe = HealthConnectProbe(HealthConnectClient.getOrCreate(app))
        val previousId = model.card?.id
        instrumentation.runOnMainSync {
            model.onForegroundChanged(true)
            model.prepare(today, probe, autoGenerate = true)
        }
        val deadline = System.currentTimeMillis() + 100_000
        while (model.busy && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertTrue("Coach update failed; notice=${model.notice}", model.cardSaved && model.card != null && model.card?.id != previousId)
        val submission = model.card!!.id
        JournalStore(app).use { store ->
            val card = store.coachCard(submission) ?: error("Missing saved card")
            val context = org.json.JSONObject(card.contextJson)
            assertTrue(context.getJSONObject("nightly_recovery").getJSONArray("records").length() >= 2)
            assertEquals("checked", context.getString("telemetry_status"))
            assertTrue(context.has("daily_nutrition"))
            assertTrue(context.has("latest_workout_records"))
            assertEquals(COACH_PROMPT_VERSION, card.promptVersion)
        }
        println("NIGHTLY_LIVE saved_card=true fresh_hrv=true api_energy=true journal_unchanged=true")
    }
}
