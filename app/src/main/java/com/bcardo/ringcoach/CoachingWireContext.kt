package com.bcardo.ringcoach

import org.json.JSONArray
import org.json.JSONObject

/** Wire-only representation. Canonical context and local archives keep their existing shapes. */
internal object CoachingWireContext {
    const val VERSION = "coaching-wire-v1"
    const val INSTRUCTIONS = """
请求可能使用 coaching-wire-v1 无损表示。rows-v1 对象的每行按 columns 顺序与 common 合并，恢复原始对象；null 仍是未知，不是零。
recovery-days-v1 的 wake_dates 与每个 series 的行一一对应，保留每个日期及指标的来源、状态、单位和定义。
ring-sessions-v1 的 sessions 是会话表；metric_series 中的 session_indices 把 values 表中的行关联到各会话，不能合并或相加重叠会话/不同来源。
standing_plan_ref=user_saved_settings 表示使用顶层 routine、diet_plan 及本系统消息中的用户 instructions，不是新的计划。
心率 samples 配合 sample_columns 按列恢复每个原始时间、数值和关联；这只是无损编码，不是降采样。按恢复后的原始含义理解证据，不把压缩、引用或缺失当作新事实。
"""

    fun encode(original: JSONObject): JSONObject = JSONObject(original.toString()).apply {
        optJSONObject("supplement_history")?.let { history ->
            val saved = history.optJSONObject("standing_plan")
            val plan = JSONObject()
            for (key in listOf("routine", "diet_plan", "instructions")) if (has(key)) plan.put(key, get(key))
            if (saved != null && listOf("routine", "diet_plan", "instructions").all { opt(it) is String } && equal(saved, plan)) {
                history.remove("standing_plan")
                history.put("standing_plan_ref", "user_saved_settings")
            }
        }
        optJSONObject("recovery_history")?.let { history ->
            history.optJSONArray("days")?.let { days ->
                if (days.length() > 0 && objects(days) != null) {
                    val entries = objects(days)!!
                    val fieldNames = keys(entries.first())
                    val metrics = fieldNames.filter { it != "wake_date" }.sorted()
                    if (fieldNames.contains("wake_date") && entries.all { keys(it) == fieldNames && metrics.all { k -> it.optJSONObject(k) != null } }) {
                        val series = JSONObject()
                        for (metric in metrics) series.put(metric, rows(JSONArray(entries.map { it.getJSONObject(metric) })))
                        val encoded = JSONObject().put("encoding", "recovery-days-v1")
                            .put("wake_dates", JSONArray(entries.map { it.get("wake_date") })).put("series", series)
                        history.put("days", smaller(days, encoded))
                    }
                }
            }
        }
        optJSONObject("nightly_recovery")?.let { vendor ->
            vendor.optJSONArray("records")?.let { vendor.put("records", rows(it)) }
        }
        optJSONObject("ring_bluetooth")?.let { ring ->
            ring.optJSONArray("sessions")?.let { sessions ->
                val originalSessions = objects(sessions)
                if (!originalSessions.isNullOrEmpty() && originalSessions.all { session ->
                    session.optJSONObject("metrics")?.let { metrics -> keys(metrics).all { metrics.optJSONObject(it) != null } } == true
                }) {
                    val bases = originalSessions.map { session -> JSONObject(session.toString()).apply { remove("metrics") } }
                    val indices = linkedMapOf<String, MutableList<Int>>()
                    val values = linkedMapOf<String, MutableList<JSONObject>>()
                    originalSessions.forEachIndexed { index, session ->
                        val metrics = session.getJSONObject("metrics")
                        for (metric in keys(metrics).sorted()) {
                            indices.getOrPut(metric) { mutableListOf() }.add(index)
                            values.getOrPut(metric) { mutableListOf() }.add(metrics.getJSONObject(metric))
                        }
                    }
                    val series = JSONObject()
                    for ((metric, points) in values) series.put(metric, JSONObject()
                        .put("session_indices", JSONArray(indices.getValue(metric))).put("values", rows(JSONArray(points))))
                    val encoded = JSONObject().put("encoding", "ring-sessions-v1")
                        .put("sessions", rows(JSONArray(bases))).put("metric_series", series)
                    ring.put("sessions", smaller(sessions, encoded))
                }
            }
        }
        optJSONObject("telemetry")?.optJSONObject("current_night")?.optJSONArray("heart_rate_series")?.let { series ->
            for (index in 0 until series.length()) {
                val record = series.optJSONObject(index) ?: continue
                val samples = record.optJSONArray("samples") ?: continue
                val points = objects(samples) ?: continue
                if (points.isEmpty()) continue
                val fields = keys(points.first())
                if (fields !in listOf(setOf("time", "beats_per_minute"), setOf("time", "beats_per_minute", "session_refs")) ||
                    points.any { keys(it) != fields }) continue
                val columns = listOf("time", "beats_per_minute", "session_refs").filter { it in fields }
                val candidate = JSONObject(record.toString()).put("samples", JSONArray(points.map { point -> JSONArray(columns.map { point.get(it) }) }))
                    .put("sample_columns", JSONArray(columns)).put("sample_encoding", "rows_in_sample_columns_order_lossless")
                if (bytes(candidate) < bytes(record)) series.put(index, candidate)
            }
        }
        put("context_encoding", VERSION)
    }

    /** Non-uniform records remain untouched; absent fields must never become explicit nulls. */
    private fun rows(array: JSONArray): Any {
        val values = objects(array) ?: return array
        if (values.isEmpty()) return array
        val fieldNames = keys(values.first()).sorted()
        if (values.any { keys(it) != fieldNames.toSet() }) return array
        val common = JSONObject()
        for (key in fieldNames) if (values.all { equal(values.first().get(key), it.get(key)) }) common.put(key, values.first().get(key))
        val columns = fieldNames.filterNot(common::has)
        val packed = JSONObject().put("encoding", "rows-v1").put("common", common).put("columns", JSONArray(columns))
            .put("rows", JSONArray(values.map { row -> JSONArray(columns.map { row.get(it) }) }))
        return smaller(array, packed)
    }
    private fun equal(a: Any, b: Any): Boolean = when {
        a is JSONObject && b is JSONObject -> keys(a) == keys(b) && keys(a).all { equal(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { equal(a.get(it), b.get(it)) }
        else -> a == b
    }
    private fun objects(array: JSONArray): List<JSONObject>? = (0 until array.length()).map {
        array.optJSONObject(it) ?: return null
    }
    private fun keys(value: JSONObject): Set<String> = value.keys().asSequence().toSet()
    private fun bytes(value: Any) = value.toString().toByteArray(Charsets.UTF_8).size
    private fun smaller(original: Any, candidate: Any): Any = if (bytes(candidate) < bytes(original)) candidate else original
}
