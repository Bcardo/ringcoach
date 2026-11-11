package com.bcardo.ringcoach

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MealPhotosTest {
    @Test
    fun cameraUriDecodesIntoBoundedReencodedJpegAndRejectsUnrelatedPaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = MealPhotos.captureFile(context)
        try {
            val original = Bitmap.createBitmap(3000, 1800, Bitmap.Config.ARGB_8888)
            original.eraseColor(Color.LTGRAY)
            file.outputStream().use { original.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            original.recycle()
            val uri = MealPhotos.captureUri(context, file)
            assertTrue(uri.toString().startsWith("content://com.bcardo.ringcoach.meal-photos/"))
            val jpeg = MealPhotos.decode(context, uri)
            assertTrue(jpeg.size <= MAX_MEAL_PHOTO_BYTES)
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            assertTrue(maxOf(decoded.width, decoded.height) <= 1280)
            decoded.recycle()
            try {
                MealPhotos.checkedCapture(context, context.filesDir.resolve("unrelated.jpg").path)
                throw AssertionError("Unrelated file accepted for capture cleanup")
            } catch (_: IllegalArgumentException) { }
        } finally { file.delete() }
    }
}
