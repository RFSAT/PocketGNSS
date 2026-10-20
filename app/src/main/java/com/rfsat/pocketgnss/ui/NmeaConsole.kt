package com.rfsat.pocketgnss.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sentence-type filters of the viewer; OTHER = everything not listed. */
private val FILTERS = listOf("ALL", "GGA", "RMC", "GSA", "GSV", "GST", "GNS", "VTG", "GLL", "TXT", "OTHER")

private fun typeOf(line: String): String = line.substringBefore(',').drop(1).let { if (it.startsWith("P")) "P" else it.drop(2) }

/**
 * The receiver's NMEA sentences shown live in the app, as an alternative to recording them to a file: filter by
 * sentence type, pause, copy to the clipboard or share what is shown as a .nmea file. Keeps the last 2000 lines.
 */
@Composable
fun NmeaConsole(engine: GnssEngine, rows: Int = 40) {
    val context = LocalContext.current
    val console by engine.console.collectAsState()
    var paused by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var more by rememberSaveable { mutableStateOf(false) }
    val all = if (paused) frozen else console
    val listed = FILTERS.drop(1).dropLast(1)
    val shown = when (filter) {
        "ALL" -> all
        "OTHER" -> all.filter { typeOf(it) !in listed }
        else -> all.filter { typeOf(it) == filter }
    }
    val txtCopied = stringResource(R.string.nmea_copied)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (f in FILTERS) ChoiceChip(filter == f, if (f == "ALL") stringResource(R.string.nmea_all) else if (f == "OTHER") stringResource(R.string.nmea_other) else f,
                { filter = f })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleButton(on = !paused, label = stringResource(if (paused) R.string.stream_paused else R.string.stream_live), onClick = {
                if (!paused) frozen = console; paused = !paused
            }, compact = true)
            ActionButton(onClick = { more = !more }, compact = true) { Text(stringResource(if (more) R.string.nmea_fewer else R.string.nmea_more)) }
            ActionButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NMEA", shown.joinToString("\n")))
                engine.toast(txtCopied)
            }, compact = true, enabled = shown.isNotEmpty()) { Text(stringResource(R.string.nmea_copy)) }
            ActionButton(onClick = { shareNmea(context, shown) }, compact = true, enabled = shown.isNotEmpty()) { Text(stringResource(R.string.nmea_share)) }
        }
        Text(stringResource(R.string.nmea_count, shown.size, all.size), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Newest at the bottom, like a terminal; long sentences scroll sideways instead of wrapping.
        Column(Modifier.fillMaxWidth().background(Color(0xFF0E1012), RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState()).padding(8.dp)) {
            if (shown.isEmpty()) Text(stringResource(R.string.nmea_empty), color = Color(0xFF9E9E9E), fontSize = 11.sp)
            for (l in shown.takeLast(if (more) rows * 5 else rows)) Text(l, color = colourOf(typeOf(l)), fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                maxLines = 1, softWrap = false)
        }
    }
}

/** Position sentences green, satellites blue, accuracy amber, the rest grey, as a quick visual cue. */
private fun colourOf(type: String) = when (type) {
    "GGA", "RMC", "GNS", "GLL", "VTG" -> Color(0xFFB9F6CA)
    "GSA", "GSV" -> Color(0xFF90CAF9)
    "GST" -> Color(0xFFFFE082)
    "TXT" -> Color(0xFFFFAB91)
    else -> Color(0xFFBDBDBD)
}

private fun shareNmea(context: Context, lines: List<String>) {
    try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "nmea_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".nmea")
        f.writeText(lines.joinToString("\r\n", postfix = "\r\n"))
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name))
    } catch (_: Exception) {}
}
