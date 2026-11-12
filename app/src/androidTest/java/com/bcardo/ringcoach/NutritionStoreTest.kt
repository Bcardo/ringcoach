package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NutritionStoreTest {
    @Test
    fun revisionsProtectCurrentDietAndReplaceImportedReferencesWithoutDuplication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "nutrition-test-" + UUID.randomUUID() + ".db"
        val store = JournalStore(context, name)
        try {
            val day = LocalDate.of(2025, 1, 10)
            val settings = CoachingSettings(instructions = "Test")
            val reference = NutritionReference(day, 70.0, 120.0, 180.0, 50.0, 1700.0, 1900.0, -200.0, day)
            store.saveImportedSettings(settings, SettingsImport(settings, listOf(reference), emptyList(), ""))
            assertEquals("reference", store.readCoachingContext(day, "").nutritionDays.first().status)
            val draft = JournalDraft(day, diet = "Total 1700 kcal", additional = "70 kg")
            store.save(draft)
            val first = store.nextPendingNutrition()!!
            val result = NutritionExtraction(NUTRITION_FIELDS.associateWith {
                if (it == "intake_kcal") NutritionValue(1700.0, "reported", "Total 1700 kcal")
                else NutritionValue(null, "missing", "")
            }, "daily_total", "Total 1700 kcal", emptyList())
            assertTrue(store.applyNutritionExtraction(first, result))
            store.save(draft.copy(training = "Bench"))
            assertEquals(null, store.nextPendingNutrition())
            assertTrue(store.readCoachingContext(day, "").nutrition.isEmpty())
            val days = store.readCoachingContext(day, "").nutritionDays
            assertEquals(7, days.size)
            assertEquals(result, days.first().result)
            assertEquals(null, days.first().result!!.values.getValue("weight_kg").value)
            store.save(draft.copy(diet = "Total 1800 kcal"))
            val second = store.nextPendingNutrition()!!
            assertTrue(first.revision != second.revision)
            assertFalse(store.applyNutritionExtraction(first, result))
            store.failNutritionExtraction(first)
            assertEquals(second, store.nextPendingNutrition())
            assertEquals(null, store.readCoachingContext(day, "").nutritionDays.first().result)
            store.save(draft.copy(diet = "", additional = "", training = "Bench"))
            assertFalse(store.applyNutritionExtraction(second, result))
            assertEquals(null, store.nextPendingNutrition())
            assertEquals("reference", store.readCoachingContext(day, "").nutritionDays.first().status)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionSevenMigrationPreservesJournalAndQueuesNutrition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "nutrition-upgrade-" + UUID.randomUUID() + ".db"
        var store = JournalStore(context, name)
        try {
            val draft = JournalDraft(LocalDate.of(2025, 1, 10), "Bench", "Total 1700 kcal", "70 kg")
            store.save(draft)
            val workout = store.nextPendingWorkout()
            store.writableDatabase.execSQL("DROP TABLE progress_submissions")
            store.writableDatabase.execSQL("DROP TABLE nutrition_extractions")
            store.writableDatabase.execSQL("DROP TABLE meal_revisions")
            store.writableDatabase.execSQL("DROP TABLE photo_meals")
            store.writableDatabase.execSQL("DROP TABLE nutrition_reconciliation")
            store.writableDatabase.version = 7
            store.close()
            store = JournalStore(context, name)
            assertEquals(draft, store.readAll().single().draft)
            assertEquals(draft.diet, store.nextPendingNutrition()!!.diet)
            assertEquals(workout, store.nextPendingWorkout())
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }
}
