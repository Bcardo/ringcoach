package com.bcardo.ringcoach

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.time.LocalDate

internal data class PendingCoaching(val id: String, val date: LocalDate)

/** The journal append and queue insertion share a transaction; a saved card completes one submission. */
internal class ProgressCoachingStore(private val db: SQLiteDatabase) {
    companion object {
        fun createTable(db: SQLiteDatabase) = db.execSQL("""CREATE TABLE IF NOT EXISTS progress_coaching (
            submission_id TEXT PRIMARY KEY NOT NULL REFERENCES progress_submissions(id) ON DELETE CASCADE)""")

        fun migrate(db: SQLiteDatabase) {
            createTable(db)
            // Pre-queue cards can cover several turns. Preserve that accepted feedback without replaying it.
            val covered = mutableSetOf<String>()
            db.rawQuery("SELECT context_json FROM coach_cards", null).use { rows ->
                while (rows.moveToNext()) {
                    val json = JSONObject(rows.getString(0))
                    val ids = json.optJSONArray("nutrition_turn_ids") ?: continue
                    for (i in 0 until ids.length()) covered.add(ids.getString(i))
                }
            }
            db.rawQuery("SELECT submission_id FROM nutrition_turns", null).use { rows ->
                while (rows.moveToNext()) if (rows.getString(0) !in covered)
                    db.execSQL("INSERT OR IGNORE INTO progress_coaching VALUES (?)", arrayOf(rows.getString(0)))
            }
        }
    }

    fun enqueue(id: String) = db.execSQL("INSERT INTO progress_coaching VALUES (?)", arrayOf(id))

    fun next(): PendingCoaching? = db.rawQuery("""SELECT p.id,p.date FROM progress_coaching q
        JOIN progress_submissions p ON p.id=q.submission_id
        LEFT JOIN coach_cards c ON c.id=p.id WHERE c.id IS NULL ORDER BY p.rowid LIMIT 1""", null).use {
        if (it.moveToFirst()) PendingCoaching(it.getString(0), LocalDate.parse(it.getString(1))) else null
    }
}
