package com.bcardo.ringcoach

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal val DAILY_RECOVERY_METRICS = listOf("energy_score", "sleeping_hrv")
internal const val RECOVERY_HISTORY_POLICY = "daily-recovery-v1-sdk-energy-preferred"

/** Calendar-day observations with source-level verification and a correction/deletion audit trail. */
internal class DailyRecoveryArchive(private val db: SQLiteDatabase) {
    companion object {
        fun migrateV1(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE nightly_metrics ADD COLUMN first_observed_at TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE nightly_metrics ADD COLUMN last_verified_at TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE nightly_metrics ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE nightly_metrics ADD COLUMN deleted_at TEXT")
            db.execSQL("""CREATE TABLE nightly_revisions (
                date TEXT NOT NULL, metric TEXT NOT NULL, source TEXT NOT NULL, source_id TEXT NOT NULL,
                revision INTEGER NOT NULL, change_kind TEXT NOT NULL, changed_at TEXT NOT NULL, json TEXT NOT NULL,
                PRIMARY KEY(date,metric,source,source_id,revision))""")
            db.execSQL("""CREATE TABLE nightly_coverage (
                date TEXT NOT NULL, metric TEXT NOT NULL, source TEXT NOT NULL,
                status TEXT NOT NULL, checked_at TEXT NOT NULL, PRIMARY KEY(date,metric,source))""")
            db.execSQL("CREATE INDEX nightly_source_window ON nightly_metrics(source,metric,date,deleted_at)")
            val rows = db.rawQuery("SELECT date,metric,source,source_id,json FROM nightly_metrics", null).use { c ->
                buildList { while (c.moveToNext()) add((0..4).map { c.getString(it) }) }
            }
            val archive = DailyRecoveryArchive(db)
            rows.sortedBy { Instant.parse(JSONObject(it[4]).getString("observed_at")) }.forEach { row ->
                val json = JSONObject(row[4]).put("source_record_id", row[3])
                    .put("metric_definition", definition(row[1]))
                val observed = json.getString("observed_at")
                db.execSQL("""UPDATE nightly_metrics SET json=?,first_observed_at=?,last_verified_at=?
                    WHERE date=? AND metric=? AND source=? AND source_id=?""",
                    arrayOf(json.toString(), observed, observed, row[0], row[1], row[2], row[3]))
                db.execSQL("INSERT INTO nightly_revisions VALUES (?,?,?,?,1,'imported_v1',?,?)",
                    arrayOf(row[0], row[1], row[2], row[3], observed, json.toString()))
                archive.coverage(LocalDate.parse(row[0]), row[1], row[2], "available", observed)
            }
        }

        fun definition(metric: String): String = when (metric) {
            "energy_score" -> "samsung_vendor_daily_energy_score"
            "sleeping_hrv" -> "samsung_vendor_daily_sleeping_hrv"
            else -> error("Unsupported daily recovery metric")
        }

        /** Ignore a moving Today/Yesterday label, not measurement or source-definition metadata. */
        private fun fingerprint(record: JSONObject): String {
            val facts = JSONObject(record.toString()).apply { remove("observed_at") }
            facts.optJSONObject("details")?.remove("date_label")
            fun canonical(value: Any?): String = when (value) {
                null, JSONObject.NULL -> "null"
                is JSONObject -> value.keys().asSequence().toList().sorted()
                    .joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
                is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
                is Number -> value.toDouble().toString()
                is Boolean -> value.toString()
                else -> JSONObject.quote(value.toString())
            }
            return canonical(facts)
        }
    }

    private fun coverage(date: LocalDate, metric: String, source: String, status: String, checked: String) {
        db.execSQL("""INSERT INTO nightly_coverage VALUES (?,?,?,?,?) ON CONFLICT(date,metric,source)
            DO UPDATE SET status=excluded.status,checked_at=excluded.checked_at""",
            arrayOf(date.toString(), metric, source, status, checked))
    }

