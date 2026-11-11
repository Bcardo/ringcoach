package com.bcardo.ringcoach

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class PhotoMealStore(private val db: SQLiteDatabase) {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE photo_meals (
                id TEXT PRIMARY KEY NOT NULL, date TEXT NOT NULL, revision TEXT NOT NULL,
                photo_jpeg BLOB NOT NULL, notes TEXT NOT NULL, consumed INTEGER NOT NULL,
                status TEXT NOT NULL, result_json TEXT, created_at INTEGER NOT NULL,
                model TEXT NOT NULL DEFAULT '', prompt_version TEXT NOT NULL DEFAULT '')""")
            db.execSQL("CREATE INDEX photo_meal_date ON photo_meals(date, created_at)")
            db.execSQL("""CREATE TABLE meal_revisions (
                meal_id TEXT NOT NULL REFERENCES photo_meals(id) ON DELETE CASCADE,
                revision TEXT NOT NULL, notes TEXT NOT NULL, consumed INTEGER NOT NULL,
                status TEXT NOT NULL, result_json TEXT, model TEXT NOT NULL, prompt_version TEXT NOT NULL,
                PRIMARY KEY(meal_id, revision))""")
            db.execSQL("""CREATE TABLE nutrition_reconciliation (
                date TEXT PRIMARY KEY NOT NULL, mode TEXT NOT NULL CHECK(mode IN ('included', 'separate')))""")
        }
    }

    private fun archive(id: String) {
        db.execSQL("""INSERT INTO meal_revisions
            SELECT id, revision, notes, consumed, status, result_json, model, prompt_version FROM photo_meals WHERE id = ?
            ON CONFLICT(meal_id, revision) DO UPDATE SET status = excluded.status,
                result_json = excluded.result_json, model = excluded.model, prompt_version = excluded.prompt_version
        """.trimIndent(), arrayOf(id))
    }

    fun create(date: LocalDate, jpeg: ByteArray): String {
        require(jpeg.isNotEmpty() && jpeg.size <= MAX_MEAL_PHOTO_BYTES)
        return insert(date, jpeg)
    }

    fun createText(date: LocalDate): String = insert(date, byteArrayOf())

    private fun insert(date: LocalDate, jpeg: ByteArray): String {
        val id = UUID.randomUUID().toString()
        transaction {
            db.insertOrThrow("photo_meals", null, ContentValues().apply {
                put("id", id); put("date", date.toString()); put("revision", UUID.randomUUID().toString())
                put("photo_jpeg", jpeg); put("notes", ""); put("consumed", 0); put("status", "draft")
                put("created_at", System.currentTimeMillis())
            })
            archive(id)
            db.delete("nutrition_reconciliation", "date = ?", arrayOf(date.toString()))
        }
        return id
    }

    fun list(start: LocalDate, end: LocalDate): List<PhotoMeal> = db.rawQuery("""
        SELECT id, date, revision, notes, consumed, status, result_json, created_at, model, prompt_version, length(photo_jpeg)
        FROM photo_meals WHERE date BETWEEN ? AND ? ORDER BY created_at DESC, id DESC
    """.trimIndent(), arrayOf(start.toString(), end.toString())).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(PhotoMeal(cursor.getString(0), LocalDate.parse(cursor.getString(1)),
                cursor.getString(2), cursor.getString(3), cursor.getInt(4) != 0, cursor.getString(5),
                if (cursor.isNull(6)) null else MealEstimate.decode(cursor.getString(6)),
                Instant.ofEpochMilli(cursor.getLong(7)), cursor.getString(8), cursor.getString(9), cursor.getInt(10) > 0))
        }
    }

    fun get(id: String): PhotoMeal? {
        val date = db.rawQuery("SELECT date FROM photo_meals WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) LocalDate.parse(it.getString(0)) else null
        } ?: return null
        return list(date, date).firstOrNull { it.id == id }
    }

    fun image(id: String): ByteArray? = db.rawQuery("SELECT photo_jpeg FROM photo_meals WHERE id = ?", arrayOf(id)).use {
        if (it.moveToFirst()) it.getBlob(0) else null
    }

    fun edit(id: String, notes: String, consumed: Boolean): PhotoMeal? {
        require(notes.length <= 4000)
        transaction {
            val old = get(id) ?: return@transaction
            if (old.notes == notes.trim() && old.consumed == consumed) return@transaction
            db.update("photo_meals", ContentValues().apply {
                put("notes", notes.trim()); put("consumed", if (consumed) 1 else 0)
                put("revision", UUID.randomUUID().toString()); put("status", "draft"); putNull("result_json")
                put("model", ""); put("prompt_version", "")
            }, "id = ?", arrayOf(id))
            archive(id)
            db.delete("nutrition_reconciliation", "date = ?", arrayOf(old.date.toString()))
        }
        return get(id)
    }

    fun markPending(meal: PhotoMeal): Boolean {
        require(meal.consumed)
        return updateResult(meal, "pending", null)
    }

    fun apply(meal: PhotoMeal, result: MealEstimate): Boolean = updateResult(meal,
        if (result.values["intake_kcal"] == null) "needs_details" else "complete", result)

    fun fail(meal: PhotoMeal): Boolean = updateResult(meal, "failed", null)

    private fun updateResult(meal: PhotoMeal, status: String, result: MealEstimate?): Boolean {
        var updated = false
        transaction {
            updated = db.update("photo_meals", ContentValues().apply {
                put("status", status)
                if (result == null) putNull("result_json") else put("result_json", result.toJson().toString())
                put("model", QWEN_MODEL); put("prompt_version", PHOTO_PROMPT_VERSION)
            }, "id = ? AND revision = ?", arrayOf(meal.id, meal.revision)) == 1
            if (updated) {
                archive(meal.id)
                db.delete("nutrition_reconciliation", "date = ?", arrayOf(meal.date.toString()))
            }
        }
        return updated
    }

    fun remove(id: String) = transaction {
        val meal = get(id) ?: return@transaction
        db.delete("photo_meals", "id = ?", arrayOf(id))
        db.delete("nutrition_reconciliation", "date = ?", arrayOf(meal.date.toString()))
    }

    fun mode(date: LocalDate): String? = db.rawQuery("SELECT mode FROM nutrition_reconciliation WHERE date = ?",
        arrayOf(date.toString())).use { if (it.moveToFirst()) it.getString(0) else null }

    fun reconcile(date: LocalDate, mode: String) {
        require(mode in setOf("included", "separate"))
        db.execSQL("INSERT INTO nutrition_reconciliation VALUES (?, ?) ON CONFLICT(date) DO UPDATE SET mode = excluded.mode",
            arrayOf(date.toString(), mode))
    }

    private fun transaction(block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
}
