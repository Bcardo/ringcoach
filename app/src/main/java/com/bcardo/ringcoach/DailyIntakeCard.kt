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
            Text(if (day?.result?.coverage == "daily_total") "使用已汇报的全天总量" else "截至目前已记录的摄入；未记录的食物不计入。", style = MaterialTheme.typography.bodySmall)
            if (day?.status in setOf("pending", "failed") && day?.result != null) Text("上次已确认的摄入；本次新进度尚未计入。", style = MaterialTheme.typography.bodySmall)
            day?.result?.foodSummary?.takeIf { it.isNotBlank() }?.let {
                Text("已计入的食物", style = MaterialTheme.typography.titleMedium)
                Text(it)
            }
            day?.result?.assumptions?.take(4)?.forEach { Text("估算依据：$it", style = MaterialTheme.typography.bodySmall) }
            day?.result?.unresolved?.forEach { Text("待澄清：$it") }
            if (day?.photoConflict == true) Text("旧记录有文字与照片重叠；下次提交进度时会统一分析。")
            if (analyzing || incomplete) Text("饮食正在分析或有待确认项；确认后更新摄入与建议。")
            Text("今日饮食安排", style = MaterialTheme.typography.titleMedium)
            if (advice == null) Text("提交进度后，教练根据当前饮食计划与 Prompt 判断今天还需补充什么。") else {
                val updated = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(advice.createdAt)
                Text("上次建议 $updated；提交新进度后更新。", style = MaterialTheme.typography.bodySmall)
                advice.advice.diet.forEach { Text("• $it") }
            }
        }
    }
}
