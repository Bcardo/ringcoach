package com.bcardo.ringcoach

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID

/** Two opted-in vision requests using a synthetic drawing; never adds meals to the owner's database. */
@RunWith(AndroidJUnit4::class)
class PhotoMealLiveTest {
    @Test
    fun visionAcceptsImageAndPortionCorrectionUpdatesSameMeal() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_photo_meal") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "photo-live-" + UUID.randomUUID() + ".db"
        val store = JournalStore(context, name)
        try {
            val image = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            canvas.drawColor(Color.LTGRAY)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.WHITE; canvas.drawCircle(320f, 270f, 170f, paint)
            paint.color = Color.rgb(240, 230, 200); canvas.drawOval(190f, 190f, 360f, 370f, paint)
            paint.color = Color.WHITE; canvas.drawOval(330f, 210f, 460f, 330f, paint)
            paint.color = Color.YELLOW; canvas.drawCircle(395f, 270f, 32f, paint)
            paint.color = Color.BLACK; paint.textSize = 24f
            canvas.drawText("Synthetic rice and egg meal", 90f, 60f, paint)
            val jpeg = ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }
            image.recycle()
            val key = QwenCredentials(context).read() ?: error("Saved key required")
            val prompt = context.assets.open("photo-meal.txt").bufferedReader().use { it.readText() }
            val photos = PhotoMealStore(store.writableDatabase)
            val date = LocalDate.now()
            val id = photos.create(date, jpeg)
            val first = photos.edit(id, "合成示意图，米饭为熟重200g，鸡蛋可食部分50g，酱汁含油10g。我全部吃完了。按这些明确份量估算。", true)!!
            fun analyze(meal: PhotoMeal): MealEstimate = MealEstimate.decode(QwenClient.responseText(
                QwenClient.post(PhotoMealClient.request(jpeg, meal.notes, prompt), key)))
            val full = analyze(first)
            assertTrue(full.values["intake_kcal"]!! > 0)
            assertTrue(photos.apply(first, full))
            val corrected = photos.edit(id, "修正：这张合成示意图中，我只吃了熟米饭100g和鸡蛋25g；酱汁全部剩下，油完全没吃。只统计这些明确吃掉的份量。", true)!!
            val half = analyze(corrected)
            assertTrue(half.values["intake_kcal"]!! < full.values["intake_kcal"]!! * 0.8)
            assertTrue(photos.apply(corrected, half))
            assertEquals(1, photos.list(date, date).size)
            assertEquals(half.values["intake_kcal"], store.readCoachingContext(date, "").nutritionDays.first().result!!.values["intake_kcal"]!!.value)
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
