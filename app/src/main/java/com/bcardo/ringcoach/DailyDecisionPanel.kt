package com.bcardo.ringcoach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun DailyDecisionPanel(date: LocalDate, feedback: DailyCoaching?, onTraining: () -> Unit) {
    val decision = dailyDecision(date, feedback)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("今日重点", style = MaterialTheme.typography.titleLarge)
            val card = decision.card
            if (card == null) {
                Text(if (decision.awaitingLatest) "正在等待本次更新的建议。" else "今日还没有教练建议。可在日志中说说训练、饮食或体感。")
            } else {
                val updated = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(card.createdAt)
                Text("教练建议 · 更新于 $updated", style = MaterialTheme.typography.labelMedium)
                if (decision.awaitingLatest) Text("以下是上次建议，尚未包含这次更新。", style = MaterialTheme.typography.bodySmall)
                Text("依据与提醒", style = MaterialTheme.typography.titleMedium)
                card.advice.notes.forEach { Text("• $it") }
                Text("训练建议", style = MaterialTheme.typography.titleMedium)
                card.advice.training.forEach { Text("• $it") }
            }
            OutlinedButton(onClick = onTraining, modifier = Modifier.fillMaxWidth()) { Text("查看训练计划与进度") }
        }
    }
}

/** Reads the existing local Ring archive only. No health permission requests, model calls or jobs. */
@Composable
internal fun DailyNightPanel(date: LocalDate, foreground: Boolean) {
    val context = LocalContext.current.applicationContext
    val zone = ZoneId.systemDefault()
    var records by remember(date, zone) { mutableStateOf<List<NightRecord>?>(null) }
    var failed by remember(date, zone) { mutableStateOf(false) }
    LaunchedEffect(date, foreground, zone) {
        if (!foreground) return@LaunchedEffect
        while (true) {
            try {
                records = withContext(Dispatchers.IO) { RingBluetoothStore(context).use { store -> nightRecords(store.snapshot(date, zone), date, zone, Instant.now()) } }
                failed = false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            delay(30_000)
        }
    }
    DailyNightContent(date, records, failed, zone)
}

@Composable
internal fun DailyNightContent(date: LocalDate, records: List<NightRecord>?, failed: Boolean, zone: ZoneId) {
    var expanded by rememberSaveable(date.toString()) { mutableStateOf(false) }
    val time = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone)
    fun number(value: Double) = String.format(Locale.getDefault(), "%.1f", value)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("昨夜记录", style = MaterialTheme.typography.titleLarge)
            Text("醒来日期 · $date", style = MaterialTheme.typography.labelMedium)
            when {
                failed -> Text("暂时无法刷新夜间记录；请稍后重试。")
                records == null -> Text("正在读取夜间记录…")
                records.isEmpty() -> Text("尚无这个醒来日期的戒指记录，恢复情况暂不能据此判断。")
                else -> {
                    if (records.size == 1) {
                        val record = records.single()
                        Text("记录区间 · ${time.format(record.start)} — ${time.format(record.end)}")
                        Text(record.heartRateMean?.let { "记录心率均值 · ${number(it)} bpm" } ?: "心率记录不足")
                    } else Text("收到 ${records.size} 段记录，分段查看以免重复计算。")
                    Text("记录区间不等于实际睡眠时长，已收到记录也不代表整夜完整。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起夜间详情" else "查看夜间详情") }
                    if (expanded) {
                        for ((index, record) in records.withIndex()) {
                            HorizontalDivider()
                            Text("记录 ${index + 1}", style = MaterialTheme.typography.titleMedium)
                            Text("${time.format(record.start)} — ${time.format(record.end)}")
                            Text(record.heartRateMean?.let { "心率均值 ${number(it)} bpm · ${record.heartRateCount} 条有效记录" } ?: "暂无可用心率均值")
                            Text(if (record.hrvWindows > 0) "HRV：收到窗口记录，仍待同夜校验。" else "HRV：暂无可用窗口记录。")
                        }
                        Text("来源：戒指蓝牙记录。心率均值不是静息心率；HRV窗口不替代厂商每日HRV。温度仍待校准，血氧尚不可用。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
