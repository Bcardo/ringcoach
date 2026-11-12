package com.bcardo.ringcoach

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/** Photos are attachments to one dated update, not a separate meal workflow. */
@Composable
internal fun ProgressAttachments(photos: PhotoMealViewModel, date: LocalDate, enabled: Boolean) {
    photos.attachmentDate = date
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photos.importPhoto(uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), photos::captureResult)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(enabled = enabled && !photos.working, onClick = { photos.capture(camera::launch) }) { Text("拍照") }
        OutlinedButton(enabled = enabled && !photos.working, onClick = {
            try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            catch (_: Exception) { photos.pickerFailed() }
        }) { Text("添加照片") }
    }
    photos.attachments.forEachIndexed { index, id ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("照片 ${index + 1}")
            TextButton(enabled = !photos.working, onClick = { photos.open(id) }) { Text("预览") }
            TextButton(enabled = enabled && !photos.working, onClick = { photos.removeAttachment(id) }) { Text("移除") }
        }
    }
    if (photos.selected?.id in photos.attachments) photos.preview?.let {
        Image(it, contentDescription = "本次更新附件", modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp))
    }
    photos.notice?.let { Text(stringResource(it)) }
}
