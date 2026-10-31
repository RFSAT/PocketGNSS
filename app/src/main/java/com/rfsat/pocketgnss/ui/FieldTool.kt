package com.rfsat.pocketgnss.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.CaptureMode
import com.rfsat.pocketgnss.core.Connection
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.data.Exporters
import com.rfsat.pocketgnss.data.Field
import com.rfsat.pocketgnss.data.FieldGeometry
import com.rfsat.pocketgnss.data.Vertex
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Geo
import com.rfsat.pocketgnss.protocol.Grid
import java.util.Locale

fun formatArea(m2: Double): String = if (m2 >= 1000) "%.4f ha".format(Locale.US, m2 / 10_000) else "%.1f m²".format(Locale.US, m2)

private val fieldQualities = listOf(FixQuality.RTK_FIXED, FixQuality.RTK_FLOAT, FixQuality.DGNSS, FixQuality.SINGLE)

/** Tools > Field boundary: capture a boundary by walking / driving it or corner by corner; area, perimeter, list, exports. */
@Composable
fun FieldTool(engine: GnssEngine) {
    val cap by engine.fieldCapture.state.collectAsState()
    val conn by engine.connection.collectAsState()
    val fix by engine.fix.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var mode by rememberSaveable { mutableStateOf(CaptureMode.WALK) }
    var minQ by rememberSaveable { mutableStateOf(FixQuality.RTK_FIXED) }
    var spacing by rememberSaveable { mutableStateOf(1.0) }
    var avg by rememberSaveable { mutableStateOf(5) }
    var name by rememberSaveable { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(cap.running) { if (cap.running && name.isBlank()) name = engine.nextFieldName() }
    val noFix = stringResource(R.string.field_no_fix)
    val need3 = stringResource(R.string.field_need3)
    val saved = stringResource(R.string.field_saved)

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (!cap.running) {
            Section(stringResource(R.string.field_new)) {
                ChoiceRow(stringResource(R.string.field_mode), listOf(CaptureMode.WALK to stringResource(R.string.field_mode_walk),
                    CaptureMode.CORNERS to stringResource(R.string.field_mode_corners)), mode) { mode = it }
                Text(stringResource(if (mode == CaptureMode.WALK) R.string.field_help_walk else R.string.field_help_corners),
                    style = MaterialTheme.typography.bodySmall, color = muted)
                val qLabels = fieldQualities.associateWith { stringResource(fixLabel(it)) }
                Picker(stringResource(R.string.field_min_q), fieldQualities, minQ, { qLabels.getValue(it) }, { minQ = it })
                if (mode == CaptureMode.WALK) ChoiceRow(stringResource(R.string.field_spacing), listOf(0.5 to "0.5", 1.0 to "1", 2.0 to "2", 5.0 to "5"), spacing) { spacing = it }
                else ChoiceRow(stringResource(R.string.field_average), listOf(0 to stringResource(R.string.field_average_now), 5 to "5", 10 to "10", 30 to "30"), avg) { avg = it }
                if (conn is Connection.Ready) ActionButton(onClick = {
                    engine.fieldCapture.start(mode, minQ, spacing, avg); name = engine.nextFieldName()
                }, emphasis = true) { Text(stringResource(R.string.field_start)) }
                else Text(stringResource(R.string.tools_need_receiver), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        } else {
            val g = FieldGeometry.of(cap.vertices)
            Section(stringResource(R.string.field_capturing)) {
                KeyValue(stringResource(R.string.field_vertices), cap.vertices.size.toString(), live = true)
                KeyValue(stringResource(R.string.field_area), formatArea(g.area), live = true)
                KeyValue(stringResource(R.string.field_perimeter), formatDistance(g.perimeter), live = true)
                val f = fix
                val start = cap.vertices.firstOrNull()
                if (f != null && start != null) KeyValue(stringResource(R.string.field_to_start), formatDistance(Geo.distance(f.latitude, f.longitude, start.latitude, start.longitude)), live = true)
                if (f != null) KeyValue(stringResource(R.string.live_fix), stringResource(fixLabel(f)) + " " + formatAccuracy(f.horizontalAccuracy), live = true,
                    valueColor = if (f.quality.rank >= cap.minQuality.rank) fixColor(f.quality) else AlarmRed)
                if (cap.rejected > 0) Text(pluralStringResource(R.plurals.field_rejected, cap.rejected, cap.rejected, stringResource(fixLabel(cap.minQuality))),
                    style = MaterialTheme.typography.bodySmall, color = InfoViolet)
                cap.averaging?.let { Text(pluralStringResource(R.plurals.field_averaging, it, it), color = InfoViolet, fontWeight = FontWeight.SemiBold) }
                if (cap.paused) Text(stringResource(R.string.field_paused_note), color = InfoViolet)
                Preview(cap.vertices, f?.let { Vertex(it.latitude, it.longitude) }, Modifier.fillMaxWidth().aspectRatio(1.4f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton(onClick = { if (!engine.fieldCapture.addCorner(System.currentTimeMillis())) engine.toast(noFix) }, emphasis = true,
                        enabled = cap.averaging == null) {
                        Text(stringResource(if (cap.mode == CaptureMode.CORNERS) R.string.field_add_corner else R.string.field_add_vertex))
                    }
                    ActionButton(onClick = { engine.fieldCapture.undo() }, enabled = cap.vertices.isNotEmpty() || cap.averaging != null) { Text(stringResource(R.string.field_undo)) }
                    if (cap.mode == CaptureMode.WALK) ToggleButton(on = !cap.paused, label = stringResource(if (cap.paused) R.string.field_paused else R.string.field_recording),
                        onClick = { engine.fieldCapture.setPaused(!cap.paused) })
                }
                TextSetting(stringResource(R.string.field_name), name, { name = it })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(onClick = {
                        if (cap.vertices.size < 3) engine.toast(need3)
                        else engine.finishField(name.ifBlank { engine.nextFieldName() })?.let { engine.toast(saved.format(it.name, formatArea(it.geometry.area))) }
                    }, emphasis = true) { Text(stringResource(R.string.field_finish)) }
                    ActionButton(onClick = { engine.fieldCapture.cancel() }) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
        FieldList(engine)
        FieldExports(engine)
    }
}

/** Saved fields with area and perimeter; rename and delete. */
@Composable
private fun FieldList(engine: GnssEngine) {
    val fields by engine.fields.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var confirm by remember { mutableStateOf<Field?>(null) }
    var renaming by remember { mutableStateOf<Field?>(null) }
    confirm?.let { f ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(stringResource(R.string.field_delete_title)) }, text = { Text(f.name) },
            confirmButton = { ActionButton(onClick = { engine.deleteField(f); confirm = null }, emphasis = true) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { ActionButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    renaming?.let { f ->
        var n by remember(f.key) { mutableStateOf(f.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.field_rename)) },
            text = { TextSetting(stringResource(R.string.field_name), n, { n = it }) },
            confirmButton = { ActionButton(onClick = { if (n.isNotBlank()) engine.renameField(f, n.trim()); renaming = null }, emphasis = true) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { ActionButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    Section(stringResource(R.string.field_list, fields.size)) {
        if (fields.isEmpty()) Text(stringResource(R.string.field_none), style = MaterialTheme.typography.bodySmall, color = muted)
        for (f in fields.reversed()) {
            val g = f.geometry
            Text(f.name, fontWeight = FontWeight.SemiBold)
            Text(pluralStringResource(R.plurals.field_row, f.vertices.size, formatArea(g.area), formatDistance(g.perimeter), f.vertices.size,
                if (f.quality == FixQuality.NONE) f.note else stringResource(fixLabel(f.quality))), style = MaterialTheme.typography.bodySmall, color = muted)
            if (g.selfIntersecting) Text(stringResource(R.string.field_crosses), style = MaterialTheme.typography.bodySmall, color = AlarmRed)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(onClick = { renaming = f }, compact = true) { Text(stringResource(R.string.field_rename)) }
                ActionButton(onClick = { confirm = f }, compact = true) { Text(stringResource(R.string.action_delete)) }
            }
            HorizontalDivider()
        }
    }
}

/** Export of all fields (KML, GeoJSON, shapefile, vertex CSV) and import of boundaries / points. */
@Composable
fun FieldExports(engine: GnssEngine) {
    val context = LocalContext.current
    val fields by engine.fields.collectAsState()
    val s by engine.store.settings.collectAsState()
    val importFile = rememberImport(engine)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val points by engine.points.collectAsState()
    val lines by engine.guidance.lines.collectAsState()
    Section(stringResource(R.string.field_exports)) {
        if (fields.isNotEmpty() || lines.isNotEmpty()) ActionButton(onClick = {
            val xml = com.rfsat.pocketgnss.data.IsoXml.taskData(fields, lines, points, com.rfsat.pocketgnss.BuildConfig.VERSION_NAME)
            share(context, exportBytes(context, "TASKDATA.zip", com.rfsat.pocketgnss.data.IsoXml.zip(xml)), "application/zip")
        }, compact = true) { Text(stringResource(R.string.isoxml_button)) }
        if (fields.isNotEmpty() || lines.isNotEmpty()) Text(stringResource(R.string.isoxml_help), style = MaterialTheme.typography.bodySmall, color = muted)
        if (fields.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(onClick = { share(context, export(context, "fields.kml", Exporters.fieldsKml(fields)), "application/vnd.google-earth.kml+xml") }, compact = true) { Text("KML") }
            ActionButton(onClick = { share(context, export(context, "fields.geojson", Exporters.fieldsGeoJson(fields)), "application/geo+json") }, compact = true) { Text("GeoJSON") }
            ActionButton(onClick = { share(context, exportBytes(context, "fields_shp.zip", Exporters.fieldsShapefile(fields)), "application/zip") }, compact = true) { Text("SHP") }
            ActionButton(onClick = { share(context, export(context, "fields_vertices.csv", Exporters.fieldsCsv(fields, s.gridSystem, s.utmZone)), "text/csv") }, compact = true) { Text("CSV") }
        }
        ActionButton(onClick = importFile, compact = true) { Text(stringResource(R.string.import_button)) }
        Text(stringResource(R.string.import_help), style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

/** The boundary so far (closed with a dashed edge) and the current position, north up, to scale. */
@Composable
private fun Preview(v: List<Vertex>, here: Vertex?, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        drawRect(bg)
        val all = v + listOfNotNull(here)
        if (all.isEmpty()) return@Canvas
        val lon0 = all.sumOf { it.longitude } / all.size
        val xy = all.map { Grid.transverseMercator(it.latitude, it.longitude, lon0, 1.0, 0.0, 0.0) }
        val minX = xy.minOf { it.first }; val maxX = xy.maxOf { it.first }; val minY = xy.minOf { it.second }; val maxY = xy.maxOf { it.second }
        val span = maxOf(maxX - minX, maxY - minY, 5.0)
        val sc = minOf(size.width, size.height) * 0.85f / span.toFloat()
        val cx = ((minX + maxX) / 2).toFloat(); val cy = ((minY + maxY) / 2).toFloat()
        fun o(p: Pair<Double, Double>) = Offset(size.width / 2 + (p.first.toFloat() - cx) * sc, size.height / 2 - (p.second.toFloat() - cy) * sc)
        val pts = xy.take(v.size).map { o(it) }
        if (pts.size >= 2) {
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(path, outline, style = Stroke(width = 4f))
            drawLine(outline.copy(alpha = 0.5f), pts.last(), pts.first(), strokeWidth = 3f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
        }
        pts.forEach { drawCircle(outline, 5f, it) }
        if (here != null) drawCircle(Color(0xFF2E7D32), 10f, o(xy.last()))
    }
}
