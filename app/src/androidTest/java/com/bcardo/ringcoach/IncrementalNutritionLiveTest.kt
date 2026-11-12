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

/** Three synthetic turns in a disposable DB: image values are supplied only in the image. */
@RunWith(AndroidJUnit4::class)
class IncrementalNutritionLiveTest {
    @Test fun imageOnceThenSnackThenCorrectionUsesConvertedState() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_incremental") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "incremental-live-${UUID.randomUUID()}.db"
        var store = JournalStore(context, name)
        try {
            val key = QwenCredentials(context).read() ?: error("Saved key required")
            val prompt = context.assets.open("nutrition-extraction.txt").bufferedReader().use { it.readText() }
            val date = LocalDate.now()
            val bitmap = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                val paint = Paint().apply { color = Color.BLACK; textSize = 36f }
                listOf("SYNTHETIC TEST MEAL - 1 SERVING", "Energy 300 kcal", "Protein 25 g", "Carbohydrate 35 g", "Fat 6.7 g")
                    .forEachIndexed { index, line -> drawText(line, 20f, 70f + index * 80f, paint) }
            }
            val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            bitmap.recycle()
            val photo = PhotoMealStore(store.writableDatabase).create(date, jpeg)
            store.appendProgress("meal", date, "午餐已吃完照片中的一整份测试餐。营养值按图中标签。", listOf(photo), true)
            fun analyze(kcal: Double, protein: Double): NutritionExtraction {
                val job = store.nextPendingNutrition()!!
                val images = store.nutritionPhotos(job)
                if (job.revision == "meal") assertEquals(1, images.size) else {
                    assertTrue(images.isEmpty())
                    assertFalse(NutritionExtractor.request(job, prompt, images).toString().contains("data:image/"))
                }
                var lastReply = ""
                val result = try {
                    NutritionExtractor.extract(job, prompt, images, key) { request, credential ->
                        QwenClient.post(request, credential).also { lastReply = it }
                    }
                } catch (e: Exception) {
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                        putString("stream", "Synthetic turn ${job.revision} failed:\n${runCatching { QwenClient.responseText(lastReply) }.getOrDefault("No valid reply")}\n")
                    })
                    throw e
                }
                assertEquals(kcal, result.values.getValue("intake_kcal").value!!, 1.0)
                assertEquals(protein, result.values.getValue("protein_grams").value!!, 0.2)
                assertTrue(store.applyNutritionExtraction(job, result))
                return result
            }
            val first = analyze(300.0, 25.0)
            val meal = first.foodItems.single()
            assertEquals(0, PhotoMealStore(store.readableDatabase).image(photo)!!.size)
            store.close(); store = JournalStore(context, name)
            store.appendProgress("snack", date, "刚吃了一份加餐，实测200kcal、蛋白质10g、碳水30g、脂肪4.4g。", emptyList(), true)
            val second = analyze(500.0, 35.0)
            assertEquals(meal, second.foodItems.first { it.id == meal.id })
            val snack = second.foodItems.first { it.id != meal.id }
            store.appendProgress("correction", date, "更正：午餐只吃了半份，请把先前这份午餐减半。加餐不变。", emptyList(), true)
            val third = analyze(350.0, 22.5)
            assertEquals(snack, third.foodItems.first { it.id == snack.id })
            assertEquals(listOf(meal.id), third.revisedFoodIds)
            assertEquals(0, PhotoMealStore(store.readableDatabase).image(photo)!!.size)
            assertNull(store.nextPendingNutrition())
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
