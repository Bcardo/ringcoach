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
    private val credentials = QwenCredentials(application)
    private var requested = false
    private var retryRequested = false
    var days by mutableStateOf(emptyList<NutritionDay>())
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
                    refresh(date)
                    if (retryRequested) {
                        retryRequested = false
                        withContext(Dispatchers.IO) { store.retryFailedNutrition() }
                    }
                    processPending()
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

    private suspend fun processPending() {
        while (true) {
            val job = withContext(Dispatchers.IO) { store.nextPendingNutrition() } ?: return
            val key = withContext(Dispatchers.IO) { credentials.read() }
            if (key == null) { needsKey = true; return }
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val prompt = getApplication<Application>().assets.open("nutrition-extraction.txt")
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    NutritionExtractor.extract(job, prompt, store.nutritionPhotos(job), key)
                }
                withContext(Dispatchers.IO) { store.applyNutritionExtraction(job, parsed) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                withContext(Dispatchers.IO) { store.failNutritionExtraction(job) }
                notice = when (exception) {
                    is QwenHttpException -> when (exception.status) {
                        401, 403 -> R.string.coach_api_access_failed
                        429 -> R.string.coach_api_quota
                        else -> R.string.coach_api_failed
                    }
                    is java.io.IOException -> R.string.coach_network_failed
                    is IllegalArgumentException, is org.json.JSONException -> R.string.nutrition_invalid_response
                    else -> R.string.nutrition_parse_failed
                }
                return
            }
        }
    }

    override fun onCleared() { store.close() }
}
