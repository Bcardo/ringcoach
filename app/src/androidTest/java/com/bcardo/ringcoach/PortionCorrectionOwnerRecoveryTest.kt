package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Explicit owner-requested recovery only. No photo replay, new submission or operational content logs. */
@RunWith(AndroidJUnit4::class)
class PortionCorrectionOwnerRecoveryTest {
    @Test fun repairSavedPortionEstimate() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("recover_portion") == "true")
        val date = LocalDate.parse(requireNotNull(args.getString("recovery_date")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProgressRuntime.mutex.withLock {
            JournalStore(context).use { store ->
                check(store.nextPendingCoaching() == null)
                val db = store.writableDatabase
                val turns = db.rawQuery("""SELECT p.rowid,p.id,p.text,t.result_json,t.source_diet,t.source_additional
                    FROM progress_submissions p JOIN nutrition_turns t ON t.submission_id=p.id
                    WHERE p.date=? AND t.status='complete' ORDER BY p.rowid""", arrayOf(date.toString())).use { rows ->
                    buildList { while (rows.moveToNext()) add((0..5).map { rows.getString(it) }) }
                }
                check(turns.size == 2) { "Recovery requires the original two-turn state" }
                val first = turns.first(); val last = turns.last()
                val previous = NutritionExtractor.decode(first[3], nutritionSource(first[4], first[5]))
                val current = NutritionExtractor.decode(last[3], nutritionSource(last[4], last[5]))
                val settings = store.readSettings("")
                val job = PendingNutrition(date, last[1], last[4], last[5] + "\n" + DailyFoodState.evidenceContext(previous), true,
                    NutritionTurn(last[1], last[2], emptyList(), last[0].toLong(), first[1], previous, "pending"),
                    settings.diet, StandingBreakfast.parse(settings.instructions)?.takeIf { date >= it.effectiveDate })
                try { DailyFoodState.validate(job, current); error("Saved result is not an eligible stale portion correction") }
                catch (e: NutritionValidationException) { check(e.code == "portion_not_recalculated") }
                val oldFoods = previous.foodItems.associateBy { it.id }
                val affected = current.foodItems.filter { food -> oldFoods[food.id]?.let {
                    it.portion != food.portion && it.estimate.values == food.estimate.values
                } == true }.single()
                val prompt = context.assets.open("nutrition-extraction.txt").bufferedReader().use { it.readText() }
                val candidate = NutritionExtractor.extract(job, prompt, emptyList(), requireNotNull(QwenCredentials(context).read()))
                val proposed = candidate.foodItems.single { it.id == affected.id }
                check(portionMassGrams(proposed.portion) != null && portionMassGrams(proposed.portion) == portionMassGrams(affected.portion)) { "Recovery changed the owner's corrected portion" }
                val corrected = proposed.copy(portion = affected.portion)
                check(corrected.estimate.values != affected.estimate.values) { "Recovery still has stale nutrients" }
                check(corrected.estimate.questions.isEmpty()) { "Recovery still requires clarification" }
                // Keep all other already confirmed foods, supplements and breakfast resolution exactly.
                val repaired = DailyFoodState.calculated(job, current.copy(foodItems = current.foodItems.map {
                    if (it.id == affected.id) corrected else it
                }))
                val raw = repaired.toJson().toString()
                db.beginTransaction()
                try {
                    check(db.rawQuery("SELECT result_json FROM daily_food_state WHERE date=? AND submission_id=?", arrayOf(date.toString(), last[1])).use {
                        it.moveToFirst() && it.getString(0) == last[3]
                    }) { "Saved state changed during recovery" }
                    check(db.rawQuery("SELECT count(*) FROM progress_submissions WHERE date=?", arrayOf(date.toString())).use { it.moveToFirst(); it.getInt(0) } == 2)
                    db.execSQL("UPDATE daily_food_state SET result_json=? WHERE date=?", arrayOf(raw, date.toString()))
                    db.execSQL("UPDATE nutrition_turns SET result_json=? WHERE submission_id=? AND result_json=?", arrayOf(raw, last[1], last[3]))
                    db.execSQL("UPDATE nutrition_extractions SET result_json=? WHERE date=? AND source_revision=?", arrayOf(raw, date.toString(), last[1]))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                ProgressRuntime.changed()
            }
        }
    }

    @Test fun refreshAdviceFromCorrectedFacts() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("refresh_portion_advice") == "true")
        val date = LocalDate.parse(requireNotNull(args.getString("recovery_date")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProgressRuntime.mutex.withLock {
            JournalStore(context).use { store ->
                val previous = requireNotNull(store.latestCoachCard(date))
                val data = JSONObject(previous.contextJson)
                check(data.optString("coaching_trigger") != "owner_requested_portion_correction") { "Recovery advice already saved" }
                val policy = context.assets.open("coaching-policy.md").bufferedReader().use { it.readText() }
                val fresh = store.readyCoachingContext(date, policy).toJson()
                for (key in fresh.keys()) data.put(key, fresh.get(key))
                data.remove("previous_advice"); data.remove("request_manifest")
                data.put("request_at", Instant.now().toString()).put("coaching_trigger", "owner_requested_portion_correction")
                    .put("telemetry_status", "retained_capture")
                data.optJSONObject("telemetry")?.put("capture_mode", "retained_statistics")
                val prompt = context.assets.open("coach-system.txt").bufferedReader().use { it.readText() }
                val packaged = CoachingRequestPackager.prepare(data, prompt, onSize = { CoachingRequestSizeStore.record(context, it) })
                val advice = QwenClient.response(QwenClient.post(packaged.request, requireNotNull(QwenCredentials(context).read())))
                store.saveCoachCard(SavedCoachCard(UUID.randomUUID().toString(), date, Instant.now(), QWEN_MODEL,
                    COACH_PROMPT_VERSION, packaged.retainedContext.toString(), advice))
                ProgressRuntime.changed()
            }
        }
    }
}
