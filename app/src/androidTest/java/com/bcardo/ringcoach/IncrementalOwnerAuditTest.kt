package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, read-only verification of the migrated owner's DB; emits counts, never photos or measurements. */
@RunWith(AndroidJUnit4::class)
class IncrementalOwnerAuditTest {
    @Test fun confirmedValuesAndReleasedPhotoCounts() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("audit_incremental") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        JournalStore(instrumentation.targetContext).use { store ->
            val db = store.readableDatabase
            fun count(sql: String): Int = db.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }
            val retainedReleased = count("SELECT count(*) FROM photo_meals p JOIN photo_provenance s ON s.photo_id=p.id WHERE s.released_at IS NOT NULL AND length(p.photo_jpeg)>0")
            assertEquals(0, retainedReleased)
            assertEquals(12, db.version)
            assertTrue(count("SELECT count(*) FROM journal_entries") > 0)
            assertEquals(1, count("SELECT count(*) FROM coaching_settings"))
            var compared = 0
            var matches = true
            store.latestCoachCard()?.let { card ->
                val days = JSONObject(card.contextJson).optJSONArray("daily_nutrition")
                for (i in 0 until (days?.length() ?: 0)) {
                    val day = days!!.getJSONObject(i)
                    val saved = day.optJSONObject("nutrition") ?: continue
                    db.rawQuery("SELECT result_json FROM nutrition_extractions WHERE date=?", arrayOf(day.getString("date"))).use {
                        if (it.moveToFirst() && !it.isNull(0)) {
                            val current = JSONObject(it.getString(0))
                            compared++
                            matches = matches && NUTRITION_FIELDS.all { field ->
                                (saved.getJSONObject(field).opt("value") as? Number)?.toDouble() ==
                                    (current.getJSONObject(field).opt("value") as? Number)?.toDouble()
                            }
                        }
                    }
                }
            }
            if (count("SELECT count(*) FROM nutrition_turns") == 0 && compared > 0) assertTrue(matches)
            val report = JSONObject().put("schema", db.version)
                .put("journal_days", count("SELECT count(*) FROM journal_entries"))
                .put("confirmed_food_states", count("SELECT count(*) FROM daily_food_state"))
                .put("converted_images_released", count("SELECT count(*) FROM photo_provenance WHERE released_at IS NOT NULL"))
                .put("released_inputs_with_image_bytes", retainedReleased)
                .put("prior_coach_comparisons", compared).put("prior_values_match", matches)
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", report.toString() + "\n") })
        }
    }
}
