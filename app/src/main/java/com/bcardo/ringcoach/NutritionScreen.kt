package com.bcardo.ringcoach

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.util.Locale

internal fun nutritionNumber(value: Double?): String = value?.let {
    String.format(Locale.ROOT, "%.1f", it).removeSuffix(".0")
} ?: "—"

@Composable
internal fun NutritionScreen(nutrition: NutritionViewModel, today: LocalDate, onPhotos: () -> Unit) {
    OutlinedButton(onClick = onPhotos) { Text(stringResource(R.string.photo_title)) }
    Text(stringResource(R.string.nutrition_help))
    if (nutrition.syncing) Text(stringResource(R.string.nutrition_syncing))
    if (nutrition.needsKey) Text(stringResource(R.string.nutrition_key_needed))
    nutrition.notice?.let { Text(stringResource(it)) }
    if (nutrition.days.any { it.status in setOf("pending", "failed") } || nutrition.notice != null) {
        OutlinedButton(enabled = !nutrition.syncing, onClick = { nutrition.sync(today, retryFailed = true) }) {
            Text(stringResource(R.string.nutrition_retry))
        }
    }
    val labels = listOf(R.string.nutrition_weight, R.string.nutrition_protein, R.string.nutrition_carbs,
        R.string.nutrition_fat, R.string.nutrition_intake, R.string.nutrition_expenditure, R.string.nutrition_balance)
    Column(modifier = Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.nutrition_date), modifier = Modifier.width(106.dp))
            for (label in labels) Text(stringResource(label), modifier = Modifier.width(96.dp))
        }
        for (day in nutrition.days) {
            HorizontalDivider()
            val reference = day.reference
            val values = if (reference != null) listOf(reference.weightKg, reference.proteinGrams,
                reference.carbohydrateGrams, reference.fatGrams, reference.intakeKcal, reference.expenditureKcal,
                reference.reportedBalanceKcal).map(::nutritionNumber) else {
                NUTRITION_FIELDS.map { field ->
                    val value = day.result?.values?.get(field)
                    (if (value?.kind == "estimated") "≈" else "") + nutritionNumber(value?.value)
                } + ((if (day.result?.values?.get("intake_kcal")?.kind == "estimated") "≈" else "") + nutritionNumber(day.result?.balance))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.width(106.dp)) {
                    Text(day.date.toString())
                    Text(stringResource(when {
                        day.status == "reference" -> R.string.nutrition_scope_reference
                        day.status == "pending" -> R.string.nutrition_scope_pending
                        day.status == "failed" -> R.string.nutrition_scope_failed
                        day.result?.coverage == "daily_total" -> R.string.nutrition_scope_daily
                        day.result?.coverage == "partial_day" -> R.string.nutrition_scope_partial
                        else -> R.string.nutrition_scope_unknown
                    }), style = MaterialTheme.typography.labelSmall)
                }
                for (value in values) Text(value, modifier = Modifier.width(96.dp))
            }
        }
    }
    Text(stringResource(R.string.nutrition_table_help), style = MaterialTheme.typography.bodySmall)
    for (day in nutrition.days) {
        HorizontalDivider()
        Text(day.date.toString(), style = MaterialTheme.typography.titleMedium)
        if (day.photoMeals.isNotEmpty()) {
            Text(stringResource(R.string.photo_day_count, day.photoMeals.size))
            if (day.result?.coverage == "daily_total") Text(stringResource(R.string.photo_daily_total_priority))
            MealOverlapControls(day, nutrition, today)
            if (day.photoMeals.any { it.consumed && it.status != "complete" }) Text(stringResource(R.string.photo_incomplete_day))
        }
        Text(stringResource(when (day.status) {
            "reference" -> R.string.nutrition_reference
            "pending" -> R.string.nutrition_pending
            "failed" -> R.string.nutrition_failed
            "missing" -> R.string.nutrition_missing
            else -> when (day.result?.coverage) {
                "daily_total" -> R.string.nutrition_daily_total
                "partial_day" -> R.string.nutrition_partial
                else -> R.string.nutrition_unknown_scope
            }
        }))
        day.reference?.let { Text(stringResource(R.string.workout_reference_date, it.snapshotDate.toString())) }
        day.result?.let { result ->
            var evidence by rememberSaveable(day.date.toString()) { mutableStateOf(false) }
            OutlinedButton(onClick = { evidence = !evidence }) {
                Text(stringResource(if (evidence) R.string.nutrition_hide_evidence else R.string.nutrition_show_evidence))
            }
            if (evidence) {
                for ((index, field) in NUTRITION_FIELDS.withIndex()) {
                    val value = result.values.getValue(field)
                    if (value.value != null) {
                        Text(stringResource(labels[index]) + " · " + stringResource(
                            if (value.kind == "estimated") R.string.nutrition_estimated else R.string.nutrition_reported))
                        Text(value.evidence)
                    }
                }
                if (result.scopeEvidence.isNotBlank()) Text(result.scopeEvidence)
            }
            if (result.unresolved.isNotEmpty()) {
                Text(stringResource(R.string.nutrition_clarify))
                result.unresolved.forEach { Text(it) }
            }
        }
    }
}

@Composable
internal fun MealOverlapControls(day: NutritionDay, nutrition: NutritionViewModel, today: LocalDate) {
    if (!day.photoConflict && day.photoMode == null) return
    Text(stringResource(R.string.photo_overlap_help))
    Text(stringResource(when (day.photoMode) {
        "included" -> R.string.photo_mode_included
        "separate" -> R.string.photo_mode_separate
        else -> R.string.photo_mode_unknown
    }))
    OutlinedButton(enabled = !nutrition.syncing, onClick = { nutrition.reconcile(day.date, "included", today) }) {
        Text(stringResource(R.string.photo_included))
    }
    OutlinedButton(enabled = !nutrition.syncing, onClick = { nutrition.reconcile(day.date, "separate", today) }) {
        Text(stringResource(R.string.photo_separate))
    }
}
