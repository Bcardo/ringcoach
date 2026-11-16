package com.bcardo.ringcoach

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

@Composable
internal fun RingBluetoothPanel(date: LocalDate, setup: Boolean) {
    val context = LocalContext.current.applicationContext
    var snapshot by remember { mutableStateOf(JSONObject()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(date, refresh) {
        while (true) {
            snapshot = withContext(Dispatchers.IO) { RingBluetoothStore(context).use { store ->
                store.snapshot(date).put("morning_advice", store.morningState(date))
            } }
            delay(5000)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (RingBluetoothService.permitted(context) && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            RingBluetoothStore(context).use { store -> store.setEnabled(true) }
            RingBluetoothService.resume(context)
            notice = "已开启持续采集和自动晨间教练。请保持戒指配对、手机蓝牙开启并放在附近。"
        } else notice = "需要附近设备和通知权限才能开启可见的睡眠采集。"
        refresh++
    }
    val collection = snapshot.optJSONObject("collection") ?: JSONObject()
    val enabled = collection.optBoolean("enabled")
    val fresh = runCatching { Instant.parse(collection.getString("heartbeat_at")) >= Instant.now().minusSeconds(180) }.getOrDefault(false)
    val state = when {
        !enabled -> "未开启"
        !fresh -> "等待确认连接；请打开应用检查"
        collection.optBoolean("subscribed") && collection.optBoolean("connected") -> "已连接，正在监听"
        else -> "正在等待戒指重连"
    }
    val sessions = snapshot.optJSONArray("sessions")
    val today = (0 until (sessions?.length() ?: 0)).map { sessions!!.getJSONObject(it) }.filter { it.optString("wake_date") == date.toString() }
    val hrvCount = today.sumOf { it.optJSONObject("metrics")?.optJSONObject("hrv_rmssd")?.optInt("sample_count") ?: 0 }
    Column {
        Text("戒指蓝牙睡眠采集 · $state", style = MaterialTheme.typography.titleSmall)
        Text(if (today.isEmpty()) "尚无此醒来日期的睡眠传输；开启采集不代表已获得昨夜数据。"
             else "已保存 ${today.size} 段睡眠记录、$hrvCount 个 HRV 窗口（实验解码）。", style = MaterialTheme.typography.bodySmall)
        val advice = snapshot.optJSONObject("morning_advice")?.optString("status")
        val adviceText = when (advice) {
            "complete" -> "晨间训练建议已保存。"
            "coaching" -> "正在生成晨间训练建议。"
            "pending", "retry" -> "晨间建议已排队，等待系统调度及网络。"
            "credential_missing" -> "已保存睡眠数据；请配置模型密钥以生成晨间建议。"
            "waiting_progress" -> "等待饮食/训练识别完成后生成晨间建议。"
            "blocked" -> "晨间建议未完成；采集数据已保存，可重试。"
            else -> null
        }
        adviceText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (setup) {
            Text("开启后使用常驻通知持续接收已配对 Galaxy Ring 的数据，并在新睡眠传输后自动生成一次晨间建议。只将带来源、日期和覆盖说明的统计汇总发送给已配置的模型；原始采样保存在本机，保留 35 天。无需夜间操作三星健康页面。", style = MaterialTheme.typography.bodySmall)
            Text("目前需要保留三星官方伴侣并在醒来后正常同步。蓝牙窗口 HRV 与三星每日睡眠 HRV 分开使用；原始 R-R 和血氧尚不用于训练决策。系统终止、手机重启或蓝牙断开可能造成缺口。", style = MaterialTheme.typography.bodySmall)
            if (!enabled) OutlinedButton(onClick = {
                permission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
            }) { Text("开启戒指睡眠采集") }
            else OutlinedButton(onClick = {
                RingBluetoothStore(context).use { it.setEnabled(false) }
                context.stopService(Intent(context, RingBluetoothService::class.java))
                refresh++
            }) { Text("停止戒指睡眠采集") }
            if (enabled) OutlinedButton(onClick = { RingBluetoothService.resume(context); refresh++ }) { Text("重新检查戒指连接") }
            if (advice in setOf("blocked", "credential_missing", "retry", "waiting_progress")) OutlinedButton(onClick = {
                RingBluetoothStore(context).use { it.retryMorning(date) }
                RingMorningJob.schedule(context); refresh++
            }) { Text("重试晨间建议") }
            collection.optString("last_packet_at").takeIf { it.isNotBlank() }?.let { Text("最近收到数据：$it", style = MaterialTheme.typography.bodySmall) }
            if (collection.optInt("decode_errors") > 0) Text("有 ${collection.optInt("decode_errors")} 次传输未能完整解码；不将缺失数据当作零。", style = MaterialTheme.typography.bodySmall)
            notice?.let { Text(it) }
        }
    }
}
