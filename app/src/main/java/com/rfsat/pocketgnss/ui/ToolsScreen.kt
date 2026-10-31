package com.rfsat.pocketgnss.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.Connection
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.core.Reference
import com.rfsat.pocketgnss.data.SurveyPoint
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Geo
import com.rfsat.pocketgnss.protocol.StartType
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

enum class ToolId(@StringRes val title: Int, @StringRes val summary: Int, val icon: ImageVector) {
    SURVEY(R.string.tool_survey, R.string.tool_survey_sum, Icons.Filled.MyLocation),
    STAKEOUT(R.string.tool_stakeout, R.string.tool_stakeout_sum, Icons.Filled.Navigation),
    FIELD(R.string.tool_field, R.string.tool_field_sum, Icons.Filled.Agriculture),
    GUIDANCE(R.string.tool_guide, R.string.tool_guide_sum, Icons.Filled.Straighten),
    CORRECTIONS(R.string.tool_corrections, R.string.tool_corrections_sum, Icons.Filled.CellTower),
    HISTORY(R.string.tool_history, R.string.tool_history_sum, Icons.Filled.History),
    RAW(R.string.tool_raw, R.string.tool_raw_sum, Icons.Filled.SatelliteAlt),
    NMEA_CONFIG(R.string.tool_nmea, R.string.tool_nmea_sum, Icons.Filled.Tune),
    RECEIVER_WEB(R.string.tool_web, R.string.tool_web_sum, Icons.Filled.Language),
    TTFF(R.string.tool_ttff, R.string.tool_ttff_sum, Icons.Filled.Timer),
    STREAM(R.string.tool_stream, R.string.tool_stream_sum, Icons.Filled.Terminal),
    APP_LOG(R.string.tool_applog, R.string.tool_applog_sum, Icons.Filled.BugReport),
}

