package com.bcardo.ringcoach

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProgressSubmissionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 11, 11)

    private fun isolated(block: (JournalStore) -> Unit) {
        val name = "progress-test-${UUID.randomUUID()}.db"
        val store = JournalStore(context, name)
        try { block(store) } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun appendCorrectionIdempotencyAndLateResultProtection() = isolated { store ->
        store.appendProgress("breakfast", date, "早餐已吃2个鸡蛋", emptyList())
        val old = store.nextPendingNutrition()!!
        store.appendProgress("correction", date, "更正：早餐实际只吃1个鸡蛋", emptyList())
        store.appendProgress("correction", date, "更正：早餐实际只吃1个鸡蛋", emptyList())
        val next = store.nextPendingNutrition()!!
        assertTrue(next.unified)
        assertTrue(next.source.indexOf("早餐已吃2个鸡蛋") < next.source.indexOf("更正：早餐实际只吃1个鸡蛋"))
        assertEquals(1, Regex("更正：早餐实际只吃1个鸡蛋").findAll(next.source).count())
        val missing = NutritionExtraction(NUTRITION_FIELDS.associateWith { NutritionValue(null, "missing", "") }, "unknown", "", emptyList())
        assertFalse(store.applyNutritionExtraction(old, missing))
        assertTrue(store.applyNutritionExtraction(next, missing))
        assertEquals(1, store.readAll().size)
    }

    @Test fun photosShareTheDayAnalysisAndAreNeverSummedAgain() = isolated { store ->
        val photos = PhotoMealStore(store.writableDatabase)
        val id = photos.create(date, byteArrayOf(1, 2, 3))
        store.appendProgress("photo", date, "午餐已吃完，照片和文字是同一餐", listOf(id))
        val job = store.nextPendingNutrition()!!
        assertEquals(listOf(id), store.nutritionPhotos(job).map { it.first })
        val values = NUTRITION_FIELDS.associateWith {
            if (it == "intake_kcal") NutritionValue(300.0, "estimated", "[照片 $id]") else NutritionValue(null, "missing", "")
        }
        assertTrue(store.applyNutritionExtraction(job, NutritionExtraction(values, "partial_day", "", emptyList())))
        val day = store.readCoachingContext(date, "").nutritionDays.first()
        assertEquals("unified", day.photoMode)
        assertFalse(day.photoConflict)
        assertEquals(300.0, day.result!!.values.getValue("intake_kcal").value!!, 0.0)
        assertFalse(day.toJson().getJSONArray("photo_meals").getJSONObject(0).has("estimate"))
        store.appendProgress("later", date, "又吃了一个苹果", emptyList())
        assertEquals(listOf(id), store.nutritionPhotos(store.nextPendingNutrition()!!).map { it.first })
    }

    @Test fun wrongDayAttachmentRollsBackAndLeavesOtherDayUntouched() = isolated { store ->
        val photos = PhotoMealStore(store.writableDatabase)
        val id = photos.create(date, byteArrayOf(1))
        try {
            store.appendProgress("wrong-date", date.minusDays(1), "午餐", listOf(id))
            fail("Cross-date photo accepted")
        } catch (_: IllegalArgumentException) { }
        assertFalse(photos.get(id)!!.consumed)
        assertTrue(store.readAll().isEmpty())
    }

    @Test fun versionNineMigrationPreservesJournalPlanAndMeal() {
        val name = "migration-progress-${UUID.randomUUID()}.db"
        val original = JournalStore(context, name)
        try {
            original.save(JournalDraft(date, diet = "早餐", additional = "已有记录"))
            original.saveSettings(CoachingSettings("训练基准", "饮食计划", "用户指令"))
            val photoId = PhotoMealStore(original.writableDatabase).create(date, byteArrayOf(1))
            original.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE progress_submissions")
                it.execSQL("ALTER TABLE nutrition_extractions DROP COLUMN unified")
                it.version = 9
            }
            JournalStore(context, name).use { migrated ->
                assertEquals("饮食计划", migrated.readSettings("").diet)
                assertEquals("已有记录", migrated.readAll().single().draft.additional)
                assertNotNull(PhotoMealStore(migrated.readableDatabase).get(photoId))
                migrated.appendProgress("new", date, "晚餐已吃", emptyList())
                assertTrue(migrated.nextPendingNutrition()!!.unified)
            }
        } finally { original.close(); context.deleteDatabase(name) }
    }
}
