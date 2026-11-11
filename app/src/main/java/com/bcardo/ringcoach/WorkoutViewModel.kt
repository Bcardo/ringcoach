package com.bcardo.ringcoach

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

internal class WorkoutViewModel(application: Application) : AndroidViewModel(application) {
    private val store = JournalStore(application)
    private val credentials = QwenCredentials(application)
    private var requested = false
    private var retryRequested = false

    var baselines by mutableStateOf(emptyList<ExerciseBaseline>())
        private set
    var records by mutableStateOf(emptyList<WorkoutRecord>())
        private set
    var statuses by mutableStateOf(emptyList<JSONObject>())
        private set
    var syncing by mutableStateOf(false)
        private set
    var needsKey by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Int?>(null)
        private set

    fun sync(date: LocalDate, retryFailed: Boolean = false) {
        requested = true
        retryRequested = retryRequested || retryFailed
        if (syncing) return
        syncing = true
        notice = null
        needsKey = false
        viewModelScope.launch {
            try {
                do {
                    requested = false
                    if (retryRequested) {
                        retryRequested = false
                        withContext(Dispatchers.IO) { store.retryFailedWorkouts() }
                    }
                    processPending()
                    val context = withContext(Dispatchers.IO) { store.readCoachingContext(date, "") }
                    baselines = context.exercises
                    records = context.workoutRecords
                    statuses = context.workoutStatuses
                } while (requested)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                notice = R.string.workout_load_failed
            } finally {
                syncing = false
            }
        }
    }

    private suspend fun processPending() {
        while (true) {
            val job = withContext(Dispatchers.IO) { store.nextPendingWorkout() } ?: return
            val key = withContext(Dispatchers.IO) { credentials.read() }
            if (key == null) {
                needsKey = true
                return
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    val baselines = store.readCoachingContext(job.date, "").exercises
                    val prompt = getApplication<Application>().assets.open("workout-extraction.txt")
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val response = QwenClient.post(WorkoutExtractor.request(job, baselines, prompt), key)
                    WorkoutExtractor.decode(QwenClient.responseText(response), job.training, baselines.map { it.exercise }.toSet())
                }
                withContext(Dispatchers.IO) { store.applyWorkoutExtraction(job, result) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                withContext(Dispatchers.IO) { store.failWorkoutExtraction(job) }
                notice = R.string.workout_parse_failed
                return
            }
        }
    }

    override fun onCleared() {
        store.close()
    }
}
