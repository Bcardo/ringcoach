package com.bcardo.ringcoach

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** New submissions update a confirmed workout state atomically; legacy pending input stays explicit. */
internal class IncrementalWorkoutStore(private val db: SQLiteDatabase) {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS workout_turns (
                submission_id TEXT PRIMARY KEY NOT NULL REFERENCES progress_submissions(id) ON DELETE CASCADE,
                date TEXT NOT NULL,status TEXT NOT NULL,result_json TEXT)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS daily_workout_state (
                date TEXT PRIMARY KEY NOT NULL,submission_id TEXT,through_rowid INTEGER NOT NULL,
                result_json TEXT NOT NULL)""")
        }
    }

    fun prepareAppend(date: LocalDate, previousTraining: String): Boolean {
        val day = date.toString()
        if (db.rawQuery("SELECT 1 FROM daily_workout_state WHERE date=?", arrayOf(day)).use { it.moveToFirst() }) return true
        val legacy = db.rawQuery("SELECT status,unresolved_json FROM workout_extractions WHERE date=?", arrayOf(day)).use {
            if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
        }
        // Never pretend an old pending/failed recognition was confirmed during migration.
        if (legacy != null && legacy.first != "complete") return false
        if (legacy == null && previousTraining.isNotBlank()) return false
        val records = db.rawQuery("SELECT ordinal,exercise,performance,evidence,status FROM exercise_logs WHERE date=? ORDER BY ordinal", arrayOf(day)).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val evidence = rows.getString(3)
                    if (evidence.isNotBlank() && previousTraining.contains(evidence)) add(ConfirmedWorkout("legacy:$day:${rows.getInt(0)}",
                        WorkoutObservation(rows.getString(1), rows.getString(2), evidence, rows.getString(4)), listOf("legacy")))
                }
            }
        }
        val unresolved = JSONArray(legacy?.second ?: "[]").let { quotes -> (0 until quotes.length()).mapNotNull { index ->
            quotes.getString(index).takeIf { it.isNotBlank() && previousTraining.contains(it) }
                ?.let { WorkoutClarification("legacy-clarification:$day:$index", it, "legacy") }
        } }
        val through = db.rawQuery("SELECT coalesce(max(rowid),0) FROM progress_submissions WHERE date=?", arrayOf(day)).use { it.moveToFirst(); it.getLong(0) }
        db.execSQL("INSERT INTO daily_workout_state VALUES (?,NULL,?,?)", arrayOf<Any>(day, through, WorkoutDayState(records, unresolved).toJson().toString()))
        return true
    }

    fun invalidate(date: LocalDate) {
        db.execSQL("UPDATE workout_turns SET status='superseded' WHERE date=? AND status!='complete'", arrayOf(date.toString()))
        db.delete("daily_workout_state", "date=?", arrayOf(date.toString()))
    }
    fun enqueue(id: String, date: LocalDate) = db.execSQL("INSERT INTO workout_turns VALUES (?,?,'pending',NULL)", arrayOf(id, date.toString()))

    fun next(): PendingWorkoutTurn? = db.rawQuery("""SELECT p.id,p.date,p.rowid,p.text,t.status,
        d.submission_id,d.result_json FROM workout_turns t JOIN progress_submissions p ON p.id=t.submission_id
        JOIN daily_workout_state d ON d.date=t.date WHERE t.status IN ('pending','failed') ORDER BY p.rowid LIMIT 1""", null).use { row ->
        if (!row.moveToFirst()) null else PendingWorkoutTurn(row.getString(0), LocalDate.parse(row.getString(1)), row.getLong(2),
            row.getString(3), if (row.isNull(5)) null else row.getString(5), WorkoutDayState.decode(JSONObject(row.getString(6))), row.getString(4))
    }

    fun apply(turn: PendingWorkoutTurn, result: WorkoutDayState): Boolean {
        val day = turn.date.toString()
        db.beginTransaction()
        try {
            val status = db.rawQuery("SELECT status FROM workout_turns WHERE submission_id=?", arrayOf(turn.id)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (status in setOf("complete", "superseded")) return false
            require(status in setOf("pending", "failed"))
            val previous = db.rawQuery("SELECT submission_id FROM daily_workout_state WHERE date=?", arrayOf(day)).use {
                if (!it.moveToFirst()) return false
                if (it.isNull(0)) null else it.getString(0)
            }
            if (previous != turn.previousId) return false
            val first = db.rawQuery("""SELECT t.submission_id FROM workout_turns t JOIN progress_submissions p ON p.id=t.submission_id
                WHERE t.date=? AND t.status IN ('pending','failed') ORDER BY p.rowid LIMIT 1""", arrayOf(day)).use { if (it.moveToFirst()) it.getString(0) else null }
            if (first != turn.id) return false
            val revision = db.rawQuery("SELECT training_revision FROM journal_entries WHERE date=?", arrayOf(day)).use { check(it.moveToFirst()); it.getString(0) }
            val raw = result.toJson().toString()
            db.execSQL("UPDATE daily_workout_state SET submission_id=?,through_rowid=?,result_json=? WHERE date=?",
                arrayOf<Any>(turn.id, turn.rowId, raw, day))
            db.execSQL("UPDATE workout_turns SET status='complete',result_json=? WHERE submission_id=?", arrayOf(raw, turn.id))
            db.delete("exercise_logs", "date=?", arrayOf(day))
            for ((index, fact) in result.records.withIndex()) {
                val r = fact.observation
                db.execSQL("INSERT INTO exercise_logs VALUES (?,?,?,?,?,?,?)", arrayOf<Any>(day, revision, index, r.exercise, r.performance, r.evidence, r.status))
            }
            val pending = db.rawQuery("SELECT count(*) FROM workout_turns WHERE date=? AND status IN ('pending','failed')", arrayOf(day)).use { it.moveToFirst(); it.getInt(0) }
            db.execSQL("UPDATE workout_extractions SET status=?,unresolved_json=? WHERE date=? AND source_revision=?",
                arrayOf(if (pending == 0) "complete" else "pending", JSONArray(result.unresolved.map { it.evidence }).toString(), day, revision))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun fail(turn: PendingWorkoutTurn) {
        db.execSQL("UPDATE workout_turns SET status='failed' WHERE submission_id=? AND status='pending'", arrayOf(turn.id))
        db.execSQL("UPDATE workout_extractions SET status='failed' WHERE date=? AND EXISTS (SELECT 1 FROM workout_turns WHERE submission_id=? AND status='failed')",
            arrayOf(turn.date.toString(), turn.id))
    }
}
