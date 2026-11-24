package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking

/** Explicit owner-requested activation; personal recipe is staged privately, never baked into the APK. */
@RunWith(AndroidJUnit4::class)
class StandingBreakfastOwnerSetupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test fun activateAndReconcileSavedRule() {
        assumeTrue(args.getString("standing_breakfast_setup") == "true")
        val file = File(context.filesDir, "standing-breakfast-rule.json")
        assertTrue(file.isFile)
        val input = JSONObject(file.readText())
        val fields = input.getJSONObject("nutrients")
        val rule = StandingBreakfast(LocalDate.parse(input.getString("effective_date")), input.getString("description"), MEAL_FIELDS.associateWith { fields.getDouble(it) })
        JournalStore(context).use { store ->
            val settings = store.readSettings("")
            val foodsBefore = IncrementalNutritionStore(store.readableDatabase).confirmed(LocalDate.now())
            val saved = store.saveSettings(settings.copy(instructions = StandingBreakfast.replace(settings.instructions, rule)))
            assertEquals(settings.routine, saved.routine); assertEquals(settings.diet, saved.diet)
            assertEquals(rule, StandingBreakfast.parse(saved.instructions))
            val key = QwenCredentials(context).read()
            if (store.breakfastReconciliationInput(LocalDate.now()) != null) {
                assertNotNull("Saved model key required to reconcile existing breakfast", key)
                assertTrue(BreakfastReconciliation.reconcile(store, LocalDate.now(), key!!))
            }
            val foodsAfter = IncrementalNutritionStore(store.readableDatabase).confirmed(LocalDate.now())
            assertEquals(foodsBefore?.foodItems, foodsAfter?.foodItems)
            assertEquals(foodsBefore?.values, foodsAfter?.values)
            val day = store.readCoachingContext(LocalDate.now(), "").nutritionDays.first()
            assertNotNull(day.breakfast)
            assertFalse(day.breakfast!!.getString("mode") in setOf("needs_reconciliation", "waiting_for_recognition", "unresolved"))
            File(context.filesDir, "standing-breakfast-setup-report.json").writeText(JSONObject()
                .put("rule_saved", true).put("effective_date", rule.effectiveDate.toString()).put("mode", day.breakfast.getString("mode"))
                .put("default_included", day.breakfast.getBoolean("included")).put("confirmed_food_facts_unchanged", true).toString())
        }
        file.delete()
    }

    @Test fun verifyLiveRecognitionExceptions() {
        assumeTrue(args.getString("standing_breakfast_live_cases") == "true")
        val rule = JournalStore(context).use { StandingBreakfast.parse(it.readSettings("").instructions) }!!
        val key = QwenCredentials(context).read()!!
        val prompt = context.assets.open("nutrition-extraction.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        fun extract(id: String, text: String, previous: NutritionExtraction?): NutritionExtraction {
            val job = PendingNutrition(LocalDate.now(), id, "", text, true,
                NutritionTurn(id, text, emptyList(), 1, null, previous, "pending"), breakfastRule = rule)
            return NutritionExtractor.extract(job, prompt, emptyList(), key)
        }
        val first = extract("breakfast-case-1", "今天早餐就是固定早餐", null)
        assertEquals("confirmed_default", first.breakfast?.mode)
        assertTrue(first.foodItems.isEmpty())
        val twice = extract("breakfast-case-2", "早餐照旧，还是刚才说的那一份固定早餐", first)
        assertTrue(twice.foodItems.isEmpty())
        val projected = StandingBreakfastProjection.apply(NutritionDay(LocalDate.now(), "complete", twice), rule)
        assertEquals(rule.nutrients.getValue("intake_kcal"), projected.result!!.values.getValue("intake_kcal").value!!, 0.0)
        val skipped = extract("breakfast-case-3", "更正一下，今天早餐没吃", first)
        assertEquals("skipped", skipped.breakfast?.mode)
        assertFalse(StandingBreakfastProjection.apply(NutritionDay(LocalDate.now(), "complete", skipped), rule).breakfast!!.getBoolean("included"))
        val replaced = extract("breakfast-case-4", "今天早餐换成2个鸡蛋，每个约50g，固定早餐没吃", first)
        assertEquals("replaced", replaced.breakfast?.mode)
        assertTrue(replaced.foodItems.isNotEmpty())
        val replacement = StandingBreakfastProjection.apply(NutritionDay(LocalDate.now(), "complete", replaced), rule)
        assertFalse(replacement.breakfast!!.getBoolean("included"))
        assertEquals(replaced.values, replacement.result!!.values)
        File(context.filesDir, "standing-breakfast-live-cases.json").writeText(JSONObject().put("confirmation", true)
            .put("repeat_not_doubled", true).put("skipped", true).put("replaced", true).put("synthetic_inputs_only", true).toString())
    }

    @Test fun refreshAdviceUsingSavedDefault() = runBlocking {
        assumeTrue(args.getString("standing_breakfast_refresh_advice") == "true")
        val date = LocalDate.now()
        JournalStore(context).use { store ->
            val id = "standing-breakfast-update:$date"
            if (store.coachCard(id) != null) return@use
            val data = store.readyCoachingContext(date, context.assets.open("coaching-policy.md").bufferedReader().use { it.readText() }).toJson()
                .put("request_timezone", ZoneId.systemDefault().id).put("request_at", Instant.now().toString())
                .put("coaching_trigger", "user_standing_breakfast_update").put("telemetry_status", "unavailable")
            RingBluetoothStore(context).use { data.put("ring_bluetooth", it.snapshot(date)) }
            data.put("nightly_recovery", SamsungNightlyReader.refresh(context, date))
            NightlyRecoveryStore(context).use { history -> data.put("recovery_history", CoachingRecoveryHistory.compact(history.history(date.minusDays(30), date.minusDays(1)), date)) }
            store.latestCoachCard(date)?.let { data.put("previous_advice", it.advice.toJson()) }
            val prompt = context.assets.open("coach-system.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val packaged = CoachingRequestPackager.prepare(data, prompt)
            assertTrue(packaged.retainedContext.getJSONObject("request_manifest").getJSONObject("standing_breakfast").optBoolean("included"))
            val advice = QwenClient.response(QwenClient.post(packaged.request, QwenCredentials(context).read()!!))
            store.saveCoachCard(SavedCoachCard(id, date, Instant.now(), QWEN_MODEL, COACH_PROMPT_VERSION, packaged.retainedContext.toString(), advice))
            assertNotNull(store.coachCard(id)); ProgressRuntime.changed()
        }
    }

    @Test fun verifyLivePortionAdjustment() {
        assumeTrue(args.getString("standing_breakfast_live_portion") == "true")
        val rule = JournalStore(context).use { StandingBreakfast.parse(it.readSettings("").instructions) }!!
        val date = LocalDate.now()
        val previous = NutritionExtraction(NUTRITION_FIELDS.associateWith { NutritionValue(null, "missing", "") }, "unknown", "", emptyList(),
            breakfast = BreakfastResolution("confirmed_default", "早餐照旧", listOf("synthetic-prior"), emptyList()))
        val text = "今天固定早餐里的乳清蛋白粉改为30g，燕麦片和酸奶不变"
        val job = PendingNutrition(date, "synthetic-portion", "", text, true,
            NutritionTurn("synthetic-portion", text, emptyList(), 2, "synthetic-prior", previous, "pending"), breakfastRule = rule)
        val prompt = context.assets.open("nutrition-extraction.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val result = NutritionExtractor.extract(job, prompt, emptyList(), QwenCredentials(context).read()!!)
        assertEquals("adjusted", result.breakfast?.mode)
        assertTrue(result.foodItems.isNotEmpty())
        assertTrue((result.values.getValue("intake_kcal").value ?: 0.0) > rule.nutrients.getValue("intake_kcal"))
        val day = StandingBreakfastProjection.apply(NutritionDay(date, "complete", result), rule)
        assertFalse(day.breakfast!!.getBoolean("included")); assertEquals(result.values, day.result!!.values)
        File(context.filesDir, "standing-breakfast-live-portion.json").writeText(JSONObject().put("adjusted", true)
            .put("complete_adjusted_meal", true).put("default_not_added_twice", true).put("synthetic_input_only", true).toString())
    }
}
