package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** One explicitly opted-in provider request; all journal data lives in a disposable database. */
@RunWith(AndroidJUnit4::class)
class NutritionLiveTest {
    @Test
    fun estimatesOnlyConsumedFoodAndPreservesPartialDayCoverage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_nutrition_parse") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "nutrition-live-" + UUID.randomUUID() + ".db"
        val store = JournalStore(context, name)
        try {
            val day = LocalDate.now()
            store.save(JournalDraft(day,
                diet = "早餐已经吃了2个鸡蛋（每个约50g）和100g原味无糖酸奶。明天计划全天摄入1500kcal，蛋白质200g。",
                additional = "今天晨起体重70kg，今天全天消耗由手表估计为1900kcal。"))
            val job = store.nextPendingNutrition()!!
            val prompt = context.assets.open("nutrition-extraction.txt").bufferedReader().use { it.readText() }
            val key = QwenCredentials(context).read() ?: error("Saved key required")
            val response = QwenClient.post(NutritionExtractor.request(job, prompt), key)
            val result = NutritionExtractor.decode(QwenClient.responseText(response), job.source)
            assertEquals("partial_day", result.coverage)
            assertEquals(70.0, result.values.getValue("weight_kg").value!!, 0.0)
            assertEquals(1900.0, result.values.getValue("expenditure_kcal").value!!, 0.0)
            val intake = result.values.getValue("intake_kcal")
            assertEquals(result.toJson().toString(), "estimated", intake.kind)
            assertTrue(intake.value!! in 1.0..1000.0)
            assertTrue(result.values.getValue("protein_grams").value!! < 200.0)
            assertTrue(store.applyNutritionExtraction(job, result))
            val restored = store.readCoachingContext(day, "").nutritionDays.first().result!!
            assertEquals(result, restored)
            assertEquals(null, store.nextPendingNutrition())
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }
}
