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
class PhotoMealStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val result = MealEstimate("Rice, half portion", MEAL_FIELDS.associateWith { 100.0 }, listOf("No sauce consumed"), emptyList())

    @Test
    fun correctionsReplaceOneMealRejectStaleRepliesAndDeletionRemovesPrivateInputs() {
        val name = "photo-test-" + UUID.randomUUID() + ".db"
        val store = JournalStore(context, name)
        try {
            val date = LocalDate.of(2025, 1, 10)
            val photos = PhotoMealStore(store.writableDatabase)
            val id = photos.create(date, byteArrayOf(1, 2, 3))
            assertFalse(photos.get(id)!!.consumed)
            val first = photos.edit(id, "Shared dish; ate half with sauce", true)!!
            assertTrue(photos.markPending(first))
            assertTrue(photos.apply(first, result))
            photos.reconcile(date, "included")
            val second = photos.edit(id, "Correction: ate one quarter, sauce left", true)!!
            assertEquals(null, photos.mode(date))
            assertFalse(photos.apply(first, result))
            assertFalse(photos.fail(first))
            assertEquals(null, photos.get(id)!!.estimate)
            assertTrue(photos.apply(second, result.copy(values = MEAL_FIELDS.associateWith { 50.0 })))
            assertEquals(1, photos.list(date, date).size)
            val day = store.readCoachingContext(date, "").nutritionDays.first()
            assertEquals(50.0, day.result!!.values.getValue("intake_kcal").value!!, 0.0)
            assertEquals(1, day.photoMeals.size)
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM meal_revisions WHERE meal_id = ?", arrayOf(id)).use {
                it.moveToFirst(); assertEquals(3, it.getInt(0))
            }
            photos.remove(id)
            assertEquals(null, photos.get(id)); assertEquals(null, photos.image(id))
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM meal_revisions", null).use {
                it.moveToFirst(); assertEquals(0, it.getInt(0))
            }
            assertEquals("missing", store.readCoachingContext(date, "").nutritionDays.first().status)
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test
    fun versionEightUpgradePreservesDietAndMealOverlapChoiceResetsOnTextEdit() {
        val name = "photo-upgrade-" + UUID.randomUUID() + ".db"
        var store = JournalStore(context, name)
        try {
            val date = LocalDate.of(2025, 1, 10)
            val draft = JournalDraft(date, diet = "500 kcal")
            store.save(draft)
            val pending = store.nextPendingNutrition()
            for (table in listOf("meal_revisions", "photo_meals", "nutrition_reconciliation")) store.writableDatabase.execSQL("DROP TABLE $table")
            store.writableDatabase.execSQL("DROP TABLE progress_submissions")
            store.writableDatabase.execSQL("ALTER TABLE nutrition_extractions DROP COLUMN unified")
            store.writableDatabase.version = 8
            store.close(); store = JournalStore(context, name)
            assertEquals(draft, store.readAll().single().draft)
            assertEquals(pending, store.nextPendingNutrition())
            val photos = PhotoMealStore(store.writableDatabase)
            photos.reconcile(date, "separate")
            store.save(draft.copy(training = "Bench"))
            assertEquals("separate", photos.mode(date))
            store.save(draft.copy(diet = "600 kcal"))
            assertEquals(null, photos.mode(date))
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
