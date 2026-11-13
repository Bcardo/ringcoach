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
import java.time.LocalDate

internal class NutritionViewModel(application: Application) : AndroidViewModel(application) {
    private val store = JournalStore(application)
    private var requested = false
    private var retryRequested = false
    var days by mutableStateOf(emptyList<NutritionDay>())
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
                        withContext(Dispatchers.IO) { store.retryFailedNutrition(); ProgressWorkStore(store.writableDatabase).clearRetries() }
                        ProgressJob.schedule(getApplication(), replaceExisting = true)
                    }
                    refresh(date)
                } while (requested)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                notice = R.string.nutrition_load_failed
            } finally {
                syncing = false
            }
        }
    }

    private suspend fun refresh(date: LocalDate) {
        days = withContext(Dispatchers.IO) { store.readCoachingContext(date, "").nutritionDays }
        loaded = true
    }

    fun reconcile(day: LocalDate, mode: String, today: LocalDate) {
        if (syncing) return
        syncing = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { PhotoMealStore(store.writableDatabase).reconcile(day, mode) }
                refresh(today)
            } catch (exception: CancellationException) { throw exception }
            catch (_: Exception) { notice = R.string.nutrition_load_failed }
            finally { syncing = false }
        }
    }

    override fun onCleared() { store.close() }
}
