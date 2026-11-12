package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DailyRecoveryArchiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val day = LocalDate.of(2026, 1, 30)
    private val t1 = Instant.parse("2026-01-30T10:00:00Z")
    private val t2 = t1.plusSeconds(3600)
    private val t3 = t2.plusSeconds(3600)
    private fun score(date: LocalDate = day, value: Double = 82.25, id: String = "score") = NightlyMetric(
        date, "energy_score", value, "score", "samsung_health_sdk", id)
    private fun hrv(date: LocalDate = day, value: Double = 60.0, label: String = "今天") = NightlyMetric(
        date, "sleeping_hrv", value, "ms", "samsung_health_screen", "daily", JSONObject().put("date_label", label))
    private fun isolated(block: (NightlyRecoveryStore) -> Unit) {
        val name = "daily-archive-${UUID.randomUUID()}.db"
        try { NightlyRecoveryStore(context, name).use(block) } finally { context.deleteDatabase(name) }
    }
    private fun metric(store: NightlyRecoveryStore, date: LocalDate = day, metric: String = "energy_score") =
        store.history(date, date).getJSONArray("days").getJSONObject(0).getJSONObject(metric)

    @Test fun identicalReadsAndMovingDateLabelsDoNotCreateCorrections() = isolated { store ->
        store.put(hrv(), t1)
        store.put(hrv(label = "昨天"), t2)
        val record = metric(store, metric = "sleeping_hrv")
        assertEquals(1, record.getInt("revision"))
        assertEquals(t1.toString(), record.getString("first_observed_at"))
        assertEquals(t2.toString(), record.getString("last_verified_at"))
        assertEquals(1, store.revisions(day, "sleeping_hrv").length())
        store.put(hrv(value = 62.0, label = "昨天"), t3)
        assertEquals(2, metric(store, metric = "sleeping_hrv").getInt("revision"))
        assertEquals("value_corrected", store.revisions(day, "sleeping_hrv").getJSONObject(1).getString("change_kind"))
    }

    @Test fun thirtyDayQueryRetainsOldValuesAndDistinguishesGapsFromVerifiedMissing() = isolated { store ->
        val old = day.minusDays(29)
        store.put(score(old, 77.0, "old"), t1)
        store.put(hrv(old), t1)
        store.replaceEnergy(day.minusDays(6), day, listOf(score()), t2)
        val history = store.history(old, day).getJSONArray("days")
        assertEquals(30, history.length())
        assertEquals(77.0, history.getJSONObject(0).getJSONObject("energy_score").getDouble("value"), 0.0)
        val unknown = history.getJSONObject(1).getJSONObject("energy_score")
        assertEquals("not_checked", unknown.getString("status"))
        assertTrue(unknown.isNull("value"))
        val missing = history.getJSONObject(28).getJSONObject("energy_score")
        assertEquals("confirmed_missing", missing.getString("status"))
        assertTrue(missing.isNull("value"))
        assertEquals(1, store.snapshot(day).getJSONArray("records").length())
    }

    @Test fun confirmedDeletionIsAuditedAndRestorationPreservesFirstObservation() = isolated { store ->
        store.replaceEnergy(day, day, listOf(score()), t1)
        store.replaceEnergy(day, day, emptyList(), t2)
        assertEquals("confirmed_missing", metric(store).getString("status"))
        assertEquals(0, store.snapshot(day).getJSONArray("records").length())
        val revisions = store.revisions(day, "energy_score")
        assertEquals(2, revisions.length())
        assertEquals("source_missing", revisions.getJSONObject(1).getString("change_kind"))
        store.replaceEnergy(day, day, emptyList(), t2.plusSeconds(10))
        assertEquals(2, store.revisions(day, "energy_score").length())
        store.replaceEnergy(day, day, listOf(score(value = 84.0)), t3)
        val restored = metric(store)
        assertEquals(3, restored.getInt("revision"))
        assertEquals(t1.toString(), restored.getString("first_observed_at"))
        assertEquals("restored", store.revisions(day, "energy_score").getJSONObject(2).getString("change_kind"))
    }

    @Test fun failedReadOnlyChangesSyncStatusNotVerifiedValues() = isolated { store ->
        store.put(score(), t1)
        store.status("samsung_health_sdk", "sdk_error_test")
        val history = store.history(day, day)
        assertEquals("sdk_error_test", history.getJSONArray("sync").getJSONObject(0).getString("status"))
        assertEquals(82.25, metric(store).getDouble("value"), 0.0)
        assertEquals(t1.toString(), metric(store).getString("last_verified_at"))
        assertEquals(1, store.revisions(day, "energy_score").length())
    }

    @Test fun apiAndScreenScoresAreOneCanonicalPointAndConflictingApiRecordsAreExplicit() = isolated { store ->
        store.put(score(), t1)
        store.replaceScreenDay(day, listOf(hrv(), NightlyMetric(day, "energy_score", 82.0, "score", "samsung_health_screen", "daily")), t2)
        val canonical = metric(store)
        assertEquals(82.25, canonical.getDouble("value"), 0.0)
        assertEquals("samsung_health_sdk", canonical.getString("source"))
        assertEquals(2, canonical.getJSONArray("observations").length())
        assertEquals(60.0, metric(store, metric = "sleeping_hrv").getDouble("value"), 0.0)
        store.replaceEnergy(day, day, listOf(score(), score(value = 85.0, id = "other-source-record")), t3)
        assertEquals("ambiguous", metric(store).getString("status"))
        assertTrue(metric(store).isNull("value"))
    }

    @Test fun duplicateRecordsAreIdempotentButConflictingSameIdRollsBack() = isolated { store ->
        store.replaceEnergy(day, day, listOf(score(), score()), t1)
        assertEquals(1, metric(store).getJSONArray("observations").length())
        try {
            store.replaceEnergy(day, day, listOf(score(value = 83.0), score(value = 84.0)), t2)
            fail("Conflicting source record accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(82.25, metric(store).getDouble("value"), 0.0)
        assertEquals(t1.toString(), metric(store).getString("last_verified_at"))
        assertEquals(1, store.revisions(day, "energy_score").length())
    }

    @Test fun failedAuditWriteRollsBackDeletionAndCoverage() = isolated { store ->
        store.replaceEnergy(day, day, listOf(score()), t1)
        store.writableDatabase.execSQL("""CREATE TRIGGER reject_revision BEFORE INSERT ON nightly_revisions
            BEGIN SELECT RAISE(ABORT,'test audit failure'); END""")
        try {
            store.replaceEnergy(day, day, emptyList(), t2)
            fail("Audit write should fail")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals("available", metric(store).getString("status"))
        assertEquals(t1.toString(), metric(store).getString("last_verified_at"))
        assertEquals(1, store.revisions(day, "energy_score").length())
        assertEquals("available", metric(store).getJSONArray("source_coverage").getJSONObject(0).getString("status"))
    }

    @Test fun missingScreenDayDoesNotEraseApiOrOtherDates() = isolated { store ->
        store.put(score(), t1)
        store.replaceScreenDay(day.minusDays(1), listOf(hrv(day.minusDays(1))), t1)
        store.replaceScreenDay(day, listOf(hrv()), t1)
        store.replaceScreenDay(day, emptyList(), t2)
        assertEquals("available", metric(store).getString("status"))
        assertEquals("confirmed_missing", metric(store, metric = "sleeping_hrv").getString("status"))
        assertEquals(60.0, metric(store, day.minusDays(1), "sleeping_hrv").getDouble("value"), 0.0)
    }

    @Test fun v1MigrationPreservesValuesAndDoesNotInventCoverageForOtherDates() {
        val name = "archive-v1-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("CREATE TABLE nightly_metrics(date TEXT NOT NULL,metric TEXT NOT NULL,source TEXT NOT NULL,source_id TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(date,metric,source,source_id))")
                db.execSQL("CREATE TABLE sync_state(source TEXT PRIMARY KEY NOT NULL,status TEXT NOT NULL,checked_at TEXT NOT NULL)")
                val original = score().toJson(t1).apply { remove("source_record_id"); remove("metric_definition") }
                db.execSQL("INSERT INTO nightly_metrics VALUES (?,?,?,?,?)", arrayOf(day.toString(), "energy_score", "samsung_health_sdk", "score", original.toString()))
                db.execSQL("INSERT INTO sync_state VALUES ('samsung_health_sdk','available',?)", arrayOf(t1.toString()))
                db.version = 1
            }
            NightlyRecoveryStore(context, name).use { store ->
                assertEquals(2, store.readableDatabase.version)
                assertEquals(82.25, metric(store).getDouble("value"), 0.0)
                assertEquals(t1.toString(), metric(store).getString("first_observed_at"))
                assertEquals("score", metric(store).getString("source_record_id"))
                assertEquals("not_checked", metric(store, day.minusDays(1)).getString("status"))
                assertEquals("imported_v1", store.revisions(day, "energy_score").getJSONObject(0).getString("change_kind"))
                store.put(score(), t2)
                assertEquals(1, store.revisions(day, "energy_score").length())
            }
        } finally { context.deleteDatabase(name) }
    }
}
