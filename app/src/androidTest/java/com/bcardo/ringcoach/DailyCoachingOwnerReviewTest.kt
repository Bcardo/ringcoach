package com.bcardo.ringcoach

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Explicit owner review only. Does not create submissions or change permissions/reader settings. */
@RunWith(AndroidJUnit4::class)
class DailyCoachingOwnerReviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private fun optIn(action: String) = assumeTrue(args.getString("daily_coaching_owner") == "true" && args.getString(action) == "true")

    @Test fun retryExistingTodayUpdate() {
        optIn("retry_today")
        val today = LocalDate.now()
        JournalStore(context).use { assertNotNull("No current update needs retry", it.dailyCoaching(today).pending) }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var model: CoachViewModel
        instrumentation.runOnMainSync {
            model = ViewModelProvider(activity)[CoachViewModel::class.java]
            model.retryProgress(HealthConnectProbe(HealthConnectClient.getOrCreate(context)), today)
        }
        val deadline = System.currentTimeMillis() + 150_000
        var complete = false
        while (!complete && System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            complete = JournalStore(context).use { it.dailyCoaching(today).pending == null }
        }
        assertTrue("Current update still incomplete; preserve facts and inspect work state", complete)
        JournalStore(context).use { store ->
            val card = requireNotNull(store.dailyCoaching(today).card)
            assertEquals(COACH_PROMPT_VERSION, card.promptVersion)
        }
    }

    @Test fun reviewTodayAndTraining() {
        optIn("review_screens")
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        runBlocking { SamsungHealthScreenReader.awaitActiveCapture() }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        fun nodes(): List<AccessibilityNodeInfo> = buildList {
            ui.clearCache()
            fun visit(node: AccessibilityNodeInfo) { add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
            ui.rootInActiveWindow?.let(::visit)
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (ui.rootInActiveWindow?.packageName?.toString() != context.packageName && System.currentTimeMillis() < deadline) Thread.sleep(100)
        fun click(label: String) {
            // Coordinate fallback for Compose navigation not exposing a click action on this phone.
            val bitmap = requireNotNull(ui.takeScreenshot())
            val index = listOf("今日", "训练", "日志", "历史").indexOf(label)
            check(index >= 0)
            val x = bitmap.width * (index + 0.5f) / 4f
            val y = bitmap.height * 0.945f
            bitmap.recycle()
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(ui.injectInputEvent(event, true)); event.recycle()
            }
        }
        ui.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "daily-ui-before-review.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun verifyAndCapture(name: String, expected: String) {
            Thread.sleep(750)
            val labels = nodes().mapNotNull { it.text?.toString() }
            assertTrue("Expected daily view missing", labels.contains(expected))
            for (text in listOf("戒指蓝牙睡眠采集", "开启采集不代表", "早餐规则", "估算依据：", "分析未能完成，内容已保存", "训练约束与说明", "完整动作计划与最新表现"))
                assertFalse("Operational or stale content remains", labels.any { it.contains(text) })
            if (name == "training") {
                assertTrue("Training progress columns missing", labels.contains(context.getString(R.string.workout_performance)))
                assertFalse("Old clarification backlog remains", labels.any { it.contains("的以下内容需要在日志中补充说明") })
            }
            val bitmap = requireNotNull(ui.takeScreenshot())
            File(context.filesDir, "daily-ui-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        click("今日"); verifyAndCapture("today", "教练建议")
        click("训练"); verifyAndCapture("training", "训练计划与最新进度")
        click("今日")
        assertTrue(SamsungHealthScreenReader.configured(context))
    }

    @Test fun refreshTodayAdviceWithoutNewSubmission() {
        optIn("refresh_advice")
        val today = LocalDate.now()
        val before = JournalStore(context).use { it.latestCoachCard(today)?.id }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var model: CoachViewModel
        instrumentation.runOnMainSync { model = ViewModelProvider(activity)[CoachViewModel::class.java] }
        val loadedBy = System.currentTimeMillis() + 15_000
        while (model.loading && System.currentTimeMillis() < loadedBy) Thread.sleep(100)
        instrumentation.runOnMainSync { model.prepare(today, HealthConnectProbe(HealthConnectClient.getOrCreate(context)), autoGenerate = true) }
        val deadline = System.currentTimeMillis() + 150_000
        var card: SavedCoachCard? = null
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            card = JournalStore(context).use { it.latestCoachCard(today) }
            if (card != null && card.id != before) break
        }
        assertNotNull(card); assertNotEquals(before, card!!.id)
        assertEquals(COACH_PROMPT_VERSION, card.promptVersion)
        val notes = card.advice.notes.joinToString("\n")
        assertFalse("Operational details leaked into coach assessment", listOf("蓝牙", "默认规则", "早餐规则", "解码", "订阅").any(notes::contains))
    }
}
