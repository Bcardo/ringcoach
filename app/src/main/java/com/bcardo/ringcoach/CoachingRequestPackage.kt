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
        val sync = recovery?.optJSONArray("sync") ?: JSONArray()
        val checks = (0 until sync.length()).map { sync.getJSONObject(it) }
        val history = context.optJSONObject("recovery_history")
        val sources = telemetry?.optJSONObject("metrics") ?: JSONObject()
        return JSONObject().put("version", "coaching-context-v1").put("request_date", date)
            .put("request_timezone", context.optString("request_timezone"))
            .put("progress_history_start", context.optString("history_start"))
            .put("health_read_start", telemetry?.opt("window_start") ?: JSONObject.NULL)
            .put("health_read_end", telemetry?.opt("window_end") ?: JSONObject.NULL)
            .put("telemetry_status", context.optString("telemetry_status"))
            .put("source_checks", JSONObject().apply { sources.keys().asSequence().forEach { key ->
                val metric = sources.getJSONObject(key)
                put(key, JSONObject().put("status", metric.optString("status")).put("record_count", metric.optInt("record_count"))
                    .put("sources", metric.optJSONArray("sources") ?: JSONArray()))
            } })
            .put("current_night", night?.optJSONObject("coverage") ?: JSONObject().put("status", "unavailable"))
            .put("hr_samples_retention", "transient_request_only_not_saved_or_replayed")
            .put("recovery_history_start", history?.opt("history_start") ?: JSONObject.NULL)
            .put("recovery_history_end", history?.opt("history_end") ?: JSONObject.NULL)
            .put("recovery_history_days", history?.optJSONArray("days")?.length() ?: 0)
            .put("recovery_series_coverage", history?.optJSONArray("series_coverage") ?: JSONArray())
            .put("freshness", JSONObject().put("missing_target_date_vendor_metrics", JSONArray(missing))
                .put("latest_sleeping_hrv_date", readings.filter { it.optString("metric") == "sleeping_hrv" }.maxOfOrNull { it.getString("wake_date") } ?: JSONObject.NULL)
                .put("screen_reader_configured", recovery?.opt("screen_reader_configured") ?: JSONObject.NULL)
                .put("screen_reader_connected", recovery?.opt("screen_reader_connected") ?: JSONObject.NULL)
                .put("screen_last_check", checks.firstOrNull { it.optString("source") == "samsung_health_screen" }?.opt("checked_at") ?: JSONObject.NULL)
                .put("target_date_recovery_complete", missing.isEmpty()))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
