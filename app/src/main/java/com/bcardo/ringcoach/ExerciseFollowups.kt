package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

internal data class ExerciseNote(val ref: String, val text: String) {
    fun toJson() = JSONObject().put("exercise_ref", ref).put("text", text)
    companion object {
        fun decode(value: Any?): List<ExerciseNote> {
            val rows = value as? JSONArray ?: return emptyList()
            if (rows.length() > 4) return emptyList()
            val parsed = (0 until rows.length()).mapNotNull { i -> runCatching {
                val row = rows.getJSONObject(i)
                require(row.keys().asSequence().toSet() == setOf("exercise_ref", "text"))
                val ref = row.getString("exercise_ref"); val text = row.getString("text").trim()
                require(Regex("e[1-9][0-9]{0,3}|fx_[a-f0-9]{24}").matches(ref))
                require(text.length in 1..240 && (text.startsWith("下次") || text.startsWith("下一次")))
                ExerciseNote(ref, text)
            }.getOrNull() }
            val duplicate = parsed.groupingBy { it.ref }.eachCount().filterValues { it > 1 }.keys
            return parsed.filterNot { it.ref in duplicate }
        }
    }
}

internal data class FollowupTarget(val index: Int, val exercise: String, val ref: String, val basis: String)
internal data class StoredExerciseNote(val text: String, val generatedAt: Instant, val date: LocalDate)

/** Identifies proposals, not user facts. No readings, text or identifiers are emitted to logs. */
internal object ExerciseFollowups {
    private fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        else -> JSONArray().put(value).toString()
    }
    fun targets(context: JSONObject): List<FollowupTarget> {
        val date = runCatching { LocalDate.parse(context.getString("request_date")) }.getOrNull() ?: return emptyList()
        val baselines = context.optJSONArray("exercise_baselines") ?: return emptyList()
        val rows = (0 until baselines.length()).mapNotNull { baselines.optJSONObject(it) }
        if (rows.size != baselines.length()) return emptyList()
        val uniqueNames = rows.groupingBy { it.optString("exercise") }.eachCount()
        val actuals = context.optJSONArray("latest_workout_records") ?: JSONArray()
        return rows.mapIndexedNotNull { index, baseline -> runCatching {
            val name = baseline.getString("exercise")
            require(name.isNotBlank() && uniqueNames[name] == 1)
            val records = (0 until actuals.length()).mapNotNull { actuals.optJSONObject(it) }.filter {
                it.optString("exercise") == name && it.optString("status") in setOf("completed", "partial") &&
                    runCatching { LocalDate.parse(it.getString("date")) <= date }.getOrDefault(false)
            }
            val lastDate = records.maxOfOrNull { it.getString("date") } ?: return@runCatching null
            require(LocalDate.parse(lastDate) >= date.minusDays(13))
            val latest = records.filter { it.getString("date") == lastDate }
            val identity = JSONObject()
            for (key in listOf("training_day", "exercise", "exercise_number", "reference_notes", "snapshot_date")) identity.put(key, baseline.get(key))
            val basis = JSONObject().put("contract", "exercise-followup-v1").put("baseline", identity)
                .put("routine", context.optString("routine")).put("instructions", context.optString("instructions"))
                .put("performance", JSONArray(latest.map { record -> JSONObject().apply {
                    for (key in listOf("date", "exercise", "performance", "evidence", "status", "source_submission_ids", "update_evidence"))
                        if (record.has(key)) put(key, record.get(key))
                } }))
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical(basis).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }.take(24)
            FollowupTarget(index, name, "e${index + 1}", "fx_$digest")
        }.getOrNull() }
    }

    fun decorate(context: JSONObject): JSONObject = JSONObject(context.toString()).apply {
        val baselines = optJSONArray("exercise_baselines")
        if (baselines != null) for (i in 0 until baselines.length()) baselines.optJSONObject(i)?.remove("followup_ref")
        for (target in targets(this)) baselines!!.getJSONObject(target.index).put("followup_ref", target.ref)
    }

    fun accepted(advice: CoachAdvice, context: JSONObject): CoachAdvice {
        val targets = targets(context).associateBy { it.ref }
        return advice.copy(exerciseNotes = advice.exerciseNotes.mapNotNull { note -> targets[note.ref]?.let { note.copy(ref = it.basis) } })
    }
}

/** Bounded advice-only scan; no large context snapshots or fresh model calls on UI refresh. */
internal fun JournalStore.exerciseFollowups(context: JSONObject, date: LocalDate, now: Instant = Instant.now()): Map<String, StoredExerciseNote> {
    val eligible = ExerciseFollowups.targets(context).associateBy { it.basis }
    if (eligible.isEmpty()) return emptyMap()
    return readableDatabase.rawQuery("""SELECT date,created_at,advice_json FROM coach_cards
        WHERE date BETWEEN ? AND ? AND created_at<=? ORDER BY created_at DESC,id DESC LIMIT 120""",
        arrayOf(date.minusDays(13).toString(), date.toString(), now.toEpochMilli().toString())).use { rows -> buildMap {
        while (rows.moveToNext()) {
            val notes = runCatching { ExerciseNote.decode(JSONObject(rows.getString(2)).opt("exercise_notes")) }.getOrDefault(emptyList())
            for (note in notes) eligible[note.ref]?.let { target ->
                if (!containsKey(target.exercise)) put(target.exercise, StoredExerciseNote(note.text, Instant.ofEpochMilli(rows.getLong(1)), LocalDate.parse(rows.getString(0))))
            }
        }
    } }
}
