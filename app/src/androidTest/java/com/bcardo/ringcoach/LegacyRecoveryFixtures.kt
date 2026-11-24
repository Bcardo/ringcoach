package com.bcardo.ringcoach

import java.time.Instant
import java.time.LocalDate

/** Simulates the retired source only to verify existing archives/revision behavior. */
internal fun NightlyRecoveryStore.replaceScreenDay(date: LocalDate, records: List<NightlyMetric>, now: Instant = Instant.now()) {
    require(records.all { it.date == date && it.source == "samsung_health_screen" })
    val db = writableDatabase
    db.beginTransaction()
    try {
        DAILY_RECOVERY_METRICS.forEach { metric ->
            DailyRecoveryArchive(db).reconcile("samsung_health_screen", metric, date, date, records.filter { it.metric == metric }, now)
        }
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}
