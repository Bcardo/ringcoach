package com.bcardo.ringcoach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

internal object MealPhotos {
    fun captureFile(context: Context): File {
        val dir = File(context.cacheDir, "meal-capture").apply { mkdirs() }
        return File(dir, UUID.randomUUID().toString() + ".jpg").apply { createNewFile() }
    }

    fun captureUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".meal-photos", checkedCapture(context, file.path))

    fun checkedCapture(context: Context, path: String): File {
        val root = File(context.cacheDir, "meal-capture").canonicalFile
        val file = File(path).canonicalFile
        require(file.parentFile == root && file.extension == "jpg")
        return file
    }

    fun decode(context: Context, uri: Uri): ByteArray {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            val scale = minOf(1.0, 1280.0 / longest)
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            for (quality in listOf(85, 70, 50)) {
                val bytes = ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)); it.toByteArray()
                }
                if (bytes.size <= MAX_MEAL_PHOTO_BYTES) return bytes
            }
            error("Photo could not be bounded")
        } finally { bitmap.recycle() }
    }
}
