package com.bcardo.ringcoach

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Opt-in read-only owner navigation; no journal submission or model request. */
@RunWith(AndroidJUnit4::class)
class RecoveryHistoryUiTest {
    @Test fun rangeSelectionDateInspectionAndReturnKeepFourTabs() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ui_recovery") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(700)
        fun nodes(): List<AccessibilityNodeInfo> = buildList {
            automation.clearCache()
            fun visit(node: AccessibilityNodeInfo) { add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
            val root = automation.windows.sortedByDescending { it.isFocused }.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == "com.bcardo.ringcoach" }
                ?: automation.rootInActiveWindow
            root?.let { it.refresh(); visit(it) }
        }
        fun labels() = nodes().mapNotNull { it.text?.toString() }
        fun await(label: String) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 40000
            while (android.os.SystemClock.elapsedRealtime() < deadline && label !in labels()) Thread.sleep(200)
            if (label !in labels()) {
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Navigation diagnostics: windows=" + automation.windows.map { "${it.root?.packageName}:${it.isFocused}" } + "; expected app labels=" + labels().filter { it in listOf("今日", "历史", "恢复趋势", "近 7 天", "近 30 天") } + "\n") })
            }
            assertTrue("Missing: $label", label in labels())
        }
        fun click(label: String) {
            val bounds = nodes().filter { it.text?.toString() == label }.map {
                android.graphics.Rect().also(it::getBoundsInScreen)
            }.filter { !it.isEmpty }.maxByOrNull { it.bottom }
            assertNotNull("Missing touch target: $label", bounds)
            if (bounds != null) {
                automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use {
                    java.io.FileInputStream(it.fileDescriptor).readBytes()
                }
            }
            Thread.sleep(350)
            try { automation.waitForIdle(300, 3000) } catch (_: Exception) { }
        }
        fun screenshot(label: String) {
            Thread.sleep(300)
            val file = File(context.filesDir, "ui-review/recovery-$label.png").apply { parentFile!!.mkdirs() }
            automation.takeScreenshot()?.let { bitmap ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        }
        await("今日"); click("今日"); Thread.sleep(250); click("历史")
        await("恢复趋势"); await("近 30 天"); click("近 30 天")
        val today = LocalDate.now()
        await("${today.minusDays(29)} — $today · 按醒来日期")
        await("睡眠 HRV")
        screenshot("30")
        click("近 7 天")
        await("${today.minusDays(6)} — $today · 按醒来日期")
        screenshot("7")
        // The date selector may be below the viewport on the cover display.
        var attempts = 0
        while (nodes().none { it.text?.toString()?.startsWith("查看日期：") == true } && attempts++ < 10) {
            val display = android.graphics.Rect().also(nodes().first()::getBoundsInScreen)
            automation.executeShellCommand("input swipe ${display.centerX()} ${display.top + display.height() * 7 / 10} ${display.centerX()} ${display.top + display.height() / 2} 600").use {
                java.io.FileInputStream(it.fileDescriptor).readBytes()
            }
            Thread.sleep(700)
        }
        screenshot("selector-search")
        val selector = labels().firstOrNull { it.startsWith("查看日期：") }
        assertNotNull("Date selector absent after $attempts swipes", selector)
        click(selector!!)
        val yesterday = today.minusDays(1)
        await(yesterday.toString()); click(yesterday.toString())
        await("$yesterday · 历史记录")
        screenshot("detail")
        assertTrue(labels().containsAll(listOf("今日", "训练", "日志", "历史")))
        click("今日")
    }
}