    fun put(metric: NightlyMetric, now: Instant) = transaction {
        val key = arrayOf(metric.date.toString(), metric.metric, metric.source, metric.sourceId)
        val old = db.rawQuery("""SELECT json,first_observed_at,last_verified_at,revision,deleted_at FROM nightly_metrics
            WHERE date=? AND metric=? AND source=? AND source_id=?""", key).use { c ->
            if (!c.moveToFirst()) null else (0..4).map { if (c.isNull(it)) null else c.getString(it) }
        }
        val previous = old?.get(0)?.let(::JSONObject)
        val candidate = metric.toJson(now)
        val changed = old == null || old[4] != null || fingerprint(previous!!) != fingerprint(candidate)
        val revision = if (old == null) 1 else old[3]!!.toInt() + if (changed) 1 else 0
        val first = old?.get(1) ?: now.toString()
        if (!changed) candidate.put("observed_at", previous!!.getString("observed_at"))
        db.execSQL("""INSERT INTO nightly_metrics
            (date,metric,source,source_id,json,first_observed_at,last_verified_at,revision,deleted_at)
            VALUES (?,?,?,?,?,?,?,?,NULL) ON CONFLICT(date,metric,source,source_id) DO UPDATE SET
            json=excluded.json,last_verified_at=excluded.last_verified_at,revision=excluded.revision,deleted_at=NULL""",
            arrayOf<Any>(key[0], key[1], key[2], key[3], candidate.toString(), first, now.toString(), revision))
        if (changed) {
            val kind = when {
                old == null -> "first_observation"
                old[4] != null -> "restored"
                previous!!.getDouble("value") != metric.value -> "value_corrected"
                else -> "source_metadata_corrected"
            }
            db.execSQL("INSERT INTO nightly_revisions VALUES (?,?,?,?,?,?,?,?)",
                arrayOf<Any>(key[0], key[1], key[2], key[3], revision, kind, now.toString(), candidate.toString()))
        }
        coverage(metric.date, metric.metric, metric.source, "available", now.toString())
    }

    /** Call only for a fully verified source window; absence in a failed/partial read is not deletion. */
    fun reconcile(source: String, metric: String, start: LocalDate, end: LocalDate, incoming: List<NightlyMetric>, now: Instant) = transaction {
        require(!start.isAfter(end))
        require(incoming.all { it.source == source && it.metric == metric && it.date in start..end })
        val groups = incoming.groupBy { it.date.toString() to it.sourceId }
        require(groups.values.all { group -> group.map { fingerprint(it.toJson(Instant.EPOCH)) }.distinct().size == 1 }) {
            "Conflicting values for one source record in a single read"
        }
        val records = groups.values.map { it.first() }
        val keys = groups.keys
        val old = db.rawQuery("""SELECT date,source_id,json,revision FROM nightly_metrics
            WHERE source=? AND metric=? AND date BETWEEN ? AND ? AND deleted_at IS NULL""",
            arrayOf(source, metric, start.toString(), end.toString())).use { c ->
            buildList { while (c.moveToNext()) add((0..3).map { c.getString(it) }) }
        }
        for (row in old) if ((row[0] to row[1]) !in keys) {
            val revision = row[3].toInt() + 1
            db.execSQL("""UPDATE nightly_metrics SET deleted_at=?,last_verified_at=?,revision=?
                WHERE date=? AND metric=? AND source=? AND source_id=?""",
                arrayOf<Any>(now.toString(), now.toString(), revision, row[0], metric, source, row[1]))
            db.execSQL("INSERT INTO nightly_revisions VALUES (?,?,?,?,?,'source_missing',?,?)",
                arrayOf<Any>(row[0], metric, source, row[1], revision, now.toString(), row[2]))
        }
        records.forEach { put(it, now) }
        var day = start
        while (!day.isAfter(end)) {
            coverage(day, metric, source, if (records.any { it.date == day }) "available" else "confirmed_missing", now.toString())
            day = day.plusDays(1)
        }
    }

    fun records(start: LocalDate, end: LocalDate): List<JSONObject> = db.rawQuery("""SELECT json,first_observed_at,last_verified_at,revision
        FROM nightly_metrics WHERE date BETWEEN ? AND ? AND deleted_at IS NULL ORDER BY date DESC,metric,source,source_id""",
        arrayOf(start.toString(), end.toString())).use { c ->
        buildList { while (c.moveToNext()) add(JSONObject(c.getString(0)).put("first_observed_at", c.getString(1))
            .put("last_verified_at", c.getString(2)).put("revision", c.getInt(3))) }
    }

