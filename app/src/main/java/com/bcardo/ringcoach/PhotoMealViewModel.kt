package com.bcardo.ringcoach

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

internal class PhotoMealViewModel(application: Application, private val state: SavedStateHandle) :
    AndroidViewModel(application) {
    private val store = JournalStore(application)
    private val credentials = QwenCredentials(application)
    var attachmentDate: LocalDate = LocalDate.now()
    var attachments by mutableStateOf(state.get<ArrayList<String>>("progress_photos")?.toList() ?: emptyList())
        private set

    fun removeAttachment(id: String) = task {
        if (id !in attachments) return@task
        withContext(Dispatchers.IO) { PhotoMealStore(store.writableDatabase).remove(id) }
        attachments = attachments - id
        state["progress_photos"] = ArrayList(attachments)
        if (selected?.id == id) { selected = null; preview = null }
    }

    fun submitProgress(id: String, draft: JournalDraft, onSaved: (JournalEntry) -> Unit, onChanged: () -> Unit) = task {
        val text = listOf(draft.training, draft.diet, draft.additional).filter { it.isNotBlank() }.joinToString("\n")
        val entry = withContext(Dispatchers.IO) { store.appendProgress(id, draft.date, text, attachments) }
        attachments = emptyList()
        state.remove<ArrayList<String>>("progress_photos")
        selected = null; preview = null; notes = ""; consumed = false
        state.remove<String>("meal_id"); state.remove<String>("meal_notes"); state.remove<Boolean>("meal_consumed")
        onSaved(entry)
        refresh()
        onChanged()
        notice = R.string.progress_submitted
    }
    var meals by mutableStateOf(emptyList<PhotoMeal>())
        private set
    var selected by mutableStateOf<PhotoMeal?>(null)
        private set
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    var notes by mutableStateOf(state.get<String>("meal_notes") ?: "")
        private set
    var consumed by mutableStateOf(state.get<Boolean>("meal_consumed") ?: false)
        private set
    var working by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Int?>(null)
        private set
    val hasChanges: Boolean get() = selected?.let { it.notes != notes.trim() || it.consumed != consumed } == true

    init { state.get<String>("meal_id")?.let { open(it, restoring = true) } }

    fun pickerFailed() { notice = R.string.photo_import_failed }

    fun updateNotes(value: String) {
        if (!working && value.length <= 4000) { notes = value; state["meal_notes"] = value }
    }
    fun updateConsumed(value: Boolean) {
        if (!working) { consumed = value; state["meal_consumed"] = value }
    }

    fun reload() = task {
        refresh()
    }

    private suspend fun refresh() {
        meals = withContext(Dispatchers.IO) { PhotoMealStore(store.readableDatabase).list(LocalDate.now().minusDays(6), LocalDate.now()) }
    }

    private suspend fun saveDraft() {
        val meal = selected ?: return
        val updated = withContext(Dispatchers.IO) { PhotoMealStore(store.writableDatabase).edit(meal.id, notes, consumed) }
        selected = updated
        updated?.let { notes = it.notes; consumed = it.consumed }
        state["meal_notes"] = notes
        state["meal_consumed"] = consumed
    }

    private suspend fun select(id: String, restoring: Boolean = false) {
        val loaded = withContext(Dispatchers.IO) {
            val photos = PhotoMealStore(store.readableDatabase)
            val meal = photos.get(id) ?: error("Missing meal")
            meal to (photos.image(id) ?: error("Missing image"))
        }
        selected = loaded.first
        preview = if (loaded.second.isEmpty()) null else withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(loaded.second, 0, loaded.second.size).asImageBitmap()
        }
        state["meal_id"] = id
        if (!restoring) { notes = loaded.first.notes; consumed = loaded.first.consumed }
        state["meal_notes"] = notes
        state["meal_consumed"] = consumed
    }

    fun open(id: String, restoring: Boolean = false) = task {
        if (!restoring) saveDraft()
        select(id, restoring)
        refresh()
    }

    fun importPhoto(uri: Uri) = task {
        saveDraft()
        val id = withContext(Dispatchers.IO) {
            val jpeg = MealPhotos.decode(getApplication(), uri)
            PhotoMealStore(store.writableDatabase).create(attachmentDate, jpeg)
        }
        attachments = attachments + id
        state["progress_photos"] = ArrayList(attachments)
        select(id)
        refresh()
    }

    fun addTextMeal() = task {
        saveDraft()
        val id = withContext(Dispatchers.IO) { PhotoMealStore(store.writableDatabase).createText(LocalDate.now()) }
        select(id)
        refresh()
    }

    fun submit(draft: JournalDraft, onJournalSaved: (JournalEntry) -> Unit, onChanged: () -> Unit) = task {
        val entry = withContext(Dispatchers.IO) {
            store.saveDailySubmission(draft, selected?.takeIf { it.date == draft.date }?.id, notes, consumed)
        }
        onJournalSaved(entry)
        selected?.let { selected = withContext(Dispatchers.IO) { PhotoMealStore(store.readableDatabase).get(it.id) } }
        refresh(); onChanged()
        val jobs = withContext(Dispatchers.IO) { PhotoMealStore(store.readableDatabase).list(draft.date, draft.date)
            .filter { it.consumed && it.status in setOf("draft", "failed", "pending") } }
        val key = if (jobs.isNotEmpty()) withContext(Dispatchers.IO) { credentials.read() } else null
        if (jobs.isNotEmpty() && key == null) { notice = R.string.coach_key_needed; return@task }
        var failed = false
        for (meal in jobs) {
            if (!meal.hasPhoto && meal.notes.isBlank()) { failed = true; continue }
            try {
                withContext(Dispatchers.IO) {
                    val data = PhotoMealStore(store.writableDatabase)
                    data.markPending(meal)
                    val prompt = getApplication<Application>().assets.open("photo-meal.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val result = MealEstimate.decode(QwenClient.responseText(QwenClient.post(
                        PhotoMealClient.request(data.image(meal.id) ?: error("Missing input"), meal.notes, prompt), key!!)))
                    data.apply(meal, result)
                }
            } catch (exception: CancellationException) { throw exception }
            catch (_: Exception) { failed = true; withContext(Dispatchers.IO) { PhotoMealStore(store.writableDatabase).fail(meal) } }
        }
        selected?.let { selected = withContext(Dispatchers.IO) { PhotoMealStore(store.readableDatabase).get(it.id) } }
        refresh(); onChanged()
        notice = if (failed) R.string.photo_analysis_failed else R.string.daily_submission_saved
    }

    fun capture(launch: (Uri) -> Unit) {
        if (working) return
        try {
            val app = getApplication<Application>()
            state.get<String>("meal_camera")?.let { MealPhotos.checkedCapture(app, it).delete() }
            val file = MealPhotos.captureFile(app)
            state["meal_camera"] = file.path
            state["progress_camera_date"] = attachmentDate.toString()
            launch(MealPhotos.captureUri(app, file))
        } catch (_: Exception) { notice = R.string.photo_import_failed }
    }

    fun captureResult(success: Boolean) {
        val path = state.get<String>("meal_camera") ?: return
        task {
            val app = getApplication<Application>()
            val file = MealPhotos.checkedCapture(app, path)
            try {
                if (success) {
                    saveDraft()
                    val id = withContext(Dispatchers.IO) {
                        val jpeg = MealPhotos.decode(app, Uri.fromFile(file))
                        PhotoMealStore(store.writableDatabase).create(
                            state.get<String>("progress_camera_date")?.let(LocalDate::parse) ?: attachmentDate, jpeg)
                    }
                    attachments = attachments + id
                    state["progress_photos"] = ArrayList(attachments)
                    select(id)
                    refresh()
                }
            } finally { file.delete(); state.remove<String>("meal_camera") }
        }
    }

    fun save(onChanged: () -> Unit) = task {
        saveDraft(); refresh(); onChanged()
        notice = R.string.photo_saved
    }

    fun analyze(onChanged: () -> Unit) = task {
        saveDraft()
        val meal = selected ?: return@task
        onChanged()
        if (!meal.consumed) { notice = R.string.photo_confirm_consumed; return@task }
        val key = withContext(Dispatchers.IO) { credentials.read() }
        if (key == null) { notice = R.string.coach_key_needed; return@task }
        withContext(Dispatchers.IO) { PhotoMealStore(store.writableDatabase).markPending(meal) }
        selected = withContext(Dispatchers.IO) { PhotoMealStore(store.readableDatabase).get(meal.id) }
        try {
            val result = withContext(Dispatchers.IO) {
                val jpeg = PhotoMealStore(store.readableDatabase).image(meal.id) ?: error("Missing image")
                val prompt = getApplication<Application>().assets.open("photo-meal.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
                MealEstimate.decode(QwenClient.responseText(QwenClient.post(PhotoMealClient.request(jpeg, meal.notes, prompt), key)))
            }
            withContext(Dispatchers.IO) { PhotoMealStore(store.writableDatabase).apply(meal, result) }
            notice = R.string.photo_analysis_saved
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            withContext(Dispatchers.IO) { PhotoMealStore(store.writableDatabase).fail(meal) }
            notice = R.string.photo_analysis_failed
        } finally {
            selected = withContext(Dispatchers.IO) { PhotoMealStore(store.readableDatabase).get(meal.id) }
            refresh()
            onChanged()
        }
    }

    fun remove(onChanged: () -> Unit) = task {
        val meal = selected ?: return@task
        withContext(Dispatchers.IO) { PhotoMealStore(store.writableDatabase).remove(meal.id) }
        selected = null; preview = null; notes = ""; consumed = false
        state.remove<String>("meal_id"); state.remove<String>("meal_notes"); state.remove<Boolean>("meal_consumed")
        refresh(); onChanged()
    }

    private fun task(block: suspend () -> Unit) {
        if (working) return
        working = true
        notice = null
        viewModelScope.launch {
            try { block() } catch (exception: CancellationException) { throw exception }
            catch (_: Exception) { notice = R.string.photo_import_failed }
            finally { working = false }
        }
    }

    override fun onCleared() { store.close() }
}
