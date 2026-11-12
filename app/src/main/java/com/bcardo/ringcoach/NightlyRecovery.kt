package com.bcardo.ringcoach

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalDateFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class NightlyMetric(
    val date: LocalDate, val metric: String, val value: Double, val unit: String,
    val source: String, val sourceId: String, val details: JSONObject = JSONObject(),
) {
    init {
        require(sourceId.isNotBlank())
        require(source in setOf("samsung_health_sdk", "samsung_health_screen"))
        require(source != "samsung_health_sdk" || metric == "energy_score")
        require(value.isFinite())
        when (metric) {
            "energy_score" -> require(value in 0.0..100.0 && unit == "score")
            "sleeping_hrv" -> require(value > 0 && value <= 2000 && unit == "ms")
            else -> error("Unknown nightly metric")
        }
    }
    fun toJson(observedAt: Instant): JSONObject = JSONObject()
        .put("wake_date", date.toString()).put("metric", metric).put("value", value).put("unit", unit)
        .put("source", source).put("source_record_id", sourceId).put("metric_definition", DailyRecoveryArchive.definition(metric))
        .put("observed_at", observedAt.toString()).put("details", details)
}

internal class NightlyRecoveryStore(context: Context, name: String = "nightly-recovery.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE nightly_metrics (date TEXT NOT NULL, metric TEXT NOT NULL, source TEXT NOT NULL, source_id TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(date, metric, source, source_id))")
        db.execSQL("CREATE TABLE sync_state (source TEXT PRIMARY KEY NOT NULL, status TEXT NOT NULL, checked_at TEXT NOT NULL)")
        DailyRecoveryArchive.migrateV1(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2)
        DailyRecoveryArchive.migrateV1(db)
    }

    @Synchronized fun put(metric: NightlyMetric, now: Instant = Instant.now()) {
        DailyRecoveryArchive(writableDatabase).put(metric, now)
    }

    @Synchronized fun replaceEnergy(start: LocalDate, end: LocalDate, records: List<NightlyMetric>, now: Instant = Instant.now()) {
        require(records.all { it.source == "samsung_health_sdk" && it.metric == "energy_score" && it.date in start..end })
        val db = writableDatabase
        db.beginTransaction()
        try {
            DailyRecoveryArchive(db).reconcile("samsung_health_sdk", "energy_score", start, end, records, now)
            status("samsung_health_sdk", if (records.isEmpty()) "empty" else "available")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun replaceScreenDay(date: LocalDate, records: List<NightlyMetric>, now: Instant = Instant.now()) {
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

    @Synchronized fun status(source: String, status: String) {
        writableDatabase.insertWithOnConflict("sync_state", null, ContentValues().apply {
            put("source", source); put("status", status); put("checked_at", Instant.now().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun snapshot(date: LocalDate): JSONObject {
        val start = date.minusDays(6)
        val records = DailyRecoveryArchive(readableDatabase).records(start, date)
        val states = readableDatabase.rawQuery("SELECT source, status, checked_at FROM sync_state", null).use { c ->
            buildList { while (c.moveToNext()) add(JSONObject().put("source", c.getString(0)).put("status", c.getString(1)).put("checked_at", c.getString(2))) }
        }
        return JSONObject().put("history_start", start.toString()).put("history_end", date.toString())
            .put("records", JSONArray(records)).put("sync", JSONArray(states))
            .put("sleeping_hrv_sdk_support", "not_exposed")
            .put("interpretation", "Vendor sleeping HRV is a separate daily summary; do not substitute a mean of raw RMSSD windows or compute an HRV z-score from it.")
    }

    @Synchronized fun history(start: LocalDate, end: LocalDate): JSONObject = DailyRecoveryArchive(readableDatabase).history(start, end)
    @Synchronized fun revisions(date: LocalDate, metric: String): JSONArray = DailyRecoveryArchive(readableDatabase).revisions(date, metric)
}

internal object SamsungNightlyReader {
    private val mutex = Mutex()
    val permissions = setOf(Permission.of(DataTypes.ENERGY_SCORE, AccessType.READ))

    suspend fun request(activity: Activity): Boolean = HealthDataService.getStore(activity)
        .requestPermissions(permissions, activity).containsAll(permissions)

    suspend fun refresh(context: Context, date: LocalDate = LocalDate.now()): JSONObject = mutex.withLock {
        NightlyRecoveryStore(context).use { cache ->
            try {
                val store = HealthDataService.getStore(context.applicationContext)
                if (!store.getGrantedPermissions(permissions).containsAll(permissions)) {
                    cache.status("samsung_health_sdk", "permission_required")
                } else {
                    val start = date.minusDays(6)
                    var token: String? = null
                    val seen = mutableSetOf<String>()
                    val records = mutableListOf<NightlyMetric>()
                    do {
                        val builder = DataTypes.ENERGY_SCORE.readDataRequestBuilder
                            .setLocalDateFilter(LocalDateFilter.of(start, date, true, true)).setPageSize(100)
                        token?.let { builder.setPageToken(it) }
                        val page = store.readData(builder.build())
                        page.dataList.forEach { point ->
                            val offset = point.zoneOffset
                            val day = NightlyDates.energyDate(point.startTime, offset)
                            val value = point.getValue(DataType.EnergyScoreType.ENERGY_SCORE)?.toDouble()
                            if (value != null && day in start..date) records += NightlyMetric(day, "energy_score", value, "score", "samsung_health_sdk", point.uid,
                                JSONObject().put("source_app", point.dataSource?.appId ?: JSONObject.NULL)
                                    .put("source_start_time", point.startTime.toString()).put("source_zone_offset", offset?.toString() ?: JSONObject.NULL)
                                    .put("timezone_basis", if (offset == null) "utc_calendar_date_without_offset" else "record_offset")
                                    .put("precision", "vendor_api_float")
                                    .put("ring_attribution", "not_verified"))
                        }
                        token = page.pageToken
                        check(token == null || (seen.add(token!!) && seen.size <= 50)) { "Invalid SDK pagination" }
                    } while (token != null)
                    cache.replaceEnergy(start, date, records)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: com.samsung.android.sdk.health.data.error.HealthDataException) {
                cache.status("samsung_health_sdk", "sdk_error_${e.errorCode}")
            } catch (e: Exception) {
                cache.status("samsung_health_sdk", "unavailable:${e.javaClass.simpleName}")
            }
            cache.snapshot(date)
        }
    }
}
