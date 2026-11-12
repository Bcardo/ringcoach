package com.bcardo.ringcoach

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import java.time.LocalDate

internal class CoachHistoryViewModel(application: Application, private val state: SavedStateHandle) :
    AndroidViewModel(application) {
    private val store = JournalStore(application)
    var cards by mutableStateOf(emptyList<CoachCardSummary>())
        private set
    var loading by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    var selected by mutableStateOf<SavedCoachCard?>(null)
        private set
    var opening by mutableStateOf(false)
        private set
    var detailError by mutableStateOf<Int?>(null)
        private set
    private var selectionRevision = 0
    private var lastLoadReset = true
    var recovery by mutableStateOf<RecoveryTrend?>(null)
        private set
    var recoveryLoading by mutableStateOf(false)
        private set
    var recoveryFailed by mutableStateOf(false)
        private set
    var recoveryRange by mutableStateOf(state.get<Int>("recovery_range") ?: 7)
        private set
    var recoveryDate by mutableStateOf(state.get<String>("recovery_date")?.let(LocalDate::parse))
        private set
    private var recoveryJob: Job? = null
    private var recoveryRevision = 0
    private var dateRevision = 0
    var recoveryCards by mutableStateOf(emptyList<CoachCardSummary>())
        private set
    var dateLoading by mutableStateOf(false)
        private set
    var dateFailed by mutableStateOf(false)
        private set

    fun selectRecoveryDate(date: LocalDate) {
        recoveryDate = date
        state["recovery_date"] = date.toString()
        val revision = ++dateRevision
        recoveryCards = emptyList()
        dateLoading = true
        dateFailed = false
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { store.coachCardSummariesForDate(date) }
                if (revision == dateRevision) recoveryCards = result
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (revision == dateRevision) dateFailed = true }
            finally { if (revision == dateRevision) dateLoading = false }
        }
    }

    fun loadRecovery(today: LocalDate, range: Int = recoveryRange) {
        require(range in listOf(7, 30))
        recoveryRange = range
        state["recovery_range"] = range
        recoveryJob?.cancel()
        val revision = ++recoveryRevision
        recoveryLoading = true
        recoveryFailed = false
        recovery = null
        recoveryJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    NightlyRecoveryStore(getApplication()).use { RecoveryTrend.decode(it.history(today.minusDays(range - 1L), today)) }
                }
                if (revision == recoveryRevision) {
                    recovery = result
                    selectRecoveryDate(recoveryDate?.takeIf { it in result.days } ?: today)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (revision == recoveryRevision) recoveryFailed = true }
            finally { if (revision == recoveryRevision) recoveryLoading = false }
        }
    }

    init {
        state.get<String>("coach_history_selected")?.let(::open)
    }

    fun reload() = load(reset = true)
    fun loadMore() = load(reset = false)
    fun retry() = load(reset = lastLoadReset)

    private fun load(reset: Boolean) {
        if (loading || (!reset && !hasMore)) return
        loading = true
        lastLoadReset = reset
        loadFailed = false
        val before = if (reset) null else cards.lastOrNull()
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { store.coachCardSummaries(before) }
                cards = (if (reset) emptyList() else cards) + page.take(20)
                hasMore = page.size > 20
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                loadFailed = true
            } finally {
                loading = false
            }
        }
    }

    fun open(id: String) {
        state["coach_history_selected"] = id
        val revision = ++selectionRevision
        selected = null
        detailError = null
        opening = true
        viewModelScope.launch {
            try {
                val card = withContext(Dispatchers.IO) { store.coachCard(id) }
                if (revision == selectionRevision) {
                    selected = card
                    if (card == null) detailError = R.string.coach_history_missing
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (revision == selectionRevision) detailError = R.string.coach_history_detail_failed
            } finally {
                if (revision == selectionRevision) opening = false
            }
        }
    }

    fun retryOpen() {
        state.get<String>("coach_history_selected")?.let(::open)
    }

    fun closeDetail() {
        selectionRevision++
        state.remove<String>("coach_history_selected")
        selected = null
        detailError = null
        opening = false
    }

    override fun onCleared() { store.close() }
}
