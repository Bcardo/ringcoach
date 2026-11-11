package com.bcardo.ringcoach

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTransferTest {
    private fun document() = JSONObject()
        .put("schema_version", 1)
        .put("routine", "基础训练")
        .put("diet", "基础饮食")
        .put("instructions", "请输出三张表格。")

    @Test
    fun importsUnicodeAndWindowsBom() {
        assertEquals(
            CoachingSettings("基础训练", "基础饮食", "请输出三张表格。"),
            SettingsTransfer.decode("\uFEFF" + document().toString()).settings,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedSchema() {
        SettingsTransfer.decode(document().put("schema_version", 3).toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongFieldTypes() {
        SettingsTransfer.decode(document().put("routine", JSONObject()).toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyInstructions() {
        SettingsTransfer.decode(document().put("instructions", "  ").toString())
    }

    @Test
    fun importsStructuredReferencesWithoutConvertingPlansToActuals() {
        val json = document().put("schema_version", 2).put("snapshot_date", "2025-01-10")
            .put("nutrition_history", JSONArray().put(JSONObject()
                .put("date", "2025-01-09").put("weight_kg", 70)
                .put("protein_grams", 120).put("carbohydrate_grams", 180).put("fat_grams", 50)
                .put("intake_kcal", 1700).put("estimated_expenditure_kcal", 1900)
                .put("reported_balance_kcal", -200)))
            .put("exercise_baselines", JSONArray().put(JSONObject()
                .put("training_day", "推").put("exercise", "测试动作").put("reference_notes", "下轮计划")))
        val imported = SettingsTransfer.decode(json.toString())
        assertEquals(-200.0, imported.nutrition.single().reportedBalanceKcal, 0.0)
        assertEquals("下轮计划", imported.exercises.single().referenceNotes)
    }

    @Test
    fun numbersExercisesInSourceOrderSeparatelyForEachDay() {
        fun row(day: String, exercise: String) = JSONObject().put("training_day", day)
            .put("exercise", exercise).put("reference_notes", "Reference")
        val json = document().put("schema_version", 2).put("snapshot_date", "2025-01-10")
            .put("nutrition_history", JSONArray()).put("exercise_baselines", JSONArray()
                .put(row("Day 1", "Z first")).put(row("Day 1", "A second"))
                .put(row("Day 2", "Y first")))
        val rows = SettingsTransfer.decode(json.toString()).exercises
        assertEquals(listOf("Z first", "A second", "Y first"), rows.map { it.exercise })
        assertEquals(listOf(1, 2, 1), rows.map { it.exerciseNumber })
    }
}
