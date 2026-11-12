package com.bcardo.ringcoach

import org.json.JSONObject
import java.time.LocalDate

internal data class RecoveryPoint(val date: LocalDate, val metric: String, val status: String,
    val value: Double?, val source: String?, val definition: String, val verified: String?,
    val observations: List<JSONObject>) {
    val displayValue: String get() = value?.let { String.format(java.util.Locale.ROOT, "%.1f", it).removeSuffix(".0") } ?: "—"
    val statusLabel: String get() = when (status) {
        "available" -> "已归档"
        "confirmed_missing" -> "已检查，来源无数据"
        "ambiguous" -> "来源记录冲突，未选用数值"
        "unsupported_source" -> "来源不支持此指标"
        else -> "尚未采集"
    }
    fun connectsTo(next: RecoveryPoint): Boolean = value != null && next.value != null &&
        date.plusDays(1) == next.date && source == next.source && definition == next.definition
}

internal data class RecoveryTrend(val days: List<LocalDate>, val series: Map<String, List<RecoveryPoint>>,
    val syncWarnings: List<String>) {
    companion object {
        fun decode(json: JSONObject): RecoveryTrend {
            val days = json.getJSONArray("days")
            val dates = (0 until days.length()).map { LocalDate.parse(days.getJSONObject(it).getString("wake_date")) }
            val series = DAILY_RECOVERY_METRICS.associateWith { metric ->
                dates.mapIndexed { i, date ->
                    val item = days.getJSONObject(i).getJSONObject(metric)
                    val observations = item.getJSONArray("observations")
                    RecoveryPoint(date, metric, item.getString("status"),
                        if (item.getString("status") == "available" && !item.isNull("value")) item.getDouble("value") else null,
                        item.optString("source").takeIf { it.isNotBlank() && it != "null" }, item.getString("metric_definition"),
                        item.optString("last_verified_at").takeIf { it.isNotBlank() && it != "null" },
                        (0 until observations.length()).map { observations.getJSONObject(it) })
                }
            }
            val sync = json.getJSONArray("sync")
            val warnings = (0 until sync.length()).mapNotNull { i ->
                val item = sync.getJSONObject(i)
                if (item.getString("status") in setOf("available", "empty")) null
                else "${if (item.getString("source") == "samsung_health_sdk") "Energy Score SDK" else "HRV 屏幕读取"}：最近读取未完成，保留已归档值"
            }
            return RecoveryTrend(dates, series, warnings)
        }
    }
}
