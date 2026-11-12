package com.bcardo.ringcoach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
internal fun WorkoutScreen(workouts: WorkoutViewModel, today: LocalDate, readOnly: Boolean = false) {
    Text(stringResource(R.string.workout_help))
    if (workouts.syncing) Text(stringResource(R.string.workout_syncing))
    if (workouts.needsKey) Text(stringResource(R.string.workout_key_needed))
    workouts.notice?.let { Text(stringResource(it)) }
    if (workouts.statuses.any { it.getString("status") != "complete" }) {
        Text(stringResource(R.string.workout_pending))
        if (!readOnly) OutlinedButton(enabled = !workouts.syncing, onClick = { workouts.sync(today, retryFailed = true) }) {
            Text(stringResource(R.string.workout_retry))
        }
    }
    for (status in workouts.statuses) {
        val unresolved = status.getJSONArray("unresolved")
        if (unresolved.length() > 0) {
            Text(stringResource(R.string.workout_clarification, status.getString("date")))
            for (index in 0 until unresolved.length()) Text(unresolved.getString(index))
        }
    }
    val actuals = workouts.records.groupBy { it.observation.exercise }
    val baselineByExercise = workouts.baselines.associateBy { it.exercise }
    val names = (workouts.baselines.map { it.exercise } + actuals.keys).distinct()
    if (names.isEmpty() && !workouts.syncing) Text(stringResource(R.string.workout_empty))
    val other = stringResource(R.string.workout_other)
    val groups = names.groupBy { baselineByExercise[it]?.trainingDay ?: other }
    for ((day, exercises) in groups) {
        Text(day, style = MaterialTheme.typography.titleLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workout_exercise), modifier = Modifier.weight(0.32f),
                style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.workout_performance), modifier = Modifier.weight(0.68f),
                style = MaterialTheme.typography.titleSmall)
        }
        for (exercise in exercises) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val number = baselineByExercise[exercise]?.exerciseNumber
                Text(if (number != null) "$number. $exercise" else exercise, modifier = Modifier.weight(0.32f))
                Column(modifier = Modifier.weight(0.68f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val latest = actuals[exercise]
                    val baseline = baselineByExercise[exercise]
                    if (!latest.isNullOrEmpty()) {
                        Text(stringResource(
                            if (latest.any { it.observation.status == "partial" }) R.string.workout_partial_date else R.string.workout_actual_date,
                            latest.first().date.toString()),
                            style = MaterialTheme.typography.labelMedium)
                        for (record in latest) Text(record.observation.performance)
                    } else if (baseline != null) {
                        Text(stringResource(R.string.workout_reference_date, baseline.snapshotDate.toString()),
                            style = MaterialTheme.typography.labelMedium)
                        Text(baseline.referenceNotes.replace("**", ""))
                    }
                }
            }
        }
    }
}
