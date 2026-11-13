package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DetailedCoachingStoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val date = LocalDate.of(2026, 11, 10)
    private fun evidence(): JSONObject {
        val start = Instant.parse("2026-11-10T01:00:00Z")
        val sleep = JSONObject().put("start", start.toString()).put("end", start.plusSeconds(3600).toString())
            .put("source_app", "synthetic.sleep").put("source_device_type", JSONObject.NULL)
            .put("stages", JSONArray().put(JSONObject().put("start", start.toString()).put("end", start.plusSeconds(3600).toString()).put("stage", "light")))
        val heart = JSONObject().put("source_app", "synthetic.heart").put("start", start.toString()).put("end", start.plusSeconds(3600).toString())
            .put("samples", JSONArray((0 until 6).map { i -> JSONObject().put("time", start.plusSeconds(i * 600L).toString()).put("beats_per_minute", 60 + i) }))
        return CurrentNightEvidence.build(date, ExportWindow(start.minusSeconds(86400), start.plusSeconds(86400)),
            listOf(MetricResult(MetricKind.SLEEP, null, ProbeStatus.AVAILABLE, exportRecords = listOf(sleep)),
                MetricResult(MetricKind.HEART_RATE, null, ProbeStatus.AVAILABLE, exportRecords = listOf(heart))), java.time.ZoneId.of("UTC"))
    }

    private fun input(store: JournalStore) = store.readCoachingContext(date, "合成测试约束").toJson()
        .put("telemetry", JSONObject().put("current_night", evidence())).put("telemetry_status", "checked")
        .put("request_timezone", "UTC")

    @Test fun sqliteBoundaryStripsTraceEvenIfCallerPassesUnredactedContextAndSurvivesRestart() {
        val name = "trace-retention-${UUID.randomUUID()}.db"
        try {
            JournalStore(context, name).use { store ->
                val raw = input(store)
                store.saveCoachCard(SavedCoachCard("retention", date, Instant.now(), QWEN_MODEL, COACH_PROMPT_VERSION,
                    raw.toString(), CoachAdvice(listOf("训练"), listOf("饮食"), listOf("依据"))))
                assertTrue(raw.getJSONObject("telemetry").getJSONObject("current_night").getJSONArray("heart_rate_series").getJSONObject(0).has("samples"))
            }
            JournalStore(context, name).use { store ->
                val saved = JSONObject(store.coachCard("retention")!!.contextJson)
                val night = saved.getJSONObject("telemetry").getJSONObject("current_night")
                val series = night.getJSONArray("heart_rate_series").getJSONObject(0)
                assertFalse(series.has("samples"))
                assertEquals(6, series.getInt("sample_count"))
                assertEquals(1, night.getJSONArray("sessions").getJSONObject(0).getJSONArray("stages").length())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun syntheticLiveDetailedRequestSavesManifestWithoutSampleStream() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_detailed") == "true")
        val name = "detailed-live-${UUID.randomUUID()}.db"
        try { JournalStore(context, name).use { store ->
            val key = QwenCredentials(context).read() ?: error("Saved Qwen key required")
            val prompt = context.assets.open("coach-system.txt").bufferedReader().use { it.readText() }
            val packaged = CoachingRequestPackager.prepare(input(store), prompt)
            val sent = JSONObject(packaged.request.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals(6, sent.getJSONObject("telemetry").getJSONObject("current_night").getJSONArray("heart_rate_series").getJSONObject(0).getJSONArray("samples").length())
            val advice = QwenClient.response(QwenClient.post(packaged.request, key))
            store.saveCoachCard(SavedCoachCard("live-detail", date, Instant.now(), QWEN_MODEL, COACH_PROMPT_VERSION,
                packaged.retainedContext.toString(), advice))
            val saved = JSONObject(store.coachCard("live-detail")!!.contextJson)
            assertTrue(saved.getJSONObject("request_manifest").has("model_request_sha256"))
            assertFalse(saved.getJSONObject("telemetry").getJSONObject("current_night").getJSONArray("heart_rate_series").getJSONObject(0).has("samples"))
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "DETAILED_LIVE sent_samples=6 saved_samples=0 request_bytes=${packaged.request.toString().toByteArray(Charsets.UTF_8).size}\n") })
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun canonicalArchivedComparisonsExcludeTargetDayAndPreserveCoverage() {
        val name = "history-context-${UUID.randomUUID()}.db"
        try { NightlyRecoveryStore(context, name).use { store ->
            store.put(NightlyMetric(date.minusDays(1), "energy_score", 70.5, "score", "samsung_health_sdk", "prior"))
            store.put(NightlyMetric(date, "energy_score", 90.0, "score", "samsung_health_sdk", "target"))
            val history = CoachingRecoveryHistory.compact(store.history(date.minusDays(30), date.minusDays(1)), date)
            assertEquals(30, history.getJSONArray("days").length())
            assertEquals(1, history.getJSONArray("series_coverage").getJSONObject(0).getInt("valid_night_count"))
            assertFalse(history.toString().contains("\"source_record_id\":\"target\""))
            assertEquals(70.5, history.getJSONArray("days").getJSONObject(29).getJSONObject("energy_score").getDouble("value"), 0.0)
        } } finally { context.deleteDatabase(name) }
    }
}
