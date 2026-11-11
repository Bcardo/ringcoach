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

@RunWith(AndroidJUnit4::class)
class DailySubmissionLiveTest {
    @Test
    fun textOnlyMealPersistsThroughUnifiedSubmissionAndAnalysis() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_text_meal") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "daily-live-" + UUID.randomUUID() + ".db"
        val store = JournalStore(context, name)
        try {
            val date = LocalDate.now()
            val meals = PhotoMealStore(store.writableDatabase)
            val id = meals.createText(date)
            val draft = JournalDraft(date, training = "Synthetic training note")
            store.saveDailySubmission(draft, id, "我刚吃了100g熟米饭和50g去皮熟鸡胸肉，没有额外油或酱汁。只估算这份已吃完的餐食。", true)
            val meal = meals.get(id)!!
            val key = QwenCredentials(context).read() ?: error("Saved key required")
            val prompt = context.assets.open("photo-meal.txt").bufferedReader().use { it.readText() }
            val estimate = MealEstimate.decode(QwenClient.responseText(QwenClient.post(
                PhotoMealClient.request(meals.image(id)!!, meal.notes, prompt), key)))
            assertTrue(estimate.values["intake_kcal"]!! > 0)
            assertTrue(meals.apply(meal, estimate))
            assertEquals(draft, store.readAll().single().draft)
            assertEquals(1, store.readCoachingContext(date, "").nutritionDays.first().photoMeals.size)
            assertEquals(estimate.values["intake_kcal"], store.readCoachingContext(date, "").nutritionDays.first().result!!.values["intake_kcal"]!!.value)
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
