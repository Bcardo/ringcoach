package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal const val COACH_REQUEST_BUDGET = 128 * 1024
internal class CoachingPayloadTooLarge(val manifest: JSONObject) : IllegalArgumentException("Required coaching evidence exceeds request budget")
internal data class CoachingRequestPackage(val request: JSONObject, val retainedContext: JSONObject)

/** Budget the actual model request; preserve target-night samples and current converted facts intact. */
internal object CoachingRequestPackager {
    fun prepare(original: JSONObject, systemPrompt: String, budget: Int = COACH_REQUEST_BUDGET): CoachingRequestPackage {
        require(budget > 0)
        val context = JSONObject(original.toString())
        val date = context.getString("request_date")
        val omissions = JSONArray()
        fun omission(section: String, count: Int, reason: String) {
            if (count > 0) omissions.put(JSONObject().put("section", section).put("count", count).put("reason", reason))
        }
        val telemetry = context.optJSONObject("telemetry")
        if (telemetry?.optString("capture_mode") == "retained_statistics") {
            omissions.put(JSONObject().put("section", "current_night_raw_hr")
                .put("count", telemetry.optJSONObject("current_night")?.optJSONObject("coverage")?.optInt("heart_rate_statistic_sample_count") ?: 0)
                .put("reason", "transient_trace_unavailable_retained_statistics_only"))
        }
        val journals = context.optJSONArray("journal_history") ?: JSONArray()
        val current = (0 until journals.length()).map { journals.getJSONObject(it) }.filter { it.optString("date") == date }
        omission("historical_journal_text", journals.length() - current.size, "historical_comparisons_use_structured_daily_facts")
        context.put("journal_history", JSONArray(current))
        val nutrition = context.optJSONArray("daily_nutrition") ?: JSONArray()
        var historicalFoods = 0
        var duplicateSupplements = 0
        for (i in 0 until nutrition.length()) {
            val day = nutrition.getJSONObject(i)
            val values = day.optJSONObject("nutrition") ?: continue
            if (context.has("supplement_history")) {
                duplicateSupplements += values.optJSONArray("supplement_events")?.length() ?: 0
                values.remove("supplement_events"); values.remove("revised_supplement_ids")
            }
            if (day.optString("date") != date) {
                historicalFoods += values.optJSONArray("food_items")?.length() ?: 0
                val compact = JSONObject().put("coverage", values.optString("coverage"))
                for (field in NUTRITION_FIELDS) values.optJSONObject(field)?.let { row ->
                    compact.put(field, JSONObject().put("value", row.opt("value") ?: JSONObject.NULL).put("kind", row.optString("kind")))
                }
                val foods = values.optJSONArray("food_items") ?: JSONArray()
                val sources = (0 until foods.length()).flatMap { index ->
                    val ids = foods.getJSONObject(index).getJSONArray("source_submission_ids")
                    (0 until ids.length()).map { ids.getString(it) }
                }.distinct()
                compact.put("source_submission_ids", JSONArray(sources)).put("recorded_food_count", foods.length())
                day.put("nutrition", compact)
            }
            val photos = day.optJSONArray("photo_meals")
            if (photos != null) { day.put("photo_source_count", photos.length()); day.remove("photo_meals") }
        }
        omission("historical_food_item_details", historicalFoods, "historical_daily_totals_status_and_source_ids_retained")
        omission("duplicate_supplement_copy", duplicateSupplements, "full_events_retained_once_in_supplement_history")
        val plan = JSONObject().put("routine", context.optString("routine")).put("diet_plan", context.optString("diet_plan"))
            .put("instructions", context.optString("instructions"))
        val manifest = manifest(context).put("omissions", omissions).put("budget_bytes", budget)
            .put("budget_status", "fits")
            .put("saved_plan_snapshot_id", sha256(plan.toString().toByteArray(Charsets.UTF_8)))
            .put("plan_scope", "current_user_saved_settings_snapshot_not_effective_date_plan_memory")
        context.put("request_manifest", manifest)
        fun serialize(): JSONObject {
            // The byte count is part of the sent manifest. Iterate until its decimal width is stable.
            repeat(10) {
                val request = QwenClient.request(context, systemPrompt)
                val bytes = request.toString().toByteArray(Charsets.UTF_8).size
                if (manifest.optInt("serialized_request_bytes", -1) == bytes) return request
                manifest.put("serialized_request_bytes", bytes)
            }
            error("Request byte count did not stabilize")
        }
        var request = serialize()
        if (manifest.getInt("serialized_request_bytes") > budget && context.has("previous_advice")) {
            context.remove("previous_advice")
            omission("previous_advice", 1, "request_budget_saved_plans_and_current_facts_preserved")
            request = serialize()
        }
        if (manifest.getInt("serialized_request_bytes") > budget && current.isNotEmpty()) {
            context.put("journal_history", JSONArray())
            omission("current_day_raw_journal_copy", current.size, "request_budget_trigger_and_complete_converted_food_workout_supplement_facts_preserved")
            request = serialize()
        }
        if (manifest.getInt("serialized_request_bytes") > budget) {
            // Retain every sample and exact timestamp/value, but write repeated field names once.
            val series = telemetry?.optJSONObject("current_night")?.optJSONArray("heart_rate_series")
            var encoded = 0
            for (i in 0 until (series?.length() ?: 0)) {
                val record = series!!.getJSONObject(i)
                val samples = record.optJSONArray("samples") ?: continue
                if (samples.length() == 0 || samples.optJSONObject(0) == null) continue
                val keys = samples.getJSONObject(0).keys().asSequence().toSet()
                if (keys !in listOf(setOf("time", "beats_per_minute"), setOf("time", "beats_per_minute", "session_refs"))) continue
                if ((0 until samples.length()).any { samples.optJSONObject(it)?.keys()?.asSequence()?.toSet() != keys }) continue
                val columns = listOf("time", "beats_per_minute", "session_refs").filter { it in keys }
                record.put("samples", JSONArray((0 until samples.length()).map { index ->
                    JSONArray(columns.map { field -> samples.getJSONObject(index).get(field) })
                })).put("sample_columns", JSONArray(columns)).put("sample_encoding", "rows_in_sample_columns_order_lossless")
                encoded += samples.length()
            }
            if (encoded > 0) {
                manifest.put("lossless_hr_sample_encoding", JSONObject().put("sample_count", encoded)
                    .put("encoding", "rows_in_sample_columns_order_lossless").put("samples_removed", 0))
                request = serialize()
            }
        }
        manifest.put("budget_status", "fits")
        request = serialize()
        if (manifest.getInt("serialized_request_bytes") > budget) {
            manifest.put("budget_status", "required_evidence_too_large_not_sent")
            throw CoachingPayloadTooLarge(JSONObject(manifest.toString()))
        }
        val retained = CoachingSnapshot.retained(context)
        retained.getJSONObject("request_manifest").put("model_request_sha256", sha256(request.toString().toByteArray(Charsets.UTF_8)))
            .put("snapshot_kind", "retained_facts_and_manifest_hr_samples_not_archived")
        return CoachingRequestPackage(request, retained)
    }

