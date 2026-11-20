package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

private fun canonicalJson(value: Any): String = when (value) {
    is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonicalJson(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
    else -> JSONArray().put(value).toString().let { it.substring(1, it.length - 1) }
}
private fun JSONObject.sameJson(other: JSONObject): Boolean = canonicalJson(this) == canonicalJson(other)

/** Independent test decoder verifies the alternate wire schema can reconstruct every original value. */
internal object CoachingWireTestDecoder {
    private fun rows(value: Any): JSONArray {
        if (value is JSONArray) return value
        val table = value as JSONObject
        val common = table.getJSONObject("common")
        val columns = table.getJSONArray("columns")
        val points = table.getJSONArray("rows")
        return JSONArray((0 until points.length()).map { i -> JSONObject(common.toString()).apply {
            val point = points.getJSONArray(i)
            for (j in 0 until columns.length()) put(columns.getString(j), point.get(j))
        } })
    }
    fun expand(wire: JSONObject): JSONObject = JSONObject(wire.toString()).apply {
        if (optString("context_encoding") != CoachingWireContext.VERSION) return@apply
        remove("context_encoding")
        optJSONObject("supplement_history")?.takeIf { it.optString("standing_plan_ref") == "user_saved_settings" }?.let {
            it.remove("standing_plan_ref")
            it.put("standing_plan", JSONObject().put("routine", get("routine")).put("diet_plan", get("diet_plan")).put("instructions", get("instructions")))
        }
        optJSONObject("recovery_history")?.optJSONObject("days")?.let { history ->
            val dates = history.getJSONArray("wake_dates")
            val days = (0 until dates.length()).map { JSONObject().put("wake_date", dates.get(it)) }
            val series = history.getJSONObject("series")
            for (metric in series.keys()) {
                val records = rows(series.get(metric))
                for (i in days.indices) days[i].put(metric, records.get(i))
            }
            getJSONObject("recovery_history").put("days", JSONArray(days))
        }
        optJSONObject("nightly_recovery")?.let { if (it.optJSONObject("records") != null) it.put("records", rows(it.get("records"))) }
        optJSONObject("ring_bluetooth")?.optJSONObject("sessions")?.let { table ->
            val sessions = rows(table.get("sessions"))
            for (i in 0 until sessions.length()) sessions.getJSONObject(i).put("metrics", JSONObject())
            val metrics = table.getJSONObject("metric_series")
            for (metric in metrics.keys()) {
                val series = metrics.getJSONObject(metric)
                val indices = series.getJSONArray("session_indices")
                val values = rows(series.get("values"))
                for (i in 0 until indices.length()) sessions.getJSONObject(indices.getInt(i)).getJSONObject("metrics").put(metric, values.get(i))
            }
            getJSONObject("ring_bluetooth").put("sessions", sessions)
        }
        optJSONObject("telemetry")?.optJSONObject("current_night")?.optJSONArray("heart_rate_series")?.let { series ->
            for (i in 0 until series.length()) {
                val record = series.getJSONObject(i)
                if (record.optString("sample_encoding") != "rows_in_sample_columns_order_lossless") continue
                val columns = record.getJSONArray("sample_columns")
                val points = record.getJSONArray("samples")
                record.put("samples", JSONArray((0 until points.length()).map { row -> JSONObject().apply {
                    for (column in 0 until columns.length()) put(columns.getString(column), points.getJSONArray(row).get(column))
                } }))
                record.remove("sample_columns"); record.remove("sample_encoding")
            }
        }
    }
}

class CoachingWireContextTest {
    companion object {
        fun context(kind: String = "mixed", sessions: Int = 4, samples: Int = 800): JSONObject {
            val day = LocalDate.of(2026, 6, 12)
            val plan = JSONObject().put("routine", "Keep the saved exercise order. ".repeat(40)).put("diet_plan", "Use the saved target range. ".repeat(40))
                .put("instructions", "Do not change confirmed facts. ".repeat(40))
            val result = JSONObject(plan.toString()).put("request_date", day.toString()).put("request_timezone", "UTC")
                .put("history_start", day.minusDays(6).toString()).put("daily_nutrition", JSONArray())
                .put("journal_history", JSONArray()).put("progress_submission", JSONObject().put("text", kind).put("id", "synthetic-trigger"))
                .put("supplement_history", JSONObject().put("standing_plan", JSONObject(plan.toString())).put("days", JSONArray()))
                .put("latest_workout_records", JSONArray().put(JSONObject().put("exercise", "Synthetic press").put("performance", "3 x 8 at 20 kg").put("status", "completed")))
            result.put("recovery_history", JSONObject().put("history_start", day.minusDays(30).toString()).put("history_end", day.minusDays(1).toString())
                .put("days", JSONArray((1..30).map { offset -> JSONObject().put("wake_date", day.minusDays(offset.toLong()).toString()).apply {
                    for (metric in listOf("energy_score", "sleeping_hrv")) put(metric, JSONObject().put("status", if (offset % 4 == 0) "not_checked" else "available")
                        .put("value", if (offset % 4 == 0) JSONObject.NULL else 40 + offset).put("unit", if (metric == "energy_score") "score" else "ms")
                        .put("metric_definition", "synthetic_vendor_daily_$metric").put("source", if (offset % 4 == 0) JSONObject.NULL else "synthetic_source")
                        .put("revision", 1).put("last_verified_at", "2026-06-12T10:00:00Z").put("source_record_id", "synthetic-$offset"))
                } })))
            result.put("nightly_recovery", JSONObject().put("records", JSONArray((0..6).map { offset -> JSONObject().put("wake_date", day.minusDays(offset.toLong()).toString())
                .put("metric", "energy_score").put("source", "synthetic_source").put("unit", "score").put("value", 70 + offset).put("revision", 1) })))
            result.put("ring_bluetooth", JSONObject().put("source", "synthetic_ring").put("sessions", JSONArray((0 until sessions).map { index ->
                JSONObject().put("wake_date", day.minusDays((index / 2).toLong()).toString()).put("session_ref", "session-$index")
                    .put("start", "2026-06-12T01:00:00Z").put("end", "2026-06-12T09:00:00Z").put("validation", "pending_reference")
                    .put("capture_origins", JSONArray(listOf("synthetic"))).put("metrics", JSONObject().apply {
                        for (metric in listOf("hrv_rmssd", "heart_rate", "respiration", "movement")) put(metric, JSONObject().put("mean", 60.25 + index)
                            .put("sample_count", 100).put("rejected_sample_count", 0).put("definition", "synthetic_$metric")
                            .put("unit", "protocol_units_pending_reference_validation").put("coverage_minutes", 100).put("source_label", "synthetic"))
                    })
            })))
            result.put("telemetry", JSONObject().put("current_night", JSONObject().put("coverage", JSONObject().put("session_count", sessions)
                .put("heart_rate_sample_count", samples)).put("heart_rate_series", JSONArray().put(JSONObject().put("source_record_id", "synthetic-hr")
                .put("samples", JSONArray((0 until samples).map { index -> JSONObject().put("time", Instant.parse("2026-06-12T01:00:00Z").plusSeconds(index.toLong()).toString())
                    .put("beats_per_minute", 60.25 + index % 7).put("session_refs", JSONArray(listOf("session-0", "session-1"))) }))))))
            return result
        }
    }

    @Test fun fullMixedEvidenceReconstructsExactlyWithoutMutationOrSessionMerging() {
        val source = context(); val before = source.toString()
        val packed = CoachingWireContext.encode(source)
        assertTrue(source.sameJson(CoachingWireTestDecoder.expand(packed)))
        assertEquals(before, source.toString())
        assertTrue(packed.sameJson(CoachingWireContext.encode(packed)))
    }

    @Test fun onlyIdenticalPlanAliasesAndHeterogeneousRecordsKeepAbsenceDistinctFromNull() {
        val source = context(samples = 0)
        source.getJSONObject("supplement_history").getJSONObject("standing_plan").put("instructions", "Different saved policy")
        val observations = source.getJSONObject("nightly_recovery").getJSONArray("records")
        observations.getJSONObject(0).put("special", JSONObject.NULL)
        val packed = CoachingWireContext.encode(source)
        assertTrue(packed.getJSONObject("supplement_history").has("standing_plan"))
        assertTrue(packed.getJSONObject("nightly_recovery").get("records") is JSONArray)
        assertTrue(source.sameJson(CoachingWireTestDecoder.expand(packed)))
        val partial = JSONObject().put("routine", "Only routine").put("supplement_history", JSONObject()
            .put("standing_plan", JSONObject().put("routine", "Only routine")))
        val partialWire = CoachingWireContext.encode(partial)
        assertTrue(partialWire.getJSONObject("supplement_history").has("standing_plan"))
        assertTrue(partial.sameJson(CoachingWireTestDecoder.expand(partialWire)))
    }

    @Test fun actualSerializerCorpusFitsWithHeadroomAndRetainsEverySample() {
        for (kind in listOf("question", "mixed", "correction", "morning", "retained", "long_plans", "full_history")) {
            val source = context(kind, sessions = if (kind == "question") 0 else 6, samples = if (kind == "question") 0 else 1200)
            if (kind == "correction") {
                source.getJSONArray("latest_workout_records").getJSONObject(0).put("performance", "3 x 8 at 18 kg").put("update_evidence", "Correction: 18 kg")
                source.put("previous_advice", JSONObject().put("training", JSONArray(listOf("Old suggestion: 20 kg"))))
            }
            if (kind == "morning") {
                source.remove("progress_submission")
                source.put("coaching_trigger", "new_ring_sleep_transfer")
            }
            if (kind == "retained") {
                val telemetry = source.getJSONObject("telemetry").put("capture_mode", "retained_statistics")
                val night = telemetry.getJSONObject("current_night")
                night.getJSONArray("heart_rate_series").getJSONObject(0).remove("samples")
                night.getJSONObject("coverage").put("heart_rate_sample_count", 0).put("heart_rate_statistic_sample_count", 1200)
            }
            if (kind == "long_plans") {
                for (key in listOf("routine", "diet_plan", "instructions")) {
                    source.put(key, source.getString(key).repeat(6))
                    source.getJSONObject("supplement_history").getJSONObject("standing_plan").put(key, source.getString(key))
                }
            }
            if (kind == "full_history") {
                val days = source.getJSONObject("recovery_history").getJSONArray("days")
                for (i in 0 until days.length()) for (metric in listOf("energy_score", "sleeping_hrv"))
                    days.getJSONObject(i).getJSONObject(metric).put("status", "available").put("value", 40 + i).put("source", "synthetic_source")
            }
            val packed = CoachingRequestPackager.prepare(source, "Use only source-backed facts.")
            assertTrue("Corpus needs headroom: $kind", packed.size.requestBytes < 112 * 1024)
            assertTrue(packed.size.encoded)
            assertTrue(packed.size.requestBytes < packed.size.unpackedBytes)
            val facts = JSONObject(packed.request.getJSONArray("messages").getJSONObject(1).getString("content"))
            // The API places this one copy in the system message.
            facts.put("instructions", source.getString("instructions"))
            val restored = CoachingWireTestDecoder.expand(facts)
            assertTrue(source.getJSONObject("telemetry").sameJson(restored.getJSONObject("telemetry")))
            assertTrue(source.getJSONObject("ring_bluetooth").sameJson(restored.getJSONObject("ring_bluetooth")))
            assertEquals(packed.size.requestBytes, packed.request.toString().toByteArray(Charsets.UTF_8).size)
            println("CONTEXT_BENCHMARK case=$kind unpacked_bytes=${packed.size.unpackedBytes} packed_bytes=${packed.size.requestBytes} target_bytes=${packed.size.targetBytes}")
        }
    }

    @Test fun sizeAccountingReconcilesAndNeverLeaksUnknownKeysOrText() {
        val source = context().put("SECRET_IDENTIFIER", "private UI text and health values")
        val packed = CoachingRequestPackager.prepare(source, "Private instructions")
        val size = packed.size
        assertEquals(size.requestBytes, size.systemBytes + size.userBytes + size.envelopeBytes)
        assertEquals(size.userBytes, size.userStructureBytes + size.sections.values.sum())
        assertTrue(size.userStructureBytes > 0)
        val report = size.toJson().toString()
        for (secret in listOf("SECRET_IDENTIFIER", "private UI", "Private instructions", "synthetic-trigger", "2026-06-12", "20 kg")) assertFalse(report.contains(secret))
        assertTrue(size.sections.containsKey("other"))
        val manual = size.copy(sections = mapOf("SECRET_IDENTIFIER" to 12)).toJson().toString()
        assertFalse(manual.contains("SECRET_IDENTIFIER"))
    }

    @Test fun failedBudgetStillReportsSafeCountsAndReporterFailureCannotMaskOutcome() {
        var observed: CoachingRequestSize? = null
        try {
            CoachingRequestPackager.prepare(context(), "Coach", 1000) { observed = it; error("Synthetic diagnostic failure") }
            fail("Oversized mandatory evidence sent")
        } catch (e: CoachingPayloadTooLarge) {
            assertNotNull(observed); assertTrue(e.size.blocked)
            assertEquals(e.size.requestBytes, e.manifest.getInt("serialized_request_bytes"))
            assertTrue(e.size.sections.containsKey("telemetry"))
        }
        assertNotNull(CoachingRequestPackager.prepare(JSONObject().put("request_date", "2026-06-12"), "Coach", onSize = { error("Synthetic reporting failure") }))
    }
}
