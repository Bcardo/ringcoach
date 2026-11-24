package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

/** Only an explicitly saved dated rule enables default intake; a recipe reference alone does not. */
internal data class StandingBreakfast(val effectiveDate: LocalDate, val description: String, val nutrients: Map<String, Double>) {
    init {
        require(description.isNotBlank() && description.length <= 1000)
        require(nutrients.keys == MEAL_FIELDS.toSet() && nutrients.values.all { it.isFinite() && it in 0.0..100000.0 })
    }
    fun toJson() = JSONObject().put("effective_date", effectiveDate.toString()).put("description", description)
        .put("nutrients", JSONObject(nutrients)).put("source", "user_saved_standing_breakfast")
        .put("counting", "once_per_date_assumed_unless_explicit_day_override")
    fun block(): String = "$OPEN\n生效日期：$effectiveDate\n内容：$description\n蛋白质g：${nutrients.getValue("protein_grams")}\n碳水g：${nutrients.getValue("carbohydrate_grams")}\n脂肪g：${nutrients.getValue("fat_grams")}\n热量kcal：${nutrients.getValue("intake_kcal")}\n$CLOSE"
    companion object {
        const val OPEN = "[每日默认早餐]"
        const val CLOSE = "[/每日默认早餐]"
        private val pattern = Regex("\\[每日默认早餐]([\\s\\S]*?)\\[/每日默认早餐]")
        fun parse(text: String): StandingBreakfast? {
            val blocks = pattern.findAll(text).toList()
            if (blocks.isEmpty()) { require(!text.contains(OPEN) && !text.contains(CLOSE)) { "Incomplete standing breakfast rule" }; return null }
            require(blocks.size == 1) { "Only one standing breakfast rule is supported" }
            val pairs = blocks.single().groupValues[1].lineSequence().map { it.trim() }.filter { it.isNotBlank() }.map { line ->
                val split = line.split('：', ':', limit = 2); require(split.size == 2); split[0].trim() to split[1].trim()
            }.toList()
            val fields = pairs.toMap()
            require(fields.size == pairs.size && fields.keys == setOf("生效日期", "内容", "蛋白质g", "碳水g", "脂肪g", "热量kcal")) { "Invalid standing breakfast fields" }
            return StandingBreakfast(LocalDate.parse(fields.getValue("生效日期")), fields.getValue("内容"),
                mapOf("protein_grams" to fields.getValue("蛋白质g").toDouble(), "carbohydrate_grams" to fields.getValue("碳水g").toDouble(),
                    "fat_grams" to fields.getValue("脂肪g").toDouble(), "intake_kcal" to fields.getValue("热量kcal").toDouble()))
        }
        fun replace(text: String, rule: StandingBreakfast?) = listOf(pattern.replace(text, "").trim(), rule?.block().orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n")
    }
}

/** Dated interpretation of the user's report. Defaults are projected separately from confirmed food facts. */
internal data class BreakfastResolution(val mode: String, val evidence: String, val submissionIds: List<String>, val foodIds: List<String>) {
    init {
        require(mode in MODES && evidence.length <= 2000 && submissionIds.distinct().size == submissionIds.size && foodIds.distinct().size == foodIds.size)
        require(submissionIds.size <= 100 && foodIds.size <= 100)
        if (mode == "default") require(evidence.isEmpty() && submissionIds.isEmpty() && foodIds.isEmpty())
        else require(evidence.isNotBlank() && submissionIds.isNotEmpty())
        if (mode in setOf("default", "confirmed_default", "skipped")) require(foodIds.isEmpty())
        if (mode in setOf("replaced", "adjusted", "already_recorded")) require(foodIds.isNotEmpty())
    }
    fun toJson() = JSONObject().put("mode", mode).put("evidence", evidence)
        .put("source_submission_ids", JSONArray(submissionIds)).put("food_ids", JSONArray(foodIds))
    companion object {
        val MODES = setOf("default", "confirmed_default", "skipped", "replaced", "adjusted", "already_recorded", "unresolved")
        fun decode(json: JSONObject): BreakfastResolution {
            require(json.keys().asSequence().toSet() == setOf("mode", "evidence", "source_submission_ids", "food_ids"))
            fun ids(key: String) = json.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it).also { id -> require(id.isNotBlank()) } } }
            return BreakfastResolution(json.getString("mode"), json.getString("evidence"), ids("source_submission_ids"), ids("food_ids"))
        }
        fun schema(): JSONObject {
            fun text() = JSONObject().put("type", "string")
            fun ids() = JSONObject().put("type", "array").put("items", text())
            return JSONObject().put("type", "object").put("additionalProperties", false)
                .put("required", JSONArray(listOf("mode", "evidence", "source_submission_ids", "food_ids")))
                .put("properties", JSONObject().put("mode", text().put("enum", JSONArray(MODES)))
                    .put("evidence", text()).put("source_submission_ids", ids()).put("food_ids", ids()))
        }
        fun validate(job: PendingNutrition, result: NutritionExtraction) {
            val previous = job.turn?.previous?.breakfast
            val current = result.breakfast
            if (job.breakfastRule == null && previous == null) { require(current == null); return }
            requireNotNull(current) { "Standing breakfast resolution required" }
            if (result.coverage == "daily_total" || job.turn?.previous?.coverage == "daily_total") {
                require(current.mode !in setOf("default", "confirmed_default")) { "A reported whole-day total must not acquire an additional default breakfast" }
            }
            require(current.foodIds.all { id -> result.foodItems.any { it.id == id } }) { "Breakfast food reference is missing" }
            if (current == previous || current.mode == "default") {
                require(previous == null || current == previous) { "Do not reset a dated breakfast override" }
                return
            }
            val turn = job.turn
            val source = if (turn == null) job.source else turn.input
            // Initial reconciliation may identify a breakfast already present in converted facts.
            val oldFoodEvidence = job.turn?.previous?.foodItems?.filter { it.id in current.foodIds }.orEmpty()
            val reconcilingOld = previous == null && current.mode == "already_recorded" && oldFoodEvidence.isNotEmpty() &&
                oldFoodEvidence.all { food -> food.submissionIds.all { it in current.submissionIds } } &&
                job.source.contains(current.evidence)
            if (!reconcilingOld) {
                require(source.contains(current.evidence)) { "Breakfast override must quote this input" }
                require(current.submissionIds.toSet() == (previous?.submissionIds.orEmpty() + (turn?.id ?: job.revision)).toSet()) { "Breakfast override source is invalid" }
            }
            if (current.mode in setOf("replaced", "adjusted")) require(current.foodIds.all { id ->
                result.foodItems.single { it.id == id }.submissionIds.contains(turn?.id ?: job.revision)
            }) { "Replacement breakfast must be supported by this input" }
        }
    }
}

