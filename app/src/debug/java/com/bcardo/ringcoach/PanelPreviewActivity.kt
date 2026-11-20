package com.bcardo.ringcoach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Disposable -PpanelPreview=true package only. No database, credentials, jobs or permissions. */
class PanelPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val day = LocalDate.parse("2026-06-12")
        val mode = intent.getStringExtra("case") ?: "ready"
        val width = intent.getIntExtra("width", 360).coerceIn(320, 840)
        val scale = intent.getFloatExtra("font_scale", 1f).coerceIn(1f, 2f)
        val density = resources.displayMetrics.widthPixels.toFloat() / width
        val card = SavedCoachCard("synthetic", day, Instant.parse("2026-06-12T08:00:00Z"), "synthetic", "synthetic", "{}",
            CoachAdvice(listOf("热身后若无明显不适，继续保存的训练安排；如头晕，停止训练。"), listOf("按已记录摄入安排接下来的饮食。"),
                listOf("今天按原计划热身，先观察体感。", "昨夜有夜间记录，覆盖仍不完整。", "最近一次已完成推举 20 kg × 8。")))
        val pending = if (mode == "pending") PendingCoaching("synthetic", day) else null
        val feedback = if (mode == "missing") null else DailyCoaching(day, card, pending, if (pending == null) "idle" else "blocked", null)
        val records = if (mode == "missing") emptyList() else listOf(NightRecord(Instant.parse("2026-06-11T23:00:00Z"), Instant.parse("2026-06-12T06:00:00Z"), 62.0, 120, 10))
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    var training by remember { mutableStateOf(false) }
                    Surface {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("界面预览 · 全部为合成数据", style = MaterialTheme.typography.labelSmall)
                            Text(if (training) "训练计划与最新进度" else "今日", style = MaterialTheme.typography.headlineLarge)
                            Text(day.toString())
                            if (training) { Text("合成训练计划与进度；正式界面仍使用原来的完整训练表。"); TextButton(onClick = { training = false }) { Text("返回今日") } }
                            else {
                                DailyDecisionPanel(day, feedback) { training = true }
                                if (pending != null) Text("这次更新未完成，上次建议尚未包含这条新记录。")
                                DailyNightContent(day, records, false, ZoneId.of("UTC"))
                            }
                        }
                    }
                }
            }
        }
    }
}
