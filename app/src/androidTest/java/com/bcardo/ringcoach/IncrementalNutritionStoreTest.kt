package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IncrementalNutritionStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 11, 11)
    private fun isolated(block: (JournalStore) -> Unit) {
        val name = "incremental-${UUID.randomUUID()}.db"
        JournalStore(context, name).use { store -> try { block(store) } finally { store.close(); context.deleteDatabase(name) } }
    }
    private fun food(id: String, refs: List<String> = listOf(id.substringBefore(':')), scale: Double = 1.0) = FoodFact(id,
        "${100 * scale}g", refs, MealEstimate("测试已吃食物", mapOf("protein_grams" to 10.0 * scale,
            "carbohydrate_grams" to 20.0 * scale, "fat_grams" to 5.0 * scale, "intake_kcal" to 165.0 * scale), emptyList(), emptyList()))
    private fun result(job: PendingNutrition, foods: List<FoodFact>, revised: List<String> = emptyList()) = NutritionExtraction(
        NUTRITION_FIELDS.associateWith { field ->
            val value = if (field in MEAL_FIELDS && foods.isNotEmpty()) foods.sumOf { it.estimate.values[field]!! } else null
            NutritionValue(value, if (value == null) "missing" else "estimated", if (value == null) "" else job.turn?.input?.ifBlank {
                job.turn.photoIds.firstOrNull()?.let { "[照片 $it]" } ?: ""
            } ?: "测试已吃食物")
        }, "partial_day", "", emptyList(), "测试食物", emptyList(), foods, revised)

    @Test fun photoThenSnackThenCorrectionSurvivesReopenWithoutOldImages() = isolated { store ->
        val photos = PhotoMealStore(store.writableDatabase)
        val photo = photos.create(date, byteArrayOf(1, 2, 3))
        store.appendProgress("meal", date, "午餐100g", listOf(photo), true)
        val first = store.nextPendingNutrition()!!
        assertEquals(listOf(photo), store.nutritionPhotos(first).map { it.first })
        val meal = food("meal:1")
        assertTrue(store.applyNutritionExtraction(first, result(first, listOf(meal))))
        assertEquals(0, photos.image(photo)!!.size)
        assertTrue(photos.get(photo)!!.wasPhoto)
        assertFalse(photos.get(photo)!!.hasPhoto)
        store.close()
        val reopened = JournalStore(context, store.databaseName)
        reopened.use {
            it.appendProgress("snack", date, "加餐100g", emptyList(), true)
            val next = it.nextPendingNutrition()!!
            assertEquals(meal, next.turn!!.previous!!.foodItems.single())
            assertTrue(it.nutritionPhotos(next).isEmpty())
            val snack = food("snack:1")
            assertTrue(it.applyNutritionExtraction(next, result(next, listOf(meal, snack))))
            it.appendProgress("correction", date, "午餐只吃一半", emptyList(), true)
            val correction = it.nextPendingNutrition()!!
            val half = food("meal:1", listOf("meal", "correction"), 0.5)
            assertTrue(it.applyNutritionExtraction(correction, result(correction, listOf(half, snack), listOf(meal.id))))
            assertEquals(247.5, it.readCoachingContext(date, "").nutritionDays.first().result!!.values["intake_kcal"]!!.value!!, 0.0)
            assertNull(it.nextPendingNutrition())
        }
    }

    @Test fun pendingTurnsAreOrderedAndNeverReleaseAnotherTurnsPhoto() = isolated { store ->
        val photos = PhotoMealStore(store.writableDatabase)
        val a = photos.create(date, byteArrayOf(1))
        store.appendProgress("first", date, "第一餐", listOf(a), true)
        val job = store.nextPendingNutrition()!!
        val b = photos.create(date, byteArrayOf(2))
        store.appendProgress("second", date, "第二餐", listOf(b), true)
        assertTrue(store.applyNutritionExtraction(job, result(job, listOf(food("first:1")))))
        assertEquals(0, photos.image(a)!!.size)
        assertEquals(1, photos.image(b)!!.size)
        assertFalse(store.applyNutritionExtraction(job, result(job, listOf(food("first:1")))))
        val next = store.nextPendingNutrition()!!
        assertEquals("second", next.revision)
        assertEquals(listOf(b), store.nutritionPhotos(next).map { it.first })
        assertEquals("first:1", next.turn!!.previous!!.foodItems.single().id)
    }

    @Test fun failureKeepsInputAndBlocksLaterTurnsUntilRetry() = isolated { store ->
        val photo = PhotoMealStore(store.writableDatabase).create(date, byteArrayOf(1))
        store.appendProgress("failed", date, "午餐", listOf(photo), true)
        val job = store.nextPendingNutrition()!!
        store.failNutritionExtraction(job)
        store.appendProgress("later", date, "加餐", emptyList(), true)
        val blocked = store.nextPendingNutrition()!!
        assertEquals("failed", blocked.revision)
        assertEquals("failed", blocked.turn!!.status)
        assertEquals(1, store.nutritionPhotos(blocked).size)
        store.retryFailedNutrition()
        assertEquals("pending", store.nextPendingNutrition()!!.turn!!.status)
    }

    @Test fun photoReleaseAndStatePersistenceRollBackTogether() = isolated { store ->
        val photo = PhotoMealStore(store.writableDatabase).create(date, byteArrayOf(1))
        store.appendProgress("meal", date, "午餐", listOf(photo), true)
        val job = store.nextPendingNutrition()!!
        store.writableDatabase.execSQL("CREATE TRIGGER block_release BEFORE UPDATE OF photo_jpeg ON photo_meals BEGIN SELECT RAISE(ABORT,'test rollback'); END")
        try { store.applyNutritionExtraction(job, result(job, listOf(food("meal:1")))); fail("Expected rollback") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertNull(store.nextPendingNutrition()!!.turn!!.previous)
        assertEquals("pending", store.nextPendingNutrition()!!.turn!!.status)
        assertEquals(1, PhotoMealStore(store.readableDatabase).image(photo)!!.size)
        store.writableDatabase.execSQL("DROP TRIGGER block_release")
        assertTrue(store.applyNutritionExtraction(job, result(job, listOf(food("meal:1")))))
    }

    @Test fun stalePreviousStateCannotReplaceConfirmedFactsOrReleaseImages() = isolated { store ->
        store.appendProgress("one", date, "第一餐", emptyList(), true)
        val first = store.nextPendingNutrition()!!
        store.applyNutritionExtraction(first, result(first, listOf(food("one:1"))))
        val photo = PhotoMealStore(store.writableDatabase).create(date, byteArrayOf(1))
        store.appendProgress("two", date, "第二餐", listOf(photo), true)
        val second = store.nextPendingNutrition()!!
        val stale = second.copy(turn = second.turn!!.copy(previousId = "not-the-current-revision"))
        assertFalse(store.applyNutritionExtraction(stale, result(stale, listOf(food("one:1"), food("two:1")))))
        assertEquals(1, PhotoMealStore(store.readableDatabase).image(photo)!!.size)
    }

    @Test fun photoFingerprintMatchesWithoutRetainingPreviousBytes() = isolated { store ->
        val photos = PhotoMealStore(store.writableDatabase)
        val first = photos.create(date, byteArrayOf(1, 2))
        store.appendProgress("one", date, "午餐", listOf(first), true)
        val job = store.nextPendingNutrition()!!
        store.applyNutritionExtraction(job, result(job, listOf(food("one:1"))))
        val second = photos.create(date, byteArrayOf(1, 2))
        store.appendProgress("two", date, "同一餐补充说明", listOf(second), true)
        assertEquals(listOf("one"), store.nextPendingNutrition()!!.turn!!.photoMatches[second])
        assertEquals(0, photos.image(first)!!.size)
    }

    @Test fun pendingUpdateShowsConfirmedFactsAndCoachingCanRecoverWithoutPreferenceHandoff() = isolated { store ->
        store.appendProgress("one", date, "第一餐", emptyList(), true)
        val first = store.nextPendingNutrition()!!
        store.applyNutritionExtraction(first, result(first, listOf(food("one:1"))))
        val covered = SavedCoachCard("card", date, java.time.Instant.now(), QWEN_MODEL, COACH_PROMPT_VERSION,
            org.json.JSONObject().put("nutrition_turn_ids", org.json.JSONArray(listOf("one"))).toString(),
            CoachAdvice(listOf("训练"), listOf("饮食"), listOf("依据")))
        assertFalse(store.hasUncoachedIncrementalProgress(date, covered))
        store.appendProgress("two", date, "加餐", emptyList(), true)
        val day = store.readCoachingContext(date, "").nutritionDays.first()
        assertEquals("pending", day.status)
        assertEquals(165.0, day.result!!.values["intake_kcal"]!!.value!!, 0.0)
        assertTrue(store.hasUncoachedIncrementalProgress(date, covered))
        assertEquals(listOf("one"), store.incrementalSubmissionIds(date, completedOnly = true))
        assertEquals(listOf("one", "two"), store.incrementalSubmissionIds(date))
    }

    @Test fun schemaTenUpgradeConvertsConfirmedPhotosWithoutDeletingUnprocessedOnes() = isolated { store ->
        val name = store.databaseName
        val photos = PhotoMealStore(store.writableDatabase)
        val confirmed = photos.create(date, byteArrayOf(1))
        store.appendProgress("old", date, "测试已吃食物", listOf(confirmed))
        val old = store.nextPendingNutrition()!!
        store.applyNutritionExtraction(old, result(old, listOf(food("old:1"))).copy(foodItems = emptyList()))
        val pending = photos.create(date.plusDays(1), byteArrayOf(2))
        store.appendProgress("unprocessed", date.plusDays(1), "尚未分析的餐食", listOf(pending))
        store.saveSettings(CoachingSettings("训练计划", "饮食计划", "指令"))
        for (table in listOf("nutrition_turns", "daily_food_state", "photo_provenance")) store.writableDatabase.execSQL("DROP TABLE $table")
        store.writableDatabase.version = 10
        store.close()
        JournalStore(context, name).use { migrated ->
            assertEquals("训练计划", migrated.readSettings("").routine)
            assertEquals(0, PhotoMealStore(migrated.readableDatabase).image(confirmed)!!.size)
            assertEquals(1, PhotoMealStore(migrated.readableDatabase).image(pending)!!.size)
            migrated.appendProgress("new", date, "加餐", emptyList(), true)
            val next = migrated.nextPendingNutrition()!!
            assertEquals("legacy:$date", next.turn!!.previous!!.foodItems.single().id)
            assertTrue(migrated.nutritionPhotos(next).isEmpty())
        }
    }
}
