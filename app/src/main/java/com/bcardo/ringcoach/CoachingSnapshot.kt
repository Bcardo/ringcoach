package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject

/** Defence at both the producer and SQLite boundary: HR sample arrays are never archived. */
internal object CoachingSnapshot {
    fun retained(context: JSONObject): JSONObject = JSONObject(context.toString()).also(::strip)

    fun retainedJson(raw: String): String {
        val copy = JSONObject(raw)
        return if (strip(copy)) copy.toString() else raw
    }

    private fun strip(value: Any): Boolean {
        var changed = false
        if (value is JSONObject) for (key in value.keys().asSequence().toList()) {
            if (key == "samples" || key == "heart_rate_samples") {
                value.remove(key)
                value.put("samples_retention", "transient_sent_not_archived")
                changed = true
            } else changed = strip(value.get(key)) || changed
        }
        if (value is JSONArray) for (i in 0 until value.length()) changed = strip(value.get(i)) || changed
        return changed
    }
}