    private fun manifest(context: JSONObject): JSONObject {
        val date = context.getString("request_date")
        val telemetry = context.optJSONObject("telemetry")
        val night = telemetry?.optJSONObject("current_night")
        val recovery = context.optJSONObject("nightly_recovery")
        val records = recovery?.optJSONArray("records") ?: JSONArray()
        val readings = (0 until records.length()).map { records.getJSONObject(it) }
        val missing = DAILY_RECOVERY_METRICS.filter { metric -> readings.none { it.optString("wake_date") == date && it.optString("metric") == metric } }
        val history = context.optJSONObject("recovery_history")
        val ring = context.optJSONObject("ring_bluetooth")
        val nutritionDays = context.optJSONArray("daily_nutrition") ?: JSONArray()
        val breakfast = (0 until nutritionDays.length()).map { nutritionDays.getJSONObject(it) }
            .firstOrNull { it.optString("date") == date }?.optJSONObject("standing_breakfast")
        val ringSessions = ring?.optJSONArray("sessions") ?: JSONArray()
        val ringToday = (0 until ringSessions.length()).map { ringSessions.getJSONObject(it) }.filter { it.optString("wake_date") == date }
        val sources = telemetry?.optJSONObject("metrics") ?: JSONObject()
        return JSONObject().put("version", "coaching-context-v1").put("request_date", date)
            .put("request_timezone", context.optString("request_timezone"))
            .put("progress_history_start", context.optString("history_start"))
            .put("health_read_start", telemetry?.opt("window_start") ?: JSONObject.NULL)
            .put("health_read_end", telemetry?.opt("window_end") ?: JSONObject.NULL)
            .put("telemetry_status", context.optString("telemetry_status"))
            .put("health_captured_at", telemetry?.opt("captured_at") ?: JSONObject.NULL)
            .put("health_capture_mode", telemetry?.optString("capture_mode") ?: "unspecified")
            .put("source_checks", JSONObject().apply { sources.keys().asSequence().forEach { key ->
                val metric = sources.getJSONObject(key)
                put(key, JSONObject().put("status", metric.optString("status")).put("record_count", metric.optInt("record_count"))
                    .put("sources", metric.optJSONArray("sources") ?: JSONArray()))
            } })
            .put("current_night", night?.optJSONObject("coverage") ?: JSONObject().put("status", "unavailable"))
            .put("standing_breakfast", breakfast ?: JSONObject().put("mode", "not_enabled"))
            .put("ring_bluetooth", JSONObject().put("source", ring?.optString("source") ?: "not_checked")
                .put("target_date_session_count", ringToday.size)
                .put("target_date_hrv_window_count", ringToday.sumOf { it.optJSONObject("metrics")?.optJSONObject("hrv_rmssd")?.optInt("sample_count") ?: 0 })
                .put("validation", "experimental_same_night_reference_pending")
                .put("raw_samples_sent", false))
            .put("hr_samples_retention", "transient_request_only_not_saved_or_replayed")
            .put("recovery_history_start", history?.opt("history_start") ?: JSONObject.NULL)
            .put("recovery_history_end", history?.opt("history_end") ?: JSONObject.NULL)
            .put("recovery_history_days", history?.optJSONArray("days")?.length() ?: 0)
            .put("recovery_series_coverage", history?.optJSONArray("series_coverage") ?: JSONArray())
            .put("freshness", JSONObject().put("missing_target_date_vendor_metrics", JSONArray(missing))
                .put("latest_sleeping_hrv_date", readings.filter { it.optString("metric") == "sleeping_hrv" }.maxOfOrNull { it.getString("wake_date") } ?: JSONObject.NULL)
                .put("screen_capture_mode", "retired")
                .put("target_date_recovery_complete", missing.isEmpty()))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
