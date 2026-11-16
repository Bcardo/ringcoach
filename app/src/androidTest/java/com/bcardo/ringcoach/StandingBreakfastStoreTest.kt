package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StandingBreakfastStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 1, 5)
    private val rule = StandingBreakfast(date, "合成测试早餐", mapOf("protein_grams" to 20.0, "carbohydrate_grams" to 30.0, "fat_grams" to 5.0, "intake_kcal" to 250.0))
    private fun isolated(block: (JournalStore) -> Unit) {
        val name = "standing-breakfast-test-${UUID.randomUUID()}.db"
        try { JournalStore(context, name).use(block) } finally { context.deleteDatabase(name) }
    }
    @Test fun settingsAndNoJournalDefaultSurviveRestartWithoutNewJournalFacts() = isolated { store ->
        store.saveSettings(CoachingSettings("Routine", "Recipe", rule.block()))
        repeat(2) {
            val day = store.readCoachingContext(date, "").nutritionDays.single { it.date == date }
            assertEquals(250.0, day.result!!.values.getValue("intake_kcal").value!!, 0.0)
            assertEquals("default", day.breakfast!!.getString("mode"))
            assertTrue(store.readAll().isEmpty())
            assertNull(store.nextPendingNutrition()); assertNull(store.nextPendingCoaching())
        }
        store.close()
        assertEquals(rule, StandingBreakfast.parse(store.readSettings("").instructions))
        assertNull(store.readCoachingContext(date.minusDays(1), "").nutritionDays.first().result)
    }
    @Test fun extractionReceivesRuleAndReconciliationChangesNoFoodFacts() = isolated { store ->
        store.saveSettings(CoachingSettings("Routine", "Saved recipe", rule.block()))
        store.appendProgress("one", date, "早餐照旧；午餐已吃", emptyList(), true)
        val job = store.nextPendingNutrition()!!
        assertEquals(rule, job.breakfastRule); assertEquals("Saved recipe", job.dietPlan)
        val food = FoodFact("one:1", "一份午餐", listOf("one"), MealEstimate("测试午餐",
            mapOf("protein_grams" to 10.0, "carbohydrate_grams" to 12.0, "fat_grams" to 3.0, "intake_kcal" to 100.0), emptyList(), emptyList()))
        val base = NutritionExtraction(NUTRITION_FIELDS.associateWith { field ->
            val v = food.estimate.values[field]; NutritionValue(v, if (v == null) "missing" else "estimated", if (v == null) "" else "午餐已吃")
        }, "partial_day", "", emptyList(), foodItems = listOf(food))
        // Seed a pre-feature confirmed record, as on the owner's existing day.
        IncrementalNutritionStore(store.writableDatabase).seed(date, base, "", "早餐照旧；午餐已吃")
        store.writableDatabase.execSQL("UPDATE nutrition_turns SET status='complete' WHERE submission_id='one'")
        store.writableDatabase.execSQL("UPDATE nutrition_extractions SET status='complete',result_json=? WHERE date=?", arrayOf(base.toJson().toString(), date.toString()))
        val before = IncrementalNutritionStore(store.readableDatabase).confirmed(date)!!
        assertTrue(BreakfastReconciliation.reconcile(store, date, "fake-key") { _, _ ->
            val result = BreakfastResolution("confirmed_default", "早餐照旧", listOf("saved-journal:$date"), emptyList())
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", result.toJson().toString())))).toString()
        })
        val after = IncrementalNutritionStore(store.readableDatabase).confirmed(date)!!
        assertEquals(before.foodItems, after.foodItems); assertEquals(before.values, after.values)
        assertEquals(350.0, store.readCoachingContext(date, "").nutritionDays.first().result!!.values.getValue("intake_kcal").value!!, 0.0)
        assertFalse(BreakfastReconciliation.reconcile(store, date, "fake-key") { _, _ -> error("Do not replay reconciliation") })
    }
}
