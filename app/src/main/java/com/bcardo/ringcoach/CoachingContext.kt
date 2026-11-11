package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal data class NutritionReference(
    val date: LocalDate,
    val weightKg: Double,
    val proteinGrams: Double,
    val carbohydrateGrams: Double,
    val fatGrams: Double,
    val intakeKcal: Double,
    val expenditureKcal: Double,
    val reportedBalanceKcal: Double,
    val snapshotDate: LocalDate,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("date", date.toString())
        .put("weight_kg", weightKg)
        .put("protein_grams", proteinGrams)
        .put("carbohydrate_grams", carbohydrateGrams)
        .put("fat_grams", fatGrams)
        .put("intake_kcal", intakeKcal)
        .put("estimated_expenditure_kcal", expenditureKcal)
        .put("reported_balance_kcal", reportedBalanceKcal)
        .put("snapshot_date", snapshotDate.toString())
        .put("source", "user_import")
}

internal data class ExerciseBaseline(
    val trainingDay: String,
    val exercise: String,
    val referenceNotes: String,
    val snapshotDate: LocalDate,
    val exerciseNumber: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("training_day", trainingDay)
        .put("exercise_number", exerciseNumber)
        .put("exercise", exercise)
        .put("reference_notes", referenceNotes)
        .put("snapshot_date", snapshotDate.toString())
        .put("record_kind", "baseline_reference")
}

internal data class CoachingContext(
    val date: LocalDate,
    val settings: CoachingSettings,
    val journals: List<JournalEntry>,
    val nutrition: List<NutritionReference>,
    val exercises: List<ExerciseBaseline>,
    val workoutRecords: List<WorkoutRecord> = emptyList(),
    val workoutStatuses: List<JSONObject> = emptyList(),
    val nutritionDays: List<NutritionDay> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("request_date", date.toString())
        .put("history_start", date.minusDays(6).toString())
        .put("routine", settings.routine)
        .put("diet_plan", settings.diet)
        .put("instructions", settings.instructions)
        .put("journal_history", JSONArray(journals.map { entry ->
            JSONObject()
                .put("date", entry.draft.date.toString())
                .put("training", entry.draft.training)
                .put("diet", entry.draft.diet)
                .put("additional", entry.draft.additional)
                .put("updated_at", entry.updatedAt.toString())
        }))
        .put("nutrition_history", JSONArray(nutrition.map { it.toJson() }))
        .put("exercise_baselines", JSONArray(exercises.map { it.toJson() }))
        .put("latest_workout_records", JSONArray(workoutRecords.map { it.toJson() }))
        .put("training_extraction_status", JSONArray(workoutStatuses))
        .put("daily_nutrition", JSONArray(nutritionDays.map { it.toJson() }))
        .put("telemetry_status", "not_loaded")
}
