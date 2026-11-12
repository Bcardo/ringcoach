package com.bcardo.ringcoach

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

internal class ProgressWorkStore(private val db: SQLiteDatabase) {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS progress_health_snapshots (
                date TEXT PRIMARY KEY NOT NULL,captured_at TEXT NOT NULL,summary_json TEXT NOT NULL)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS progress_work_state (
                id INTEGER PRIMARY KEY CHECK(id=1),stage TEXT NOT NULL,date TEXT,submission_id TEXT,
                error_code TEXT,updated_at TEXT NOT NULL)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS progress_work_retries (
                task_key TEXT PRIMARY KEY NOT NULL,error_code TEXT NOT NULL,attempts INTEGER NOT NULL)""")
        }
    }

    fun hasWork(): Boolean = db.rawQuery("""SELECT 1 FROM progress_coaching q LEFT JOIN coach_cards c ON c.id=q.submission_id WHERE c.id IS NULL
        UNION ALL SELECT 1 FROM nutrition_extractions WHERE status='pending'
        UNION ALL SELECT 1 FROM workout_extractions WHERE status='pending' LIMIT 1""", null).use { it.moveToFirst() }

    fun stage(stage: String, date: LocalDate? = null, id: String? = null, error: String? = null) {
        db.execSQL("INSERT OR REPLACE INTO progress_work_state VALUES (1,?,?,?,?,?)",
            arrayOf(stage, date?.toString(), id, error, Instant.now().toString()))
        ProgressRuntime.changed()
    }

    fun state(): JSONObject = db.rawQuery("SELECT stage,date,submission_id,error_code,updated_at FROM progress_work_state WHERE id=1", null).use {
        if (!it.moveToFirst()) JSONObject().put("stage", "idle") else JSONObject().apply {
            listOf("stage", "date", "submission_id", "error_code", "updated_at").forEachIndexed { i, key -> put(key, if (it.isNull(i)) JSONObject.NULL else it.getString(i)) }
        }
    }

    fun health(date: LocalDate): JSONObject? = db.rawQuery("SELECT summary_json FROM progress_health_snapshots WHERE date=?", arrayOf(date.toString())).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }

    fun putHealth(date: LocalDate, telemetry: JSONObject) {
        val retained = ProgressHealth.retained(telemetry)
        db.execSQL("INSERT OR REPLACE INTO progress_health_snapshots VALUES (?,?,?)",
            arrayOf(date.toString(), retained.getString("captured_at"), retained.toString()))
        db.execSQL("""DELETE FROM progress_health_snapshots WHERE date<? AND date NOT IN
            (SELECT p.date FROM progress_coaching q JOIN progress_submissions p ON p.id=q.submission_id
            LEFT JOIN coach_cards c ON c.id=p.id WHERE c.id IS NULL)""", arrayOf(LocalDate.now().minusDays(7).toString()))
        ProgressRuntime.changed()
    }

    fun failure(key: String, code: String): Int {
        db.execSQL("""INSERT INTO progress_work_retries VALUES (?,?,1) ON CONFLICT(task_key)
            DO UPDATE SET error_code=excluded.error_code,attempts=attempts+1""", arrayOf(key, code))
        return db.rawQuery("SELECT attempts FROM progress_work_retries WHERE task_key=?", arrayOf(key)).use { it.moveToFirst(); it.getInt(0) }
    }

    fun error(key: String): String? = db.rawQuery("SELECT error_code FROM progress_work_retries WHERE task_key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    fun clearRetry(key: String) = db.execSQL("DELETE FROM progress_work_retries WHERE task_key=?", arrayOf(key))
    fun canRetry(key: String): Boolean = db.rawQuery("SELECT attempts,error_code FROM progress_work_retries WHERE task_key=?", arrayOf(key)).use {
        !it.moveToFirst() || (it.getInt(0) < 3 && it.getString(1) in setOf("network", "provider_temporary", "provider_quota"))
    }
    fun clearRetries() = db.execSQL("DELETE FROM progress_work_retries")

    fun resetTransientFailures() {
        val tasks = db.rawQuery("""SELECT task_key FROM progress_work_retries WHERE attempts<3
            AND error_code IN ('network','provider_temporary','provider_quota')""", null).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        for (key in tasks) {
            val revision = key.substringAfter(':')
            if (key.startsWith("nutrition:")) {
                db.execSQL("UPDATE nutrition_turns SET status='pending' WHERE submission_id=? AND status='failed'", arrayOf(revision))
                db.execSQL("UPDATE nutrition_extractions SET status='pending' WHERE source_revision=? AND status='failed'", arrayOf(revision))
            }
            if (key.startsWith("workout:")) db.execSQL("UPDATE workout_extractions SET status='pending' WHERE source_revision=? AND status='failed'", arrayOf(revision))
        }
    }
}
