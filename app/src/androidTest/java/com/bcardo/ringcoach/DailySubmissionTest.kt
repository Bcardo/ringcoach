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
class DailySubmissionTest {
    @Test
    fun appendTextMealCorrectExistingMealAndPersistPhotoOnlyDay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "daily-submission-" + UUID.randomUUID() + ".db"
        val store = JournalStore(context, name)
        try {
            val date = LocalDate.of(2025, 1, 10)
            val meals = PhotoMealStore(store.writableDatabase)
            val first = meals.createText(date)
            store.saveDailySubmission(JournalDraft(date), first, "Ate 100g rice", true)
            assertEquals(1, store.readAll().size)
            assertFalse(meals.get(first)!!.hasPhoto)
            fun apply(id: String, kcal: Double) {
                meals.apply(meals.get(id)!!, MealEstimate("Reported meal", MEAL_FIELDS.associateWith { kcal }, emptyList(), emptyList()))
            }
            apply(first, 100.0)
            val second = meals.createText(date)
            store.saveDailySubmission(JournalDraft(date, training = "Bench 20 lb x8"), second, "Ate a second meal", true)
            apply(second, 200.0)
            assertEquals(300.0, store.readCoachingContext(date, "").nutritionDays.first().result!!.values["intake_kcal"]!!.value!!, 0.0)
            store.saveDailySubmission(store.readAll().single().draft, first, "Correction: ate half", true)
            apply(first, 50.0)
            assertEquals(2, meals.list(date, date).size)
            assertEquals(250.0, store.readCoachingContext(date, "").nutritionDays.first().result!!.values["intake_kcal"]!!.value!!, 0.0)
            assertEquals("Bench 20 lb x8", store.readAll().single().draft.training)
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test
    fun failedJournalWriteRollsBackMealCorrection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "daily-rollback-" + UUID.randomUUID() + ".db"
        val store = JournalStore(context, name)
        try {
            val date = LocalDate.of(2025, 1, 10)
            val meals = PhotoMealStore(store.writableDatabase)
            val id = meals.createText(date)
            val original = meals.get(id)!!
            store.writableDatabase.execSQL("""CREATE TRIGGER reject_daily BEFORE INSERT ON journal_entries
                BEGIN SELECT RAISE(ABORT, 'test'); END""")
            try {
                store.saveDailySubmission(JournalDraft(date, diet = "New total"), id, "Correction", true)
                throw AssertionError("Expected atomic submission failure")
            } catch (_: android.database.sqlite.SQLiteException) {
                assertEquals(original, meals.get(id))
                assertTrue(store.readAll().isEmpty())
            }
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
