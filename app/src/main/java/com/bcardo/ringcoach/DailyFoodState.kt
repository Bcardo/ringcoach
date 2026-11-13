package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.abs

/** Converted evidence, not a recommendation or a photograph. */
internal data class FoodFact(
    val id: String,
    val portion: String,
    val submissionIds: List<String>,
    val estimate: MealEstimate,
) {
    fun toJson(): JSONObject = estimate.toJson().put("id", id).put("portion", portion)
        .put("source_submission_ids", JSONArray(submissionIds))

    companion object {
        fun decode(json: JSONObject): FoodFact {
            val extra = setOf("id", "portion", "source_submission_ids")
            val keys = MEAL_FIELDS + listOf("description", "assumptions", "questions")
            require(json.keys().asSequence().toSet() == keys.toSet() + extra)
            val id = json.getString("id")
            val portion = json.getString("portion")
            require(id.isNotBlank() && id.length <= 160 && portion.isNotBlank() && portion.length <= 2000)
            val sources = json.getJSONArray("source_submission_ids")
            require(sources.length() in 1..100)
            val ids = (0 until sources.length()).map {
                sources.getString(it).also { value -> require(value.isNotBlank() && value.length <= 160) }
            }
            require(ids.distinct().size == ids.size)
            val estimate = JSONObject().apply { keys.forEach { put(it, json.get(it)) } }
            return FoodFact(id, portion, ids, MealEstimate.decode(estimate.toString()))
        }

        fun schema(): JSONObject {
            fun text() = JSONObject().put("type", "string")
            fun texts() = JSONObject().put("type", "array").put("items", text())
            val fields = JSONObject().put("id", text()).put("portion", text()).put("description", text())
                .put("source_submission_ids", texts()).put("assumptions", texts()).put("questions", texts())
            MEAL_FIELDS.forEach { fields.put(it, JSONObject().put("type", JSONArray(listOf("number", "null")))) }
            return JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", fields).put("required", JSONArray(MEAL_FIELDS +
                    listOf("id", "portion", "description", "source_submission_ids", "assumptions", "questions")))
        }
    }
}

internal data class NutritionTurn(
    val id: String, val input: String, val photoIds: List<String>, val rowId: Long,
    val previousId: String?, val previous: NutritionExtraction?, val status: String,
    val photoMatches: Map<String, List<String>> = emptyMap(),
)

internal object DailyFoodState {
    fun evidenceContext(state: NutritionExtraction?): String = state?.let {
        (it.values.values.map { value -> value.evidence } + it.scopeEvidence + it.foodItems.map { food ->
            "[已确认食品 ${food.id}] ${food.estimate.description}；份量：${food.portion}"
        }).filter { text -> text.isNotBlank() }.distinct().joinToString("\n")
    } ?: ""
    /** Old successful totals are kept as one explicitly aggregate fact, never invented per-meal precision. */
    fun bootstrap(date: LocalDate, result: NutritionExtraction): NutritionExtraction {
        if (result.foodItems.isNotEmpty() || MEAL_FIELDS.none { result.values[it]?.value != null }) return result
        val id = "legacy:$date"
        val missing = MEAL_FIELDS.filter { result.values[it]?.value == null }
        val fact = FoodFact(id, "此前已确认的合计；尚无逐餐拆分", listOf(id), MealEstimate(
            result.foodSummary.ifBlank { "此前已保存的摄入汇总" },
            MEAL_FIELDS.associateWith { result.values[it]?.value }, result.assumptions,
            result.unresolved + if (missing.isEmpty()) emptyList() else listOf("此前汇总未提供完整营养项"),
        ))
        return result.copy(foodItems = listOf(fact), revisedFoodIds = emptyList())
    }

    fun validate(job: PendingNutrition, result: NutritionExtraction) {
        val turn = job.turn ?: return
        SupplementState.validate(job, result)
        val previous = turn.previous?.foodItems?.associateBy { it.id } ?: emptyMap()
        val current = result.foodItems.associateBy { it.id }
        require(current.size == result.foodItems.size && current.size <= 100) { "Duplicate food IDs" }
        val revised = previous.keys.filter { previous[it] != current[it] }.toSet()
        require(result.revisedFoodIds.toSet() == revised && result.revisedFoodIds.distinct().size == result.revisedFoodIds.size) {
            "Prior food facts must be copied exactly unless explicitly revised"
        }
        for (food in current.values) {
            val old = previous[food.id]
            if (old == null) {
                require(food.id.startsWith("${turn.id}:") && food.id.length > turn.id.length + 1) { "New food ID must belong to this turn" }
                require(food.submissionIds == listOf(turn.id)) { "New food source must be this turn" }
            } else if (food != old) {
                require(turn.input.isNotBlank() || turn.photoIds.isNotEmpty())
                require(food.submissionIds.containsAll(old.submissionIds) && turn.id in food.submissionIds)
                require(food.submissionIds.all { it in old.submissionIds || it == turn.id })
            }
        }
        // The visible total cannot silently disagree with the returned, reusable facts.
        for (field in MEAL_FIELDS) {
            val known = current.values.map { it.estimate.values[field] }
            val total = result.values.getValue(field).value
            if (known.isNotEmpty() && known.all { it != null }) {
                require(total != null && abs(total - known.sumOf { it!! }) <= if (field == "intake_kcal") 1.0 else 0.2) {
                    "Daily total does not match converted food facts: $field"
                }
            }
            if (known.any { it == null }) require(total == null) { "Unknown food nutrient cannot become a complete total: $field" }
            if (current.isEmpty()) require(total == null) { "No food facts support intake" }
        }
    }
}
