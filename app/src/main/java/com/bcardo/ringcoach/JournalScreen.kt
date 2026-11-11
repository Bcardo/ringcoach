package com.bcardo.ringcoach

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
internal fun JournalApp(
    journal: JournalViewModel,
    settings: SettingsViewModel,
    coach: CoachViewModel,
    workouts: WorkoutViewModel,
    coachHistory: CoachHistoryViewModel,
    today: LocalDate,
    probe: HealthConnectProbe?,
    probeContent: @Composable () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf("home") }
    var lastToday by rememberSaveable { mutableStateOf(today.toString()) }
    LaunchedEffect(tab, today) {
        if (tab !in setOf("history", "coach_history")) workouts.sync(today)
    }
    LaunchedEffect(tab) { if (tab == "coach_history") coachHistory.reload() }
    LaunchedEffect(today, journal.loading) {
        if (!journal.loading && lastToday != today.toString()) {
            val previousDay = LocalDate.parse(lastToday)
            lastToday = today.toString()
            if (journal.draft.date == previousDay) journal.openDay(today)
        }
    }
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    val enabled = !journal.loading && !journal.saving && !journal.loadFailed
    fun openDay(date: LocalDate) {
        if (journal.openDay(date)) tab = "journal"
    }
    BackHandler(enabled = tab != "home") { tab = if (tab == "coach_history") "history" else "home" }
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    for ((destination, title) in listOf(
                        "home" to R.string.nav_home, "training" to R.string.nav_training,
                        "journal" to R.string.nav_journal,
                        "coach" to R.string.nav_coach,
                        "history" to R.string.nav_history,
                    )) {
                        NavigationBarItem(
                            selected = tab == destination || (destination == "history" && tab == "coach_history"),
                            onClick = {
                                if (destination == "journal") openDay(today) else tab = destination
                            },
                            icon = { Text(stringResource(title)) },
                        )
                    }
                }
            },
        ) { padding ->
            if (tab == "coach_history") {
                CoachHistoryScreen(coachHistory, Modifier.padding(padding)) { tab = "history" }
            } else if (tab == "history") {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Text(stringResource(R.string.nav_history),
                        modifier = Modifier.padding(top = 24.dp),
                        style = MaterialTheme.typography.headlineLarge) }
                    item { JournalLoadState(journal) }
                    item {
                        OutlinedButton(onClick = { tab = "coach_history" }) {
                            Text(stringResource(R.string.coach_history_title))
                        }
                    }
                    if (enabled && journal.entries.isEmpty()) {
                        item { Text(stringResource(R.string.journal_history_empty)) }
                    }
                    items(journal.entries, key = { it.draft.date.toString() }) { entry ->
                        JournalEntryCard(entry, enabled) { openDay(entry.draft.date) }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding)
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(when (tab) {
                            "journal" -> R.string.nav_journal
                            "data" -> R.string.nav_data
                            "settings" -> R.string.nav_settings
                            "coach" -> R.string.nav_coach
                            "training" -> R.string.nav_training
                            else -> R.string.app_name
                        }),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    when (tab) {
                        "data" -> probeContent()
                        "training" -> WorkoutScreen(workouts, today)
                        "settings" -> SettingsEditor(settings, coach, today)
                        "coach" -> CoachScreen(
                            coach,
                            hasUnsavedInput = journal.hasChanges || settings.hasChanges,
                            inputsReady = !journal.loading && !journal.loadFailed &&
                                !settings.busy && !settings.loadFailed && !workouts.syncing,
                            onPrepare = { coach.prepare(today, probe) },
                            onSettings = { tab = "settings" },
                        )
                        "journal" -> {
                            JournalLoadState(journal)
                            JournalEditor(journal, enabled) { workouts.sync(today, retryFailed = true) }
                        }
                        else -> {
                            Text(today.toString(), style = MaterialTheme.typography.titleMedium)
                            JournalLoadState(journal)
                            val entry = journal.entries.firstOrNull { it.draft.date == today }
                            if (entry == null && enabled) {
                                Text(stringResource(R.string.journal_today_empty))
                            }
                            entry?.let { JournalEntryCard(it, enabled) { openDay(today) } }
                            if (entry == null) {
                                Button(
                                    enabled = enabled,
                                    onClick = { openDay(today) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.journal_open_today)) }
                            }
                            OutlinedButton(
                                onClick = { tab = "data" },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.home_health_data)) }
                            Button(
                                onClick = { tab = "coach" },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.home_coach)) }
                            TextButton(
                                onClick = { tab = "settings" },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.nav_settings)) }
                        }
                    }
                }
            }
        }
        journal.pendingDay?.let {
            AlertDialog(
                onDismissRequest = journal::cancelOpen,
                title = { Text(stringResource(R.string.journal_discard_title)) },
                text = { Text(stringResource(R.string.journal_discard_message)) },
                confirmButton = {
                    TextButton(onClick = { journal.discardAndOpen(); tab = "journal" }) {
                        Text(stringResource(R.string.journal_discard_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = journal::cancelOpen) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }
    }
}

@Composable
private fun JournalLoadState(journal: JournalViewModel) {
    if (journal.loading) Text(stringResource(R.string.journal_loading))
    if (journal.loadFailed) {
        Text(stringResource(R.string.journal_load_failed))
        OutlinedButton(onClick = journal::reload) { Text(stringResource(R.string.journal_retry)) }
    }
}

@Composable
private fun JournalEditor(journal: JournalViewModel, enabled: Boolean, onSaved: () -> Unit) {
    val draft = journal.draft
    Text(stringResource(R.string.journal_editing_date, draft.date.toString()),
        style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = draft.training,
        onValueChange = { journal.updateDraft(draft.copy(training = it)) },
        label = { Text(stringResource(R.string.journal_training)) },
        minLines = 3,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.diet,
        onValueChange = { journal.updateDraft(draft.copy(diet = it)) },
        label = { Text(stringResource(R.string.journal_diet)) },
        minLines = 3,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.additional,
        onValueChange = { journal.updateDraft(draft.copy(additional = it)) },
        label = { Text(stringResource(R.string.journal_additional)) },
        minLines = 2,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    journal.notice?.let { Text(stringResource(it)) }
    Text(stringResource(R.string.workout_save_help), style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = { journal.save(onSaved) },
        enabled = enabled && journal.hasChanges,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(if (journal.saving) R.string.journal_saving else R.string.journal_save)) }
}

@Composable
private fun JournalEntryCard(entry: JournalEntry, enabled: Boolean, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.draft.date.toString(), style = MaterialTheme.typography.titleMedium)
            if (entry.draft.training.isNotBlank()) {
                Text(stringResource(R.string.journal_training_value, entry.draft.training),
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (entry.draft.diet.isNotBlank()) {
                Text(stringResource(R.string.journal_diet_value, entry.draft.diet),
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (entry.draft.additional.isNotBlank()) {
                Text(stringResource(R.string.journal_additional_value, entry.draft.additional),
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            TextButton(enabled = enabled, onClick = onOpen) { Text(stringResource(R.string.journal_edit)) }
        }
    }
}
