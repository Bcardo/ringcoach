package com.bcardo.ringcoach

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import org.json.JSONObject

@Composable
internal fun CoachingManifestPanel(manifest: JSONObject?) {
    if (manifest == null) return
    var expanded by rememberSaveable(manifest.toString()) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起本次依据与覆盖" else "查看本次依据与覆盖") }
    if (!expanded) return
    Text("分析日期：${manifest.optString("request_date")}", style = MaterialTheme.typography.bodySmall)
    Text("进度历史：${manifest.optString("progress_history_start")} 至 ${manifest.optString("request_date")}")
    if (!manifest.isNull("recovery_history_start")) Text("恢复比较：${manifest.optString("recovery_history_start")} 至 ${manifest.optString("recovery_history_end")}；缺失日期不补零。")
    manifest.optJSONArray("recovery_series_coverage")?.let { series ->
        for (index in 0 until series.length()) {
            val item = series.getJSONObject(index)
            val label = if (item.optString("metric") == "energy_score") "能量得分" else "睡眠 HRV"
            val source = if (item.optString("source") == "samsung_health_sdk") "SDK" else "页面读取"
            Text("$label · $source：${item.optInt("valid_night_count")} 个有效日期；其余 ${item.optInt("missing_or_other_series_nights")} 日缺失或属于其他来源。", style = MaterialTheme.typography.bodySmall)
        }
    }
    manifest.optJSONObject("current_night")?.takeIf { it.has("session_count") }?.let {
        Text("本次睡眠证据：${it.optInt("session_count")} 段记录、${it.optInt("stage_interval_count")} 段阶段、${it.optInt("heart_rate_sample_count")} 个心率采样点。")
    }
    manifest.optJSONObject("freshness")?.let { freshness ->
        val missing = freshness.optJSONArray("missing_target_date_vendor_metrics")
        val labels = (0 until (missing?.length() ?: 0)).map { index ->
            if (missing!!.getString(index) == "sleeping_hrv") "今日睡眠 HRV" else "今日能量得分"
        }
        if (labels.isNotEmpty()) Text("本次缺少：${labels.joinToString("、")}。")
        if (!freshness.isNull("latest_sleeping_hrv_date")) Text("最近已采集 HRV 日期：${freshness.optString("latest_sleeping_hrv_date")}")
        if (freshness.has("screen_reader_configured") && !freshness.isNull("screen_reader_configured") && !freshness.optBoolean("screen_reader_configured"))
            Text("HRV 自动读取未启用，可在设置中开启辅助功能。")
    }
    val omissions = manifest.optJSONArray("omissions")
    for (index in 0 until (omissions?.length() ?: 0)) {
        val omission = omissions!!.getJSONObject(index)
        val label = when (omission.optString("section")) {
            "historical_journal_text" -> "较早日志原文（使用结构化日记录）"
            "historical_food_item_details" -> "较早逐项食品说明（保留每日合计与来源）"
            "duplicate_supplement_copy" -> "重复补剂副本（补剂记录已单独提供）"
            "previous_advice" -> "此前建议（请求大小限制）"
            "current_day_raw_journal_copy" -> "当日日志原文副本（保留本次提交与已转换事实）"
            else -> omission.optString("section")
        }
        Text("未重复提供：$label · ${omission.optInt("count")} 项", style = MaterialTheme.typography.bodySmall)
    }
    if (manifest.optString("budget_status") == "required_evidence_too_large_not_sent") Text("本次必要证据超出大小限制，尚未发送；未截断昨夜数据。")
    else Text("本次请求 ${manifest.optInt("serialized_request_bytes")} 字节；历史保留睡眠区间和心率统计，不保留逐点心率。", style = MaterialTheme.typography.bodySmall)
}
