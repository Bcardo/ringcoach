package com.bcardo.ringcoach

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.round

/** App-private, bounded sensor archive. No identifiers or sample arrays enter coaching snapshots. */
internal class RingBluetoothStore(context: Context, name: String = DATABASE) :
    SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    companion object { const val DATABASE = "ring-bluetooth.db" }
    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE points (source TEXT NOT NULL,metric TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,value REAL NOT NULL,extra REAL,received INTEGER NOT NULL,origin TEXT NOT NULL,PRIMARY KEY(source,metric,start,end))")
        db.execSQL("CREATE INDEX ring_point_end ON points(end)")
        db.execSQL("CREATE TABLE morning (date TEXT PRIMARY KEY,status TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,error TEXT,updated INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported Ring archive version")
    fun enabled(): Boolean = read("enabled") == "true"
    fun setEnabled(enabled: Boolean) = write("enabled", enabled.toString())
    private fun read(key: String): String? = readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    private fun write(key: String, value: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO settings VALUES (?,?)", arrayOf(key, value)) }
    fun status(value: JSONObject) = write("status", value.toString())
    fun status(): JSONObject = JSONObject(read("status") ?: "{}").put("enabled", enabled())

    /** Returns only new eligible wake dates, never queues historical backfill or repeated transfers. */
    fun ingest(transfer: RingTransfer, source: String, at: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(), origin: String = "live_bluetooth"): Set<LocalDate> {
        require(source.isNotBlank())
        require(origin in setOf("live_bluetooth", "research_capture_import"))
        val now = at.toEpochMilli()
        val earliest = now - 35L * 86400000
        require(transfer.points.all { it.start >= earliest && it.end <= now + 300000 && it.end >= it.start &&
            it.value.isFinite() && (it.extra == null || it.extra.isFinite()) &&
            (it.metric != "sleep_session" || it.end - it.start <= 24 * 3600000) })
        val db = writableDatabase
        val queued = mutableSetOf<LocalDate>()
        db.beginTransaction()
        try {
            for (p in transfer.points) db.insertWithOnConflict("points", null, ContentValues().apply {
                put("source", source); put("metric", p.metric); put("start", p.start); put("end", p.end)
                put("value", p.value); if (p.extra == null) putNull("extra") else put("extra", p.extra)
                put("received", now); put("origin", origin)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("points", "end < ?", arrayOf(earliest.toString()))
            db.execSQL("DELETE FROM points WHERE rowid IN (SELECT rowid FROM points ORDER BY end DESC LIMIT -1 OFFSET 200000)")
            db.delete("morning", "date < ?", arrayOf(at.atZone(zone).toLocalDate().minusDays(35).toString()))
            if (enabled() && origin == "live_bluetooth") {
                val today = at.atZone(zone).toLocalDate()
                val storedSleeps = db.rawQuery("SELECT start,end,value FROM points WHERE source=? AND metric='sleep_session' AND end>=?",
                    arrayOf(source, (now - 12 * 3600000).toString())).use { c -> buildList {
                    while (c.moveToNext()) add(RingPoint("sleep_session", c.getLong(0), c.getLong(1), c.getDouble(2)))
                } }
                for (sleep in storedSleeps.filter { it.end - it.start >= 90 * 60000 }) {
                    val date = Instant.ofEpochMilli(sleep.end).atZone(zone).toLocalDate()
                    if (date != today || sleep.end > now || now - sleep.end > 12 * 3600000) continue
                    val hrv = db.rawQuery("SELECT COUNT(*) FROM points WHERE source=? AND metric='hrv_rmssd' AND start < ? AND end >= ? AND value>0",
                        arrayOf(source, sleep.end.toString(), sleep.start.toString())).use { it.moveToFirst(); it.getInt(0) }
                    if (hrv == 0) continue
                    val inserted = db.insertWithOnConflict("morning", null, ContentValues().apply {
                        put("date", date.toString()); put("status", "pending"); put("updated", now)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                    if (inserted != -1L) queued.add(date)
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return queued
    }

    private data class Stored(val source: String, val point: RingPoint, val received: Long, val origin: String)
    fun snapshot(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): JSONObject {
        val first = date.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val last = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = readableDatabase.rawQuery("SELECT source,metric,start,end,value,extra,received,origin FROM points WHERE end>=? AND end<? ORDER BY start,end",
            arrayOf((first - 86400000).toString(), last.toString())).use { c -> buildList {
            while (c.moveToNext()) add(Stored(c.getString(0), RingPoint(c.getString(1), c.getLong(2), c.getLong(3), c.getDouble(4),
                if (c.isNull(5)) null else c.getDouble(5)), c.getLong(6), c.getString(7)))
        } }
        val sessions = rows.filter { it.point.metric == "sleep_session" && it.point.end > it.point.start &&
            Instant.ofEpochMilli(it.point.end).atZone(zone).toLocalDate() in date.minusDays(6)..date }
        val nights = sessions.mapIndexed { index, stored ->
            val sleep = stored.point
            val wake = Instant.ofEpochMilli(sleep.end).atZone(zone).toLocalDate()
            val matches = rows.filter { it.source == stored.source && it.point.metric != "sleep_session" && it.point.start < sleep.end && it.point.end >= sleep.start }
            val metrics = JSONObject()
            for ((metric, candidates) in matches.groupBy { it.point.metric }) {
                val valid = candidates.filter { valid(it.point) }
                val stats = stats(valid.map { it.point.value }).put("rejected_sample_count", candidates.size - valid.size)
                    .put("definition", definition(metric)).put("unit", unit(metric))
                    .put("coverage_minutes", coverage(valid.map { it.point }, sleep.start, sleep.end) / 60000.0)
                if (metric == "hrv_rmssd") stats.put("sdnn", stats(valid.mapNotNull { it.point.extra }))
                    .put("overlapping_windows", true).put("same_night_reference_validation", "pending")
                metrics.put(metric, stats)
            }
            JSONObject().put("session_ref", "bonded_ring_session_${index + 1}")
                .put("wake_date", wake.toString()).put("start", Instant.ofEpochMilli(sleep.start).toString())
                .put("end", Instant.ofEpochMilli(sleep.end).toString()).put("session_minutes", sleep.value)
                .put("timezone_basis", "session_end_in_request_timezone").put("metrics", metrics)
                .put("received_at", Instant.ofEpochMilli(maxOf(stored.received, matches.maxOfOrNull { it.received } ?: 0)).toString())
                .put("validation", "experimental_protocol_decode_not_vendor_daily_summary")
                .put("capture_origins", JSONArray((matches.map { it.origin } + stored.origin).distinct()))
        }
        return JSONObject().put("source", "direct_bonded_galaxy_ring_bluetooth").put("request_date", date.toString())
            .put("timezone", zone.id).put("history_start", date.minusDays(6).toString()).put("history_end", date.toString())
            .put("sessions", JSONArray(nights)).put("target_date_session_count", nights.count { it.getString("wake_date") == date.toString() })
            .put("collection", status()).put("raw_samples_included", false)
            .put("interpretation", "Experimental per-session sensor statistics. Multiple/overlapping sessions must not be summed. RMSSD/SDNN are overlapping protocol windows, not Samsung daily sleeping HRV. Units/calibration and same-night reference remain pending. No z-score, recovery score, clinical diagnosis or automatic training threshold is computed. Missing data is unknown, not zero. Optical blood oxygen and raw RRI are not validated measurements.")
    }

    fun nextMorning(today: LocalDate = LocalDate.now()): LocalDate? {
        writableDatabase.execSQL("UPDATE morning SET status='expired' WHERE date<? AND status NOT IN ('complete','expired')", arrayOf(today.toString()))
        return readableDatabase.rawQuery("SELECT date FROM morning WHERE date=? AND status IN ('pending','retry','waiting_progress','credential_missing','coaching') AND attempts<3", arrayOf(today.toString())).use {
            if (it.moveToFirst()) LocalDate.parse(it.getString(0)) else null
        }
    }
    fun morningState(date: LocalDate): JSONObject = readableDatabase.rawQuery("SELECT status,attempts,error FROM morning WHERE date=?", arrayOf(date.toString())).use {
        if (it.moveToFirst()) JSONObject().put("status", it.getString(0)).put("attempts", it.getInt(1)).put("error", it.getString(2) ?: JSONObject.NULL)
        else JSONObject().put("status", "awaiting_sleep_transfer")
    }
    fun morningStatus(date: LocalDate, status: String, error: String? = null, increment: Boolean = false) {
        writableDatabase.execSQL("UPDATE morning SET status=?,error=?,attempts=attempts+?,updated=? WHERE date=?",
            arrayOf<Any?>(status, error, if (increment) 1 else 0, Instant.now().toEpochMilli(), date.toString()))
    }
    fun retryMorning(date: LocalDate) {
        writableDatabase.execSQL("UPDATE morning SET status='pending',attempts=0,error=NULL WHERE date=? AND status!='complete'", arrayOf(date.toString()))
    }
    fun queueMorning(date: LocalDate) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO morning(date,status,updated) VALUES (?,'pending',?)", arrayOf<Any>(date.toString(), Instant.now().toEpochMilli()))
    }

    private fun valid(p: RingPoint): Boolean = when (p.metric) {
        "heart_rate", "heart_rate_summary" -> p.value in 20.0..250.0
        "hrv_rmssd" -> p.value > 0 && p.value <= 2000 && p.extra != null && p.extra > 0 && p.extra <= 2000
        "respiration" -> p.value > 0 && p.value <= 100
        "skin_temperature" -> p.value > 0 && p.value < 100
        "movement" -> p.value >= 0
        "stress" -> p.value in 0.0..100.0
        else -> false
    }
    private fun definition(metric: String) = when (metric) {
        "hrv_rmssd" -> "overlapping_window_rmssd_with_sdnn_not_vendor_sleeping_hrv"
        "heart_rate" -> "minute_binning_not_resting_heart_rate"
        "skin_temperature" -> "object_sensor_temperature_not_core_temperature"
        else -> "protocol_$metric"
    }
    private fun unit(metric: String) = if (metric in setOf("heart_rate", "heart_rate_summary")) "bpm" else "protocol_units_pending_reference_validation"
    private fun stats(values: List<Double>): JSONObject = JSONObject().put("sample_count", values.size).apply {
        if (values.isNotEmpty()) {
            val sorted = values.sorted(); val mid = sorted.size / 2
            fun rounded(v: Double) = round(v * 100) / 100
            put("min", rounded(sorted.first())); put("max", rounded(sorted.last())); put("mean", rounded(values.average()))
            put("median", rounded(if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2))
        }
    }
    private fun coverage(points: List<RingPoint>, start: Long, end: Long): Long {
        var until = start; var total = 0L
        for (p in points.sortedBy { it.start }) {
            val a = maxOf(start, until, p.start); val b = minOf(end, p.end)
            if (b > a) total += b - a
            until = maxOf(until, b)
        }
        return total
    }
}
