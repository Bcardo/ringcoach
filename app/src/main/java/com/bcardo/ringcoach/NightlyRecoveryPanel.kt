package com.bcardo.ringcoach

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate

@Composable
internal fun NightlyRecoveryPanel(coach: CoachViewModel, date: LocalDate, setup: Boolean = false) {
    val activity = LocalContext.current as? Activity
    Column {
        Text("昨夜恢复数据", style = MaterialTheme.typography.titleMedium)
        val records = coach.nightlySnapshot?.optJSONArray("records")
        val today = (0 until (records?.length() ?: 0)).map { records!!.getJSONObject(it) }
            .filter { it.optString("wake_date") == date.toString() }
        val energy = today.filter { it.optString("metric") == "energy_score" }.distinctBy { it.optDouble("value").toInt() }
        val hrv = today.filter { it.optString("metric") == "sleeping_hrv" }
        Text(if (energy.isEmpty()) "能量得分：尚未读取到今日数据" else "能量得分：" + energy.joinToString(" / ") { it.getDouble("value").toInt().toString() })
        Text(if (hrv.isEmpty()) "睡眠 HRV：尚未读取到今日数据" else "睡眠 HRV：" + hrv.joinToString(" / ") { "${it.getDouble("value")} ms" })
        if (hrv.isEmpty()) {
            val configured = SamsungHealthScreenReader.configured(LocalContext.current)
            Text(if (!configured) "HRV 自动读取未启用；请在设置中开启辅助功能。" else if (!SamsungHealthScreenReader.enabled)
                "HRV 读取服务尚未连接；今日数据尚未确认。" else "今日 HRV 尚未采集，旧记录不能代表今日。",
                style = MaterialTheme.typography.bodySmall)
            val checks = coach.nightlySnapshot?.optJSONArray("sync")
            val status = (0 until (checks?.length() ?: 0)).map { checks!!.getJSONObject(it) }
                .firstOrNull { it.optString("source") == "samsung_health_screen" }?.optString("status").orEmpty()
            if (configured && SamsungHealthScreenReader.enabled && status.startsWith("capture_incomplete:"))
                Text("上次页面采集未完成，今日 HRV 尚未确认；可在设置中重试刷新。", style = MaterialTheme.typography.bodySmall)
        }
        if (coach.nightlySyncing) Text("正在同步昨夜数据…")
        if (setup) {
            Text("连接后定期读取最近七天能量得分；每次提交饮食或训练时同步，并与睡眠记录一起更新教练建议。")
            OutlinedButton(enabled = !coach.nightlySyncing && activity != null, onClick = { activity?.let(coach::authorizeNightly) }) { Text("连接三星健康") }
            OutlinedButton(enabled = !coach.nightlySyncing, onClick = { coach.refreshNightly(date, readScreen = true, forceScreen = true) }) { Text("刷新恢复数据") }
            Text("睡眠 HRV 使用三星健康页面读取；需要一次性开启辅助功能。每次打开 RingCoach 或提交进度时自动读取最近七天，再返回本应用。")
            OutlinedButton(onClick = {
                activity?.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text("启用睡眠 HRV 自动读取") }
            coach.nightlyNotice?.let { Text(it) }
        }
    }
}
