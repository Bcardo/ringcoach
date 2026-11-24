package com.bcardo.ringcoach

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Fixed categories and byte counts only; never copies manifest/user keys, values, references or hashes. */
internal data class CoachingRequestSize(
    val requestBytes: Int, val unpackedBytes: Int, val budgetBytes: Int, val targetBytes: Int,
    val encoded: Boolean, val blocked: Boolean, val systemBytes: Int, val userBytes: Int,
    val envelopeBytes: Int, val userStructureBytes: Int, val sections: Map<String, Int>,
) {
    fun toJson(): JSONObject = JSONObject().put("version", "request-size-v1").put("wire_policy", CoachingWireContext.VERSION)
        .put("request_bytes", requestBytes).put("unpacked_bytes", unpackedBytes).put("budget_bytes", budgetBytes)
        .put("target_bytes", targetBytes).put("encoding_applied", encoded).put("blocked", blocked)
        .put("system_content_bytes", systemBytes).put("user_content_bytes", userBytes).put("envelope_bytes", envelopeBytes)
        .put("user_structure_bytes", userStructureBytes).put("sections", safeSections(sections))

    companion object {
        private val allowed = setOf("request_date", "history_start", "request_timezone", "routine", "diet_plan", "journal_history",
            "nutrition_history", "exercise_baselines", "latest_workout_records", "training_extraction_status", "daily_nutrition",
            "standing_breakfast_rule", "supplement_history", "telemetry_status", "telemetry", "nightly_recovery", "ring_bluetooth",
            "recovery_history", "progress_submission", "previous_advice", "request_manifest", "request_at", "coaching_trigger", "context_encoding")

        private fun safeSections(sections: Map<String, Int>): JSONObject = JSONObject().apply {
            for ((key, bytes) in sections) {
                val category = key.takeIf { it in allowed } ?: "other"
                put(category, optInt(category) + bytes)
            }
        }

        fun measure(request: JSONObject, unpackedBytes: Int, budget: Int, target: Int, blocked: Boolean): CoachingRequestSize {
            val messages = request.getJSONArray("messages")
            val system = messages.getJSONObject(0).getString("content")
            val user = messages.getJSONObject(1).getString("content")
            val facts = JSONObject(user)
            val sections = linkedMapOf<String, Int>()
            for (key in facts.keys().asSequence()) {
                val wrapped = org.json.JSONArray().put(facts.get(key)).toString()
                val entry = JSONObject.quote(key) + ":" + wrapped.substring(1, wrapped.length - 1)
                val count = quotedBytes(entry) - 2 // Content of this field after the outer JSON string escaping.
                val category = key.takeIf { it in allowed } ?: "other"
                sections[category] = (sections[category] ?: 0) + count
            }
            val systemBytes = quotedBytes(system)
            val userBytes = quotedBytes(user)
            val total = request.toString().toByteArray(Charsets.UTF_8).size
            return CoachingRequestSize(total, unpackedBytes, budget, target, facts.optString("context_encoding") == CoachingWireContext.VERSION,
                blocked, systemBytes, userBytes, total - systemBytes - userBytes, userBytes - sections.values.sum(), sections)
        }
        private fun quotedBytes(value: String) = JSONObject.quote(value).toByteArray(Charsets.UTF_8).size
    }
}

/** Best-effort, bounded operational ledger shared by manual, progress and morning advice paths. */
internal class CoachingRequestSizeStore(context: Context, name: String = "coaching-request-sizes.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) = db.execSQL("CREATE TABLE sizes (id INTEGER PRIMARY KEY,at INTEGER NOT NULL,report_json TEXT NOT NULL)")
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported request-size ledger version")
    fun record(size: CoachingRequestSize) {
        writableDatabase.execSQL("INSERT INTO sizes(at,report_json) VALUES (?,?)", arrayOf<Any>(System.currentTimeMillis(), size.toJson().toString()))
        writableDatabase.execSQL("DELETE FROM sizes WHERE id NOT IN (SELECT id FROM sizes ORDER BY id DESC LIMIT 100)")
    }
    companion object {
        fun record(context: Context, size: CoachingRequestSize) {
            runCatching { CoachingRequestSizeStore(context).use { it.record(size) } }
        }
    }
}
