package com.bcardo.ringcoach

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID

/** Uses a disposable DB and synthetic label; never writes synthetic food into the owner's journal. */
@RunWith(AndroidJUnit4::class)
class ProgressSubmissionLiveTest {
    @Test fun additionPhotoTextDeduplicationAndCorrection() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_progress") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "progress-live-${UUID.randomUUID()}.db"
        val store = JournalStore(context, name)
        try {
            val key = QwenCredentials(context).read() ?: error("Saved key required")
            val prompt = context.assets.open("nutrition-extraction.txt").bufferedReader().use { it.readText() }
            val date = LocalDate.now()
            fun analyze(kcal: Double, protein: Double) {
                val job = store.nextPendingNutrition()!!
                val result = NutritionExtractor.extract(job, prompt, store.nutritionPhotos(job), key)
                assertEquals(kcal, result.values.getValue("intake_kcal").value!!, 0.1)
                assertEquals(protein, result.values.getValue("protein_grams").value!!, 0.1)
                assertTrue(store.applyNutritionExtraction(job, result))
            }
            store.appendProgress("breakfast", date, "早餐已经吃完。早餐实测摄入200kcal，蛋白质10g。", emptyList())
            analyze(200.0, 10.0)
            val image = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
            Canvas(image).apply {
                drawColor(Color.WHITE)
                val paint = Paint().apply { color = Color.BLACK; textSize = 32f }
                drawText("TEST DRINK - ONE BOTTLE", 20f, 70f, paint)
                drawText("Energy 300 kcal", 20f, 140f, paint)
                drawText("Protein 25 g", 20f, 210f, paint)
            }
            val jpeg = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            image.recycle()
            val photo = PhotoMealStore(store.writableDatabase).create(date, jpeg)
            store.appendProgress("lunch", date, "午餐喝完图中一整瓶饮料。标签每瓶300kcal、蛋白质25g。文字和照片是同一瓶，只计一次。", listOf(photo))
            analyze(500.0, 35.0)
            store.appendProgress("correction", date, "更正早餐：实际摄入250kcal、蛋白质15g，替换之前早餐的200kcal和10g。午餐不变。这不是新增一餐。", emptyList())
            analyze(550.0, 40.0)
            assertEquals(1, store.readCoachingContext(date, "").nutritionDays.first().photoMeals.size)
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
