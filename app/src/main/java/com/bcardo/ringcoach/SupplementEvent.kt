package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Reported intake, plans and retractions stay distinct; nutrients live only in linked food facts. */
internal data class SupplementEvent(
    val id: String, val date: LocalDate, val name: String, val amount: Double?, val unit: String?,
    val intakeTime: String?, val status: String, val evidence: String, val submissionIds: List<String>,
    val foodId: String? = null,
) {
    fun toJson() = JSONObject().put("id", id).put("date", date.toString()).put("name", name)
        .put("amount", amount ?: JSONObject.NULL).put("unit", unit ?: JSONObject.NULL)
        .put("intake_time", intakeTime ?: JSONObject.NULL).put("status", status).put("evidence", evidence)
        .put("source_submission_ids", JSONArray(submissionIds)).put("food_id", foodId ?: JSONObject.NULL)

    companion object {
        private val fields = listOf("id", "date", "name", "amount", "unit", "intake_time", "status", "evidence", "source_submission_ids", "food_id")
        fun decode(json: JSONObject): SupplementEvent {
            require(json.keys().asSequence().toSet() == fields.toSet())
            fun text(key: String, max: Int = 1000) = json.getString(key).also { require(it.isNotBlank() && it.length <= max) }
            fun optional(key: String) = if (json.isNull(key)) null else text(key)
            val amount = if (json.isNull("amount")) null else json.get("amount").let {
                require(it is Number && it.toDouble().isFinite() && it.toDouble() > 0 && it.toDouble() <= 1_000_000)
                it.toDouble()
            }
            val ids = json.getJSONArray("source_submission_ids")
            require(ids.length() in 1..100)
            val sources = (0 until ids.length()).map { ids.getString(it).also { id -> require(id.isNotBlank() && id.length <= 160) } }
            require(sources.distinct().size == sources.size)
            val status = text("status")
            require(status in setOf("taken", "planned", "skipped", "retracted"))
            return SupplementEvent(text("id", 160), LocalDate.parse(text("date")), text("name", 200), amount,
                optional("unit"), optional("intake_time"), status, text("evidence", 2000), sources, optional("food_id"))
        }

        fun schema(): JSONObject {
            fun text() = JSONObject().put("type", "string")
            fun nullableText() = JSONObject().put("type", JSONArray(listOf("string", "null")))
            val props = JSONObject()
            fields.forEach { props.put(it, text()) }
            props.put("amount", JSONObject().put("type", JSONArray(listOf("number", "null"))))
                .put("unit", nullableText()).put("intake_time", nullableText()).put("food_id", nullableText())
                .put("status", text().put("enum", JSONArray(listOf("taken", "planned", "skipped", "retracted"))))
                .put("source_submission_ids", JSONObject().put("type", "array").put("items", text()))
            return JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", props).put("required", JSONArray(fields))
        }
    }
}

internal object SupplementState {
    fun validate(job: PendingNutrition, result: NutritionExtraction) {
        val turn = job.turn ?: return
        val previous = turn.previous?.supplements?.associateBy { it.id } ?: emptyMap()
        val current = result.supplements.associateBy { it.id }
        require(current.size == result.supplements.size && current.size <= 100)
        // Keep retracted events as tombstones so corrections retain their provenance.
        require(current.keys.containsAll(previous.keys)) { "Retract a supplement instead of deleting its provenance" }
        val changed = previous.keys.filter { previous[it] != current[it] }.toSet()
        require(result.revisedSupplementIds.toSet() == changed && result.revisedSupplementIds.distinct().size == result.revisedSupplementIds.size)
        for (event in current.values) {
            require(event.date == job.date) { "Supplement date must match selected Journal date" }
            val old = previous[event.id]
            if (old == null) {
                require(event.id.startsWith("${turn.id}:supplement:") && event.submissionIds == listOf(turn.id))
            } else if (old != event) {
                require(event.submissionIds.containsAll(old.submissionIds) && turn.id in event.submissionIds)
                require(event.submissionIds.all { it in old.submissionIds || it == turn.id })
            }
            if (old != event) {
                require(event.evidence.isNotBlank() && turn.input.contains(event.evidence)) { "Supplement evidence must quote this submission" }
                require(event.evidence.contains(event.name))
                event.amount?.takeIf { it != old?.amount }?.let { amount ->
                    require(Regex("[0-9]+(?:\\.[0-9]+)?").findAll(event.evidence).any { it.value.toDouble() == amount })
                }
                event.unit?.takeIf { it != old?.unit }?.let { require(event.evidence.contains(it)) }
                event.intakeTime?.takeIf { it != old?.intakeTime }?.let {
                    require(event.evidence.contains(it)) { "New supplement time must be quoted in its evidence" }
                }
            }
            event.foodId?.let { id ->
                require(event.status == "taken")
                val food = result.foodItems.singleOrNull { it.id == id }
                require(food != null && food.submissionIds.any { it in event.submissionIds }) { "Supplement food link must resolve to the same consumption" }
            }
        }
    }
}

internal fun supplementSummary(event: SupplementEvent): String {
    val status = when (event.status) { "taken" -> "已服用"; "planned" -> "计划"; "skipped" -> "已跳过"; else -> "已撤销" }
    val amount = event.amount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "剂量未知"
    return "${event.name} · $status · $amount${event.unit ?: ""} · ${event.intakeTime ?: "时间未知"}"
}
