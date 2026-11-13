package com.bcardo.ringcoach

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Explicit recovery refresh through the visible Activity's actual ViewModel; no journal re-entry. */
@RunWith(AndroidJUnit4::class)
class ForegroundCoachingRecoveryTest {
    @Test fun refreshExistingProgressWithForegroundHealthEvidence() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("recover_coach_health") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val activity = monitor.waitForActivityWithTimeout(10000) as? ComponentActivity ?: error("RingCoach did not open")
            lateinit var model: CoachViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(activity)[CoachViewModel::class.java] }
            val readyBy = System.currentTimeMillis() + 20000
            while ((model.busy || !model.foreground) && System.currentTimeMillis() < readyBy) Thread.sleep(100)
            assertFalse("Model still busy", model.busy)
            assertTrue("RingCoach must remain visible for the health read", model.foreground)
            val previousId = model.card?.id
            instrumentation.runOnMainSync { model.prepare(LocalDate.now(), HealthConnectProbe(HealthConnectClient.getOrCreate(context))) }
            val preparedBy = System.currentTimeMillis() + 60000
            while (model.preparing && System.currentTimeMillis() < preparedBy) Thread.sleep(100)
            val prepared = model.prepared ?: error("Prepare failed; notice=${model.notice}")
            val counts = prepared.context.getJSONObject("request_manifest").getJSONObject("current_night")
            assertTrue("No current sleep evidence collected; request was not sent", counts.optInt("session_count") > 0)
            assertTrue("No HR samples collected; request was not sent", counts.optInt("heart_rate_sample_count") > 0)
            instrumentation.runOnMainSync { model.generate() }
            val generatedBy = System.currentTimeMillis() + 90000
            while (model.busy && System.currentTimeMillis() < generatedBy) Thread.sleep(200)
            assertTrue("Response not saved; notice=${model.notice}", model.cardSaved && model.card?.id != previousId)
            JournalStore(context).use { store ->
                val card = store.coachCard(model.card!!.id) ?: error("Missing refreshed response")
                val saved = JSONObject(card.contextJson)
                val series = saved.getJSONObject("telemetry").getJSONObject("current_night").getJSONArray("heart_rate_series")
                for (i in 0 until series.length()) assertFalse(series.getJSONObject(i).has("samples"))
                assertNull(store.nextPendingCoaching())
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "FOREGROUND_RECOVERY saved=true sleep_sessions=${counts.getInt("session_count")} hr_samples=${counts.getInt("heart_rate_sample_count")} raw_trace_saved=false\n") })
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
