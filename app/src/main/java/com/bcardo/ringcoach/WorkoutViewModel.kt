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
    private var requested = false
    private var retryRequested = false

    var baselines by mutableStateOf(emptyList<ExerciseBaseline>())
        private set
    var records by mutableStateOf(emptyList<WorkoutRecord>())
        private set
    var histories by mutableStateOf(emptyMap<String, ExerciseHistory>())
        private set
    var followups by mutableStateOf(emptyMap<String, StoredExerciseNote>())
        private set
    var historyEpoch by mutableStateOf(0)
        private set

    fun loadHistory(exercise: String, date: LocalDate, retry: Boolean = false) {
        if (!retry && histories.containsKey(exercise)) return
        val epoch = historyEpoch
        histories = histories + (exercise to ExerciseHistory())
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { store.workoutHistory(exercise, date) }
                if (epoch == historyEpoch) histories = histories + (exercise to ExerciseHistory(result, loading = false))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (epoch == historyEpoch) histories = histories + (exercise to ExerciseHistory(loading = false, failed = true))
            }
        }
    }
    var statuses by mutableStateOf(emptyList<JSONObject>())
        private set
    var syncing by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
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
                        withContext(Dispatchers.IO) { store.retryFailedWorkouts(); ProgressWorkStore(store.writableDatabase).clearRetries() }
                        ProgressJob.schedule(getApplication(), replaceExisting = true)
                    }
                    val result = withContext(Dispatchers.IO) {
                        val policy = getApplication<Application>().assets.open("coaching-policy.md").bufferedReader().use { it.readText() }
                        val context = store.readCoachingContext(date, policy)
                        context to store.exerciseFollowups(context.toJson(), date)
                    }
                    val context = result.first
                    baselines = context.exercises
                    records = context.workoutRecords
                    followups = result.second
                    historyEpoch++
                    histories = emptyMap()
                    statuses = context.workoutStatuses
                    loaded = true
                } while (requested)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                followups = emptyMap()
                notice = R.string.workout_load_failed
            } finally {
                syncing = false
            }
        }
    }

    override fun onCleared() {
        store.close()
    }
}
