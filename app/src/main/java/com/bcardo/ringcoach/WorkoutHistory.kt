package com.bcardo.ringcoach

import java.time.LocalDate

/** UI-only bounded history. Uses the same current-revision/evidence guard as latestWorkoutRecords. */
internal fun JournalStore.workoutHistory(exercise: String, through: LocalDate, dates: Int = 4): List<WorkoutRecord> {
    require(dates in 1..10)
    return readableDatabase.rawQuery("""WITH eligible AS (
        SELECT e.date,e.exercise,e.performance,e.evidence,e.status,e.ordinal
        FROM exercise_logs e JOIN journal_entries j ON j.date=e.date
        WHERE e.exercise=? AND e.date<=? AND e.status IN ('completed','partial')
          AND (j.training_revision=e.source_revision OR (e.evidence!='' AND instr(j.training,e.evidence)>0))
    ) SELECT date,exercise,performance,evidence,status FROM eligible
      WHERE date IN (SELECT DISTINCT date FROM eligible ORDER BY date DESC LIMIT ?)
      ORDER BY date DESC,ordinal""", arrayOf(exercise, through.toString(), dates.toString())).use { rows -> buildList {
        while (rows.moveToNext()) add(WorkoutRecord(LocalDate.parse(rows.getString(0)),
            WorkoutObservation(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4))))
    } }
}

internal data class ExerciseHistory(val records: List<WorkoutRecord> = emptyList(), val loading: Boolean = true, val failed: Boolean = false)
