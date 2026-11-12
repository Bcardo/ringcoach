package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Explicit owner-requested retry of saved real input; never creates a journal event. */
@RunWith(AndroidJUnit4::class)
class ProgressRecoveryLiveTest {
    @Test fun retrySavedNutrition() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("recover_progress") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        JournalStore(context).use { store ->
            val date = LocalDate.now()
            val job = store.readableDatabase.rawQuery("""SELECT source_revision, source_diet, source_additional, unified
                FROM nutrition_extractions WHERE date = ?""", arrayOf(date.toString())).use {
                check(it.moveToFirst())
                PendingNutrition(date, it.getString(0), it.getString(1), it.getString(2), it.getInt(3) != 0)
            }
            val prompt = context.assets.open("nutrition-extraction.txt").bufferedReader().use { it.readText() }
            val key = QwenCredentials(context).read() ?: error("Missing saved key")
            val photos = store.nutritionPhotos(job)
            assertTrue("Saved food photos must reach the nutrition model", photos.isNotEmpty())
            val result = NutritionExtractor.extract(job, prompt, photos, key)
            assertTrue("Photo analysis should explain the food counted", result.foodSummary.isNotBlank())
            assertTrue("Photo plus supplied weight should produce an estimate", result.values["intake_kcal"]?.value != null)
            assertTrue(store.applyNutritionExtraction(job, result))
            println("PROGRESS_RECOVERY nutrition_saved=true has_intake=${result.values["intake_kcal"]?.value != null}")
        }
    }
}
