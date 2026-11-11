package com.bcardo.ringcoach

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val MAX_EXPORT_BYTES = 1024 * 1024

internal class ExportPreview(val json: String) {
    val chunks = json.lineSequence().chunked(24).map { it.joinToString("\n") }.toList()
}

internal class ExportViewModel : ViewModel() {
    var preview by mutableStateOf<ExportPreview?>(null)
        private set
    var notice by mutableStateOf<Int?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var choosingFile by mutableStateOf(false)
        private set

    fun prepare(probe: HealthConnectProbe, selected: Set<MetricKind>) {
        if (busy || choosingFile) return
        notice = null
        busy = true
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { probe.prepareExport(selected) }
                when {
                    json == null -> notice = R.string.export_no_records
                    json.toByteArray(Charsets.UTF_8).size > MAX_EXPORT_BYTES -> notice = R.string.export_too_large
                    else -> preview = withContext(Dispatchers.Default) { ExportPreview(json) }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                notice = R.string.export_preview_failed
            } finally {
                busy = false
            }
        }
    }

    fun dismissPreview() {
        if (!busy && !choosingFile) preview = null
    }

    fun clearNotice() {
        notice = null
    }

    fun chooseFile(launch: () -> Unit) {
        if (preview == null || busy || choosingFile) return
        choosingFile = true
        notice = null
        try {
            launch()
        } catch (_: Exception) {
            choosingFile = false
            notice = R.string.export_save_failed
        }
    }

    fun onDocumentResult(resolver: ContentResolver, uri: Uri?) {
        choosingFile = false
        if (uri == null) return
        val reviewed = preview
        if (reviewed == null) {
            notice = R.string.export_preview_expired
            return
        }
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    try {
                        val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("No document stream")
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(reviewed.json) }
                    } catch (exception: IOException) {
                        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                        throw exception
                    }
                }
                preview = null
                notice = R.string.export_saved
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                notice = R.string.export_save_failed
            } finally {
                busy = false
            }
        }
    }
}
