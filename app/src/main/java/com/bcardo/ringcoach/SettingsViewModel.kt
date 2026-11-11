package com.bcardo.ringcoach

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
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
import java.io.IOException
import java.time.LocalDate

internal class SettingsViewModel(application: Application, private val state: SavedStateHandle) :
    AndroidViewModel(application) {
    private val store = JournalStore(application)
    private var saved: CoachingSettings? = null
    private var stagedImport = state.get<String>("settings_import")?.let(SettingsTransfer::decode)

    var draft by mutableStateOf<CoachingSettings?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var saving by mutableStateOf(false)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Int?>(null)
        private set
    var choosingFile by mutableStateOf(false)
        private set
    var importing by mutableStateOf(false)
        private set
    var pendingImport by mutableStateOf<SettingsImport?>(null)
        private set
    var contextPreview by mutableStateOf<ExportPreview?>(null)
        private set
    var preparingContext by mutableStateOf(false)
        private set

    val busy: Boolean
        get() = loading || saving || importing || choosingFile || preparingContext

    val hasChanges: Boolean
        get() = draft != null && (draft != saved || stagedImport != null)

    init {
        reload()
    }

    fun reload() {
        loading = true
        loadFailed = false
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    store.readSettings(defaultInstructions())
                }
                saved = loaded
                if (draft == null) {
                    draft = CoachingSettings(
                        state["settings_routine"] ?: loaded.routine,
                        state["settings_diet"] ?: loaded.diet,
                        state["settings_instructions"] ?: loaded.instructions,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                loadFailed = true
            } finally {
                loading = false
            }
        }
    }

    fun updateDraft(value: CoachingSettings) {
        if (busy || loadFailed) return
        draft = value
        state["settings_routine"] = value.routine
        state["settings_diet"] = value.diet
        state["settings_instructions"] = value.instructions
        notice = null
    }

    fun save() {
        if (busy || loadFailed) return
        val reviewed = draft ?: return
        if (reviewed.instructions.isBlank()) {
            notice = R.string.settings_instructions_empty
            return
        }
        saving = true
        viewModelScope.launch {
            try {
                val imported = stagedImport
                val stored = withContext(Dispatchers.IO) {
                    if (imported == null) store.saveSettings(reviewed) else store.saveImportedSettings(reviewed, imported)
                }
                saved = stored
                stagedImport = null
                state.remove<String>("settings_import")
                saving = false
                updateDraft(stored)
                notice = R.string.settings_saved
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                notice = R.string.settings_save_failed
            } finally {
                saving = false
            }
        }
    }

    fun chooseImport(launch: () -> Unit) {
        if (busy || loadFailed) return
        choosingFile = true
        notice = null
        try {
            launch()
        } catch (_: Exception) {
            choosingFile = false
            notice = R.string.settings_import_failed
        }
    }

    fun onImportResult(resolver: ContentResolver, uri: Uri?) {
        choosingFile = false
        if (uri == null) return
        importing = true
        notice = null
        viewModelScope.launch {
            try {
                pendingImport = withContext(Dispatchers.IO) {
                    val bytes = resolver.openInputStream(uri)?.use { it.readNBytes(SettingsTransfer.MAX_BYTES + 1) }
                        ?: throw IOException("No settings document stream")
                    require(bytes.size <= SettingsTransfer.MAX_BYTES)
                    SettingsTransfer.decode(bytes.toString(Charsets.UTF_8))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                notice = R.string.settings_import_failed
            } finally {
                importing = false
            }
        }
    }

    fun confirmImport() {
        val imported = pendingImport ?: return
        stagedImport = imported
        state["settings_import"] = imported.document
        updateDraft(imported.settings)
        pendingImport = null
        notice = R.string.settings_import_review
    }

    fun cancelImport() {
        pendingImport = null
    }

    fun previewContext(date: LocalDate) {
        if (busy || loadFailed) return
        if (hasChanges) {
            notice = R.string.settings_context_save_first
            return
        }
        preparingContext = true
        notice = null
        viewModelScope.launch {
            try {
                contextPreview = withContext(Dispatchers.IO) {
                    ExportPreview(store.readCoachingContext(date, defaultInstructions()).toJson().toString(2))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                notice = R.string.settings_context_failed
            } finally {
                preparingContext = false
            }
        }
    }

    fun dismissContext() {
        contextPreview = null
    }

    private fun defaultInstructions(): String = getApplication<Application>().assets.open("coaching-policy.md")
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    override fun onCleared() {
        store.close()
    }
}
