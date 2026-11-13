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
                    val context = withContext(Dispatchers.IO) { store.readCoachingContext(date, "") }
                    baselines = context.exercises
                    records = context.workoutRecords
                    statuses = context.workoutStatuses
                    loaded = true
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

    override fun onCleared() {
        store.close()
    }
}
