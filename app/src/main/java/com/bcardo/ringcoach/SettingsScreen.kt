package com.bcardo.ringcoach

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
internal fun SettingsEditor(settings: SettingsViewModel, coach: CoachViewModel, today: LocalDate) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        settings.onImportResult(resolver, it)
    }
    if (settings.loading) Text(stringResource(R.string.settings_loading))
    if (settings.loadFailed) {
        Text(stringResource(R.string.settings_load_failed))
        OutlinedButton(onClick = settings::reload) { Text(stringResource(R.string.journal_retry)) }
    }
    val draft = settings.draft ?: return
    val enabled = !settings.busy && !settings.loadFailed
    var connectionOpen by rememberSaveable { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { connectionOpen = !connectionOpen }) { Text("账号与健康连接") }
    if (connectionOpen) {
        QwenKeyEditor(coach)
        NightlyRecoveryPanel(coach, today, setup = true)
    }
    Text(stringResource(R.string.settings_context_help))
    TextButton(onClick = { advancedOpen = !advancedOpen }) { Text("高级 · 导入与上下文检查") }
    if (advancedOpen) {
    OutlinedButton(enabled = enabled, onClick = {
        settings.chooseImport { importLauncher.launch(arrayOf("application/json")) }
    }) { Text(stringResource(R.string.settings_import)) }
    OutlinedButton(enabled = enabled, onClick = { settings.previewContext(today) }) {
        Text(stringResource(if (settings.preparingContext) R.string.settings_context_loading else R.string.settings_context_preview))
    }
    }
    if (settings.importing) Text(stringResource(R.string.settings_importing))
    OutlinedTextField(
        value = draft.routine,
        onValueChange = { settings.updateDraft(draft.copy(routine = it)) },
        label = { Text(stringResource(R.string.settings_routine)) },
        minLines = 4,
        maxLines = 8,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.diet,
        onValueChange = { settings.updateDraft(draft.copy(diet = it)) },
        label = { Text(stringResource(R.string.settings_diet)) },
        minLines = 4,
        maxLines = 8,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.instructions,
        onValueChange = { settings.updateDraft(draft.copy(instructions = it)) },
        label = { Text(stringResource(R.string.settings_instructions)) },
        minLines = 8,
        maxLines = 12,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    settings.notice?.let { Text(stringResource(it)) }
    Button(
        onClick = settings::save,
        enabled = enabled && settings.hasChanges,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (settings.saving) R.string.settings_saving else R.string.settings_save))
    }
    val pending = settings.pendingImport
    if (pending != null) {
        AlertDialog(
            onDismissRequest = settings::cancelImport,
            title = { Text(stringResource(R.string.settings_import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_import_message))
                    if (pending.nutrition.isNotEmpty() || pending.exercises.isNotEmpty()) {
                        Text(stringResource(R.string.settings_import_counts, pending.nutrition.size, pending.exercises.size))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = settings::confirmImport) {
                    Text(stringResource(R.string.settings_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = settings::cancelImport) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
    settings.contextPreview?.let { preview ->
        Dialog(onDismissRequest = settings::dismissContext) {
            Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxHeight(0.85f)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_context_preview), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.settings_context_preview_help), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(preview.chunks) { chunk ->
                            Text(chunk, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    TextButton(onClick = settings::dismissContext) { Text(stringResource(R.string.settings_context_close)) }
                }
            }
        }
    }
}