@Composable
fun ToolsTab(engine: GnssEngine, initialTool: ToolId? = null) {
    var openName by rememberSaveable { mutableStateOf(initialTool?.name) }
    val open = openName?.let { n -> ToolId.entries.firstOrNull { it.name == n } }
    if (open == null) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            Text(stringResource(R.string.tools_intro), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (t in ToolId.entries) {
                Row(Modifier.fillMaxWidth().clickable { openName = t.name }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(t.icon, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(stringResource(t.title), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(t.summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
        return
    }
    BackHandler { openName = null }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
            IconButton(onClick = { openName = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
            Column {
                Text(stringResource(open.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(open.summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (open) {
            ToolId.SURVEY -> SurveyTool(engine)
            ToolId.STAKEOUT -> StakeoutTool(engine)
            ToolId.FIELD -> FieldTool(engine)
            ToolId.GUIDANCE -> GuidanceTool(engine)
            ToolId.CORRECTIONS -> CorrectionsTool(engine)
            ToolId.RAW -> RawTool(engine)
            ToolId.HISTORY -> HistoryTool(engine)
            ToolId.NMEA_CONFIG -> NmeaConfigTool(engine)
            ToolId.RECEIVER_WEB -> ReceiverWebTool(engine)
            ToolId.TTFF -> TtffTool(engine)
            ToolId.STREAM -> StreamTool(engine)
            ToolId.APP_LOG -> AppLogTool(engine)
        }
    }
}

@Composable
private fun NeedsReceiver(engine: GnssEngine, content: @Composable () -> Unit) {
    val conn by engine.connection.collectAsState()
    if (conn is Connection.Ready) content()
    else Text(stringResource(R.string.tools_need_receiver), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ------------------------------------------------------------------------------------------------ survey

private val qualityChoices = listOf(FixQuality.RTK_FIXED, FixQuality.RTK_FLOAT, FixQuality.DGNSS, FixQuality.SINGLE)

@Composable
private fun SurveyTool(engine: GnssEngine) = NeedsReceiver(engine) {
    val sv by engine.survey.collectAsState()
    val fix by engine.fix.collectAsState()
    val points by engine.points.collectAsState()
    val s by engine.store.settings.collectAsState()
    var minQ by remember { mutableStateOf(sv.minQuality) }
    var name by remember { mutableStateOf(engine.nextPointName()) }
    var code by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var photo by rememberSaveable { mutableStateOf("") }
    var refLat by remember { mutableStateOf(sv.reference?.latitude?.toString() ?: "") }
    var refLon by remember { mutableStateOf(sv.reference?.longitude?.toString() ?: "") }
    var refH by remember { mutableStateOf(sv.reference?.height?.toString() ?: "") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sv.running) { while (sv.running) { now = System.currentTimeMillis(); delay(1000) } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.survey_accept)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (q in qualityChoices) ChoiceChip(minQ == q, stringResource(R.string.survey_at_least, stringResource(fixLabel(q))), { minQ = q }, enabled = !sv.running)
            }
            Text(stringResource(R.string.survey_accept_help), style = MaterialTheme.typography.bodySmall, color = muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleButton(on = sv.running, label = stringResource(if (sv.running) R.string.survey_running else R.string.survey_stopped), onClick = {
                    if (sv.running) engine.stopSurvey() else engine.startSurvey(minQ, parseRef(refLat, refLon, refH))
                })
                val f = fix
                if (f != null) Text(stringResource(R.string.survey_now, stringResource(fixLabel(f))), Modifier.align(Alignment.CenterVertically),
                    color = fixColor(f.quality), fontWeight = FontWeight.SemiBold)
            }
        }

        Section(stringResource(R.string.survey_results)) {
            val st = sv.stats
            KeyValue(stringResource(R.string.survey_samples), listOf(pluralStringResource(R.plurals.n_used, sv.samples, sv.samples), pluralStringResource(R.plurals.n_rejected, sv.rejected, sv.rejected),
                formatDuration(((if (sv.running) now else (sv.startedMs.takeIf { it > 0 } ?: now)) - (sv.startedMs.takeIf { it > 0 } ?: now)) / 1000.0)).joinToString(" · "), live = true)
            if (st == null) Text(stringResource(R.string.survey_need_two), style = MaterialTheme.typography.bodySmall, color = muted)
            else {
                KeyValue(stringResource(R.string.live_lat), formatLat(st.meanLatitude, s.coordFormat), live = true)
                KeyValue(stringResource(R.string.live_lon), formatLon(st.meanLongitude, s.coordFormat), live = true)
                GridRows(s, st.meanLatitude, st.meanLongitude, st.meanHeight)
                KeyValue(stringResource(R.string.survey_h), stringResource(R.string.survey_h_value, formatHeight(st.meanHeight), formatHeight(sv.meanMsl)), live = true)
                NationalHeightRow(engine, st.meanLatitude, st.meanLongitude, st.meanHeight)
                KeyValue(stringResource(R.string.survey_sd), "σE %s · σN %s · σU %s".format(formatDistance(st.sdEast), formatDistance(st.sdNorth), formatDistance(st.sdUp)), live = true)
                KeyValue(stringResource(R.string.survey_cep), "CEP50 %s · CEP95 %s · 2DRMS %s".format(formatDistance(st.cep50), formatDistance(st.cep95), formatDistance(st.drms2)), live = true)
                KeyValue(stringResource(R.string.survey_max), formatDistance(st.maxHorizontal), live = true)
                if (st.biasHorizontal != null) {
                    KeyValue(stringResource(R.string.survey_bias), "H %s · U %s".format(formatDistance(st.biasHorizontal), formatOffset(st.biasUp ?: 0.0)), live = true)
                    KeyValue(stringResource(R.string.survey_rms), "H %s · U %s".format(formatDistance(st.rmsHorizontal ?: 0.0), formatDistance(st.rmsUp ?: 0.0)), live = true)
                }
                // D3.5 static spec for the selected mode.
                val (specH, specV) = when (sv.minQuality) {
                    FixQuality.RTK_FIXED -> 0.0125 to 0.025
                    FixQuality.RTK_FLOAT, FixQuality.DGNSS -> 0.375 to 0.75
                    else -> 1.25 to 2.5
                }
                val h = st.rmsHorizontal ?: sqrt(st.sdEast * st.sdEast + st.sdNorth * st.sdNorth)
                val v = st.rmsUp ?: st.sdUp
                val pass = h <= specH && v <= specV
                Text(stringResource(R.string.survey_spec, formatDistance(specH), formatDistance(specV), stringResource(if (pass) R.string.survey_pass else R.string.survey_fail)),
                    color = if (pass) SafeGreen else AlarmRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Scatter(st.enu, st.cep50, st.cep95, Modifier.fillMaxWidth().aspectRatio(1f))
            }
        }

        Section(stringResource(R.string.survey_reference)) {
            Text(stringResource(R.string.survey_reference_help), style = MaterialTheme.typography.bodySmall, color = muted)
            TextSetting(stringResource(R.string.survey_ref_lat), refLat, { refLat = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
            TextSetting(stringResource(R.string.survey_ref_lon), refLon, { refLon = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
            TextSetting(stringResource(R.string.survey_ref_h), refH, { refH = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = { engine.setSurveyReference(parseRef(refLat, refLon, refH)) }) { Text(stringResource(R.string.survey_ref_apply)) }
                ActionButton(onClick = { refLat = ""; refLon = ""; refH = ""; engine.setSurveyReference(null) }) { Text(stringResource(R.string.survey_ref_clear)) }
            }
            if (points.isNotEmpty()) {
                Picker(stringResource(R.string.survey_ref_from_point), listOf<SurveyPoint?>(null) + points, null, { it?.name ?: "—" }, { p ->
                    if (p != null) { refLat = "%.10f".format(Locale.US, p.latitude); refLon = "%.10f".format(Locale.US, p.longitude); refH = if (p.heightEllipsoid.isNaN()) "" else "%.4f".format(Locale.US, p.heightEllipsoid)
                        engine.setSurveyReference(Reference(p.latitude, p.longitude, if (p.heightEllipsoid.isNaN()) 0.0 else p.heightEllipsoid)) }
                })
            }
        }

        Section(stringResource(R.string.survey_save)) {
            TextSetting(stringResource(R.string.survey_name), name, { name = it })
            PointAttributes(engine, code, { code = it }, note, { note = it }, photo, { photo = it }, name)
            ActionButton(onClick = {
                engine.saveSurveyPoint(name.ifBlank { engine.nextPointName() }, note.trim(), code.trim(), photo)?.let {
                    engine.toast(it.name); name = engine.nextPointName(); note = ""; photo = ""
                }
            }, enabled = sv.stats != null, emphasis = true) { Text(stringResource(R.string.survey_save_btn)) }
        }
    }
}

private fun parseRef(lat: String, lon: String, h: String): Reference? {
    val a = lat.replace(',', '.').toDoubleOrNull() ?: return null
    val b = lon.replace(',', '.').toDoubleOrNull() ?: return null
    return Reference(a, b, h.replace(',', '.').toDoubleOrNull() ?: 0.0)
}

/** East / north scatter (cm) with the CEP50 and CEP95 circles, as in the D3.5 Matlab plots. */
@Composable
private fun Scatter(enu: List<DoubleArray>, cep50: Double, cep95: Double, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    val dot = MaterialTheme.colorScheme.primary
    Canvas(modifier.padding(top = 6.dp)) {
        val maxR = (enu.maxOfOrNull { maxOf(abs(it[0]), abs(it[1])) } ?: 0.01).coerceAtLeast(cep95 * 1.15).coerceAtLeast(0.005)
        val half = size.minDimension / 2 * 0.88f
        val k = half / maxR.toFloat()
        val c = Offset(size.width / 2, size.height / 2)
        drawLine(grid.copy(alpha = 0.5f), Offset(c.x - half, c.y), Offset(c.x + half, c.y))
        drawLine(grid.copy(alpha = 0.5f), Offset(c.x, c.y - half), Offset(c.x, c.y + half))
        for (p in enu) drawCircle(dot.copy(alpha = 0.55f), 2.5.dp.toPx(), Offset(c.x + p[0].toFloat() * k, c.y - p[1].toFloat() * k))
        drawCircle(SafeGreen, cep50.toFloat() * k, c, style = Stroke(2.5f))
        drawCircle(WarnAmber, cep95.toFloat() * k, c, style = Stroke(2.5f))
        val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 11.sp.toPx(); color = grid.toArgb() }
        drawContext.canvas.nativeCanvas.apply {
            drawText("±" + formatDistance(maxR), c.x + half - paint.measureText("±" + formatDistance(maxR)), c.y - 6, paint)
            drawText("N", c.x + 6, c.y - half + paint.textSize, paint)
            drawText("CEP50", 4f, paint.textSize, paint.apply { color = SafeGreen.toArgb() })
            drawText("CEP95", 4f, paint.textSize * 2.2f, paint.apply { color = WarnAmber.toArgb() })
        }
    }
}

// ------------------------------------------------------------------------------------------------ stake-out

@Composable
private fun StakeoutTool(engine: GnssEngine) = NeedsReceiver(engine) {
    val points by engine.points.collectAsState()
    val fix by engine.fix.collectAsState()
    var targetKey by rememberSaveable { mutableStateOf<String?>(null) }
    val target = points.firstOrNull { "${it.name}@${it.timeMs}" == targetKey } ?: points.lastOrNull()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (points.isEmpty()) { Text(stringResource(R.string.stake_no_points), Modifier.padding(16.dp), color = muted); return@Column }
        Section(stringResource(R.string.stake_target)) {
            Picker(stringResource(R.string.stake_point), points, target ?: points.first(), { it.name }, { targetKey = "${it.name}@${it.timeMs}" })
        }
        val f = fix
        if (f == null || target == null) { Text(stringResource(R.string.top_no_fix), Modifier.padding(16.dp), color = muted); return@Column }
        val enu = Geo.enu(f.latitude, f.longitude, f.ellipsoidHeight ?: 0.0, target.latitude, target.longitude,
            if (target.heightEllipsoid.isNaN()) f.ellipsoidHeight ?: 0.0 else target.heightEllipsoid)
        // Imported points may carry only a height above sea level: compare it with the receiver's.
        val up: Double? = when {
            !target.heightEllipsoid.isNaN() && f.ellipsoidHeight != null -> enu[2]
            target.heightMsl != null && f.altitudeMsl != null -> target.heightMsl - f.altitudeMsl
            else -> null
        }
        val dist = sqrt(enu[0] * enu[0] + enu[1] * enu[1])
        val brg = Geo.bearing(f.latitude, f.longitude, target.latitude, target.longitude)
        val moving = (f.speedMs ?: 0.0) > 0.4 && f.courseDeg != null
        val arrow = if (moving) brg - f.courseDeg!! else brg
        val onTarget = dist <= maxOf(0.02, f.horizontalAccuracy * 2)
        Column(Modifier.fillMaxWidth().padding(12.dp).background(if (onTarget) OnGreen else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Navigation, null, Modifier.padding(8.dp).fillMaxWidth(0.4f).aspectRatio(1f).rotate(arrow.toFloat()),
                tint = if (onTarget) Color.White else MaterialTheme.colorScheme.primary)
            Text(formatDistance(dist), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = if (onTarget) Color.White else MaterialTheme.colorScheme.onSurface)
            Text(stringResource(if (moving) R.string.stake_heading_up else R.string.stake_north_up), color = if (onTarget) Color.White else muted, fontSize = 14.sp)
        }
        Section(stringResource(R.string.stake_offsets)) {
            KeyValue(stringResource(R.string.stake_east), formatOffset(enu[0]), live = true)
            KeyValue(stringResource(R.string.stake_north), formatOffset(enu[1]), live = true)
            KeyValue(stringResource(R.string.stake_up), up?.let { formatOffset(it) } ?: "—", live = true)
            KeyValue(stringResource(R.string.stake_bearing), "%.1f°".format(Locale.US, brg), live = true)
            KeyValue(stringResource(R.string.live_fix), stringResource(fixLabel(f)) + " " + formatAccuracy(f.horizontalAccuracy), live = true, valueColor = fixColor(f.quality))
        }
    }
}

// ------------------------------------------------------------------------------------------------ TTFF

@Composable
private fun TtffTool(engine: GnssEngine) = NeedsReceiver(engine) {
    val t by engine.ttff.collectAsState()
    val rx by engine.receiver.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(t.current) { while (t.current != null) { now = System.currentTimeMillis(); delay(200) } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ubx = engine.linkInfo?.ubxDirect == true || rx.ubxAnswered
    val cold = stringResource(R.string.ttff_cold); val warm = stringResource(R.string.ttff_warm); val hot = stringResource(R.string.ttff_hot)
    val manual = stringResource(R.string.ttff_manual)
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.ttff_start)) {
            Text(stringResource(if (ubx) R.string.ttff_help_ubx else R.string.ttff_help_manual), style = MaterialTheme.typography.bodySmall, color = muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(onClick = { engine.startTtff(StartType.COLD, cold) }, enabled = t.current == null) { Text(cold) }
                ActionButton(onClick = { engine.startTtff(StartType.WARM, warm) }, enabled = t.current == null) { Text(warm) }
                ActionButton(onClick = { engine.startTtff(StartType.HOT, hot) }, enabled = t.current == null) { Text(hot) }
                ActionButton(onClick = { engine.startTtff(null, manual) }, enabled = t.current == null) { Text(manual) }
                if (t.current != null) ActionButton(onClick = { engine.cancelTtff() }) { Text(stringResource(R.string.action_cancel)) }
            }
        }
        t.current?.let { r ->
            Section(stringResource(R.string.ttff_running, r.label)) {
                KeyValue(stringResource(R.string.ttff_elapsed), "%.1f s".format(Locale.US, (now - r.startMs) / 1000.0), live = true)
                TtffRows(r)
            }
        }
        Section(stringResource(R.string.ttff_history)) {
            if (t.history.isEmpty()) Text(stringResource(R.string.ttff_none), style = MaterialTheme.typography.bodySmall, color = muted)
            for (r in t.history) {
                Text(r.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                TtffRows(r)
                HorizontalDivider()
            }
            if (t.reacquisitions.isNotEmpty()) KeyValue(stringResource(R.string.ttff_reacq),
                t.reacquisitions.take(10).joinToString(", ") { "%.1f s".format(Locale.US, it) })
            Text(stringResource(R.string.ttff_spec), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun TtffRows(r: com.rfsat.pocketgnss.core.TtffRun) {
    fun v(t: Long?) = r.seconds(t)?.let { "%.1f s".format(Locale.US, it) } ?: "—"
    KeyValue(stringResource(R.string.ttff_first_fix), v(r.firstFixMs), live = true)
    KeyValue(stringResource(R.string.ttff_first_dgnss), v(r.firstDgnssMs), live = true)
    KeyValue(stringResource(R.string.ttff_first_float), v(r.firstFloatMs), live = true)
    KeyValue(stringResource(R.string.ttff_first_fixed), v(r.firstFixedMs), live = true)
}

// ------------------------------------------------------------------------------------------------ data stream

@Composable
private fun StreamTool(engine: GnssEngine) = NeedsReceiver(engine) {
    val st by engine.stream.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.stream_counts)) {
            KeyValue(stringResource(R.string.stream_bytes), pluralStringResource(R.plurals.stream_bytes_value, st.bytes.toPlural(), st.bytes, st.nmeaLines, st.ubxFrames), live = true)
            KeyValue(stringResource(R.string.stream_errors), pluralStringResource(R.plurals.n_bad_checksums, st.badChecksums.toPlural(), st.badChecksums) + " · " + pluralStringResource(R.plurals.n_other_bytes, st.garbageBytes.toPlural(), st.garbageBytes), live = true)
            st.baud?.let { KeyValue(stringResource(R.string.stream_baud), "$it bd", live = true) }
            Row(Modifier.fillMaxWidth()) {
                for (h in listOf(R.string.stream_type, R.string.stream_rate, R.string.stream_total, R.string.stream_gap))
                    Text(stringResource(h), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted)
            }
            for (r in st.rates) Row(Modifier.fillMaxWidth()) {
                Text(r.type, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text("%.1f /s".format(Locale.US, r.hz), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text("${r.total}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text("${r.maxGapMs} ms", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.stream_spec), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        Section(stringResource(R.string.stream_console)) { NmeaConsole(engine) }
    }
}
