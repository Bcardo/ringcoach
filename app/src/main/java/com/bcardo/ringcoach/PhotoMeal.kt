package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

internal val MEAL_FIELDS = listOf("protein_grams", "carbohydrate_grams", "fat_grams", "intake_kcal")
internal const val PHOTO_PROMPT_VERSION = "meal-v2"
internal const val MAX_MEAL_PHOTO_BYTES = 700_000

internal data class MealEstimate(
    val description: String,
    val values: Map<String, Double?>,
    val assumptions: List<String>,
    val questions: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().put("description", description)
        .put("assumptions", JSONArray(assumptions)).put("questions", JSONArray(questions)).apply {
            for (field in MEAL_FIELDS) put(field, values[field] ?: JSONObject.NULL)
        }
    companion object {
        fun decode(content: String): MealEstimate {
            val json = JSONObject(content)
            require(json.keys().asSequence().toSet() == (MEAL_FIELDS + listOf("description", "assumptions", "questions")).toSet())
            val description = json.get("description")
            require(description is String && description.isNotBlank() && description.length <= 2000)
            fun strings(key: String): List<String> {
                val rows = json.getJSONArray(key)
                require(rows.length() <= 8)
                return (0 until rows.length()).map {
                    val text = rows.get(it)
                    require(text is String && text.isNotBlank() && text.length <= 1000)
                    text
                }
            }
            val values = MEAL_FIELDS.associateWith { field ->
                if (json.isNull(field)) null else {
                    val raw = json.get(field)
                    require(raw is Number)
                    raw.toDouble().also { require(it.isFinite() && it >= 0 && it <= 100_000) }
                }
            }
            val questions = strings("questions")
            if (values.values.any { it == null }) require(questions.isNotEmpty()) { "Unknown food nutrients require an explicit clarification question" }
            return MealEstimate(description, values, strings("assumptions"), questions)
        }
    }
}

internal data class PhotoMeal(
    val id: String,
    val date: LocalDate,
    val revision: String,
    val notes: String,
    val consumed: Boolean,
    val status: String,
    val estimate: MealEstimate?,
    val createdAt: Instant,
    val model: String,
    val promptVersion: String,
    val hasPhoto: Boolean = true,
    val wasPhoto: Boolean = hasPhoto,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("date", date.toString())
        .put("revision", revision).put("notes", notes).put("consumed", consumed).put("status", status)
        .put("estimate", estimate?.toJson() ?: JSONObject.NULL).put("model", model)
        .put("prompt_version", promptVersion).put("source", if (wasPhoto) "photo_with_user_notes" else "text_meal")
        .put("image_retained", hasPhoto)
}

internal object PhotoMealClient {
    fun request(jpeg: ByteArray, notes: String, prompt: String): JSONObject {
        require(jpeg.size <= MAX_MEAL_PHOTO_BYTES)
        require(notes.length <= 4000)
        val properties = JSONObject().put("description", JSONObject().put("type", "string"))
        for (field in MEAL_FIELDS) properties.put(field, JSONObject().put("type", JSONArray(listOf("number", "null"))))
        for (field in listOf("assumptions", "questions")) properties.put(field,
            JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
        val schema = JSONObject().put("type", "object").put("properties", properties).put("additionalProperties", false)
            .put("required", JSONArray(MEAL_FIELDS + listOf("description", "assumptions", "questions")))
        val content = JSONArray()
        if (jpeg.isNotEmpty()) content.put(JSONObject().put("type", "image_url").put("image_url",
            JSONObject().put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg))))
        content.put(JSONObject().put("type", "text").put("text", JSONObject().put("consumed", true)
            .put("user_notes", notes).toString()))
        return JSONObject().put("model", QWEN_MODEL).put("enable_thinking", false).put("max_tokens", 2400)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", prompt))
                .put(JSONObject().put("role", "user").put("content", content)))
            .put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject()
                .put("name", "photo_meal").put("strict", true).put("schema", schema)))
    }
}

/** Never add photographs to a full-day total or ambiguous overlapping text without an explicit choice. */
internal object MealNutrition {
    fun merge(day: NutritionDay, meals: List<PhotoMeal>, mode: String?): NutritionDay {
        if (meals.isEmpty()) return day
        val eligible = meals.filter { it.consumed && it.status == "complete" && it.estimate != null }
        val text = day.result
        val hasTextIntake = text?.values?.any { it.key in MEAL_FIELDS && it.value.value != null } == true
        val conflict = eligible.isNotEmpty() && hasTextIntake && text?.coverage != "daily_total" && mode == null
        if (text?.coverage == "daily_total" || (hasTextIntake && mode != "separate") || day.status in setOf("pending", "failed")) {
            return day.copy(photoMeals = meals, photoConflict = conflict, photoMode = mode)
        }
        if (eligible.isEmpty()) return day.copy(photoMeals = meals, photoMode = mode)
        val values = NUTRITION_FIELDS.associateWith { field ->
            if (field !in MEAL_FIELDS) text?.values?.get(field) ?: NutritionValue(null, "missing", "") else {
                val mealValues = eligible.map { it.estimate!!.values[field] }
                val textValue = if (hasTextIntake && mode == "separate") text!!.values[field] else null
                val allKnown = mealValues.all { it != null } && (!hasTextIntake || textValue?.value != null)
                val sum = if (allKnown) mealValues.sumOf { it!! } + (textValue?.value ?: 0.0) else null
                NutritionValue(sum, if (sum == null) "missing" else "estimated",
                    if (sum == null) "" else eligible.joinToString("\n") { "餐食 ${it.id}: ${it.estimate!!.description}" } +
                        (textValue?.evidence?.let { "\n$it" } ?: ""))
            }
        }
        return day.copy(status = "complete", reference = null,
            result = NutritionExtraction(values, "partial_day", "", text?.unresolved ?: emptyList()),
            photoMeals = meals, photoMode = mode)
    }
}
