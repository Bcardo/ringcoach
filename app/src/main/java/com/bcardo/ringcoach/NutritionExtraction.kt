package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal val NUTRITION_FIELDS = listOf("weight_kg", "protein_grams", "carbohydrate_grams",
    "fat_grams", "intake_kcal", "expenditure_kcal")

internal data class NutritionValue(val value: Double?, val kind: String, val evidence: String) {
    fun toJson() = JSONObject().put("value", value ?: JSONObject.NULL).put("kind", kind).put("evidence", evidence)
}

internal data class NutritionExtraction(
    val values: Map<String, NutritionValue>,
    val coverage: String,
    val scopeEvidence: String,
    val unresolved: List<String>,
    val foodSummary: String = "",
    val assumptions: List<String> = emptyList(),
) {
    val balance: Double?
        get() = values["intake_kcal"]?.value?.let { intake -> values["expenditure_kcal"]?.value?.let { intake - it } }

    fun toJson(): JSONObject = JSONObject().apply {
        for (field in NUTRITION_FIELDS) put(field, values.getValue(field).toJson())
        put("coverage", coverage).put("scope_evidence", scopeEvidence).put("unresolved", JSONArray(unresolved))
        put("food_summary", foodSummary).put("assumptions", JSONArray(assumptions))
    }
}

internal data class PendingNutrition(val date: LocalDate, val revision: String, val diet: String, val additional: String,
    val unified: Boolean = false) {
    val source: String get() = nutritionSource(diet, additional)
}

internal fun nutritionSource(diet: String, additional: String) = "饮食记录：\n$diet\n其他记录：\n$additional"

internal data class NutritionDay(
    val date: LocalDate,
    val status: String,
    val result: NutritionExtraction? = null,
    val reference: NutritionReference? = null,
    val photoMeals: List<PhotoMeal> = emptyList(),
    val photoConflict: Boolean = false,
    val photoMode: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("date", date.toString()).put("status", status)
        .put("source", when {
            reference != null -> "user_import"
            photoMeals.isNotEmpty() && result != null -> "journal_and_photo_meals"
            status == "missing" -> "none"
            else -> "saved_journal_input"
        })
        .put("nutrition", result?.toJson() ?: JSONObject.NULL)
        .put("balance_kcal", result?.balance ?: JSONObject.NULL)
        .put("balance_is_daily", result?.coverage == "daily_total")
        .put("photo_meals", JSONArray(photoMeals.map {
            if (photoMode == "unified") JSONObject().put("id", it.id).put("notes", it.notes)
                .put("source", "included_in_unified_daily_analysis") else it.toJson()
        }))
        .put("photo_text_overlap_unresolved", photoConflict)
        .put("photo_text_relation", photoMode ?: JSONObject.NULL)
}

internal object NutritionExtractor {
    fun extract(job: PendingNutrition, instructions: String, photos: List<Pair<String, ByteArray>>, key: String): NutritionExtraction {
        val request = request(job, instructions, photos)
        val answer = QwenClient.responseText(QwenClient.post(request, key))
        try { return decode(answer, job.source) } catch (_: IllegalArgumentException) {
            // Repair a structurally invalid answer once, without accepting unsupported evidence or changing totals locally.
            request.getJSONArray("messages")
                .put(JSONObject().put("role", "assistant").put("content", answer))
                .put(JSONObject().put("role", "user").put("content",
                    "上次结果未通过校验。请重新输出完整JSON：evidence中每一行必须逐字引用journal_input的原文片段；reported的value必须作为数字直接出现在该evidence中。多餐相加或更正后计算出的营养总计若未逐字汇报，必须标estimated，不得标reported。未知value必须null、kind=missing、evidence为空。unresolved只写需要澄清的具体问题。不要改变有依据的总计。"))
            return decode(QwenClient.responseText(QwenClient.post(request, key)), job.source)
        }
    }

