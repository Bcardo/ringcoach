package com.bcardo.ringcoach

import android.app.UiAutomation
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Owner opt-in: prepares existing facts in memory, never sends a model request or edits a journal. */
@RunWith(AndroidJUnit4::class)
class ScreenFreeRecoveryOwnerTest {
    @Test fun refreshAndPrepareStayInRingCoachWithoutScreenCapture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verify_screen_free") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuredBefore = SamsungHealthScreenReader.configured(context)
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) as MainActivity
        lateinit var coach: CoachViewModel
        instrumentation.runOnMainSync { coach = ViewModelProvider(activity)[CoachViewModel::class.java] }
        val loadedBy = System.currentTimeMillis() + 15_000
        while (coach.loading && System.currentTimeMillis() < loadedBy) Thread.sleep(100)
        assertFalse(coach.loading); assertFalse(coach.loadFailed)
        ui.clearCache()
        assertEquals("RingCoach must be visible before this check", context.packageName, ui.rootInActiveWindow?.packageName?.toString())
        val keepScreenOn = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val alreadyKeptOn = activity.window.attributes.flags and keepScreenOn != 0
        instrumentation.runOnMainSync { activity.window.addFlags(keepScreenOn) }
        try {
            val screenAttemptBefore = context.getSharedPreferences("nightly-screen", 0).all
            instrumentation.runOnMainSync {
                coach.refreshNightly()
                coach.prepare(LocalDate.now(), null)
            }
            // Observe beyond the former one-minute automatic retry cooldown. Never read or emit UI text.
            val deadline = System.currentTimeMillis() + 65_000
            while (System.currentTimeMillis() < deadline) {
                ui.clearCache()
                val activePackage = ui.rootInActiveWindow?.packageName?.toString()
                assertNotEquals("Samsung Health opened during recovery preparation", SAMSUNG_HEALTH_PACKAGE, activePackage)
                Thread.sleep(500)
            }
            assertFalse(coach.preparing)
            val prepared = requireNotNull(coach.prepared) { "Coaching preparation failed" }
            assertTrue(prepared.context.has("ring_bluetooth"))
            assertEquals("retired", prepared.context.getJSONObject("nightly_recovery").getString("screen_capture_mode"))
            assertEquals(screenAttemptBefore, context.getSharedPreferences("nightly-screen", 0).all)
            assertEquals(configuredBefore, SamsungHealthScreenReader.configured(context))
            ui.clearCache()
            assertEquals("Preparation must leave RingCoach visible", context.packageName, ui.rootInActiveWindow?.packageName?.toString())
        } finally {
            instrumentation.runOnMainSync {
                coach.dismissPrepared()
                if (!alreadyKeptOn) activity.window.clearFlags(keepScreenOn)
            }
        }
    }
}
