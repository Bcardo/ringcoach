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
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        if (InstrumentationRegistry.getArguments().getString("await_owner_unlock") == "true") {
            val unlockDeadline = android.os.SystemClock.elapsedRealtime() + 90_000
            while (keyguard.isDeviceLocked && android.os.SystemClock.elapsedRealtime() < unlockDeadline) Thread.sleep(250)
        }
        assertFalse("Unlock the device before this read-only navigation check", keyguard.isDeviceLocked)
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
        fun find(label: String) = nodes().lastOrNull { it.isVisibleToUser && it.text?.toString() == label }
        fun await(label: String) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 12_000
            while (find(label) == null && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
            assertNotNull("Expected fixed navigation label missing", find(label))
        }
        fun click(label: String) {
            val candidates = nodes().filter { it.isVisibleToUser && it.text?.toString() == label }.mapNotNull { match ->
                var node: AccessibilityNodeInfo? = match
                while (node != null && !node.isClickable) node = node.parent
                node
            }
            if (!candidates.any { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }) {
                // Some Compose navigation labels expose bounds before their click action is exported.
                // Target only a known fixed label in this package, choosing the bottom navigation label over its heading.
                val bounds = nodes().filter { it.isVisibleToUser && it.text?.toString() == label }.map { match ->
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
        assertFalse("Removed primary tab is still present", nodes().any { it.text?.toString() == "历史" })
        repeat(12) { if (scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) Thread.sleep(100) }
        await("今日重点")
        assertFalse("Daily advice still has a collapse control", nodes().any {
            it.text?.toString() in setOf("查看完整建议", "收起完整建议")
        })
        reveal("依据与提醒")
        reveal("训练建议")
        reveal("查看训练计划与进度")
        click("查看训练计划与进度")
        await("今日训练建议")
        reveal("训练计划与最新进度")
        reveal("查看过往表现"); click("查看过往表现"); await("收起过往表现")
        click("收起过往表现")
        click("今日")
        reveal("昨夜记录")
        val expectedCarbs = JournalStore(context).use { store ->
            store.readCoachingContext(java.time.LocalDate.now(), "").nutritionDays
                .firstOrNull { it.date == java.time.LocalDate.now() }?.result?.values?.get("carbohydrate_grams")
        }
        val carbLabel = "碳水：" + (if (expectedCarbs?.kind == "estimated") "≈" else "") + nutritionNumber(expectedCarbs?.value) + " g"
        reveal(carbLabel)
        assertNotNull("Intake display does not match the saved calculation", find(carbLabel))
        click("日志")
        await("提交更新")
        reveal("查看今日建议"); click("查看今日建议")
        await("今日重点")
        click("日志"); await("提交更新")
        lateinit var journal: JournalViewModel
        lateinit var photos: PhotoMealViewModel
        instrumentation.runOnMainSync {
            val provider = androidx.lifecycle.ViewModelProvider(activity as androidx.activity.ComponentActivity)
            journal = provider[JournalViewModel::class.java]; photos = provider[PhotoMealViewModel::class.java]
        }
        val draft = journal.draft
        val submission = journal.submissionId
        val attachments = photos.attachments.toList()
        click("查看以往日志与建议")
        await("以往记录")
        click("返回日志")
        await("提交更新")
        click("查看以往日志与建议")
        await("以往记录")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        await("提交更新")
        assertTrue("Draft changed while browsing archive", draft == journal.draft)
        assertTrue("Submission identity changed while browsing archive", submission == journal.submissionId)
        assertTrue("Attachments changed while browsing archive", attachments == photos.attachments)
        click("今日")
        reveal("查看恢复趋势"); click("查看恢复趋势")
        await("恢复详情"); await("恢复趋势")
        val today = java.time.LocalDate.now()
        click("近 30 天"); await("${today.minusDays(29)} — $today · 按醒来日期")
        click("近 7 天"); await("${today.minusDays(6)} — $today · 按醒来日期")
        fun dateSelector(): String {
            repeat(12) {
                nodes().firstOrNull { node -> node.isVisibleToUser && node.text?.toString()?.startsWith("查看日期：") == true }?.let { return it.text.toString() }
                scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(150)
            }
            throw AssertionError("Recovery date selector unavailable")
        }
        click(dateSelector()); click(today.minusDays(1).toString())
        await("${today.minusDays(1)} · 历史记录")
        reveal("查看当天日志与建议"); click("查看当天日志与建议")
        await("以往记录"); click("返回日志"); await("提交更新")
        click("今日")
        assertEquals(configured, SamsungHealthScreenReader.configured(context))
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "DAILY_PANEL_NAV tabs=3 focus=true training_shortcut=true inline_workout_history=true night_panel=true journal_archive=true back=true draft_preserved=true attachments_preserved=true recovery_range_and_date=true recovery_drilldown=true owner_text_logged=false submissions_created=0\n")
        })
        } finally {
            if (!wasKeptOn) instrumentation.runOnMainSync { activity.window.clearFlags(keepOn) }
        }
    }
}
