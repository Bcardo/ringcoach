package com.bcardo.ringcoach

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import java.time.LocalDate

@Composable
internal fun NightlyRecoveryPanel(coach: CoachViewModel, date: LocalDate, setup: Boolean = false) {
    val activity = LocalActivity.current
    Column {
        RingBluetoothPanel(date, setup)
        Text("三星恢复数据", style = MaterialTheme.typography.titleMedium)
        val records = coach.nightlySnapshot?.optJSONArray("records")
        val today = (0 until (records?.length() ?: 0)).map { records!!.getJSONObject(it) }
            .filter { it.optString("wake_date") == date.toString() }
        val energy = today.filter { it.optString("metric") == "energy_score" }.distinctBy { it.optDouble("value").toInt() }
        val hrv = today.filter { it.optString("metric") == "sleeping_hrv" }
        Text(if (energy.isEmpty()) "能量得分：尚无此日期的数据" else "能量得分：" + energy.joinToString(" / ") { it.getDouble("value").toInt().toString() })
        if (hrv.isNotEmpty()) Text("已保存的三星每日睡眠 HRV：" + hrv.joinToString(" / ") { "${it.getDouble("value")} ms" })
        Text("教练使用戒指蓝牙数据、已保存的恢复记录和可用的健康接口；不会打开三星健康或等待页面读取。", style = MaterialTheme.typography.bodySmall)
        if (coach.nightlySyncing) Text("正在刷新可用的恢复数据…")
        if (setup) {
            Text("可选连接三星能量得分接口。戒指蓝牙 HRV 与三星每日睡眠 HRV 分开使用；缺少每日汇总不会阻止教练回复。")
            OutlinedButton(enabled = !coach.nightlySyncing && activity != null, onClick = { activity?.let(coach::authorizeNightly) }) { Text("连接三星能量得分") }
            OutlinedButton(enabled = !coach.nightlySyncing, onClick = { coach.refreshNightly(date) }) { Text("刷新可用恢复数据") }
            coach.nightlyNotice?.let { Text(it) }
        }
    }
}
