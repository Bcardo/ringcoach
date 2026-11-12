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

/** Read-only navigation smoke test on the owner's app; never enters or submits progress. */
@RunWith(AndroidJUnit4::class)
class FourTabUiTest {
    @Test fun fourTabsHaveDistinctRoles() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ui_review") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun nodes(): List<AccessibilityNodeInfo> {
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) {
                result.add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            automation.rootInActiveWindow?.let(::visit)
            return result
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 35000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (nodes().any { it.text?.toString() == "今日已记录摄入" }) break
            Thread.sleep(250)
        }
        fun labels() = nodes().mapNotNull { it.text?.toString() }
        fun awaitLabel(label: String) {
            val until = android.os.SystemClock.elapsedRealtime() + 7000
            while (android.os.SystemClock.elapsedRealtime() < until && label !in labels()) Thread.sleep(200)
            assertTrue("Missing expected label: $label", label in labels())
        }
        assertTrue(labels().containsAll(listOf("今日", "训练", "日志", "历史")))
        assertFalse(labels().contains("教练"))
        val output = File(context.filesDir, "ui-review").apply { mkdirs() }
        fun screenshot(name: String) {
            automation.takeScreenshot()?.let { bitmap ->
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun click(label: String) {
            var node: AccessibilityNodeInfo? = nodes().last { it.text?.toString() == label }
            while (node != null && !node.isClickable) node = node.parent
            assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            try { automation.waitForIdle(500, 5000) } catch (_: Exception) { }
        }
        screenshot("today")
        assertFalse(labels().any { it in listOf("记录 / 追加餐食", "查看教练建议", "记录今天") })
        click("训练")
        screenshot("training")
        awaitLabel("当前训练计划")
        awaitLabel("完整动作计划与最新表现")
        assertFalse(labels().contains("记录或追加一餐"))
        screenshot("training")
        click("日志")
        screenshot("journal")
        awaitLabel("提交更新")
        assertFalse(labels().any { it in listOf("追加一餐（文字）", "今天保存的餐食", "刷新餐食记录") })
        assertEquals(2, nodes().count { it.isEditable })
        screenshot("journal")
        click("历史")
        val historyDeadline = android.os.SystemClock.elapsedRealtime() + 7000
        while (android.os.SystemClock.elapsedRealtime() < historyDeadline && labels().count { it == "历史" } < 2) Thread.sleep(200)
        assertTrue(labels().count { it == "历史" } >= 2)
        instrumentation.waitForIdleSync()
        Thread.sleep(200) // Allow the rendered frame to catch up with accessibility semantics before capturing it.
        screenshot("history")
        click("今日")
    }
}