    fun revisions(date: LocalDate, metric: String): JSONArray = db.rawQuery("""SELECT source,source_id,revision,change_kind,changed_at,json
        FROM nightly_revisions WHERE date=? AND metric=?""",
        arrayOf(date.toString(), metric)).use { c ->
        val rows = buildList { while (c.moveToNext()) add(JSONObject().put("source", c.getString(0)).put("source_record_id", c.getString(1))
            .put("revision", c.getInt(2)).put("change_kind", c.getString(3)).put("changed_at", c.getString(4))
            .put("observation", JSONObject(c.getString(5)))) }
        JSONArray(rows.sortedWith(compareBy<JSONObject> { Instant.parse(it.getString("changed_at")) }
            .thenBy { it.getString("source") }.thenBy { it.getString("source_record_id") }.thenBy { it.getInt("revision") }))
    }

    /** Query pagination, not retention: charts can request any one-year window of accumulated local history. */
    fun history(start: LocalDate, end: LocalDate): JSONObject {
        require(!start.isAfter(end) && ChronoUnit.DAYS.between(start, end) < 366) { "Query at most 366 calendar days at once" }
        val records = records(start, end).groupBy { it.getString("wake_date") }
        val coverage = db.rawQuery("SELECT date,metric,source,status,checked_at FROM nightly_coverage WHERE date BETWEEN ? AND ? ORDER BY date,metric,source",
            arrayOf(start.toString(), end.toString())).use { c ->
            buildList { while (c.moveToNext()) add(JSONObject().put("wake_date", c.getString(0)).put("metric", c.getString(1))
                .put("source", c.getString(2)).put("status", c.getString(3)).put("checked_at", c.getString(4))) }
        }.groupBy { it.getString("wake_date") }
        val days = JSONArray()
        var day = start
        while (!day.isAfter(end)) {
            val row = JSONObject().put("wake_date", day.toString())
            for (metric in DAILY_RECOVERY_METRICS) row.put(metric, RecoveryHistoryPolicy.canonical(metric,
                records[day.toString()].orEmpty().filter { it.getString("metric") == metric },
                coverage[day.toString()].orEmpty().filter { it.getString("metric") == metric }))
            days.put(row)
            day = day.plusDays(1)
        }
        return JSONObject().put("history_start", start.toString()).put("history_end", end.toString())
            .put("policy_version", RECOVERY_HISTORY_POLICY).put("days", days)
            .put("sync", db.rawQuery("SELECT source,status,checked_at FROM sync_state ORDER BY source", null).use { c ->
                JSONArray().apply { while (c.moveToNext()) put(JSONObject().put("source", c.getString(0))
                    .put("status", c.getString(1)).put("checked_at", c.getString(2))) }
            })
            .put("query_scope", "locally_archived_observations_not_source_backfill")
    }

    private fun transaction(block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
}

internal object RecoveryHistoryPolicy {
    fun canonical(metric: String, observations: List<JSONObject>, coverage: List<JSONObject>): JSONObject {
        require(metric in DAILY_RECOVERY_METRICS)
        val sdk = observations.filter { it.getString("source") == "samsung_health_sdk" }
        val screen = observations.filter { it.getString("source") == "samsung_health_screen" }
        val preferred = if (metric == "energy_score" && sdk.isNotEmpty()) sdk else screen
        val unique = preferred.map { it.getDouble("value") to it.getString("metric_definition") }.distinct()
        val chosen = if (unique.size == 1) preferred.sortedWith(compareBy<JSONObject> { Instant.parse(it.getString("last_verified_at")) }
            .thenBy { it.getString("source_record_id") }).last() else null
        val status = when {
            unique.size > 1 -> "ambiguous"
            chosen != null -> "available"
            observations.isNotEmpty() && preferred.isEmpty() -> "unsupported_source"
            coverage.any { it.getString("status") == "confirmed_missing" } -> "confirmed_missing"
            else -> "not_checked"
        }
        return JSONObject().put("status", status).put("value", chosen?.getDouble("value") ?: JSONObject.NULL)
            .put("unit", if (metric == "energy_score") "score" else "ms")
            .put("metric_definition", DailyRecoveryArchive.definition(metric))
            .put("source", chosen?.getString("source") ?: JSONObject.NULL)
            .put("first_observed_at", chosen?.getString("first_observed_at") ?: JSONObject.NULL)
            .put("last_verified_at", chosen?.getString("last_verified_at") ?: JSONObject.NULL)
            .put("revision", chosen?.getInt("revision") ?: JSONObject.NULL)
            .put("source_record_id", chosen?.getString("source_record_id") ?: JSONObject.NULL)
            .put("observations", JSONArray(observations)).put("source_coverage", JSONArray(coverage))
    }
}
