package com.bcardo.ringcoach

import java.time.LocalDate

/** Presentation state for one day; an unrelated queue item never labels this day's advice. */
internal data class DailyCoaching(
    val date: LocalDate,
    val card: SavedCoachCard?,
    val pending: PendingCoaching?,
    val stage: String,
    val error: String?,
) {
    val needsRetry: Boolean get() = pending != null && stage == "blocked"
    val updating: Boolean get() = pending != null && stage in setOf(
        "recognizing_intake", "recognizing_workout", "coaching", "retry_scheduled", "waiting_health")
}

internal fun JournalStore.dailyCoaching(date: LocalDate): DailyCoaching {
    // One read snapshot; BEGIN IMMEDIATE would unnecessarily contend with background writers.
    return readableDatabase.rawQuery("""SELECT c.id,c.date,c.created_at,c.model,c.prompt_version,
        c.context_json,c.advice_json,p.id,s.stage,s.error_code
        FROM (SELECT ? AS date) d
        LEFT JOIN coach_cards c ON c.id=(SELECT id FROM coach_cards WHERE date=d.date ORDER BY created_at DESC,id DESC LIMIT 1)
        LEFT JOIN progress_submissions p ON p.id=(SELECT q.submission_id FROM progress_coaching q
            JOIN progress_submissions x ON x.id=q.submission_id LEFT JOIN coach_cards done ON done.id=x.id
            WHERE x.date=d.date AND done.id IS NULL ORDER BY x.rowid LIMIT 1)
        LEFT JOIN progress_work_state s ON s.id=1 AND s.date=d.date""", arrayOf(date.toString())).use { row ->
        check(row.moveToFirst())
        val card = if (row.isNull(0)) null else SavedCoachCard(row.getString(0), LocalDate.parse(row.getString(1)),
            java.time.Instant.ofEpochMilli(row.getLong(2)), row.getString(3), row.getString(4), row.getString(5), CoachAdvice.decode(row.getString(6)))
        val pending = if (row.isNull(7)) null else PendingCoaching(row.getString(7), date)
        DailyCoaching(date, card, pending,
            if (pending == null) "idle" else if (row.isNull(8)) "pending" else row.getString(8),
            if (pending == null || row.isNull(9)) null else row.getString(9))
    }
}

internal fun JournalStore.retryDailyCoaching(date: LocalDate) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        for (table in listOf("nutrition_turns", "nutrition_extractions", "workout_extractions", "workout_turns"))
            db.execSQL("UPDATE $table SET status='pending' WHERE status='failed' AND date=?", arrayOf(date.toString()))
        db.execSQL("""DELETE FROM progress_work_retries WHERE task_key IN (
            SELECT 'nutrition:' || submission_id FROM nutrition_turns WHERE date=?
            UNION SELECT 'nutrition:' || source_revision FROM nutrition_extractions WHERE date=?
            UNION SELECT 'workout:' || source_revision FROM workout_extractions WHERE date=?
            UNION SELECT 'coach:' || id FROM progress_submissions WHERE date=?
            UNION SELECT 'workout:' || submission_id FROM workout_turns WHERE date=?)""",
            Array(5) { date.toString() })
        ProgressWorkStore(db).stage("pending", date)
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}
