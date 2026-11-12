package com.bcardo.ringcoach

import androidx.health.connect.client.HealthConnectClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** Read existing owner facts/Health Connect; package in memory, emit counts, never send or save a card. */
@RunWith(AndroidJUnit4::class)
class CurrentContextOwnerAuditTest {
    @Test fun measureAvailableCurrentNightEvidenceWithoutSendingAnotherRequest() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("audit_context") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val date = LocalDate.now()
        val prompt = context.assets.open("coach-system.txt").bufferedReader().use { it.readText() }
        val policy = context.assets.open("coaching-policy.md").bufferedReader().use { it.readText() }
        JournalStore(context).use { journal ->
            val cardsBefore = journal.coachCardSummaries(limit = 100).map { it.id }
            val facts = journal.readyCoachingContext(date, policy).toJson()
                .put("request_timezone", ZoneId.systemDefault().id)
            val latestId = journal.readableDatabase.rawQuery("SELECT id FROM progress_submissions WHERE date=? ORDER BY rowid DESC LIMIT 1", arrayOf(date.toString())).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            latestId?.let { facts.put("progress_submission", journal.progressSubmission(it)) }
            facts.put("telemetry", HealthConnectProbe(HealthConnectClient.getOrCreate(context)).coachingSnapshot(date))
                .put("telemetry_status", "checked")
            NightlyRecoveryStore(context).use { recovery ->
                facts.put("nightly_recovery", recovery.snapshot(date).put("screen_reader_configured", SamsungHealthScreenReader.configured(context))
                    .put("screen_reader_connected", SamsungHealthScreenReader.enabled))
                    .put("recovery_history", CoachingRecoveryHistory.compact(recovery.history(date.minusDays(30), date.minusDays(1)), date))
            }
            val packaged = try { CoachingRequestPackager.prepare(facts, prompt) } catch (e: CoachingPayloadTooLarge) {
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "CONTEXT_OWNER_BUDGET ${e.manifest}\n") })
                throw e
            }
            val manifest = packaged.retainedContext.getJSONObject("request_manifest")
            val report = JSONObject().put("sent_to_model", false).put("saved_new_card", false)
                .put("request_bytes_if_sent", manifest.getInt("serialized_request_bytes"))
                .put("current_night", manifest.getJSONObject("current_night"))
                .put("recovery_comparison_calendar_days", manifest.getInt("recovery_history_days"))
                .put("recovery_series_coverage", manifest.getJSONArray("recovery_series_coverage"))
                .put("freshness", manifest.getJSONObject("freshness"))
            assertEquals(cardsBefore, journal.coachCardSummaries(limit = 100).map { it.id })
            val night = packaged.retainedContext.getJSONObject("telemetry").getJSONObject("current_night")
            val series = night.getJSONArray("heart_rate_series")
            for (i in 0 until series.length()) assertFalse(series.getJSONObject(i).has("samples"))
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "CONTEXT_OWNER_AUDIT $report\n") })
        }
    }
}
