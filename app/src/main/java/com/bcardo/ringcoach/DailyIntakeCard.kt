package com.bcardo.ringcoach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DailyIntakeCard(day: NutritionDay?, analyzing: Boolean, advice: SavedCoachCard?) {
    val incomplete = day?.photoConflict == true || day?.status in setOf("pending", "failed") ||
        (day?.photoMode != "unified" && day?.photoMeals?.any { it.consumed && it.status != "complete" } == true)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("今日已记录摄入", style = MaterialTheme.typography.titleLarge)
            for ((field, label) in listOf("intake_kcal" to "热量", "protein_grams" to "蛋白质", "carbohydrate_grams" to "碳水", "fat_grams" to "脂肪")) {
                val value = day?.result?.values?.get(field)
                val unit = if (field == "intake_kcal") "kcal" else "g"
                val actual = (if (value?.kind == "estimated") "≈" else "") + nutritionNumber(value?.value)
                Text("$label：$actual $unit")
            }
            if (day?.status in setOf("pending", "failed") && day?.result != null)
                Text("还有一条饮食更新未计入。", style = MaterialTheme.typography.bodySmall)
            day?.result?.foodSummary?.takeIf { it.isNotBlank() }?.let {
                Text("已计入的食物", style = MaterialTheme.typography.titleMedium)
                Text(it)
            }
            day?.result?.supplements?.takeIf { it.isNotEmpty() }?.let { events ->
                Text("已记录补剂", style = MaterialTheme.typography.titleMedium)
                events.forEach { Text(supplementSummary(it)) }
            }
            if (day?.photoConflict == true) Text("部分餐食可能重复，请在日志中确认。")
            if (analyzing && !incomplete) Text("正在更新摄入…")
            Text("今日饮食安排", style = MaterialTheme.typography.titleMedium)
            if (advice == null) Text("今日暂无饮食建议。") else {
                val updated = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(advice.createdAt)
                Text("更新于 $updated", style = MaterialTheme.typography.bodySmall)
                advice.advice.diet.forEach { Text("• $it") }
            }
        }
    }
}
