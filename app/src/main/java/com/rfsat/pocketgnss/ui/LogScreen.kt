package com.rfsat.pocketgnss.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.data.Exporters
import com.rfsat.pocketgnss.data.SurveyPoint
import com.rfsat.pocketgnss.protocol.FixQuality
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private val logQualities = listOf(FixQuality.NONE, FixQuality.SINGLE, FixQuality.DGNSS, FixQuality.RTK_FLOAT, FixQuality.RTK_FIXED)

@Composable
fun LogScreen(engine: GnssEngine) {
    val context = LocalContext.current
    val rec by engine.recording.collectAsState()
    val s by engine.store.settings.collectAsState()
    val points by engine.points.collectAsState()
    val importFile = rememberImport(engine)
    var version by remember { mutableIntStateOf(0) }
    val rawSt by engine.raw.state.collectAsState()
    val sessions = remember(version, rec.active, rawSt.recording) { engine.sessions() }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    var confirmPoint by remember { mutableStateOf<SurveyPoint?>(null) }
    var editPoint by remember { mutableStateOf<SurveyPoint?>(null) }
    var replaySpeed by remember { mutableStateOf(5.0) }
    editPoint?.let { p -> PointEditDialog(engine, p) { editPoint = null } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val any = stringResource(R.string.log_any_fix)
    val qualityLabels = logQualities.associateWith { if (it == FixQuality.NONE) any else stringResource(R.string.survey_at_least, stringResource(fixLabel(it))) }

    confirmDelete?.let { d ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text(stringResource(R.string.log_delete_title)) }, text = { Text(d.name) },
            confirmButton = { ActionButton(onClick = { d.deleteRecursively(); confirmDelete = null; version++ }, emphasis = true) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { ActionButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    confirmPoint?.let { p ->
        AlertDialog(onDismissRequest = { confirmPoint = null }, title = { Text(stringResource(R.string.log_delete_point)) }, text = { Text(p.name) },
            confirmButton = { ActionButton(onClick = { engine.deletePoint(p); confirmPoint = null }, emphasis = true) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { ActionButton(onClick = { confirmPoint = null }) { Text(stringResource(R.string.action_cancel)) } })
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.log_recording)) {
            ToggleButton(on = rec.active, label = stringResource(if (rec.active) R.string.log_recording_on else R.string.log_recording_off),
                onClick = { if (rec.active) engine.stopRecording() else engine.startRecording() })
            if (rec.active) KeyValue(rec.dir?.name ?: "", pluralStringResource(R.plurals.n_fixes, rec.fixes.toPlural(), rec.fixes) + " · " + pluralStringResource(R.plurals.n_nmea_lines, rec.lines.toPlural(), rec.lines), live = true)
            Picker(stringResource(R.string.log_min_quality), logQualities, s.logMinQuality,
                { qualityLabels.getValue(it) },
                { q -> engine.store.update { it.copy(logMinQuality = q) } })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (iv in listOf(0.0, 1.0, 5.0, 10.0, 60.0)) ChoiceChip(s.logIntervalSec == iv,
                    if (iv == 0.0) stringResource(R.string.log_every_epoch) else "%.0f s".format(Locale.US, iv), { engine.store.update { it.copy(logIntervalSec = iv) } })
            }
            Text(stringResource(R.string.log_help), style = MaterialTheme.typography.bodySmall, color = muted)
        }

        Section(stringResource(R.string.nmea_title)) {
            val conn by engine.connection.collectAsState()
            Text(stringResource(R.string.nmea_help), style = MaterialTheme.typography.bodySmall, color = muted)
            if (conn is com.rfsat.pocketgnss.core.Connection.Ready) NmeaConsole(engine, rows = 25)
            else Text(stringResource(R.string.tools_need_receiver), style = MaterialTheme.typography.bodySmall, color = muted)
        }

        Section(stringResource(R.string.log_points, points.size)) {
            ActionButton(onClick = importFile, compact = true) { Text(stringResource(R.string.import_button)) }
            Text(stringResource(R.string.import_help), style = MaterialTheme.typography.bodySmall, color = muted)
            if (points.isEmpty()) Text(stringResource(R.string.log_no_points), style = MaterialTheme.typography.bodySmall, color = muted)
            else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(onClick = { share(context, exportBytes(context, "points_shp.zip", Exporters.pointsShapefile(points, s.gridSystem, s.utmZone, heights = engine.nationalHeights())), "application/zip") },
                        compact = true) { Text("SHP") }
                    if (points.any { it.photo.isNotBlank() }) ActionButton(onClick = {
                        share(context, exportBytes(context, "points.kmz", Exporters.pointsKmz(points, engine.photoDir)), "application/vnd.google-earth.kmz")
                    }, compact = true) { Text("KMZ") }
                    ActionButton(onClick = { share(context, export(context, "points.kml", Exporters.pointsKml(points)), "application/vnd.google-earth.kml+xml") }, compact = true) { Text("KML") }
                    ActionButton(onClick = { share(context, export(context, "points.csv", Exporters.pointsCsv(points, s.gridSystem, s.utmZone, engine.nationalHeights())), "text/csv") }, compact = true) { Text("CSV") }
                    ActionButton(onClick = { share(context, export(context, "points.geojson", Exporters.pointsGeoJson(points, engine.nationalHeights())), "application/geo+json") }, compact = true) { Text("GeoJSON") }
                }
                for (p in points.reversed()) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name + (if (p.code.isNotBlank()) "  ·  " + p.code else "") + (if (p.photo.isNotBlank()) "  📷" else ""), fontWeight = FontWeight.SemiBold)
                            if (p.note.isNotBlank() && p.samples > 0) Text(p.note, style = MaterialTheme.typography.bodySmall)
                            Text(if (p.samples == 0) "%.8f, %.8f · %s".format(Locale.US, p.latitude, p.longitude, p.note)
                                else "%.8f, %.8f · %s · σH %s · n=%d".format(Locale.US, p.latitude, p.longitude, stringResource(fixLabel(p.quality)),
                                formatDistance(kotlin.math.sqrt(p.sdEast * p.sdEast + p.sdNorth * p.sdNorth)), p.samples),
                                style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ActionButton(onClick = { editPoint = p }, compact = true) { Text(stringResource(R.string.point_edit_short)) }
                            ActionButton(onClick = { confirmPoint = p }, compact = true) { Text(stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        }

        FieldExports(engine)

        Section(stringResource(R.string.log_sessions, sessions.size)) {
            val rp by com.rfsat.pocketgnss.link.Replay.flow.collectAsState()
            ChoiceRow(stringResource(R.string.log_replay_speed), com.rfsat.pocketgnss.link.Replay.speeds.map { v ->
                v to (if (v <= 0) stringResource(R.string.log_replay_max) else "%.0f×".format(Locale.US, v)) }, replaySpeed) { replaySpeed = it }
            if (rp.active) {
                Text(stringResource(R.string.log_replay_running, rp.name, rp.done, rp.total), color = SafeGreen, fontWeight = FontWeight.SemiBold)
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    ActionButton(onClick = { com.rfsat.pocketgnss.link.Replay.setPaused(!rp.paused) }, compact = true) {
                        Text(stringResource(if (rp.paused) R.string.log_replay_resume else R.string.log_replay_pause))
                    }
                    ActionButton(onClick = { com.rfsat.pocketgnss.link.Replay.setPaused(false); engine.disconnect() }, compact = true) { Text(stringResource(R.string.log_replay_stop)) }
                }
            }
            Text(stringResource(R.string.log_replay_help), style = MaterialTheme.typography.bodySmall, color = muted)
            if (sessions.isEmpty()) Text(stringResource(R.string.log_no_sessions), style = MaterialTheme.typography.bodySmall, color = muted)
            for (d in sessions) {
                if (d.name.startsWith("history_")) {
                    val csv = File(d, "positions.csv")
                    val live = engine.history.logging && engine.history.fileName == d.name
                    Text(stringResource(R.string.log_history_title, d.name.removePrefix("history_")) + if (live) "  ●" else "", fontWeight = FontWeight.SemiBold,
                        color = if (live) SafeGreen else MaterialTheme.colorScheme.onSurface)
                    Text("%.1f kB".format(Locale.US, csv.length() / 1024.0), style = MaterialTheme.typography.bodySmall, color = muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActionButton(onClick = { share(context, csv, "text/csv") }, compact = true, enabled = csv.exists()) { Text("CSV") }
                        ActionButton(onClick = { confirmDelete = d }, compact = true, enabled = !live) { Text(stringResource(R.string.action_delete)) }
                    }
                    HorizontalDivider()
                    continue
                }
                if (d.name.startsWith("raw_")) {
                    RawSessionRow(d, live = rawSt.recording && rawSt.dir == d, onDelete = { confirmDelete = d }, onReplay = { engine.replay(d, replaySpeed) })
                    HorizontalDivider()
                    continue
                }
                val raw = File(d, "raw.nmea"); val csv = File(d, "fixes.csv")
                val live = rec.active && rec.dir == d
                Text(d.name.removePrefix("session_") + if (live) "  ●" else "", fontWeight = FontWeight.SemiBold, color = if (live) SafeGreen else MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.log_sizes, raw.length() / 1024.0, csv.length() / 1024.0), style = MaterialTheme.typography.bodySmall, color = muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton(onClick = { share(context, raw, "text/plain") }, compact = true, enabled = raw.exists()) { Text("NMEA") }
                    ActionButton(onClick = { share(context, csv, "text/csv") }, compact = true, enabled = csv.exists()) { Text("CSV") }
                    ActionButton(onClick = { share(context, export(context, d.name + ".gpx", Exporters.trackGpx(engine.sessionTrack(d), d.name)), "application/gpx+xml") },
                        compact = true, enabled = csv.exists()) { Text("GPX") }
                    ActionButton(onClick = { share(context, export(context, d.name + ".kml", Exporters.trackKml(engine.sessionTrack(d), d.name)), "application/vnd.google-earth.kml+xml") },
                        compact = true, enabled = csv.exists()) { Text("KML") }
                    ActionButton(onClick = { engine.replay(d, replaySpeed) }, compact = true, enabled = !live && raw.length() > 0) { Text(stringResource(R.string.log_replay)) }
                    ActionButton(onClick = { confirmDelete = d }, compact = true, enabled = !live) { Text(stringResource(R.string.action_delete)) }
                }
                HorizontalDivider()
            }
        }
    }
}


