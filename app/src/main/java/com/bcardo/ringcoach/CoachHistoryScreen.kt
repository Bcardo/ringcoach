package com.bcardo.ringcoach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun CoachHistoryScreen(history: CoachHistoryViewModel, modifier: Modifier, onJournal: () -> Unit) {
    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.coach_history_title), modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.headlineLarge)
            TextButton(onClick = onJournal) { Text(stringResource(R.string.coach_history_journals)) }
            Text(stringResource(R.string.coach_history_help))
        }
        if (history.loading) item { Text(stringResource(R.string.coach_loading)) }
        if (history.loadFailed) item {
            Text(stringResource(R.string.coach_history_load_failed))
            OutlinedButton(enabled = !history.loading, onClick = history::retry) {
                Text(stringResource(R.string.journal_retry))
            }
        }
        if (!history.loading && !history.loadFailed && history.cards.isEmpty()) item {
            Text(stringResource(R.string.coach_history_empty))
        }
        items(history.cards, key = { it.id }) { summary ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary.date.toString(), style = MaterialTheme.typography.titleMedium)
                    Text(coachCardTimestamp(summary.createdAt))
                    Text("${summary.model} · ${summary.promptVersion}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { history.open(summary.id) }) { Text(stringResource(R.string.coach_history_open)) }
                }
            }
        }
        if (history.hasMore && !history.loadFailed) item {
            OutlinedButton(enabled = !history.loading, onClick = history::loadMore) {
                Text(stringResource(R.string.coach_history_more))
            }
        }
    }
    if (history.opening || history.detailError != null || history.selected != null) {
        Dialog(onDismissRequest = history::closeDetail) {
            Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxHeight(0.9f)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.coach_history_open), style = MaterialTheme.typography.titleLarge)
                    if (history.opening) Text(stringResource(R.string.coach_loading))
                    history.detailError?.let { error ->
                        Text(stringResource(error))
                        TextButton(onClick = history::retryOpen) { Text(stringResource(R.string.journal_retry)) }
                    }
                    history.selected?.let { card -> ArchivedCoachCard(card, Modifier.weight(1f)) }
                    TextButton(onClick = history::closeDetail) { Text(stringResource(R.string.settings_context_close)) }
                }
            }
        }
    }
}

internal fun coachCardTimestamp(time: java.time.Instant): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault()).format(time)

@Composable
private fun ArchivedCoachCard(card: SavedCoachCard, modifier: Modifier) {
    var showInput by rememberSaveable(card.id) { mutableStateOf(false) }
    val snapshot = remember(card.id) { ExportPreview(card.contextJson) }
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(card.date.toString(), style = MaterialTheme.typography.titleMedium)
            Text(coachCardTimestamp(card.createdAt))
            Text("${card.model} · ${card.promptVersion}")
            Text(stringResource(R.string.coach_history_archived))
        }
        for ((title, advice) in listOf(
            R.string.coach_training to card.advice.training,
            R.string.coach_diet to card.advice.diet,
            R.string.coach_notes to card.advice.notes,
        )) {
            item { Text(stringResource(title), style = MaterialTheme.typography.titleMedium) }
            items(advice) { Text("• $it") }
        }
        item {
            OutlinedButton(onClick = { showInput = !showInput }) {
                Text(stringResource(if (showInput) R.string.coach_history_hide_input else R.string.coach_history_show_input))
            }
        }
        if (showInput) {
            item { Text(stringResource(R.string.coach_history_input_help)) }
            items(snapshot.chunks) { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
    }
}
