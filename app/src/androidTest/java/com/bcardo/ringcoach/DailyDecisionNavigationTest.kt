package com.bcardo.ringcoach

import android.app.UiAutomation
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Owner opt-in, read-only navigation. No text/screenshots/owner values emitted or progress submitted. */
@RunWith(AndroidJUnit4::class)
class DailyDecisionNavigationTest {
    @Test fun dailyPanelsAndTrainingShortcutAreAvailable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verify_daily_panel") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configured = SamsungHealthScreenReader.configured(context)
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val keepOn = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val wasKeptOn = activity.window.attributes.flags and keepOn != 0
        instrumentation.runOnMainSync { activity.window.addFlags(keepOn) }
        try {
        assertFalse("Unlock the device before this read-only navigation check", context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
        instrumentation.waitForIdleSync()
        Thread.sleep(500)
        fun nodes(): List<AccessibilityNodeInfo> {
            ui.clearCache()
            val root = ui.rootInActiveWindow ?: return emptyList()
            if (root.packageName?.toString() != context.packageName) return emptyList()
            return buildList {
                fun visit(node: AccessibilityNodeInfo) { add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
                visit(root)
            }
        }
        fun find(label: String) = nodes().lastOrNull { it.text?.toString() == label }
        fun await(label: String) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 12_000
            while (find(label) == null && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
            assertNotNull("Expected fixed navigation label missing", find(label))
        }
        fun click(label: String) {
            val candidates = nodes().filter { it.text?.toString() == label }.mapNotNull { match ->
                var node: AccessibilityNodeInfo? = match
                while (node != null && !node.isClickable) node = node.parent
                node
            }
            if (!candidates.any { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }) {
                // Some Compose navigation labels expose bounds before their click action is exported.
                // Target only a known fixed label in this package, choosing the bottom navigation label over its heading.
                val bounds = nodes().filter { it.text?.toString() == label }.map { match ->
                    android.graphics.Rect().also { match.getBoundsInScreen(it) }
                }.filter { !it.isEmpty }.maxByOrNull { it.centerY() }
                assertNotNull("Navigation target unavailable", bounds)
                val time = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(time, time + if (action == android.view.MotionEvent.ACTION_UP) 50 else 0, action, bounds!!.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                    try { assertTrue("Navigation touch unavailable", ui.injectInputEvent(event, true)) } finally { event.recycle() }
                }
            }
            Thread.sleep(400)
        }
        fun scroll(action: Int): Boolean = nodes().firstOrNull { it.isScrollable }?.performAction(action) == true
        fun reveal(label: String) {
            repeat(12) {
                if (find(label) != null) return
                scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(150)
            }
            assertNotNull("Expected panel label missing", find(label))
        }
        await("今日"); click("今日")
        repeat(12) { if (scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) Thread.sleep(100) }
        await("今日重点")
        reveal("查看训练计划与进度")
        click("查看训练计划与进度")
        await("训练计划与最新进度")
        click("今日")
        reveal("昨夜记录")
        assertEquals(configured, SamsungHealthScreenReader.configured(context))
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "DAILY_PANEL_NAV focus=true training_shortcut=true night_panel=true owner_text_logged=false submissions_created=0\n")
        })
        } finally {
            if (!wasKeptOn) instrumentation.runOnMainSync { activity.window.clearFlags(keepOn) }
        }
    }
}
