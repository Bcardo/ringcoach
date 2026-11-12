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
import java.time.LocalDate
import java.util.UUID

internal class JournalViewModel(application: Application, private val state: SavedStateHandle) :
    AndroidViewModel(application) {
    private val store = JournalStore(application)
    private val restoringDraft = state.contains("journal_date")
    private val legacyAdditional = listOfNotNull(
        state.get<String>("journal_notes"),
        state.get<String>("journal_readiness")?.takeIf { it.isNotBlank() }?.let { "晨起体感：" + it },
        state.get<String>("journal_discomfort")?.takeIf { it.isNotBlank() }?.let { "不适记录：" + it },
    ).filter { it.isNotBlank() }.joinToString("\n")

    var draft by mutableStateOf(JournalDraft(
        LocalDate.parse(state["journal_date"] ?: LocalDate.now().toString()),
        state["journal_training"] ?: "",
        state["journal_diet"] ?: "",
        state["journal_additional"] ?: legacyAdditional,
    ))
        private set
    var entries by mutableStateOf(emptyList<JournalEntry>())
        private set
    var loading by mutableStateOf(true)
        private set
    var saving by mutableStateOf(false)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Int?>(null)
        private set
    var pendingDay by mutableStateOf<LocalDate?>(null)
        private set

    val hasChanges: Boolean
        get() = draft.hasContent

    var submissionId: String = state.get<String>("progress_id") ?: UUID.randomUUID().toString()
        private set

    init {
        reload()
    }

    fun reload() {
        val keepDraft = restoringDraft || hasChanges
        loading = true
        loadFailed = false
        viewModelScope.launch {
            try {
                entries = withContext(Dispatchers.IO) { store.readAll() }
                if (!keepDraft) updateDraft(JournalDraft(draft.date))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                loadFailed = true
            } finally {
                loading = false
            }
        }
    }

    fun updateDraft(value: JournalDraft) {
        if (saving) return
        if (value != draft) submissionId = UUID.randomUUID().toString()
        draft = value
        state["journal_date"] = value.date.toString()
        state["journal_training"] = value.training
        state["journal_diet"] = value.diet
        state["journal_additional"] = value.additional
        state["progress_id"] = submissionId
        notice = null
    }

    fun openDay(date: LocalDate): Boolean {
        if (loading || saving || loadFailed) return false
        if (date == draft.date) return true
        if (hasChanges) {
            pendingDay = date
            return false
        }
        selectDay(date)
        return true
    }

    private fun selectDay(date: LocalDate) {
        submissionId = UUID.randomUUID().toString()
        updateDraft(JournalDraft(date))
    }

    fun discardAndOpen() {
        pendingDay?.let(::selectDay)
        pendingDay = null
    }

    fun cancelOpen() {
        pendingDay = null
    }

    fun acceptSaved(entry: JournalEntry) {
        entries = (entries.filterNot { it.draft.date == entry.draft.date } + entry).sortedByDescending { it.draft.date }
        submissionId = UUID.randomUUID().toString()
        updateDraft(JournalDraft(entry.draft.date))
        notice = R.string.journal_saved
    }

    fun save(onSaved: () -> Unit = {}) {
        if (loading || saving || loadFailed) return
        if (!draft.hasContent) {
            notice = R.string.journal_empty
            return
        }
        saving = true
        val reviewed = draft
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.save(reviewed) }
                entries = (entries.filterNot { it.draft.date == saved.draft.date } + saved)
                    .sortedByDescending { it.draft.date }
                saving = false
                updateDraft(saved.draft)
                notice = R.string.journal_saved
                onSaved()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                notice = R.string.journal_save_failed
            } finally {
                saving = false
            }
        }
    }

    override fun onCleared() {
        store.close()
    }
}
