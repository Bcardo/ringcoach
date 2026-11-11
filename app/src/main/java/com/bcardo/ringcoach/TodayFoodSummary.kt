package com.bcardo.ringcoach

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
internal fun TodayFoodSummary(day: NutritionDay?, diet: String, onJournal: () -> Unit) {
    Text(stringResource(R.string.daily_food_title), style = MaterialTheme.typography.titleLarge)
    val result = day?.result
    if (result != null) {
        for ((field, label) in listOf("intake_kcal" to R.string.nutrition_intake, "protein_grams" to R.string.nutrition_protein,
            "carbohydrate_grams" to R.string.nutrition_carbs, "fat_grams" to R.string.nutrition_fat)) {
            val value = result.values[field]
            Text(stringResource(label) + " · " + (if (value?.kind == "estimated") "≈" else "") + nutritionNumber(value?.value))
        }
        Text(stringResource(if (result.coverage == "daily_total") R.string.nutrition_daily_total else R.string.nutrition_partial))
    }
    if (diet.isNotBlank()) Text(diet)
    val meals = day?.photoMeals?.filter { it.consumed } ?: emptyList()
    for ((index, meal) in meals.withIndex()) {
        Text("${index + 1}. " + (meal.estimate?.description ?: meal.notes))
        if (meal.status != "complete") Text(stringResource(R.string.photo_incomplete_day))
    }
    if (diet.isBlank() && meals.isEmpty()) Text(stringResource(R.string.daily_no_food))
    if (day?.photoConflict == true) Text(stringResource(R.string.photo_overlap_help))
    if (result?.coverage == "daily_total" && meals.isNotEmpty()) Text(stringResource(R.string.photo_daily_total_priority))
    OutlinedButton(onClick = onJournal) { Text(stringResource(R.string.daily_record_food)) }
    HorizontalDivider()
}
