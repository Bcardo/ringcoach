package com.bcardo.ringcoach

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalDate

/** Explicitly enabled fallback for the daily HRV summary absent from Samsung's SDK. */
class SamsungHealthScreenReader : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: CompletableDeferred<Boolean>? = null
    private var dayIndex = 0
    private var captureToday = LocalDate.now()
    private var lastAction = 0L
    private var stableFingerprint: String? = null
    private var stableSince = 0L
    private var captured = 0
    private var step = "starting"
    private var capturedToday = false
    private val tick = object : Runnable {
        override fun run() {
            if (pending == null) return
            inspect()
            if (pending != null) handler.postDelayed(this, 350)
        }
    }

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        instance = this
    }
    override fun onDestroy() { if (instance === this) instance = null; finish(false); super.onDestroy() }
    override fun onInterrupt() { finish(false) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Poll only during an explicit capture. */ }

    private fun begin(): CompletableDeferred<Boolean> {
        pending?.let { return it }
        val completion = CompletableDeferred<Boolean>()
        pending = completion
        dayIndex = 0; captured = 0; capturedToday = false; captureToday = LocalDate.now(); stableFingerprint = null; step = "starting"
        lastAction = System.currentTimeMillis()
        try {
            startActivity(Intent("com.samsung.android.app.shealth.action.VITALITY_TODAY_VIEW_LAUNCH")
                .setPackage(SAMSUNG_HEALTH_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            handler.postDelayed(tick, 700)
            handler.postDelayed({ if (pending === completion) finish(false) }, 25_000)
        } catch (_: Exception) { finish(false) }
        return completion
    }

    private fun finish(success: Boolean) {
        val completion = pending ?: return
        pending = null
        handler.removeCallbacks(tick)
        NightlyRecoveryStore(this).use { it.status("samsung_health_screen", if (success) (if (captured > 0) "available" else "empty") else "capture_incomplete:$step") }
        if (success && capturedToday) getSharedPreferences("nightly-screen", MODE_PRIVATE).edit()
            .putString("last_date", captureToday.toString()).putLong("completed_at", System.currentTimeMillis()).apply()
        completion.complete(success)
        // Resume the user's RingCoach workflow; never unlock or open another app.
        try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        catch (_: Exception) { }
    }

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) { add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        visit(root)
    }
    private fun id(name: String) = "$SAMSUNG_HEALTH_PACKAGE:id/$name"
    private fun click(node: AccessibilityNodeInfo?): Boolean {
        var target = node
        repeat(8) {
            if (target == null) return false
            if (target!!.isClickable && target!!.isEnabled && target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastAction = System.currentTimeMillis(); stableFingerprint = null; return true
            }
            target = target!!.parent
        }
        return false
    }

    private fun inspect() {
        if (LocalDate.now() != captureToday) { finish(false); return }
        val root = rootInActiveWindow ?: run { step = "awaiting_window"; return }
        if (root.packageName?.toString() != SAMSUNG_HEALTH_PACKAGE) { step = "awaiting_samsung_health"; return }
        if (System.currentTimeMillis() - lastAction < 650) return
        val all = nodes(root)
        val period = (all.firstOrNull { it.viewIdResourceName == id("switcher_period") }
            ?: all.firstOrNull { it.viewIdResourceName == id("text_button") })
            ?.let { nodes(it).mapNotNull { n -> n.text?.toString() }.joinToString("") }
        if (period == null) {
            step = "opening_energy_screen"
            val drawer = all.firstOrNull { it.viewIdResourceName == id("drawer_icon") && it.contentDescription?.contains("收起") == true }
            if (drawer != null) { click(drawer); return }
            click(all.firstOrNull { it.viewIdResourceName == id("vitality_tile_score_text") })
            return
        }
        val expected = captureToday.minusDays(dayIndex.toLong())
        if (!SamsungSummaryParser.matchesDate(period, expected, captureToday)) {
            step = "date_not_verified"
            // A retained historic screen must first reach Today. Never assign its values to today.
            if (dayIndex == 0) click(all.firstOrNull { it.viewIdResourceName == id("date_right_btn") && it.isEnabled && it.isClickable })
            return
        }
        val rootBounds = Rect().also(root::getBoundsInScreen)
        val noData = all.any {
            if (it.viewIdResourceName != id("no_data_text")) false else {
                val bounds = Rect().also(it::getBoundsInScreen)
                bounds.width() > 0 && bounds.centerX() in rootBounds.left..rootBounds.right
            }
        }
        if (noData) {
            val fingerprint = "$period|no_data"
            if (stableFingerprint != fingerprint) { stableFingerprint = fingerprint; stableSince = System.currentTimeMillis(); return }
            if (System.currentTimeMillis() - stableSince < 700) return
            NightlyRecoveryStore(this).use { it.replaceScreenDay(expected, emptyList()) }
            nextDay(all)
            return
        }
        val pages = all.filter { it.viewIdResourceName == id("contributor_trend_layout") }.filter {
            val bounds = Rect().also(it::getBoundsInScreen)
            bounds.width() > rootBounds.width() / 2
        }
        if (pages.size != 1) { step = "ambiguous_page"; return }
        step = "reading_daily_summary"
        val page = nodes(pages.single())
        val scoreLabel = page.firstOrNull { it.viewIdResourceName == id("vitality_score_main_view") }?.contentDescription?.toString()
        val hrvLabel = page.firstOrNull { it.viewIdResourceName == id("last_shrv") }?.text?.toString()
        val fingerprint = "$period|$scoreLabel|$hrvLabel"
        if (fingerprint != stableFingerprint) { stableFingerprint = fingerprint; stableSince = System.currentTimeMillis(); return }
        if (System.currentTimeMillis() - stableSince < 700) return
        val readings = SamsungSummaryParser.dailyReadings(scoreLabel, hrvLabel)
        if (readings == null) {
            step = "unreadable_daily_summary"
            finish(false)
            return
        }
        val (energy, hrv) = readings
        val details = JSONObject().put("source_app", SAMSUNG_HEALTH_PACKAGE)
            .put("measurement", "vendor_daily_summary").put("ring_attribution", "not_verified")
            .put("date_label", period)
        NightlyRecoveryStore(this).use { store ->
            val records = buildList {
                add(NightlyMetric(expected, "energy_score", energy, "score", "samsung_health_screen", "daily",
                    JSONObject(details.toString()).put("precision", "vendor_screen_integer")))
                add(NightlyMetric(expected, "sleeping_hrv", hrv, "ms", "samsung_health_screen", "daily",
                    JSONObject(details.toString()).put("precision", "vendor_daily_hrv_display")))
            }
            store.replaceScreenDay(expected, records)
        }
        captured++
        if (dayIndex == 0) capturedToday = true
        nextDay(all)
    }

    private fun nextDay(all: List<AccessibilityNodeInfo>) {
        if (dayIndex >= 6) { finish(true); return }
        val previous = all.firstOrNull { it.viewIdResourceName == id("date_left_btn") }
        if (click(previous)) dayIndex++ else finish(false)
    }

    companion object {
        private var instance: SamsungHealthScreenReader? = null
        val enabled: Boolean get() = instance != null
        fun configured(context: android.content.Context): Boolean {
            val services = android.provider.Settings.Secure.getString(context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return services.split(':').any { android.content.ComponentName.unflattenFromString(it)?.let { component ->
                component.packageName == context.packageName && component.className == SamsungHealthScreenReader::class.java.name
            } == true }
        }
        suspend fun capture(): Boolean {
            val reader = instance ?: return false
            val preferences = reader.getSharedPreferences("nightly-screen", MODE_PRIVATE)
            val age = System.currentTimeMillis() - preferences.getLong("completed_at", 0)
            if (preferences.getString("last_date", null) == LocalDate.now().toString() && age in 0..3_600_000) return true
            val deferred = reader.begin()
            return try { withTimeoutOrNull(27_000) { deferred.await() } ?: false }
            finally { if (reader.pending === deferred) reader.finish(false) }
        }
    }
}

internal object SamsungSummaryParser {
    fun dailyReadings(scoreLabel: String?, hrvLabel: String?): Pair<Double, Double>? {
        val energy = energy(scoreLabel) ?: return null
        val hrv = hrv(hrvLabel) ?: return null
        return energy to hrv
    }
    fun energy(label: String?): Double? = label?.let {
        Regex("(?:能量得分|Energy\\s*score)\\s*(\\d{1,3})(?!\\d)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toDoubleOrNull()
    }?.takeIf { it in 0.0..100.0 }
    fun hrv(label: String?): Double? = label?.let {
        Regex("(?:平均[：:]?|Average[：:]?)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:毫秒|ms)(?![A-Za-z])", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toDoubleOrNull()
    }?.takeIf { it > 0 && it <= 2000 }
    fun matchesDate(label: String, expected: LocalDate, today: LocalDate): Boolean {
        val text = label.trim().replace(" ", "")
        if (text.equals("今天", true) || text.equals("Today", true)) return expected == today
        if (text == "昨天" || text.equals("Yesterday", true)) return expected == today.minusDays(1)
        val chinese = Regex("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日(?:周[一二三四五六日天])?").matchEntire(text)
        if (chinese != null) return chinese.groupValues[2].toInt() == expected.monthValue &&
            chinese.groupValues[3].toInt() == expected.dayOfMonth &&
            (chinese.groupValues[1].isEmpty() || chinese.groupValues[1].toInt() == expected.year)
        return false
    }
}
