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
