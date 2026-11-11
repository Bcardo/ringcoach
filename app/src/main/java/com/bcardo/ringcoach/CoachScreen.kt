package com.bcardo.ringcoach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun QwenKeyEditor(coach: CoachViewModel) {
    Text(stringResource(R.string.coach_api_settings), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(if (coach.hasKey) R.string.coach_key_present else R.string.coach_key_missing))
    OutlinedTextField(
        value = coach.keyInput,
        onValueChange = coach::updateKeyInput,
        label = { Text(stringResource(R.string.coach_api_key)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        singleLine = true,
        enabled = !coach.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(enabled = !coach.busy && coach.keyInput.isNotBlank(), onClick = coach::saveKey) {
        Text(stringResource(R.string.coach_save_key))
    }
    if (coach.hasKey) {
        TextButton(enabled = !coach.busy, onClick = coach::clearKey) { Text(stringResource(R.string.coach_remove_key)) }
    }
    coach.keyNotice?.let { Text(stringResource(it)) }
}

@Composable
internal fun CoachScreen(
    coach: CoachViewModel,
    hasUnsavedInput: Boolean,
    inputsReady: Boolean,
    onPrepare: () -> Unit,
    onSettings: () -> Unit,
) {
    Text(stringResource(R.string.coach_flow_help))
    if (coach.loading) Text(stringResource(R.string.coach_loading))
    if (coach.loadFailed) {
        Text(stringResource(R.string.coach_load_failed))
        OutlinedButton(onClick = coach::reload) { Text(stringResource(R.string.journal_retry)) }
    }
    if (!coach.hasKey) {
        OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.coach_configure_key)) }
    }
    if (hasUnsavedInput) Text(stringResource(R.string.coach_unsaved_input))
    Button(
        enabled = inputsReady && !hasUnsavedInput && !coach.busy && !coach.loadFailed,
        onClick = onPrepare,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(if (coach.preparing) R.string.coach_preparing else R.string.coach_prepare)) }
    if (coach.generating) Text(stringResource(R.string.coach_generating))
    coach.notice?.let { Text(stringResource(it)) }
    coach.card?.let { card ->
        Text(stringResource(R.string.coach_last_card, card.date.toString()), style = MaterialTheme.typography.titleLarge)
        val created = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(card.createdAt)
        Text(stringResource(R.string.coach_card_metadata, created, card.model), style = MaterialTheme.typography.bodySmall)
        for ((title, items) in listOf(
            R.string.coach_training to card.advice.training,
            R.string.coach_diet to card.advice.diet,
            R.string.coach_notes to card.advice.notes,
        )) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            for (item in items) Text("• $item")
        }
        if (!coach.cardSaved) {
            OutlinedButton(enabled = !coach.busy, onClick = coach::retrySave) {
                Text(stringResource(R.string.coach_retry_save))
            }
        }
    }
    coach.prepared?.let { prepared ->
        Dialog(onDismissRequest = coach::dismissPrepared) {
            Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxHeight(0.9f)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.coach_review_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.coach_review_help), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(prepared.preview.chunks) { chunk ->
                            Text(chunk, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (!coach.hasKey) Text(stringResource(R.string.coach_key_needed))
                    TextButton(onClick = coach::dismissPrepared) { Text(stringResource(android.R.string.cancel)) }
                    Button(enabled = coach.hasKey && !coach.busy, onClick = coach::generate) {
                        Text(stringResource(R.string.coach_send))
                    }
                }
            }
        }
    }
}