/** A phone raw GNSS session: RINEX observations, GnssLogger text and the caster's RTCM, shared one by one or zipped. */
@Composable
private fun RawSessionRow(d: File, live: Boolean, onDelete: () -> Unit, onReplay: () -> Unit) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val scope = rememberCoroutineScope()
    val obs = File(d, "raw.obs"); val log = File(d, "gnss_log.txt"); val rtcm = File(d, "base.rtcm3")
    Text(stringResource(R.string.log_raw_title, d.name.removePrefix("raw_")) + if (live) "  ●" else "", fontWeight = FontWeight.SemiBold,
        color = if (live) SafeGreen else MaterialTheme.colorScheme.onSurface)
    Text(stringResource(R.string.log_raw_sizes, obs.length() / 1024.0, log.length() / 1024.0, rtcm.length() / 1024.0),
        style = MaterialTheme.typography.bodySmall, color = muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ActionButton(onClick = { share(context, copyAs(context, obs, d.name + ".obs"), "text/plain") }, compact = true, enabled = !live && obs.exists()) { Text("RINEX") }
        ActionButton(onClick = { share(context, copyAs(context, log, d.name + "_gnsslog.txt"), "text/plain") }, compact = true, enabled = !live && log.exists()) { Text("GnssLog") }
        ActionButton(onClick = { share(context, copyAs(context, rtcm, d.name + ".rtcm3"), "application/octet-stream") }, compact = true,
            enabled = !live && rtcm.length() > 0) { Text("RTCM") }
        ActionButton(onClick = {
            scope.launch {
                val z = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { zipDir(context, d) }
                share(context, z, "application/zip")
            }
        }, compact = true, enabled = !live) { Text("ZIP") }
        ActionButton(onClick = onReplay, compact = true, enabled = !live && (obs.length() > 0 || log.length() > 0) && com.rfsat.pocketgnss.link.PhoneRtkLink.engineAvailable) {
            Text(stringResource(R.string.log_replay))
        }
        ActionButton(onClick = onDelete, compact = true, enabled = !live) { Text(stringResource(R.string.action_delete)) }
    }
}

private fun copyAs(context: Context, src: File, name: String): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    return File(dir, name).also { src.copyTo(it, overwrite = true) }
}

private fun zipDir(context: Context, d: File): File {
    val out = File(File(context.cacheDir, "exports").apply { mkdirs() }, d.name + ".zip")
    java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { z ->
        for (f in d.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".part") }) {
            z.putNextEntry(java.util.zip.ZipEntry(d.name + "/" + f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
        }
    }
    return out
}
