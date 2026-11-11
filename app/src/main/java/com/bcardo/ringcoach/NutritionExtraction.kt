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
) {
    val balance: Double?
        get() = values["intake_kcal"]?.value?.let { intake -> values["expenditure_kcal"]?.value?.let { intake - it } }

    fun toJson(): JSONObject = JSONObject().apply {
        for (field in NUTRITION_FIELDS) put(field, values.getValue(field).toJson())
        put("coverage", coverage).put("scope_evidence", scopeEvidence).put("unresolved", JSONArray(unresolved))
    }
}

internal data class PendingNutrition(val date: LocalDate, val revision: String, val diet: String, val additional: String) {
    val source: String get() = nutritionSource(diet, additional)
}

internal fun nutritionSource(diet: String, additional: String) = "饮食记录：\n$diet\n其他记录：\n$additional"

internal data class NutritionDay(
    val date: LocalDate,
    val status: String,
    val result: NutritionExtraction? = null,
    val reference: NutritionReference? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("date", date.toString()).put("status", status)
        .put("source", if (reference != null) "user_import" else "saved_journal_input")
        .put("nutrition", result?.toJson() ?: JSONObject.NULL)
        .put("balance_kcal", result?.balance ?: JSONObject.NULL)
        .put("balance_is_daily", result?.coverage == "daily_total")
}

internal object NutritionExtractor {
    fun request(job: PendingNutrition, instructions: String): JSONObject {
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
        val schema = JSONObject().put("type", "object").put("properties", properties).put("additionalProperties", false)
            .put("required", JSONArray(NUTRITION_FIELDS + listOf("coverage", "scope_evidence", "unresolved")))
        return JSONObject().put("model", QWEN_MODEL).put("enable_thinking", false).put("max_tokens", 3000)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", instructions))
                .put(JSONObject().put("role", "user").put("content", JSONObject()
                    .put("date", job.date.toString()).put("journal_input", job.source).toString())))
            .put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject()
                .put("name", "daily_nutrition").put("strict", true).put("schema", schema)))
    }

    fun decode(content: String, source: String): NutritionExtraction {
        val json = JSONObject(content)
        require(json.keys().asSequence().toSet() == (NUTRITION_FIELDS + listOf("coverage", "scope_evidence", "unresolved")).toSet())
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
                require(evidence.isNotBlank() && source.contains(evidence))
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
            require(value is String && value.isNotBlank() && source.contains(value))
            value
        }.distinct()
        return NutritionExtraction(values, coverage, scope, fragments)
    }
}
