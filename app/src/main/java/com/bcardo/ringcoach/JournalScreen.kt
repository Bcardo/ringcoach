package com.bcardo.ringcoach

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
internal fun JournalApp(
    journal: JournalViewModel, settings: SettingsViewModel, coach: CoachViewModel,
    workouts: WorkoutViewModel, coachHistory: CoachHistoryViewModel, nutrition: NutritionViewModel,
    photos: PhotoMealViewModel, today: LocalDate, probe: HealthConnectProbe?, probeContent: @Composable () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf("home") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var lastToday by rememberSaveable { mutableStateOf(today.toString()) }
    val enabled = !journal.loading && !journal.saving && !journal.loadFailed && !photos.working
    val processing = photos.working || workouts.syncing || nutrition.syncing || coach.busy
    val extractionReady = nutrition.loaded && workouts.loaded && !nutrition.needsKey && !workouts.needsKey && nutrition.notice == null && workouts.notice == null &&
        nutrition.days.none { it.status in setOf("pending", "failed") } && workouts.statuses.none { it.optString("status") != "complete" }
    LaunchedEffect(coach.pendingProgressId, coach.retryTick, photos.working, workouts.syncing, nutrition.syncing, coach.busy, coach.hasKey, settings.busy, extractionReady) {
        if (coach.pendingProgressId != null && !photos.working && !workouts.syncing && !nutrition.syncing &&
            !coach.busy && coach.hasKey && !coach.loadFailed && !settings.busy && extractionReady) coach.prepareProgress(probe)
    }
    LaunchedEffect(tab, today) {
        workouts.sync(today); nutrition.sync(today)
        if (tab == "history") coachHistory.reload()
    }
    LaunchedEffect(tab, today, coach.nightlySnapshot) {
        if (tab == "history") coachHistory.loadRecovery(today)
    }
    LaunchedEffect(today, journal.loading) {
        if (!journal.loading && lastToday != today.toString()) {
            if (journal.draft.date.toString() == lastToday && !journal.hasChanges && photos.attachments.isEmpty()) journal.openDay(today)
            lastToday = today.toString()
        }
    }
    BackHandler(enabled = settingsOpen || tab != "home") {
        if (diagnostics) diagnostics = false else if (settingsOpen) settingsOpen = false else tab = "home"
    }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Scaffold(bottomBar = {
            NavigationBar {
                for ((destination, title) in listOf("home" to "今日", "training" to "训练", "journal" to "日志", "history" to "历史")) {
                    NavigationBarItem(selected = tab == destination, onClick = {
                        tab = destination; settingsOpen = false; diagnostics = false
                    }, icon = { Text(title) })
                }
            }
        }) { padding ->
            val scroll = key(tab, settingsOpen, diagnostics) { rememberScrollState() }
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (settingsOpen) { if (diagnostics) "健康连接与诊断" else "设置" }
                        else when (tab) { "training" -> "训练"; "journal" -> "日志"; "history" -> "历史"; else -> "今日" },
                        style = MaterialTheme.typography.headlineLarge)
                    TextButton(onClick = { if (settingsOpen) { if (diagnostics) diagnostics = false else settingsOpen = false }
                        else settingsOpen = true }) { Text(if (settingsOpen) "返回" else "设置") }
                }
                if (settingsOpen) {
                    if (diagnostics) probeContent() else {
                        SettingsEditor(settings, coach, today)
                        TextButton(onClick = { diagnostics = true }) { Text("Health Connect · 连接与诊断") }
                    }
                } else when (tab) {
                    "home" -> {
                        Text(today.toString())
                        NightlyRecoveryPanel(coach, today)
                        DailyIntakeCard(nutrition.days.firstOrNull { it.date == today }, nutrition.syncing,
                            coach.card?.takeIf { it.date == today })
                        if (processing) Text("正在更新摄入、训练计划与教练建议…")
                        if (coach.pendingProgressId != null && !processing) Text("有进度等待更新，可在日志中查看处理状态。")
                        coach.card?.let {
                            Text("最新教练建议", style = MaterialTheme.typography.titleLarge)
                            Text("更新于 ${coachCardTimestamp(it.createdAt)}", style = MaterialTheme.typography.bodySmall)
                            if (it.date != today) Text("这是 ${it.date} 的建议，今日尚未更新。")
                            it.advice.notes.forEach { note -> Text("• $note") }
                        }
                    }
                    "training" -> {
                        val card = coach.card
                        Text("当前训练计划", style = MaterialTheme.typography.titleLarge)
                        Text("完整动作计划与最新表现", style = MaterialTheme.typography.titleMedium)
                        WorkoutScreen(workouts, today, readOnly = true)
                        if (card != null) {
                            Text("最新教练调整", style = MaterialTheme.typography.titleMedium)
                            Text("计划日期 ${card.date} · 更新于 ${coachCardTimestamp(card.createdAt)}", style = MaterialTheme.typography.bodySmall)
                            card.advice.training.forEach { Text("• $it") }
                        } else Text("提交进度后，教练会结合恢复状态更新训练安排。")
                        settings.draft?.routine?.takeIf { it.isNotBlank() }?.let {
                            Text("训练约束与说明", style = MaterialTheme.typography.titleMedium)
                            Text(it.replace("**", "").replace(Regex("(?m)^#+\\s*"), ""))
                        }
                    }
                    "journal" -> {
                        JournalLoadState(journal)
                        ProgressComposer(journal, photos, enabled && !processing, today) {
                            photos.submitProgress(journal.submissionId, journal.draft, { entry ->
                                journal.acceptSaved(entry); coach.queueProgress(today)
                            }) {
                                workouts.sync(today, retryFailed = true); nutrition.sync(today, retryFailed = true)
                            }
                        }
                        if (processing) Text("正在结合当天记录与恢复数据更新…")
                        val failed = nutrition.notice != null || workouts.notice != null ||
                            nutrition.days.any { it.status == "failed" } || workouts.statuses.any { it.optString("status") == "failed" }
                        if (!processing && (failed || coach.pendingProgressId != null)) {
                            Text(when {
                                nutrition.notice != null || nutrition.days.any { it.status == "failed" } -> "饮食分析未完成，因此摄入和教练建议尚未更新。已提交内容保留，无需再次输入。"
                                workouts.notice != null || workouts.statuses.any { it.optString("status") == "failed" } -> "训练识别未完成。已提交内容保留，无需再次输入。"
                                else -> "摄入和训练识别已完成，教练建议尚未更新。无需再次输入。"
                            })
                            nutrition.notice?.let { Text(stringResource(it)) }
                            workouts.notice?.let { Text(stringResource(it)) }
                            if (!coach.hasKey) Text("请在设置中配置千问密钥。")
                            coach.notice?.let { Text(stringResource(it)) }
                            OutlinedButton(enabled = coach.hasKey, onClick = {
                                workouts.sync(today, retryFailed = true); nutrition.sync(today, retryFailed = true)
                                if (coach.pendingProgressId == null) coach.queueProgress(today) else coach.allowProgressRetry()
                            }) { Text("重试更新") }
                        }
                    }
                    "history" -> {
                        RecoveryHistoryPanel(coachHistory, today)
                        ProgressHistory(journal, nutrition, coachHistory)
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalLoadState(journal: JournalViewModel) {
    if (journal.loading) Text("正在读取日志…")
    if (journal.loadFailed) {
        Text("无法读取日志")
        OutlinedButton(onClick = journal::reload) { Text("重试") }
    }
}

@Composable
private fun ProgressComposer(journal: JournalViewModel, photos: PhotoMealViewModel, enabled: Boolean, today: LocalDate, onSubmit: () -> Unit) {
    var dateInput by rememberSaveable { mutableStateOf(journal.draft.date.toString()) }
    LaunchedEffect(journal.draft.date) { dateInput = journal.draft.date.toString() }
    val parsedDate = runCatching { LocalDate.parse(dateInput) }.getOrNull()?.takeIf { !it.isAfter(today) }
    OutlinedTextField(value = dateInput, onValueChange = {
        dateInput = it
        runCatching { LocalDate.parse(it) }.getOrNull()?.takeIf { date -> !date.isAfter(today) }?.let { date ->
            journal.updateDraft(journal.draft.copy(date = date))
        }
    }, label = { Text("记录日期 · YYYY-MM-DD") }, singleLine = true,
        enabled = enabled && photos.attachments.isEmpty(), isError = parsedDate == null, modifier = Modifier.fillMaxWidth())
    Text("汇报这一天的新进度，也可以直接更正之前的记录。文字和照片会一起分析，无需另建餐食。")
    OutlinedTextField(value = listOf(journal.draft.training, journal.draft.diet, journal.draft.additional).filter { it.isNotBlank() }.joinToString("\n"),
        onValueChange = { if (it.length <= 12000) journal.updateDraft(JournalDraft(journal.draft.date, additional = it)) },
        label = { Text("饮食、训练或其他进度") }, minLines = 6, enabled = enabled, modifier = Modifier.fillMaxWidth())
    ProgressAttachments(photos, journal.draft.date, enabled && parsedDate == journal.draft.date)
    Button(enabled = enabled && parsedDate == journal.draft.date && (journal.hasChanges || photos.attachments.isNotEmpty()),
        onClick = onSubmit, modifier = Modifier.fillMaxWidth()) { Text("提交更新") }
}

@Composable
private fun ProgressHistory(journal: JournalViewModel, nutrition: NutritionViewModel, history: CoachHistoryViewModel) {
    Text("日志与教练建议", style = MaterialTheme.typography.titleLarge)
    JournalLoadState(journal)
    if (history.loading) Text("正在读取历史…")
    if (history.loadFailed) OutlinedButton(onClick = history::retry) { Text("重试读取历史") }
    val savedDates = (journal.entries.map { it.draft.date } + history.cards.map { it.date } + nutrition.days.filter { it.status != "missing" }.map { it.date })
        .distinct().sortedDescending()
    val selectedDate = history.recoveryDate.takeIf { history.recovery != null }
    val dates = (listOfNotNull(selectedDate) + savedDates).distinct()
    if (dates.isEmpty()) Text("提交第一条进度后，会在这里按日期保留记录和建议。")
    for (date in dates) {
        var expanded by rememberSaveable(date.toString(), selectedDate) { mutableStateOf(date == selectedDate) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { expanded = !expanded }) { Text("$date · ${if (expanded) "收起" else "查看当天记录"}") }
                nutrition.days.firstOrNull { it.date == date }?.result?.let {
                    Text("已记录摄入：${nutritionNumber(it.values["intake_kcal"]?.value)} kcal · 蛋白质 ${nutritionNumber(it.values["protein_grams"]?.value)} g")
                }
                nutrition.days.firstOrNull { it.date == date }?.reference?.let {
                    Text("导入历史参考：${nutritionNumber(it.intakeKcal)} kcal · 蛋白质 ${nutritionNumber(it.proteinGrams)} g")
                }
                if (expanded) {
                    journal.entries.firstOrNull { it.draft.date == date }?.draft?.let {
                        val legacyTraining = it.training.substringBefore("[更新 ").trim()
                        if (legacyTraining.isNotBlank()) Text(legacyTraining)
                        if (it.diet.isNotBlank()) Text(it.diet)
                        if (it.additional.isNotBlank()) Text(it.additional
                            .replace(Regex("\\[更新 ([^ ]+) · [^]]+]")) { match ->
                                runCatching { "更新于 " + coachCardTimestamp(java.time.Instant.parse(match.groupValues[1])) }.getOrDefault("进度更新")
                            }.replace(Regex("\\[照片 [^]]+]"), "（附照片）"))
                    }
                    val cards = if (date == selectedDate) history.recoveryCards else history.cards.filter { it.date == date }
                    if (date == selectedDate && history.dateLoading) Text("正在读取当天建议…")
                    if (date == selectedDate && history.dateFailed) TextButton(onClick = { history.selectRecoveryDate(date) }) { Text("重试读取当天建议") }
                    if (date == selectedDate && !history.dateLoading && !history.dateFailed && !journal.loading && !journal.loadFailed &&
                        cards.isEmpty() && journal.entries.none { it.draft.date == date }) Text("当天暂无日志或教练建议。")
                    cards.forEach { summary ->
                        TextButton(onClick = { history.open(summary.id) }) { Text("教练建议 · ${coachCardTimestamp(summary.createdAt)}") }
                    }
                }
            }
        }
    }
    if (history.hasMore) OutlinedButton(enabled = !history.loading, onClick = history::loadMore) { Text("加载更早的建议") }
    if (history.opening || history.selected != null || history.detailError != null) {
        AlertDialog(onDismissRequest = history::closeDetail, title = { Text("当时的教练建议") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (history.opening) Text("正在读取…")
                history.detailError?.let { Text(stringResource(it)) }
                history.selected?.let { card ->
                    Text(coachCardTimestamp(card.createdAt))
                    for ((title, rows) in listOf("训练" to card.advice.training, "饮食" to card.advice.diet, "依据" to card.advice.notes)) {
                        Text(title, style = MaterialTheme.typography.titleMedium); rows.forEach { Text("• $it") }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = history::closeDetail) { Text("关闭") } })
    }
}
