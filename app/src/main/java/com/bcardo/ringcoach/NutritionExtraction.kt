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
    val foodItems: List<FoodFact> = emptyList(),
    val revisedFoodIds: List<String> = emptyList(),
    val supplements: List<SupplementEvent> = emptyList(),
    val revisedSupplementIds: List<String> = emptyList(),
) {
    val balance: Double?
        get() = values["intake_kcal"]?.value?.let { intake -> values["expenditure_kcal"]?.value?.let { intake - it } }

    fun toJson(): JSONObject = JSONObject().apply {
        for (field in NUTRITION_FIELDS) put(field, values.getValue(field).toJson())
        put("coverage", coverage).put("scope_evidence", scopeEvidence).put("unresolved", JSONArray(unresolved))
        put("food_summary", foodSummary).put("assumptions", JSONArray(assumptions))
        put("food_items", JSONArray(foodItems.map { it.toJson() })).put("revised_food_ids", JSONArray(revisedFoodIds))
        put("supplement_events", JSONArray(supplements.map { it.toJson() }))
        put("revised_supplement_ids", JSONArray(revisedSupplementIds))
    }
}

internal data class PendingNutrition(val date: LocalDate, val revision: String, val diet: String, val additional: String,
    val unified: Boolean = false, val turn: NutritionTurn? = null) {
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
    fun extract(job: PendingNutrition, instructions: String, photos: List<Pair<String, ByteArray>>, key: String,
        post: (JSONObject, String) -> String = QwenClient::post): NutritionExtraction {
        val request = request(job, instructions, photos)
        val answer = QwenClient.responseText(post(request, key))
        try { return decodeForJob(answer, job).also { DailyFoodState.validate(job, it) } } catch (invalid: Exception) {
            if (invalid !is IllegalArgumentException && invalid !is org.json.JSONException) throw invalid
            // Repair a structurally invalid answer once, without accepting unsupported evidence or changing totals locally.
            request.getJSONArray("messages")
                .put(JSONObject().put("role", "assistant").put("content", answer))
                .put(JSONObject().put("role", "user").put("content",
                    "上次结果未通过校验（${invalid.message ?: "结构或来源不符"}）。请重新输出完整JSON：evidence每行逐字引用原文；reported数值必须出现在evidence中。图片标签里的数字若未在用户文字中出现，必须用estimated并引用[照片 ID]，不能编造文字引用；计算合计也用estimated。旧food_items未被本次明确修正时必须逐字段原样复制。改变或移除的旧ID必须准确列入revised_food_ids；新ID用本次submission_id加冒号和编号，新source_submission_ids只含本次ID。修正项保留旧来源并追加本次ID。每日营养合计必须等于当前食品项之和。缺失用null，不用0。补剂同样保留未修改旧事件的所有字段与原始evidence，重复确认不改写旧事件。改动的补剂必须用本次new_input逐字引用名称及本次改动字段的证据。未改动的已知剂量/单位/时间保留旧值与来源，不强制在新证据中重述；新增字段的未知信息为null。准确列出revised_supplement_ids。"))
            return decodeForJob(QwenClient.responseText(post(request, key)), job).also { DailyFoodState.validate(job, it) }
        }
    }

    /** Image labels are interpreted measurements, never user-typed numeric reports. Numbers are unchanged. */
    fun decodeForJob(content: String, job: PendingNutrition): NutritionExtraction {
        if (job.turn == null) return decode(content, job.source)
        val json = JSONObject(content)
        require(json.has("supplement_events") && json.has("revised_supplement_ids")) { "Incremental result must include supplement events and revision IDs" }
        val marker = Regex("\\[照片 [^]]+]")
        for (field in MEAL_FIELDS) {
            val row = json.getJSONObject(field)
            val evidence = row.getString("evidence")
            if (row.optString("kind") == "reported" && !row.isNull("value") && marker.containsMatchIn(evidence)) {
                val outsideMarker = evidence.replace(marker, "")
                val value = row.getDouble("value")
                val directlyQuoted = Regex("[0-9]+(?:\\.[0-9]+)?").findAll(outsideMarker).any { it.value.toDouble() == value }
                if (!directlyQuoted) row.put("kind", "estimated")
            }
        }
        // A repeated report must not rewrite provenance when the actual event fields did not change.
        val oldEvents = job.turn.previous?.supplements?.associateBy { it.id }.orEmpty()
        val events = json.optJSONArray("supplement_events")
        val unchanged = mutableSetOf<String>()
        if (events != null) for (i in 0 until events.length()) {
            val current = SupplementEvent.decode(events.getJSONObject(i))
            val old = oldEvents[current.id] ?: continue
            if (old.copy(evidence = current.evidence, submissionIds = current.submissionIds) == current) {
                events.put(i, old.toJson())
                unchanged.add(old.id)
            }
        }
        json.optJSONArray("revised_supplement_ids")?.let { ids ->
            json.put("revised_supplement_ids", JSONArray((0 until ids.length()).map { ids.getString(it) }.filter { it !in unchanged }))
        }
        return decode(json.toString(), job.source)
    }

    fun request(job: PendingNutrition, instructions: String, photos: List<Pair<String, ByteArray>> = emptyList()): JSONObject {
        job.turn?.takeIf { it.previous != null }?.let { turn ->
            require(photos.all { it.first in turn.photoIds }) { "Only this turn's new images may be uploaded" }
        }
        val input = JSONObject().put("date", job.date.toString()).put("journal_input", job.source)
        job.turn?.let {
            input.put("new_input", JSONObject().put("submission_id", it.id).put("text", it.input).put("photo_ids", JSONArray(it.photoIds)))
                .put("previous_daily_state", it.previous?.toJson() ?: JSONObject.NULL)
                .put("photo_matches_previous_submissions", JSONObject(it.photoMatches.mapValues { entry -> JSONArray(entry.value) }))
                .put("update_mode", "incremental")
        }
        val allowedEvidence = job.turn?.let { turn ->
            val fragments = listOf("") + listOf(turn.input).filter { it.isNotBlank() } +
                Regex("\\[照片 [^]]+]").findAll(job.source).map { it.value }.toList() +
                (turn.previous?.values?.values?.map { it.evidence } ?: emptyList()) +
                DailyFoodState.evidenceContext(turn.previous).lineSequence().filter { it.isNotBlank() }.toList()
            (fragments + fragments.filter { it.isNotBlank() }.distinct().joinToString("\n")).distinct()
        }
        if (allowedEvidence != null) input.put("allowed_evidence", JSONArray(allowedEvidence))
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
        if (allowedEvidence != null) numberSchema.getJSONObject("properties").getJSONObject("evidence")
            .put("enum", JSONArray(allowedEvidence))
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
        if (job.turn != null) {
            properties.put("food_items", JSONObject().put("type", "array").put("items", FoodFact.schema()))
                .put("revised_food_ids", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("supplement_events", JSONObject().put("type", "array").put("items", SupplementEvent.schema()))
                .put("revised_supplement_ids", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
            schema.getJSONArray("required").put("food_items").put("revised_food_ids")
                .put("supplement_events").put("revised_supplement_ids")
        }
        return JSONObject().put("model", QWEN_MODEL).put("enable_thinking", false).put("max_tokens", if (job.turn == null) 3000 else 6500)
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
        require(keys.containsAll(required) && keys.all { it in required || it in setOf("food_summary", "assumptions", "food_items", "revised_food_ids", "supplement_events", "revised_supplement_ids") })
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
        val foods = json.optJSONArray("food_items") ?: JSONArray()
        require(foods.length() <= 100)
        val foodItems = (0 until foods.length()).map { FoodFact.decode(foods.getJSONObject(it)) }
        val revisions = json.optJSONArray("revised_food_ids") ?: JSONArray()
        require(revisions.length() <= 100)
        val revisedIds = (0 until revisions.length()).map { revisions.getString(it) }
        val events = json.optJSONArray("supplement_events") ?: JSONArray()
        val supplementRevisions = json.optJSONArray("revised_supplement_ids") ?: JSONArray()
        require(events.length() <= 100 && supplementRevisions.length() <= 100)
        return NutritionExtraction(values, coverage, scope, fragments, summary, assumptionTexts, foodItems, revisedIds,
            (0 until events.length()).map { SupplementEvent.decode(events.getJSONObject(it)) },
            (0 until supplementRevisions.length()).map { supplementRevisions.getString(it) })
    }
}
