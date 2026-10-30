package com.rfsat.pocketgnss.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.data.ImportIssue
import java.io.File

/** Writes an export to the app's cache (shared through the FileProvider). */
fun export(context: Context, name: String, text: String): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    return File(dir, name).apply { writeText(text) }
}

fun exportBytes(context: Context, name: String, bytes: ByteArray): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    return File(dir, name).apply { writeBytes(bytes) }
}

/**
 * Share sheet for an exported file, with "Save to phone" (Downloads/PocketGNSS, [SaveToPhoneActivity]) as the first
 * target beside the other apps.
 */
fun share(context: Context, file: File, mime: String) {
    try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val save = Intent(context, SaveToPhoneActivity::class.java).setAction(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), file.name)
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(save)))
    } catch (_: Exception) {}
}

private fun displayName(context: Context, uri: Uri): String = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (_: Exception) { null } ?: uri.lastPathSegment ?: "file"

/** Opens the system file picker and imports points / boundaries from the chosen file; reports the result as a toast. */
@Composable
fun rememberImport(engine: GnssEngine): () -> Unit {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val msgGrid = stringResource(R.string.import_no_grid)
    val msgFormat = stringResource(R.string.import_format)
    val msgNothing = stringResource(R.string.import_nothing)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = try { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
        if (bytes == null || bytes.size > 50_000_000) { engine.toast(msgFormat); return@rememberLauncherForActivityResult }
        val r = engine.importFile(bytes, displayName(context, uri))
        val msgDone = res.getString(R.string.import_done, res.getQuantityString(R.plurals.import_points, r.points.size, r.points.size),
            res.getQuantityString(R.plurals.import_boundaries, r.fields.size, r.fields.size))
        engine.toast(when (r.issue) {
            ImportIssue.NO_GRID -> msgGrid
            ImportIssue.UNKNOWN_FORMAT -> msgFormat
            ImportIssue.NOTHING_FOUND -> msgNothing
            ImportIssue.SKIPPED -> msgDone + " " + res.getQuantityString(R.plurals.import_skipped, r.skipped, r.skipped)
            null -> msgDone
        })
    }
    return { launcher.launch(arrayOf("*/*")) }
}
