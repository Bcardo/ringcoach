package com.bcardo.ringcoach

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

/** Event queue, confirmed state and photo-release boundary live in the same SQLite transaction. */
internal class IncrementalNutritionStore(private val db: SQLiteDatabase) {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS nutrition_turns (
                submission_id TEXT PRIMARY KEY NOT NULL REFERENCES progress_submissions(id) ON DELETE CASCADE,
                date TEXT NOT NULL, source_diet TEXT NOT NULL, source_additional TEXT NOT NULL,
                status TEXT NOT NULL, result_json TEXT)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS daily_food_state (
                date TEXT PRIMARY KEY NOT NULL, submission_id TEXT, through_rowid INTEGER NOT NULL DEFAULT 0,
                result_json TEXT NOT NULL, source_diet TEXT NOT NULL, source_additional TEXT NOT NULL)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS photo_provenance (
                photo_id TEXT PRIMARY KEY NOT NULL REFERENCES photo_meals(id) ON DELETE CASCADE,
                sha256 TEXT NOT NULL, released_at TEXT)""")
        }

        fun rememberPhoto(db: SQLiteDatabase, id: String, jpeg: ByteArray) {
            if (jpeg.isEmpty()) return
            val hash = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }
            db.execSQL("INSERT OR IGNORE INTO photo_provenance(photo_id, sha256) VALUES (?, ?)", arrayOf(id, hash))
        }
    }

    fun seed(date: LocalDate, result: NutritionExtraction, diet: String, additional: String) {
        val photos = PhotoMealStore(db)
        photos.list(date, date).filter { it.consumed && it.hasPhoto }.forEach {
            rememberPhoto(db, it.id, requireNotNull(photos.image(it.id)))
        }
        val state = DailyFoodState.bootstrap(date, result)
        db.execSQL("""INSERT OR IGNORE INTO daily_food_state(date, result_json, source_diet, source_additional)
            VALUES (?, ?, ?, ?)""", arrayOf(date.toString(), state.toJson().toString(), diet, additional + "\n" + DailyFoodState.evidenceContext(state)))
    }

    fun enqueue(id: String, date: LocalDate, diet: String, additional: String) {
        db.execSQL("INSERT INTO nutrition_turns VALUES (?, ?, ?, ?, 'pending', NULL)", arrayOf(id, date.toString(), diet, additional))
    }

    fun confirmed(date: LocalDate): NutritionExtraction? = db.rawQuery(
        "SELECT result_json, source_diet, source_additional FROM daily_food_state WHERE date=?", arrayOf(date.toString()),
    ).use {
        if (!it.moveToFirst()) null else NutritionExtractor.decode(it.getString(0), nutritionSource(it.getString(1), it.getString(2)))
    }

    /** Convert confirmed legacy results before releasing their app-private images; unresolved inputs stay retryable. */
    fun migrateConfirmedPhotos() {
        val dates = db.rawQuery("SELECT DISTINCT date FROM photo_meals WHERE consumed=1 AND length(photo_jpeg)>0", null).use { rows ->
            buildList { while (rows.moveToNext()) add(LocalDate.parse(rows.getString(0))) }
        }
        val photos = PhotoMealStore(db)
        for (date in dates) {
            val raw = db.rawQuery("SELECT source_diet, source_additional, status, result_json, unified FROM nutrition_extractions WHERE date=?",
                arrayOf(date.toString())).use { row ->
                if (!row.moveToFirst()) null else (0..4).map { if (row.isNull(it)) null else row.getString(it) }
            }
            val diet = raw?.get(0) ?: ""
            val additional = raw?.get(1) ?: ""
            val result = raw?.get(3)?.let { NutritionExtractor.decode(it, nutritionSource(diet, additional)) }
            val base = NutritionDay(date, raw?.get(2) ?: "missing", result)
            val meals = photos.list(date, date)
            val unified = raw?.get(4)?.toIntOrNull() == 1
            val day = if (unified) base else MealNutrition.merge(base, meals, photos.mode(date))
            val confirmed = day.result ?: continue
            if (day.status != "complete" || day.photoConflict || confirmed.values["intake_kcal"]?.value == null ||
                confirmed.unresolved.isNotEmpty() || (!unified && meals.any { it.consumed && it.status != "complete" })) continue
            seed(date, confirmed, diet, additional)
            meals.filter { it.consumed }.forEach { release(it.id) }
        }
    }

    private fun release(id: String) {
        val image = PhotoMealStore(db).image(id) ?: return
        rememberPhoto(db, id, image)
        db.execSQL("UPDATE photo_meals SET photo_jpeg=X'' WHERE id=?", arrayOf(id))
        db.execSQL("UPDATE photo_provenance SET released_at=? WHERE photo_id=?", arrayOf(Instant.now().toString(), id))
    }

    fun next(): PendingNutrition? = db.rawQuery("""SELECT p.rowid, p.id, p.date, p.text, p.photo_ids,
        t.source_diet, t.source_additional, t.status FROM nutrition_turns t JOIN progress_submissions p ON p.id=t.submission_id
        WHERE t.status != 'complete' ORDER BY p.rowid LIMIT 1""", null).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val date = LocalDate.parse(cursor.getString(2))
        val state = db.rawQuery("SELECT submission_id, result_json, source_diet, source_additional FROM daily_food_state WHERE date=?",
            arrayOf(date.toString())).use { row ->
            if (!row.moveToFirst()) null else row.getString(0) to NutritionExtractor.decode(row.getString(1), nutritionSource(row.getString(2), row.getString(3)))
        }
        val photos = JSONArray(cursor.getString(4))
        val photoIds = (0 until photos.length()).map { photos.getString(it) }
        val matches = photoIds.associateWith { id ->
            db.rawQuery("""SELECT DISTINCT p.id FROM photo_provenance old JOIN photo_provenance current ON old.sha256=current.sha256
                JOIN progress_submissions p ON instr(p.photo_ids, old.photo_id)>0
                WHERE current.photo_id=? AND p.date=? AND p.rowid<?""",
                arrayOf(id, date.toString(), cursor.getLong(0).toString())).use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            }
        }.filterValues { it.isNotEmpty() }
        PendingNutrition(date, cursor.getString(1), cursor.getString(5), cursor.getString(6) + "\n" + DailyFoodState.evidenceContext(state?.second), true,
            NutritionTurn(cursor.getString(1), cursor.getString(3), photoIds, cursor.getLong(0), state?.first, state?.second, cursor.getString(7), matches))
    }

    fun photos(job: PendingNutrition): List<Pair<String, ByteArray>> {
        val turn = requireNotNull(job.turn)
        val store = PhotoMealStore(db)
        // A day without a successful old result may still need its unconverted legacy inputs once.
        val bootstrap = if (turn.previous == null) store.list(job.date, job.date)
            .filter { it.consumed && it.hasPhoto && job.source.contains("[照片 ${it.id}]") }.map { it.id } else emptyList()
        return (turn.photoIds + bootstrap).distinct().map { id ->
            val data = requireNotNull(store.image(id)) { "Missing pending photo" }
            require(data.isNotEmpty()) { "Pending photo released before conversion" }
            id to data
        }
    }

    fun apply(job: PendingNutrition, result: NutritionExtraction): Boolean {
        DailyFoodState.validate(job, result)
        val turn = requireNotNull(job.turn)
        db.beginTransaction()
        try {
            val status = db.rawQuery("SELECT status FROM nutrition_turns WHERE submission_id=?", arrayOf(turn.id)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (status == "complete") return false
            require(status in setOf("pending", "failed")) { "Unknown nutrition turn" }
            val previous = db.rawQuery("SELECT submission_id FROM daily_food_state WHERE date=?", arrayOf(job.date.toString())).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (previous != turn.previousId) return false
            val first = db.rawQuery("""SELECT t.submission_id FROM nutrition_turns t JOIN progress_submissions p ON p.id=t.submission_id
                WHERE t.date=? AND t.status!='complete' ORDER BY p.rowid LIMIT 1""", arrayOf(job.date.toString())).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (first != turn.id) return false
            val raw = result.toJson().toString()
            db.execSQL("""INSERT INTO daily_food_state VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(date) DO UPDATE SET submission_id=excluded.submission_id, through_rowid=excluded.through_rowid,
                result_json=excluded.result_json, source_diet=excluded.source_diet, source_additional=excluded.source_additional""",
                arrayOf<Any>(job.date.toString(), turn.id, turn.rowId, raw, job.diet, job.additional))
            db.execSQL("UPDATE nutrition_turns SET status='complete', result_json=? WHERE submission_id=?", arrayOf(raw, turn.id))
            db.execSQL("""UPDATE nutrition_extractions SET status='complete', result_json=?, source_diet=?, source_additional=?
                WHERE date=? AND source_revision=?""", arrayOf(raw, job.diet, job.additional, job.date.toString(), job.revision))
            val photos = PhotoMealStore(db)
            val alreadyConvertedLegacy = turn.previous != null && turn.previousId == null
            val toRelease = photos.list(job.date, job.date).filter {
                it.consumed && (it.id in turn.photoIds || (alreadyConvertedLegacy && job.source.contains("[照片 ${it.id}]")) ||
                    (turn.previous == null && job.source.contains("[照片 ${it.id}]")))
            }
            for (photo in toRelease) {
                release(photo.id)
            }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun fail(job: PendingNutrition) {
        db.execSQL("UPDATE nutrition_turns SET status='failed' WHERE submission_id=? AND status!='complete'", arrayOf(job.revision))
    }

    fun retryFailed() = db.execSQL("UPDATE nutrition_turns SET status='pending' WHERE status='failed'")
}
