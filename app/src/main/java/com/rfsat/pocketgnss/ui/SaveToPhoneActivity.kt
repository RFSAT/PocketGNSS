package com.rfsat.pocketgnss.ui

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.rfsat.pocketgnss.R

/**
 * "Save to phone" in every share sheet of the app ([share]): copies the shared file to Downloads/PocketGNSS, where the
 * phone's Files app (and a PC over USB) finds it. Android 10+ needs no storage permission for that (MediaStore);
 * on Android 8-9 the system's save dialog asks where to put it.
 *
 * Exported so that the system share sheet can open it, but it only accepts files from this app's own FileProvider.
 */
class SaveToPhoneActivity : ComponentActivity() {
    private val legacySave = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        val src = source()
        if (target != null && src != null) report(copy(src, target), target.lastPathSegment ?: "")
        finish()
    }

    private fun source(): Uri? {
        @Suppress("DEPRECATION")
        val uri = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)) ?: return null
        return uri.takeIf { it.scheme == "content" && it.authority == "$packageName.files" }
    }

    private fun name(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null } ?: uri.lastPathSegment ?: "PocketGNSS"

    private fun copy(from: Uri, to: Uri): Boolean = try {
        contentResolver.openInputStream(from)!!.use { i -> contentResolver.openOutputStream(to)!!.use { o -> i.copyTo(o) } }
        true
    } catch (_: Exception) { false }

    private fun report(ok: Boolean, name: String) {
        Toast.makeText(this, if (ok) getString(R.string.save_done, name) else getString(R.string.save_failed), Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return   // the legacy save dialog is open: its result finishes the activity
        val src = source()
        if (src == null) { report(false, ""); finish(); return }
        val name = name(src)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, intent.type ?: "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PocketGNSS")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = try { contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) } catch (_: Exception) { null }
            val ok = target != null && copy(src, target)
            if (target != null) try {
                if (ok) contentResolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                else contentResolver.delete(target, null, null)
            } catch (_: Exception) {}
            report(ok, name)
            finish()
        } else legacySave.launch(name)
    }
}
