package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Opt-in owner migration audit. No source refresh, model call, or measurement output. */
@RunWith(AndroidJUnit4::class)
class RecoveryArchiveOwnerAuditTest {
    @Test fun migratedRecoveryMatchesPreviouslySavedContext() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("audit_recovery_archive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        NightlyRecoveryStore(context).use { store ->
            val db = store.readableDatabase
            assertEquals(2, db.version)
            var compared = 0
            JournalStore(context).use { journal ->
                val records = journal.latestCoachCard()?.let { JSONObject(it.contextJson) }
                    ?.optJSONObject("nightly_recovery")?.optJSONArray("records")
                for (i in 0 until (records?.length() ?: 0)) {
                    val saved = records!!.getJSONObject(i)
                    val date = LocalDate.parse(saved.getString("wake_date"))
                    val current = store.history(date, date).getJSONArray("days").getJSONObject(0)
                        .getJSONObject(saved.getString("metric")).getJSONArray("observations")
                    val matches = (0 until current.length()).map { current.getJSONObject(it) }
                        .filter { it.getString("source") == saved.getString("source") &&
                            it.getDouble("value") == saved.getDouble("value") }
                    assertTrue("Previously saved recovery reading missing", matches.isNotEmpty())
                    compared++
                }
            }
            val today = LocalDate.now()
            assertEquals(30, store.history(today.minusDays(29), today).getJSONArray("days").length())
            val count = db.rawQuery("SELECT count(*) FROM nightly_metrics", null).use { it.moveToFirst(); it.getInt(0) }
            assertTrue(count > 0)
            assertTrue("No saved recovery context compared", compared > 0)
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", JSONObject().put("schema", db.version).put("archived_observations", count)
                    .put("prior_context_comparisons", compared).put("history_query_days", 30).toString() + "\n")
            })
        }
    }
}
