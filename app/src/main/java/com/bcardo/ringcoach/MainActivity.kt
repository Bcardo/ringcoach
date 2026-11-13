package com.bcardo.ringcoach

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.metadata.Device
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : ComponentActivity() {
    private var today by mutableStateOf(LocalDate.now())
    private var lastForegroundSync = 0L
    private var foregroundProbe: HealthConnectProbe? = null

    override fun onResume() {
        super.onResume()
        val coach = ViewModelProvider(this)[CoachViewModel::class.java]
        coach.onForegroundChanged(true)
        RecoveryReliability.observe(this, "app_resumed")
        coach.reload()
        coach.captureProgressHealth(foregroundProbe)
        ProgressJob.schedule(this)
        ProgressRuntime.changed()
        val newDate = LocalDate.now()
        val changedDate = newDate != today
        today = newDate
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (changedDate || elapsed - lastForegroundSync > 300_000) {
            lastForegroundSync = elapsed
            ViewModelProvider(this)[CoachViewModel::class.java].refreshNightly(readScreen = true)
        }
    }

    override fun onPause() {
        ViewModelProvider(this)[CoachViewModel::class.java].onForegroundChanged(false)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val probe = if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectProbe(HealthConnectClient.getOrCreate(this))
        } else {
            null
        }
        foregroundProbe = probe
        val samsungHealthVersion = try {
            packageManager.getPackageInfo(SAMSUNG_HEALTH_PACKAGE, PackageManager.PackageInfoFlags.of(0)).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val export = ViewModelProvider(this)[ExportViewModel::class.java]
        val journal = ViewModelProvider(this)[JournalViewModel::class.java]
        val settings = ViewModelProvider(this)[SettingsViewModel::class.java]
        val coach = ViewModelProvider(this)[CoachViewModel::class.java]
        NightlySyncJob.schedule(this)
        lastForegroundSync = android.os.SystemClock.elapsedRealtime()
        coach.refreshNightly(readScreen = true)
        val workouts = ViewModelProvider(this)[WorkoutViewModel::class.java]
        val coachHistory = ViewModelProvider(this)[CoachHistoryViewModel::class.java]
        val nutrition = ViewModelProvider(this)[NutritionViewModel::class.java]
        val photos = ViewModelProvider(this)[PhotoMealViewModel::class.java]
        setContent { JournalApp(journal, settings, coach, workouts, coachHistory, nutrition, photos, today, probe) { ProbeScreen(probe, samsungHealthVersion, export) } }
    }
}

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RingCoachScreen { Text(stringResource(R.string.permission_rationale)) } }
    }
}

@Composable
private fun RingCoachScreen(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                content()
            }
        }
    }
}

