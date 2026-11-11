package com.bcardo.ringcoach

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
internal fun PhotoMealScreen(photos: PhotoMealViewModel, today: LocalDate, onChanged: () -> Unit, onSettings: () -> Unit, batch: Boolean = false) {
    LaunchedEffect(today) { photos.reload() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photos.importPhoto(uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), photos::captureResult)
    Text(stringResource(R.string.photo_help))
    OutlinedButton(enabled = !photos.working, onClick = photos::addTextMeal) { Text(stringResource(R.string.daily_add_meal)) }
    Text(today.toString())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(enabled = !photos.working, onClick = {
            try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            catch (_: Exception) { photos.pickerFailed() }
        }) { Text(stringResource(R.string.photo_choose)) }
        OutlinedButton(enabled = !photos.working, onClick = { photos.capture(camera::launch) }) {
            Text(stringResource(R.string.photo_capture))
        }
    }
    if (photos.working) Text(stringResource(R.string.photo_working))
    photos.notice?.let { Text(stringResource(it)) }
    TextButton(enabled = !photos.working, onClick = photos::reload) { Text(stringResource(R.string.photo_refresh)) }
    photos.selected?.takeIf { it.date == today }?.let { meal ->
        HorizontalDivider()
        Text(stringResource(R.string.photo_selected_date, meal.date.toString()), style = MaterialTheme.typography.titleMedium)
        photos.preview?.let {
            Image(it, contentDescription = stringResource(R.string.photo_preview),
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp))
        }
        OutlinedTextField(value = photos.notes, onValueChange = photos::updateNotes,
            label = { Text(stringResource(R.string.photo_notes)) }, minLines = 3, maxLines = 7,
            enabled = !photos.working, modifier = Modifier.fillMaxWidth())
        Row {
            Checkbox(checked = photos.consumed, onCheckedChange = photos::updateConsumed, enabled = !photos.working)
            Text(stringResource(R.string.photo_consumed))
        }
        if (photos.hasChanges) Text(stringResource(R.string.photo_unsaved))
        if (!batch) OutlinedButton(enabled = !photos.working && photos.hasChanges, onClick = { photos.save(onChanged) }) {
            Text(stringResource(R.string.photo_save_notes))
        }
        Text(stringResource(if (batch) R.string.daily_meal_submit_help else R.string.photo_send_help))
        if (!batch) Button(enabled = !photos.working && photos.consumed, onClick = { photos.analyze(onChanged) },
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.photo_analyze)) }
        if (!batch) TextButton(enabled = !photos.working, onClick = onSettings) { Text(stringResource(R.string.coach_configure_key)) }
        Text(stringResource(photoStatus(meal.status)))
        if (!photos.hasChanges) meal.estimate?.let { estimate ->
            Text(estimate.description, style = MaterialTheme.typography.titleMedium)
            Text("≈ " + nutritionNumber(estimate.values["intake_kcal"]) + " kcal")
            for ((field, label) in listOf("protein_grams" to R.string.nutrition_protein,
                "carbohydrate_grams" to R.string.nutrition_carbs, "fat_grams" to R.string.nutrition_fat)) {
                Text(stringResource(label) + " ≈ " + nutritionNumber(estimate.values[field]))
            }
            estimate.assumptions.forEach { Text("• $it") }
            if (estimate.questions.isNotEmpty()) Text(stringResource(R.string.photo_questions))
            estimate.questions.forEach { Text("• $it") }
            Text("${meal.model} · ${meal.promptVersion}", style = MaterialTheme.typography.bodySmall)
        }
        var confirmDelete by rememberSaveable(meal.id) { mutableStateOf(false) }
        TextButton(enabled = !photos.working, onClick = { confirmDelete = true }) { Text(stringResource(R.string.photo_remove)) }
        if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.photo_remove)) }, text = { Text(stringResource(R.string.photo_remove_help)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; photos.remove(onChanged) }) { Text(stringResource(R.string.photo_remove)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(android.R.string.cancel)) } })
    }
    HorizontalDivider()
    Text(stringResource(R.string.photo_recent), style = MaterialTheme.typography.titleLarge)
    if (photos.meals.isEmpty() && !photos.working) Text(stringResource(R.string.photo_empty))
    for (meal in photos.meals.filter { it.date == today }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(meal.date.toString() + " · " + stringResource(photoStatus(meal.status)))
            Text(meal.estimate?.description ?: meal.notes.ifBlank { stringResource(R.string.photo_no_notes) })
            if (!meal.consumed) Text(stringResource(R.string.photo_not_consumed))
            OutlinedButton(enabled = !photos.working, onClick = { photos.open(meal.id) }) {
                Text(stringResource(R.string.photo_edit))
            }
        }
        HorizontalDivider()
    }
}

private fun photoStatus(status: String): Int = when (status) {
    "complete" -> R.string.photo_complete
    "pending" -> R.string.photo_pending
    "failed" -> R.string.photo_failed
    "needs_details" -> R.string.photo_needs_details
    else -> R.string.photo_draft
}
