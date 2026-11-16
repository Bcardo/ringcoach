package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** One-time classification of existing facts when an explicit standing rule is enabled. No food is rewritten. */
internal object BreakfastReconciliation {
    fun reconcile(store: JournalStore, date: LocalDate, key: String, post: (JSONObject, String) -> String = QwenClient::post): Boolean {
        val input = store.breakfastReconciliationInput(date) ?: return false
        val ref = "saved-journal:$date"
        val prompt = """只核对已保存的当天早餐是否已包含在confirmed_state中，不改变食品或营养数值。返回breakfast_override结构。
            |若没有明确早餐报告且旧食品未包含早餐，mode=default、evidence=""、source_submission_ids=[]、food_ids=[]。
            |若原文明确说早餐为固定早餐/固定饮食/照旧而旧food_items未包含它，mode=confirmed_default；用户明确没吃早餐为skipped，food_ids=[]。
            |如果已保存食品已经包含完整早餐，mode=already_recorded，food_ids只引用那些早餐食品。不要因为午餐含有酸奶或蛋白粉就猜测其为早餐。
            |如果旧汇总不能拆分或早餐更改尚未完整记录，mode=unresolved，不擅自加餐。
            |非default时evidence必须逐字引用journal_input，source_submission_ids只含source_ref。固定配方和计划不是当天报告的证据。不能输出新食品、改动数值或生成饮食建议。""".trimMargin()
        val request = JSONObject().put("model", QWEN_MODEL).put("enable_thinking", false).put("max_tokens", 1200)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", prompt))
                .put(JSONObject().put("role", "user").put("content", JSONObject(input.toString()).put("source_ref", ref).toString())))
            .put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject()
                .put("name", "standing_breakfast_reconciliation").put("strict", true).put("schema", BreakfastResolution.schema())))
        val interpreted = JSONObject(QwenClient.responseText(post(request, key)))
        // The quoted saved journal determines provenance. Do not ask the model to invent trusted IDs.
        interpreted.put("source_submission_ids", JSONArray(if (interpreted.getString("mode") == "default") emptyList<String>() else listOf(ref)))
        val resolution = BreakfastResolution.decode(interpreted)
        require(resolution.mode in setOf("default", "confirmed_default", "skipped", "already_recorded", "unresolved"))
        if (resolution.mode != "default") {
            require(input.getString("journal_input").contains(resolution.evidence))
            require(resolution.submissionIds == listOf(ref))
        }
        val ids = input.getJSONObject("confirmed_state").optJSONArray("food_items") ?: JSONArray()
        require(resolution.foodIds.all { id -> (0 until ids.length()).any { ids.getJSONObject(it).getString("id") == id } })
        return store.applyBreakfastReconciliation(date, input.getJSONObject("confirmed_state"), resolution)
    }
}
