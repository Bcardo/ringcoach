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
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal class PreparedCoaching(val date: LocalDate, val context: JSONObject) {
    val preview = ExportPreview(context.toString(2))
}

internal class CoachViewModel(application: Application) : AndroidViewModel(application) {
    private val store = JournalStore(application)
    private val credentials = QwenCredentials(application)

    var keyInput by mutableStateOf("")
        private set
    var hasKey by mutableStateOf(false)
        private set
    var keyNotice by mutableStateOf<Int?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false)
        private set
    var generating by mutableStateOf(false)
        private set
    var savingKey by mutableStateOf(false)
        private set
    var savingCard by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Int?>(null)
        private set
    var prepared by mutableStateOf<PreparedCoaching?>(null)
        private set
    var card by mutableStateOf<SavedCoachCard?>(null)
        private set
    var cardSaved by mutableStateOf(false)
        private set

    val busy: Boolean
        get() = loading || preparing || generating || savingKey || savingCard

    init {
        reload()
    }

    fun reload() {
        loading = true
        loadFailed = false
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { (credentials.read() != null) to store.latestCoachCard() }
                hasKey = loaded.first
                card = loaded.second
                cardSaved = loaded.second != null
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                loadFailed = true
            } finally {
                loading = false
            }
        }
    }

    fun updateKeyInput(value: String) {
        if (!busy) {
            keyInput = value
            keyNotice = null
        }
    }

    fun saveKey() {
        if (busy) return
        val reviewed = keyInput.trim()
        if (!reviewed.startsWith("sk-") || reviewed.any { it.isWhitespace() }) {
            keyNotice = R.string.coach_key_invalid
            return
        }
        savingKey = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { credentials.save(reviewed) }
                keyInput = ""
                hasKey = true
                keyNotice = R.string.coach_key_saved
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                keyNotice = R.string.coach_key_failed
            } finally {
                savingKey = false
            }
        }
    }

    fun clearKey() {
        if (busy) return
        savingKey = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { credentials.clear() }
                keyInput = ""
                hasKey = false
                keyNotice = R.string.coach_key_removed
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                keyNotice = R.string.coach_key_failed
            } finally {
                savingKey = false
            }
        }
    }

    fun prepare(date: LocalDate, probe: HealthConnectProbe?) {
        if (busy || loadFailed) return
        preparing = true
        notice = null
        viewModelScope.launch {
            try {
                val context = withContext(Dispatchers.IO) {
                    val json = store.readCoachingContext(date, asset("coaching-policy.md")).toJson()
                        .put("request_timezone", ZoneId.systemDefault().id)
                    if (probe == null) {
                        json.put("telemetry_status", "unavailable")
                    } else {
                        try {
                            json.put("telemetry", probe.coachingSnapshot()).put("telemetry_status", "checked")
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: Exception) {
                            json.put("telemetry_status", "error")
                        }
                    }
                    json
                }
                if (context.toString().toByteArray(Charsets.UTF_8).size > 128 * 1024) {
                    notice = R.string.coach_context_too_large
                } else {
                    prepared = PreparedCoaching(date, context)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                notice = R.string.coach_prepare_failed
            } finally {
                preparing = false
            }
        }
    }

    fun dismissPrepared() {
        if (!generating) prepared = null
    }

    fun generate() {
        if (busy) return
        val reviewed = prepared ?: return
        if (!hasKey) {
            notice = R.string.coach_key_needed
            return
        }
        prepared = null
        generating = true
        notice = null
        viewModelScope.launch {
            try {
                val advice = withContext(Dispatchers.IO) {
                    val key = credentials.read() ?: throw IOException("No saved credential")
                    QwenClient.generate(reviewed.context, asset("coach-system.txt"), key)
                }
                val result = SavedCoachCard(
                    UUID.randomUUID().toString(), reviewed.date, Instant.now(), QWEN_MODEL,
                    COACH_PROMPT_VERSION, reviewed.context.toString(), advice,
                )
                card = result
                cardSaved = false
                persistCard(result)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: QwenHttpException) {
                notice = when (exception.status) {
                    401, 403 -> R.string.coach_api_access_failed
                    429 -> R.string.coach_api_quota
                    else -> R.string.coach_api_failed
                }
            } catch (_: IOException) {
                notice = R.string.coach_network_failed
            } catch (_: Exception) {
                notice = R.string.coach_response_failed
            } finally {
                generating = false
            }
        }
    }

    fun retrySave() {
        if (busy) return
        val result = card ?: return
        if (cardSaved) return
        savingCard = true
        viewModelScope.launch {
            try {
                persistCard(result)
            } finally {
                savingCard = false
            }
        }
    }

    private suspend fun persistCard(result: SavedCoachCard) {
        try {
            withContext(Dispatchers.IO) { store.saveCoachCard(result) }
            cardSaved = true
            notice = R.string.coach_card_saved
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            notice = R.string.coach_card_save_failed
        }
    }

    private fun asset(name: String): String = getApplication<Application>().assets.open(name)
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    override fun onCleared() {
        store.close()
    }
}