    fun request(job: PendingNutrition, instructions: String, photos: List<Pair<String, ByteArray>> = emptyList()): JSONObject {
        val input = JSONObject().put("date", job.date.toString()).put("journal_input", job.source)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", input.toString()))
        for ((id, jpeg) in photos) {
            require(jpeg.isNotEmpty() && jpeg.size <= MAX_MEAL_PHOTO_BYTES && job.source.contains("[照片 $id]"))
            content.put(JSONObject().put("type", "text").put("text", "[照片 $id]"))
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                .put("url", "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg))))
        }
        val numberSchema = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("value", "kind", "evidence")))
            .put("properties", JSONObject()
                .put("value", JSONObject().put("type", JSONArray(listOf("number", "null"))))
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("reported", "estimated", "missing"))))
                .put("evidence", JSONObject().put("type", "string")))
        val properties = JSONObject()
        for (field in NUTRITION_FIELDS) properties.put(field, numberSchema)
        properties.put("coverage", JSONObject().put("type", "string")
            .put("enum", JSONArray(listOf("partial_day", "daily_total", "unknown"))))
            .put("scope_evidence", JSONObject().put("type", "string"))
            .put("unresolved", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
            .put("food_summary", JSONObject().put("type", "string"))
            .put("assumptions", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
        val schema = JSONObject().put("type", "object").put("properties", properties).put("additionalProperties", false)
            .put("required", JSONArray(NUTRITION_FIELDS + listOf("coverage", "scope_evidence", "unresolved", "food_summary", "assumptions")))
        return JSONObject().put("model", QWEN_MODEL).put("enable_thinking", false).put("max_tokens", 3000)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", instructions))
                .put(JSONObject().put("role", "user").put("content", if (photos.isEmpty()) input.toString() else content)))
            .put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject()
                .put("name", "daily_nutrition").put("strict", true).put("schema", schema)))
    }

    fun decode(content: String, source: String): NutritionExtraction {
        fun supportedEvidence(evidence: String): Boolean {
            if (evidence.isBlank()) return false
            fun normalized(value: String) = value.replace(Regex("\\s+"), "")
            val normalizedSource = normalized(source)
            return evidence.lineSequence().filter { it.isNotBlank() }.all {
                normalizedSource.contains(normalized(it))
            }
        }
        val json = JSONObject(content)
        val required = (NUTRITION_FIELDS + listOf("coverage", "scope_evidence", "unresolved")).toSet()
        val keys = json.keys().asSequence().toSet()
        require(keys.containsAll(required) && keys.all { it in required || it in setOf("food_summary", "assumptions") })
        val values = NUTRITION_FIELDS.associateWith { field ->
            val row = json.getJSONObject(field)
            require(row.keys().asSequence().toSet() == setOf("value", "kind", "evidence"))
            val kind = row.getString("kind")
            val evidence = row.getString("evidence")
            if (row.isNull("value")) {
                require(kind == "missing" && evidence.isEmpty())
                NutritionValue(null, kind, evidence)
            } else {
                val raw = row.get("value")
                require(raw is Number)
                val value = raw.toDouble()
                require(value.isFinite() && value >= 0 && value <= 1_000_000)
                require(supportedEvidence(evidence))
                require(kind in setOf("reported", "estimated"))
                if (field in setOf("weight_kg", "expenditure_kcal")) require(kind == "reported")
                if (field == "weight_kg") require(value > 0)
                if (kind == "reported") {
                    require(Regex("[0-9]+(?:\\.[0-9]+)?").findAll(evidence).any { it.value.toDouble() == value })
                }
                NutritionValue(value, kind, evidence)
            }
        }
        val coverage = json.getString("coverage")
        require(coverage in setOf("partial_day", "daily_total", "unknown"))
        val scope = json.getString("scope_evidence")
        require(scope.isEmpty() || source.contains(scope))
        if (coverage == "daily_total") require(scope.isNotBlank())
        val unresolved = json.getJSONArray("unresolved")
        val fragments = (0 until unresolved.length()).map {
            val value = unresolved.get(it)
            require(value is String && value.isNotBlank() && value.length <= 1000)
            value
        }.distinct()
        val summary = json.optString("food_summary", "")
        require(summary.length <= 2000)
        val assumptions = json.optJSONArray("assumptions") ?: JSONArray()
        require(assumptions.length() <= 8)
        val assumptionTexts = (0 until assumptions.length()).map {
            val value = assumptions.get(it)
            require(value is String && value.isNotBlank() && value.length <= 1000)
            value
        }
        return NutritionExtraction(values, coverage, scope, fragments, summary, assumptionTexts)
    }
}