@Composable
private fun ProbeScreen(probe: HealthConnectProbe?, samsungHealthVersion: String?, export: ExportViewModel) {
    Text(stringResource(R.string.probe_window, PROBE_DAYS))
    Text(stringResource(
        R.string.probe_versions,
        Build.VERSION.RELEASE,
        samsungHealthVersion ?: stringResource(R.string.version_unknown),
    ))
    if (probe == null) {
        Text(stringResource(R.string.health_connect_unavailable))
        return
    }

    val scope = rememberCoroutineScope()
    val resolverContext = LocalContext.current.applicationContext
    val resolver = resolverContext.contentResolver
    var results by remember { mutableStateOf(emptyList<MetricResult>()) }
    var selected by remember { mutableStateOf(MetricKind.entries.toSet()) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) {
        requesting = false
        ProgressJob.schedule(resolverContext)
    }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        export.onDocumentResult(resolver, result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK })
    }
    val controlsEnabled = !busy && !requesting && !export.busy && !export.choosingFile

    fun runProbeAction(action: suspend () -> Unit) {
        if (!controlsEnabled) return
        busy = true
        notice = null
        export.clearNotice()
        scope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                requesting = false
                notice = R.string.probe_error
            } finally {
                busy = false
            }
        }
    }

    OutlinedButton(enabled = controlsEnabled, onClick = {
        runProbeAction {
            val missing = probe.missingPermissions()
            if (missing.isEmpty()) {
                notice = R.string.permissions_already_granted
            } else {
                results = emptyList()
                requesting = true
                permissionLauncher.launch(missing)
            }
        }
    }) { Text(stringResource(R.string.request_permissions)) }
    if (probe.supportsBackgroundRead()) {
        OutlinedButton(enabled = controlsEnabled, onClick = {
            runProbeAction {
                if (probe.hasBackgroundReadAccess()) notice = R.string.permissions_already_granted
                else {
                    requesting = true
                    permissionLauncher.launch(setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND))
                }
            }
        }) { Text("允许后台读取健康数据（可选）") }
        Text("允许后可在后台读取最新健康记录；未允许时使用已捕获的睡眠和心率统计，并标明采集时间。")
    }
    Button(enabled = controlsEnabled, onClick = {
        runProbeAction {
            results = emptyList()
            results = probe.read()
        }
    }) {
        Text(stringResource(if (busy) R.string.reading_health_records else R.string.read_health_records))
    }
    notice?.let { Text(stringResource(it)) }
    if (export.preview == null) export.notice?.let { Text(stringResource(it)) }
    if (results.isNotEmpty()) {
        Text(stringResource(R.string.export_select_metrics), style = MaterialTheme.typography.titleMedium)
        for (result in results) {
            val checked = result.kind in selected
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(checked, controlsEnabled, Role.Checkbox) { enabled ->
                    selected = if (enabled) selected + result.kind else selected - result.kind
                }.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = controlsEnabled)
                Text(stringResource(result.kind.title))
            }
        }
        OutlinedButton(
            enabled = controlsEnabled && results.any { it.kind in selected && it.coverage != null },
            onClick = {
                notice = null
                export.prepare(probe, selected)
            },
        ) { Text(stringResource(if (export.busy) R.string.export_preparing else R.string.export_preview)) }
    }
    results.forEach { ResultCard(it) }
    if (results.any { it.coverage != null }) Text(stringResource(R.string.source_note))

    export.preview?.let { reviewed ->
        ExportPreviewDialog(reviewed, export) {
            export.chooseFile {
                documentLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    putExtra(Intent.EXTRA_TITLE, "ringcoach-export.json")
                })
            }
        }
    }
}

@Composable
private fun ExportPreviewDialog(preview: ExportPreview, export: ExportViewModel, onSave: () -> Unit) {
    Dialog(onDismissRequest = export::dismissPreview) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxHeight(0.85f)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.export_preview), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.export_preview_help), style = MaterialTheme.typography.bodySmall)
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(preview.chunks) { chunk ->
                        Text(chunk, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                export.notice?.let { Text(stringResource(it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val enabled = !export.busy && !export.choosingFile
                    TextButton(enabled = enabled, onClick = export::dismissPreview) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Button(enabled = enabled, onClick = onSave) {
                        Text(stringResource(R.string.export_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: MetricResult) {
    val timeFormat = remember {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(result.kind.title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(result.status.label))
            result.coverage?.let { coverage ->
                Text(stringResource(R.string.record_count, coverage.records))
                result.samplesLabel?.let { Text(stringResource(it, coverage.samples)) }
                Text(stringResource(
                    R.string.coverage_range,
                    timeFormat.format(coverage.earliest),
                    timeFormat.format(coverage.latest),
                ))
                coverage.sources.forEach { (source, count) ->
                    val device = source.device
                    val model = listOfNotNull(device?.manufacturer, device?.model).joinToString(" ")
                    val description = when {
                        device == null -> stringResource(R.string.unknown_device)
                        device.type == Device.TYPE_RING -> listOf(stringResource(R.string.ring_device_type), model)
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        device.type == Device.TYPE_UNKNOWN -> model.ifBlank { stringResource(R.string.unknown_device) }
                        else -> stringResource(R.string.device_description, model, device.type)
                    }
                    Text(stringResource(R.string.record_source, source.packageName, description, count))
                }
            }
        }
    }
}
