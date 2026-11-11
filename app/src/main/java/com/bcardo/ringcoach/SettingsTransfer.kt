package com.bcardo.ringcoach

import org.json.JSONObject
import java.time.LocalDate

internal data class SettingsImport(
    val settings: CoachingSettings,
    val nutrition: List<NutritionReference>,
    val exercises: List<ExerciseBaseline>,
    val document: String,
)

internal object SettingsTransfer {
    const val MAX_BYTES = 128 * 1024

    fun decode(content: String): SettingsImport {
        val json = JSONObject(content.removePrefix("\uFEFF"))
        val version = json.get("schema_version")
        require(version == 1 || version == 2)
        val fields = setOf("schema_version", "routine", "diet", "instructions")
        val expected = if (version == 2) fields + setOf("snapshot_date", "nutrition_history", "exercise_baselines") else fields
        require(json.keys().asSequence().toSet() == expected)
        val routine = text(json, "routine")
        val diet = text(json, "diet")
        val instructions = text(json, "instructions")
        require(instructions.isNotBlank())
        val settings = CoachingSettings(routine, diet, instructions)
        if (version == 1) return SettingsImport(settings, emptyList(), emptyList(), content)
        val snapshot = LocalDate.parse(text(json, "snapshot_date"))
        val nutritionJson = json.getJSONArray("nutrition_history")
        val nutrition = (0 until nutritionJson.length()).map { index ->
            val row = nutritionJson.getJSONObject(index)
            val date = LocalDate.parse(text(row, "date"))
            require(date <= snapshot)
            val record = NutritionReference(
                date, number(row, "weight_kg"), number(row, "protein_grams"),
                number(row, "carbohydrate_grams"), number(row, "fat_grams"),
                number(row, "intake_kcal"), number(row, "estimated_expenditure_kcal"),
                number(row, "reported_balance_kcal"), snapshot,
            )
            require(record.weightKg > 0 && listOf(
                record.proteinGrams, record.carbohydrateGrams, record.fatGrams,
                record.intakeKcal, record.expenditureKcal,
            ).all { it >= 0 })
            record
        }
        require(nutrition.map { it.date }.distinct().size == nutrition.size)
        val exerciseJson = json.getJSONArray("exercise_baselines")
        val dayCounts = mutableMapOf<String, Int>()
        val exercises = (0 until exerciseJson.length()).map { index ->
            val row = exerciseJson.getJSONObject(index)
            val day = text(row, "training_day")
            val number = dayCounts.getOrDefault(day, 0) + 1
            dayCounts[day] = number
            ExerciseBaseline(
                day, text(row, "exercise"), text(row, "reference_notes"), snapshot, number,
            ).also { require(it.trainingDay.isNotBlank() && it.exercise.isNotBlank() && it.referenceNotes.isNotBlank()) }
        }
        require(exercises.map { it.trainingDay to it.exercise }.distinct().size == exercises.size)
        return SettingsImport(settings, nutrition, exercises, content)
    }

    private fun text(json: JSONObject, key: String): String {
        val value = json.get(key)
        require(value is String)
        return value
    }

    private fun number(json: JSONObject, key: String): Double {
        val value = json.get(key)
        require(value is Number)
        return value.toDouble().also { require(it.isFinite()) }
    }
}
