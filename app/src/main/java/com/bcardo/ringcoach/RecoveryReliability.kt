package com.bcardo.ringcoach

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/** Bounded local operational metadata only: no readings, UI text, photos, credentials or sample arrays. */
internal object RecoveryReliability {
    val revision = MutableStateFlow(0L)
    private fun prefs(context: Context) = context.getSharedPreferences("recovery-reliability", Context.MODE_PRIVATE)

    @Synchronized fun observe(context: Context, event: String, detail: JSONObject? = null) {
        runCatching {
            val preferences = prefs(context)
            val now = Instant.now().toString()
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            val row = JSONObject().put("at", now).put("date", LocalDate.now().toString()).put("event", event)
                .put("configured", SamsungHealthScreenReader.configured(context)).put("connected", SamsungHealthScreenReader.enabled)
                .put("boot_count", Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1))
                .put("app_updated_at_ms", info.lastUpdateTime)
            detail?.let { row.put("detail", it) }
            val previous = JSONArray(preferences.getString("events", "[]"))
            val events = JSONArray()
            for (i in maxOf(0, previous.length() - 63) until previous.length()) events.put(previous.get(i))
            events.put(row)
            preferences.edit().putString("events", events.toString()).apply()
            revision.value++
        }
    }

    @Synchronized fun captured(context: Context, date: LocalDate) {
        updateDay(context, date) { it.put("captured_at", Instant.now().toString()) }
    }

    /** Called only after successful response persistence, using the exact retained request context. */
    @Synchronized fun coached(context: Context, date: LocalDate, data: JSONObject) {
        val rows = data.optJSONObject("nightly_recovery")?.optJSONArray("records") ?: return
        val current = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("wake_date") == date.toString() }
        val hrv = current.any { it.optString("metric") == "sleeping_hrv" }
        val energy = current.any { it.optString("metric") == "energy_score" && it.optString("source") == "samsung_health_sdk" }
        updateDay(context, date) { it.put("coached_at", Instant.now().toString()).put("hrv_in_context", hrv).put("sdk_energy_in_context", energy) }
    }

    private fun updateDay(context: Context, date: LocalDate, update: (JSONObject) -> Unit) {
        runCatching {
            val preferences = prefs(context)
            val days = JSONObject(preferences.getString("days", "{}") ?: "{}")
            val row = days.optJSONObject(date.toString()) ?: JSONObject()
            update(row); days.put(date.toString(), row)
            days.keys().asSequence().toList().filter { it < LocalDate.now().minusDays(30).toString() }.forEach { days.remove(it) }
            preferences.edit().putString("days", days.toString()).apply()
            revision.value++
        }
    }
}