internal object StandingBreakfastProjection {
    /** Read-time projection is repeatable and never persists invented journal submissions or modifies confirmed facts. */
    fun apply(day: NutritionDay, rule: StandingBreakfast?): NutritionDay {
        if (day.breakfast != null) return day
        if (rule == null || day.date < rule.effectiveDate) return day
        val raw = day.result
        val mode = when {
            day.reference != null -> "historical_reference_not_modified"
            day.status in setOf("pending", "failed") -> "waiting_for_recognition"
            raw?.coverage == "daily_total" -> "included_in_reported_daily_total"
            raw?.breakfast != null -> raw.breakfast.mode
            raw != null && (raw.foodItems.isNotEmpty() || MEAL_FIELDS.any { raw.values[it]?.value != null }) -> "needs_reconciliation"
            else -> "default"
        }
        val included = mode in setOf("default", "confirmed_default")
        val annotation = JSONObject().put("mode", mode).put("included", included).put("rule", rule.toJson())
            .put("label", if (mode == "default") "按固定规则默认计入；未逐日确认" else if (mode == "confirmed_default") "已确认固定早餐；仅计入一次" else when (mode) {
                "skipped" -> "当天已说明跳过早餐；不计默认早餐"
                "replaced", "adjusted", "already_recorded" -> "使用已记录早餐；不重复添加默认早餐"
                "included_in_reported_daily_total" -> "已报告全天总量；不额外叠加默认早餐"
                else -> "默认早餐暂未叠加：等待澄清或核对已有记录"
            })
        raw?.breakfast?.let { annotation.put("day_resolution", it.toJson()) }
        if (!included) return day.copy(breakfast = annotation)
        val base = raw ?: NutritionExtraction(NUTRITION_FIELDS.associateWith { NutritionValue(null, "missing", "") }, "unknown", "", emptyList())
        val noOtherIntake = base.foodItems.isEmpty() && MEAL_FIELDS.all { base.values[it]?.value == null }
        val values = base.values.toMutableMap()
        val evidence = "${annotation.getString("label")}：${rule.description}（规则生效 ${rule.effectiveDate}）"
        for (field in MEAL_FIELDS) {
            val old = base.values.getValue(field)
            val total = if (old.value != null) BigDecimal.valueOf(old.value).add(BigDecimal.valueOf(rule.nutrients.getValue(field))).toDouble()
                else if (noOtherIntake) rule.nutrients.getValue(field) else null
            values[field] = if (total == null) NutritionValue(null, "missing", "") else NutritionValue(total, "estimated", listOf(old.evidence, evidence).filter { it.isNotBlank() }.joinToString("\n"))
        }
        annotation.put("contribution", JSONObject(rule.nutrients))
        val fact = FoodFact("standing-breakfast:${day.date}", rule.description, listOf("standing-breakfast:${rule.effectiveDate}"),
            MealEstimate("固定早餐（${annotation.getString("label")}）", rule.nutrients.mapValues { it.value as Double? }, listOf(evidence), emptyList()))
        val projected = base.copy(values = values, coverage = "partial_day", scopeEvidence = "", foodItems = base.foodItems + fact,
            assumptions = (base.assumptions + evidence).takeLast(8), totalsSource = "local_sum_with_standing_breakfast_v1")
        return day.copy(result = projected, status = if (day.status == "missing") "complete" else day.status, breakfast = annotation)
    }
}
