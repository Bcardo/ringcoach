package com.bcardo.ringcoach

import android.app.Application
import android.app.UiAutomation
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Opt-in: send one real coaching refresh from existing facts, with no journal edits or permission changes. */
@RunWith(AndroidJUnit4::class)
class ReaderReliabilityLiveTest {
    @Test fun freshHrvReachesActualCoachingAndRemainsEnabled() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verify_reader_coaching") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val date = LocalDate.now()
        assertTrue("Reader permission must already be enabled by the owner", SamsungHealthScreenReader.configured(app))
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun nodes(): List<AccessibilityNodeInfo> = buildList {
            automation.clearCache()
            fun visit(node: AccessibilityNodeInfo) { add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
            automation.rootInActiveWindow?.let(::visit)
        }
        val home = requireNotNull(app.packageManager.getLaunchIntentForPackage(SAMSUNG_HEALTH_PACKAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        app.startActivity(home)
        val homeBy = System.currentTimeMillis() + 15_000
        while (nodes().none { it.viewIdResourceName == "$SAMSUNG_HEALTH_PACKAGE:id/new_me_recycler_view" } && System.currentTimeMillis() < homeBy) Thread.sleep(100)
        repeat(2) {
            val dashboard = nodes().firstOrNull { it.viewIdResourceName == "$SAMSUNG_HEALTH_PACKAGE:id/new_me_recycler_view" }
                ?: error("Samsung dashboard not found; no coaching request was made")
            assertTrue("Dashboard did not scroll", dashboard.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            Thread.sleep(500)
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val visibleBy = System.currentTimeMillis() + 40_000
        while (automation.rootInActiveWindow?.packageName?.toString() != app.packageName && System.currentTimeMillis() < visibleBy) Thread.sleep(100)
        assertEquals("Keep RingCoach visible for the foreground read", app.packageName, automation.rootInActiveWindow?.packageName?.toString())
        val boundBy = System.currentTimeMillis() + 10_000
        while (!SamsungHealthScreenReader.enabled && System.currentTimeMillis() < boundBy) Thread.sleep(100)
        assertTrue("Enabled reader is not bound", SamsungHealthScreenReader.enabled)
        val captureStarted = Instant.now()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try { runBlocking { withTimeout(40_000) { scope.async { SamsungHealthScreenReader.capture(force = true) }.await() } } }
        finally { scope.cancel() }
        val hrv = NightlyRecoveryStore(app).use { store ->
            val records = store.snapshot(date).getJSONArray("records")
            (0 until records.length()).map { records.getJSONObject(it) }
                .singleOrNull { it.optString("wake_date") == date.toString() && it.optString("metric") == "sleeping_hrv" }
        } ?: error("No HRV for the current date; no model request was made")
        assertFalse("Only an old capture was available", Instant.parse(hrv.getString("last_verified_at")).isBefore(captureStarted))
        val returnedBy = System.currentTimeMillis() + 10_000
        while (automation.rootInActiveWindow?.packageName?.toString() != app.packageName && System.currentTimeMillis() < returnedBy) Thread.sleep(100)
        assertEquals(app.packageName, automation.rootInActiveWindow?.packageName?.toString())
        fun submissionCount(): Int = JournalStore(app).use { it.readableDatabase.rawQuery("SELECT count(*) FROM progress_submissions", null).use { rows -> rows.moveToFirst(); rows.getInt(0) } }
        val beforeCount = submissionCount()
        lateinit var model: CoachViewModel
        val models = ViewModelStore()
        instrumentation.runOnMainSync {
            model = CoachViewModel(app); models.put("reader-verification", model)
            model.onForegroundChanged(true)
        }
        try {
            val loadedBy = System.currentTimeMillis() + 15_000
            while (model.loading && System.currentTimeMillis() < loadedBy) Thread.sleep(100)
            assertFalse(model.loadFailed); assertTrue(model.hasKey)
            val previousCardId = model.card?.id
            instrumentation.runOnMainSync { model.prepare(date, HealthConnectProbe(HealthConnectClient.getOrCreate(app))) }
            val preparedBy = System.currentTimeMillis() + 60_000
            while (model.preparing && System.currentTimeMillis() < preparedBy) Thread.sleep(100)
            val prepared = model.prepared ?: error("Preparation failed; no request was sent")
            val records = prepared.context.getJSONObject("nightly_recovery").getJSONArray("records")
            val sent = (0 until records.length()).map { records.getJSONObject(it) }.single {
                it.optString("wake_date") == date.toString() && it.optString("metric") == "sleeping_hrv"
            }
            assertEquals(hrv.getDouble("value"), sent.getDouble("value"), 0.0)
            assertTrue(prepared.context.getJSONObject("request_manifest").getJSONObject("current_night").optInt("session_count") > 0)
            assertEquals(app.packageName, automation.rootInActiveWindow?.packageName?.toString())
            instrumentation.runOnMainSync { model.generate() }
            val savedBy = System.currentTimeMillis() + 90_000
            while (model.busy && System.currentTimeMillis() < savedBy) Thread.sleep(100)
            assertTrue("Live response not saved", model.cardSaved)
            val card = requireNotNull(model.card)
            assertNotEquals("An old card cannot stand in for a new response", previousCardId, card.id)
            val retained = JSONObject(card.contextJson)
            assertTrue(retained.getJSONObject("request_manifest").getJSONObject("freshness").optBoolean("target_date_recovery_complete"))
            assertEquals(beforeCount, submissionCount())
            assertTrue(SamsungHealthScreenReader.configured(app)); assertTrue(SamsungHealthScreenReader.enabled)
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
                "READER_VERIFICATION fresh_hrv_in_request=true response_saved=true journal_unchanged=true reader_enabled=true\n") })
        } finally { instrumentation.runOnMainSync { model.onForegroundChanged(false); models.clear() } }
    }
}
