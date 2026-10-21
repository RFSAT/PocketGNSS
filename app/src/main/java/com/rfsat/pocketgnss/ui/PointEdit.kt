package com.rfsat.pocketgnss.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.data.SurveyPoint
import java.io.File

/** Code (with the codes used so far as chips), note and photo of a point. */
@Composable
fun PointAttributes(engine: GnssEngine, code: String, onCode: (String) -> Unit, note: String, onNote: (String) -> Unit,
                    photo: String, onPhoto: (String) -> Unit, photoPrefix: String) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending?.let { File(engine.photoDir, it) }
        if (ok && f != null && f.length() > 0) onPhoto(f.name) else f?.delete()
        pending = null
    }
    val noCamera = stringResource(R.string.point_no_camera)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextSetting(stringResource(R.string.point_code), code, onCode)
        val codes = remember { engine.recentCodes() }
        if (codes.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in codes) ChoiceChip(c == code, c, { onCode(c) })
        }
        TextSetting(stringResource(R.string.point_note), note, onNote)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val file = photo.takeIf { it.isNotBlank() }?.let { File(engine.photoDir, it) }
            val bmp = remember(photo) {
                file?.takeIf { it.isFile }?.let { f ->
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, o)
                    var ss = 1; while (o.outWidth / ss > 400 || o.outHeight / ss > 400) ss *= 2
                    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = ss })?.asImageBitmap()
                }
            }
            if (bmp != null && file != null) Image(bmp, null, Modifier.size(84.dp).clickable {
                try {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) {}
            }, contentScale = ContentScale.Crop)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionButton(onClick = {
                    val f = engine.newPhotoFile(photoPrefix)
                    pending = f.name
                    try { camera.launch(FileProvider.getUriForFile(context, context.packageName + ".files", f)) } catch (_: Exception) { pending = null; engine.toast(noCamera) }
                }, compact = true) { Text(stringResource(if (photo.isBlank()) R.string.point_photo else R.string.point_photo_again)) }
                if (photo.isNotBlank()) ActionButton(onClick = { onPhoto("") }, compact = true) { Text(stringResource(R.string.point_photo_remove)) }
            }
        }
        if (photo.isBlank()) Text(stringResource(R.string.point_photo_help), style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

/** Log > Saved points: edit a point's name, code, note and photo. */
@Composable
fun PointEditDialog(engine: GnssEngine, p: SurveyPoint, onDismiss: () -> Unit) {
    var name by remember(p) { mutableStateOf(p.name) }
    var code by remember(p) { mutableStateOf(p.code) }
    var note by remember(p) { mutableStateOf(p.note) }
    var photo by remember(p) { mutableStateOf(p.photo) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.point_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextSetting(stringResource(R.string.survey_name), name, { name = it })
                PointAttributes(engine, code, { code = it }, note, { note = it }, photo, { photo = it }, name)
            }
        },
        confirmButton = {
            ActionButton(onClick = {
                engine.updatePoint(p, p.copy(name = name.ifBlank { p.name }, code = code.trim(), note = note.trim(), photo = photo)); onDismiss()
            }, emphasis = true) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            ActionButton(onClick = {
                // A photo taken in this dialog but not saved is not kept.
                if (photo.isNotBlank() && photo != p.photo) File(engine.photoDir, photo).delete()
                onDismiss()
            }) { Text(stringResource(R.string.action_cancel)) }
        })
}
