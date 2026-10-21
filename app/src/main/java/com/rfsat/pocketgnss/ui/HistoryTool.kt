package com.rfsat.pocketgnss.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.core.SolutionHistory
import com.rfsat.pocketgnss.core.SolutionRecord
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

private val AndroidPurple = Color(0xFF7E57C2)

/** Colour of a method: the fix-type colour, and purple for Android's own location (not computed by the app). */
fun methodColor(r: SolutionRecord): Color = if (r.method == com.rfsat.pocketgnss.link.PhoneRtkLink.METHOD_ANDROID) AndroidPurple else fixColor(r.quality)

/** "Nice" scale: 1, 2 or 5 x 10^n just above [v]. */
private fun niceScale(v: Double): Double {
    if (v <= 0) return 1.0
    val p = 10.0.pow(kotlin.math.floor(log10(v)))
    val m = v / p
    return p * when { m <= 1 -> 1.0; m <= 2 -> 2.0; m <= 5 -> 5.0; else -> 10.0 }
}

/**
 * Position history: every computed position with the method that produced it and the accuracy reported, plotted
 * around a reference point, with per-method statistics (reported accuracy against actual scatter), the latest
 * fixes with the phone RTK engine's internals, a continuous CSV log and an export.
 */
@Composable
fun HistoryTool(engine: GnssEngine) {
    val context = LocalContext.current
    val h = engine.history
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var version by remember { mutableLongStateOf(-1) }
    var tick by remember { mutableLongStateOf(0) }
    LaunchedEffect(Unit) { while (true) { if (h.version != version) version = h.version; tick++; delay(1000) } }
    val fix by engine.fix.collectAsState()
    val list = remember(version) { h.records() }
    val ref = remember(version, h.reference) { h.referencePoint(list) }
    var showN by remember { mutableStateOf(600) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.hist_title)) {
            Text(stringResource(R.string.hist_help), style = MaterialTheme.typography.bodySmall, color = muted)
            KeyValue(stringResource(R.string.hist_count), stringResource(R.string.hist_count_value, list.size,
                list.firstOrNull()?.let { ((System.currentTimeMillis() + tick * 0 - it.timeMs) / 60000.0) } ?: 0.0), live = true)
            KeyValue(stringResource(R.string.hist_reference), when {
                ref == null -> "—"
                h.reference != null -> stringResource(R.string.hist_ref_user)
                else -> stringResource(R.string.hist_ref_auto, list.maxByOrNull { it.quality.rank }?.method?.let { methodText(it) } ?: "")
            })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(onClick = {
                    fix?.let { f -> h.reference = Triple(f.latitude, f.longitude, f.ellipsoidHeight); version = -2 }
                }, compact = true, enabled = fix != null) { Text(stringResource(R.string.hist_ref_here)) }
                ActionButton(onClick = { h.reference = null; version = -3 }, compact = true, enabled = h.reference != null) { Text(stringResource(R.string.hist_ref_auto_btn)) }
                ActionButton(onClick = { h.clear() }, compact = true, enabled = list.isNotEmpty()) { Text(stringResource(R.string.hist_clear)) }
                ActionButton(onClick = { shareText(context, "positions_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv", h.csv()) },
                    compact = true, enabled = list.isNotEmpty()) { Text(stringResource(R.string.hist_export)) }
            }
            SwitchRow(stringResource(R.string.hist_log), h.logging, { on -> if (on) h.startFile() else h.stopFile(); version = -4 },
                if (h.logging) pluralStringResource(R.plurals.hist_log_on, h.fileRows.toPlural(), h.fileName ?: "", h.fileRows) else stringResource(R.string.hist_log_help))
        }

        if (ref != null && list.isNotEmpty()) {
            val shown = list.takeLast(showN)
            Section(stringResource(R.string.hist_scatter)) {
                ChoiceRow(stringResource(R.string.hist_show), listOf(600 to "10 min", 3600 to "1 h", 7200 to stringResource(R.string.hist_all)),
                    showN) { showN = it }
                Scatter(shown, ref)
                // Legend: methods present, in the order of the statistics.
                val stats = remember(version, showN, ref) { SolutionHistory.stats(shown, ref) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (s in stats) Row(verticalAlignment = Alignment.CenterVertically) {
                        val sample = shown.first { it.method == s.method }
                        Box(Modifier.size(10.dp).background(methodColor(sample), CircleShape))
                        Text(" " + methodText(s.method), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Section(stringResource(R.string.hist_stats)) {
                Text(stringResource(R.string.hist_stats_help), style = MaterialTheme.typography.bodySmall, color = muted)
                val stats = remember(version, showN, ref) { SolutionHistory.stats(shown, ref) }
                Row(Modifier.fillMaxWidth()) {
                    for ((w, t) in listOf(0.3f to R.string.hist_col_method, 0.12f to R.string.hist_col_n, 0.15f to R.string.hist_col_reported,
                        0.15f to R.string.hist_col_actual, 0.14f to R.string.hist_col_precision, 0.14f to R.string.hist_col_within))
                        Text(stringResource(t), Modifier.weight(w), style = MaterialTheme.typography.labelSmall, color = muted)
                }
                for (s in stats) Row(Modifier.fillMaxWidth()) {
                    val sample = shown.first { it.method == s.method }
                    val bad = s.within2Sigma < 0.8
                    Text(methodText(s.method), Modifier.weight(0.3f), style = MaterialTheme.typography.bodySmall, color = methodColor(sample), fontWeight = FontWeight.SemiBold)
                    Text("${s.count}", Modifier.weight(0.12f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(formatAccuracy(s.meanAccH), Modifier.weight(0.15f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(formatAccuracy(s.rmsDist), Modifier.weight(0.15f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(formatAccuracy(s.rmsOwn), Modifier.weight(0.14f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("%.0f%%".format(Locale.US, s.within2Sigma * 100), Modifier.weight(0.14f), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, color = if (bad) WarnAmber else SafeGreen)
                }
            }
            Section(stringResource(R.string.hist_latest)) {
                val time = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                Row(Modifier.fillMaxWidth()) {
                    for ((w, t) in listOf(0.2f to R.string.hist_col_time, 0.26f to R.string.hist_col_method, 0.24f to R.string.hist_col_en,
                        0.14f to R.string.hist_col_acc, 0.16f to R.string.hist_col_debug))
                        Text(stringResource(t), Modifier.weight(w), style = MaterialTheme.typography.labelSmall, color = muted)
                }
                for (r in list.takeLast(40).asReversed()) {
                    val o = SolutionHistory.enu(r, ref)
                    Row(Modifier.fillMaxWidth()) {
                        Text(time.format(Date(r.timeMs)), Modifier.weight(0.2f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text(methodText(r.method), Modifier.weight(0.26f), style = MaterialTheme.typography.bodySmall, color = methodColor(r), maxLines = 1)
                        Text("%+.2f %+.2f".format(Locale.US, o[0], o[1]), Modifier.weight(0.24f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text(formatAccuracy(r.accH), Modifier.weight(0.14f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text(listOfNotNull(r.satellites?.let { "n$it" }, r.phaseSignals?.let { "φ$it" }, r.ratio?.takeIf { it > 0 }?.let { "r%.1f".format(Locale.US, it) })
                            .joinToString(" "), Modifier.weight(0.16f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                }
                Text(stringResource(R.string.hist_latest_help), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

/** East / north offsets from the reference, coloured by method, with distance rings and a scale label. */
@Composable
private fun Scatter(list: List<SolutionRecord>, ref: Triple<Double, Double, Double?>) {
    val pts = list.map { r -> SolutionHistory.enu(r, ref).let { Triple(it[0], it[1], methodColor(r)) } }
    // Scale to the 95th percentile so that a few outliers do not squash the cloud; outliers are drawn at the edge.
    val d = pts.map { sqrt(it.first * it.first + it.second * it.second) }.sorted()
    val p95 = d.getOrElse((d.size * 0.95).toInt().coerceAtMost(d.size - 1)) { 0.0 }
    val ring = niceScale(max(p95, 0.01) / 2)
    val extent = ring * 2.2
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val px = size.minDimension / 2 / extent.toFloat()
        for (k in 1..2) drawCircle(onSurface.copy(alpha = 0.35f), radius = (ring * k * px).toFloat(), center = c, style = Stroke(1.5f))
        drawLine(onSurface.copy(alpha = 0.25f), Offset(0f, c.y), Offset(size.width, c.y))
        drawLine(onSurface.copy(alpha = 0.25f), Offset(c.x, 0f), Offset(c.x, size.height))
        for ((e, n, col) in pts) {
            var x = e; var y = n
            val r = sqrt(x * x + y * y)
            if (r > extent) { x *= extent / r; y *= extent / r }
            drawCircle(col.copy(alpha = 0.75f), radius = 6f, center = Offset(c.x + (x * px).toFloat(), c.y - (y * px).toFloat()))
        }
        drawContext.canvas.nativeCanvas.drawText("ring %s · N ↑".format(Locale.US, formatAccuracy(ring)), 8f, size.height - 10f,
            android.graphics.Paint().apply { color = onSurface.toArgb(); textSize = 30f; isAntiAlias = true })
    }
}

private fun shareText(context: Context, name: String, text: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, name).apply { writeText(text) }
    try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), name))
    } catch (_: Exception) {}
}
