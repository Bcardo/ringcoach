package com.bcardo.ringcoach

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
internal fun RecoveryHistoryPanel(history: CoachHistoryViewModel, today: LocalDate) {
    Text("恢复趋势", style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (days in listOf(7, 30)) FilterChip(selected = history.recoveryRange == days,
            onClick = { history.loadRecovery(today, days) }, label = { Text("近 $days 天") })
    }
    if (history.recoveryLoading) Text("正在读取恢复历史…")
    if (history.recoveryFailed) {
        Text("恢复历史读取失败，未显示旧范围的数据。")
        TextButton(onClick = { history.loadRecovery(today) }) { Text("重试读取恢复历史") }
    }
    val trend = history.recovery ?: return
    Text("${trend.days.first()} — ${trend.days.last()} · 按醒来日期", style = MaterialTheme.typography.bodySmall)
    trend.syncWarnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 640.dp) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DAILY_RECOVERY_METRICS.forEach { metric ->
                RecoveryChart(trend.series.getValue(metric), history.recoveryDate, history::selectRecoveryDate, Modifier.weight(1f))
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DAILY_RECOVERY_METRICS.forEach { metric ->
                RecoveryChart(trend.series.getValue(metric), history.recoveryDate, history::selectRecoveryDate, Modifier.fillMaxWidth())
            }
        }
    }
    Text("空缺不是 0；不同来源之间不连线。仅展示已采集历史，打开应用可补采最近 7 天。", style = MaterialTheme.typography.bodySmall)
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { menu = true }) { Text("查看日期：${history.recoveryDate ?: today}") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            trend.days.asReversed().forEach { date ->
                DropdownMenuItem(text = { Text("$date${if (date == today) " · 今天" else ""}") },
                    onClick = { history.selectRecoveryDate(date); menu = false })
            }
        }
    }
    val selected = history.recoveryDate ?: today
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$selected · ${if (selected == today) "今天" else "历史记录"}", style = MaterialTheme.typography.titleMedium)
            DAILY_RECOVERY_METRICS.forEach { metric ->
                val point = trend.series.getValue(metric).firstOrNull { it.date == selected } ?: return@forEach
                Text("${metricTitle(metric)}：${point.displayValue} ${metricUnit(metric)} · ${point.statusLabel}")
                point.verified?.let { verified ->
                    Text("最后核对：${coachCardTimestamp(Instant.parse(verified))}", style = MaterialTheme.typography.bodySmall)
                    if (selected == today && Instant.parse(verified).atZone(ZoneId.systemDefault()).toLocalDate() < today)
                        Text("今天的读数尚未重新核对，当前显示归档值。", style = MaterialTheme.typography.bodySmall)
                }
                point.observations.forEach { observation ->
                    val details = observation.optJSONObject("details")
                    val source = sourceTitle(observation.getString("source"))
                    val precision = when (details?.optString("precision")) {
                        "vendor_api_float" -> "API 小数精度"
                        "vendor_screen_integer" -> "屏幕整数精度"
                        "vendor_daily_hrv_display" -> "Samsung 每日 HRV 显示值"
                        else -> "精度未记录"
                    }
                    val canonical = observation.getString("source") == point.source && observation.getDouble("value") == point.value
                    val value = java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols(java.util.Locale.ROOT)).format(observation.getDouble("value"))
                    Text("$source · $value ${metricUnit(metric)} · $precision${if (canonical) " · 采用值" else ""}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("归档版本 ${observation.getInt("revision")}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("睡眠 HRV 是 Samsung 每日汇总，不等同于原始 RMSSD 窗口。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun metricTitle(metric: String) = if (metric == "energy_score") "能量得分" else "睡眠 HRV"
private fun metricUnit(metric: String) = if (metric == "energy_score") "分" else "ms"
private fun sourceTitle(source: String) = if (source == "samsung_health_sdk") "Samsung Health SDK" else "Samsung Health 屏幕"

@Composable
private fun RecoveryChart(points: List<RecoveryPoint>, selected: LocalDate?, onSelect: (LocalDate) -> Unit, modifier: Modifier) {
    val metric = points.first().metric
    val valid = points.filter { it.value != null }
    val latest = valid.lastOrNull()
    val values = valid.map { it.value!! }
    val low = if (metric == "energy_score" || values.isEmpty()) 0.0 else (values.min() - 5).coerceAtLeast(0.0)
    val high = if (metric == "energy_score") 100.0 else if (values.isEmpty()) 100.0 else values.max() + 5
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val marker = MaterialTheme.colorScheme.secondary
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(metricTitle(metric), style = MaterialTheme.typography.titleMedium)
            Text(if (latest == null) "暂无已采集读数" else "最近 ${latest.displayValue} ${metricUnit(metric)} · ${latest.date}")
            Text("有效 ${valid.size} / ${points.size} 天", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().height(144.dp)) {
                Column(Modifier.fillMaxHeight().width(36.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(high.roundToInt().toString(), style = MaterialTheme.typography.labelSmall)
                    Text(low.roundToInt().toString(), style = MaterialTheme.typography.labelSmall)
                }
                Canvas(Modifier.weight(1f).fillMaxHeight().semantics {
                    contentDescription = "${metricTitle(metric)}趋势图，${valid.size}天有数据；可点选日期，或使用下方日期选择器"
                }.pointerInput(points) {
                    detectTapGestures { offset ->
                        val index = ((offset.x / size.width).coerceIn(0f, 1f) * (points.size - 1)).roundToInt()
                        onSelect(points[index].date)
                    }
                }) {
                    fun x(index: Int) = index.toFloat() / (points.size - 1).coerceAtLeast(1) * size.width
                    fun y(value: Double) = (size.height - 8.dp.toPx()) - ((value - low) / (high - low)).toFloat() * (size.height - 16.dp.toPx())
                    for (fraction in listOf(0f, 0.5f, 1f)) drawLine(grid, Offset(0f, 8.dp.toPx() + fraction * (size.height - 16.dp.toPx())), Offset(size.width, 8.dp.toPx() + fraction * (size.height - 16.dp.toPx())))
                    points.indexOfFirst { it.date == selected }.takeIf { it >= 0 }?.let { index ->
                        drawLine(marker, Offset(x(index), 0f), Offset(x(index), size.height), 1.dp.toPx())
                    }
                    points.forEachIndexed { i, point ->
                        point.value?.let { value ->
                            if (i > 0 && points[i - 1].connectsTo(point)) drawLine(color, Offset(x(i - 1), y(points[i - 1].value!!)), Offset(x(i), y(value)), 2.dp.toPx())
                            drawCircle(color, 3.dp.toPx(), Offset(x(i), y(value)))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(points.first().date.toString().substring(5), style = MaterialTheme.typography.labelSmall)
                Text(points.last().date.toString().substring(5), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
