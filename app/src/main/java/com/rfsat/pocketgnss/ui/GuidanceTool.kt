package com.rfsat.pocketgnss.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.AbLine
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.data.SurveyPoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private fun cm(m: Double) = if (abs(m) < 1) "%.0f cm".format(Locale.US, abs(m) * 100) else "%.2f m".format(Locale.US, abs(m))

/** Tools > A-B guidance: parallel passes at the implement width, with a lightbar. */
@Composable
fun GuidanceTool(engine: GnssEngine) {
    val st by engine.guidance.state.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (st.line == null) GuidanceSetup(engine) else GuidanceActive(engine)
    }
}

@Composable
private fun GuidanceActive(engine: GnssEngine) {
    val st by engine.guidance.state.collectAsState()
    val s by engine.store.settings.collectAsState()
    val fix by engine.fix.collectAsState()
    val line = st.line ?: return
    val sol = st.solution
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Lightbar
    Column(Modifier.fillMaxWidth().padding(12.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val n = 7
        // Segments light towards the side to steer to ("chase the lights"): steer = + means steer left.
        val lit = sol?.let { (-it.steer / s.guideStep).roundToInt().coerceIn(-n, n) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in -n..n) {
                val base = when { i == 0 -> SafeGreen; abs(i) <= 2 -> Color(0xFF9CCC65); abs(i) <= 4 -> WarnAmber; else -> AlarmRed }
                val on = lit != null && (if (lit == 0) i == 0 else (i * lit > 0 && abs(i) <= abs(lit)))
                Box(Modifier.weight(1f).height(if (i == 0) 34.dp else 26.dp).background(if (on) base else base.copy(alpha = 0.15f), RoundedCornerShape(4.dp)))
            }
        }
        if (sol == null || fix == null) Text(stringResource(R.string.top_no_fix), color = muted)
        else {
            val onLine = abs(sol.steer) < s.guideStep / 2
            Text(when {
                onLine -> stringResource(R.string.guide_on_line)
                sol.steer > 0 -> "◀  " + cm(sol.steer)
                else -> cm(sol.steer) + "  ▶"
            }, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = if (onLine) SafeGreen else if (abs(sol.steer) > 4 * s.guideStep) AlarmRed else MaterialTheme.colorScheme.onSurface)
            Text(stringResource(if (onLine) R.string.guide_hold else if (sol.steer > 0) R.string.guide_steer_left else R.string.guide_steer_right), color = muted)
        }
    }
    Section(line.name) {
        if (sol != null) {
            KeyValue(stringResource(R.string.guide_pass), when {
                line.closed -> com.rfsat.pocketgnss.core.Guidance.headlandPass(sol).let { h ->
                    if (h == 0) stringResource(R.string.guide_pass_outside) else stringResource(R.string.guide_pass_headland, h) }
                sol.pass == 0 -> stringResource(if (line.isCurve) R.string.guide_pass_curve else R.string.guide_pass_ab)
                sol.pass > 0 -> stringResource(R.string.guide_pass_right, sol.pass)
                else -> stringResource(R.string.guide_pass_left, -sol.pass)
            }, live = true)
            KeyValue(stringResource(R.string.guide_heading), sol.headingError?.let { "%+.1f°".format(Locale.US, it) } ?: "—", live = true)
            KeyValue(stringResource(R.string.guide_along), "%.1f m".format(Locale.US, sol.along), live = true)
        }
        CoverageRows(engine)
        fix?.let { KeyValue(stringResource(R.string.live_fix), stringResource(fixLabel(it)) + " " + formatAccuracy(it.horizontalAccuracy), live = true, valueColor = fixColor(it.quality)) }
        KeyValue(stringResource(R.string.guide_line), lineText(line), live = true)
        if (line.shift != 0.0) KeyValue(stringResource(R.string.guide_shift), "%+.2f m".format(Locale.US, line.shift), live = true)
        Text(stringResource(R.string.guide_shift_help), style = MaterialTheme.typography.bodySmall, color = muted)
        // Shift as the driver sees it (the line's own right is the driver's left when driving from B to A).
        val dir = if (sol?.forward != false) 1 else -1
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(onClick = { engine.guidance.setShift(line.shift - dir * 0.05) }, compact = true) { Text("◀ 5 cm") }
            ActionButton(onClick = { engine.guidance.setShift(line.shift + dir * 0.05) }, compact = true) { Text("5 cm ▶") }
            ActionButton(onClick = { engine.guidance.setShift(0.0) }, compact = true, enabled = line.shift != 0.0) { Text(stringResource(R.string.guide_shift_reset)) }
        }
        ActionButton(onClick = { engine.guidance.activate(null) }, emphasis = true) { Text(stringResource(R.string.guide_stop)) }
    }
    GuidanceSettings(engine)
}

/** Worked area, overlaps and field coverage, with recording on / off and clearing the coverage map. */
@Composable
private fun CoverageRows(engine: GnssEngine) {
    val st by engine.guidance.state.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var confirm by rememberSaveable { mutableStateOf(false) }
    SwitchRow(stringResource(R.string.guide_record), st.recording, { engine.guidance.setRecording(it) }, stringResource(R.string.guide_record_help))
    KeyValue(stringResource(R.string.guide_worked), formatArea(st.worked), live = true)
    KeyValue(stringResource(R.string.guide_overlap), formatArea(st.overlap) + if (st.worked > 0) " (%.1f %%)".format(Locale.US, 100 * st.overlap / st.worked) else "",
        live = true, valueColor = if (st.worked > 0 && st.overlap / st.worked > 0.05) InfoViolet else Color.Unspecified)
    val share = st.fieldShare
    if (st.field != null && share != null)
        KeyValue(st.field ?: "", stringResource(R.string.guide_field_cov, 100 * share, formatArea(st.gaps ?: 0.0)), live = true)
    else Text(stringResource(R.string.guide_no_field), style = MaterialTheme.typography.bodySmall, color = muted)
    val context = androidx.compose.ui.platform.LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ActionButton(onClick = {
            engine.guidance.coverageShapes()?.let { c ->
                share(context, export(context, "worked_area.geojson", com.rfsat.pocketgnss.data.Exporters.coverageGeoJson(c, st.worked, st.overlap, st.field)),
                    "application/geo+json")
            }
        }, compact = true, enabled = st.worked > 0) { Text(stringResource(R.string.guide_export_cov)) }
        ActionButton(onClick = { confirm = true }, compact = true, enabled = st.worked > 0) { Text(stringResource(R.string.guide_clear_cov)) }
    }
    if (confirm) androidx.compose.material3.AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.guide_clear_title)) }, text = { Text(stringResource(R.string.guide_clear_text)) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { engine.guidance.resetWorked(); confirm = false }) { Text(stringResource(R.string.guide_clear_cov)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun GuidanceSettings(engine: GnssEngine) {
    val s by engine.store.settings.collectAsState()
    Section(stringResource(R.string.guide_settings)) {
        ChoiceRow(stringResource(R.string.guide_step), listOf(0.02 to "2", 0.05 to "5", 0.1 to "10", 0.2 to "20"), s.guideStep) { v -> engine.store.update { it.copy(guideStep = v) } }
        ChoiceRow(stringResource(R.string.guide_warn), listOf(0.0 to stringResource(R.string.guide_warn_off), 0.1 to "10", 0.25 to "25", 0.5 to "50"), s.guideWarn) { v ->
            engine.store.update { it.copy(guideWarn = v) }
        }
    }
}

@Composable
private fun GuidanceSetup(engine: GnssEngine) {
    val s by engine.store.settings.collectAsState()
    val fix by engine.fix.collectAsState()
    val points by engine.points.collectAsState()
    val lines by engine.guidance.lines.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var aLat by rememberSaveable { mutableStateOf<Double?>(null) }; var aLon by rememberSaveable { mutableStateOf<Double?>(null) }
    var bLat by rememberSaveable { mutableStateOf<Double?>(null) }; var bLon by rememberSaveable { mutableStateOf<Double?>(null) }
    var bearing by rememberSaveable { mutableStateOf<Double?>(null) }
    var name by rememberSaveable { mutableStateOf("AB${lines.size + 1}") }
    val noFix = stringResource(R.string.field_no_fix)
    val pointLabel: (SurveyPoint?) -> String = { it?.name ?: "—" }

    Section(stringResource(R.string.guide_new)) {
        Text(stringResource(R.string.guide_help), style = MaterialTheme.typography.bodySmall, color = muted)
        TextSetting(stringResource(R.string.guide_name), name, { name = it })
        NumberField(stringResource(R.string.guide_width), s.guideWidth, { v -> engine.store.update { it.copy(guideWidth = v) } }, decimals = 2, min = 0.1, max = 100.0, suffix = "m")
        // A
        KeyValue("A", if (aLat != null) "%.8f, %.8f".format(Locale.US, aLat, aLon) else "—")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(onClick = { val f = fix; if (f == null) engine.toast(noFix) else { aLat = f.latitude; aLon = f.longitude } }, compact = true) { Text(stringResource(R.string.guide_set_a)) }
        }
        if (points.isNotEmpty()) Picker(stringResource(R.string.guide_a_point), listOf<SurveyPoint?>(null) + points, null, pointLabel, { p -> if (p != null) { aLat = p.latitude; aLon = p.longitude } })
        // B or a bearing
        KeyValue("B", if (bLat != null) "%.8f, %.8f".format(Locale.US, bLat, bLon) else bearing?.let { "%.1f°".format(Locale.US, it) } ?: "—")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(onClick = { val f = fix; if (f == null) engine.toast(noFix) else { bLat = f.latitude; bLon = f.longitude; bearing = null } }, compact = true) { Text(stringResource(R.string.guide_set_b)) }
        }
        if (points.isNotEmpty()) Picker(stringResource(R.string.guide_b_point), listOf<SurveyPoint?>(null) + points, null, pointLabel, { p -> if (p != null) { bLat = p.latitude; bLon = p.longitude; bearing = null } })
        NumberField(stringResource(R.string.guide_bearing), bearing ?: 0.0, { v -> bearing = v; bLat = null; bLon = null }, decimals = 1, min = 0.0, max = 360.0, suffix = "°")
        val la = aLat; val lo = aLon
        val tooShort = la != null && lo != null && bLat != null && com.rfsat.pocketgnss.protocol.Geo.distance(la, lo, bLat!!, bLon!!) < 5
        if (tooShort) Text(stringResource(R.string.guide_short), style = MaterialTheme.typography.bodySmall, color = AlarmRed)
        val ready = la != null && lo != null && (bLat != null || bearing != null) && !tooShort
        ActionButton(onClick = {
            if (la != null && lo != null) {
                val now = System.currentTimeMillis()
                val l = if (bLat != null) AbLine(name.ifBlank { "AB" }, la, lo, bLat!!, bLon!!, s.guideWidth, 0.0, now)
                    else AbLine.fromBearing(name.ifBlank { "AB" }, la, lo, bearing ?: 0.0, s.guideWidth, now)
                engine.guidance.add(l); engine.guidance.activate(l)
                aLat = null; aLon = null; bLat = null; bLon = null; bearing = null; name = "AB${lines.size + 2}"
            }
        }, emphasis = true, enabled = ready) { Text(stringResource(R.string.guide_start)) }
    }
    CurveSetup(engine, name) { name = "AB${lines.size + 2}" }
    HeadlandSetup(engine)
    Section(stringResource(R.string.guide_saved, lines.size)) {
        if (lines.isEmpty()) Text(stringResource(R.string.guide_none), style = MaterialTheme.typography.bodySmall, color = muted)
        for (l in lines.reversed()) {
            Text(l.name, fontWeight = FontWeight.SemiBold)
            Text(lineText(l), style = MaterialTheme.typography.bodySmall, color = muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(onClick = { engine.guidance.activate(l) }, compact = true, emphasis = true) {
                    Text(stringResource(R.string.guide_use))
                }
                ActionButton(onClick = { engine.guidance.remove(l) }, compact = true) { Text(stringResource(R.string.action_delete)) }
            }
            HorizontalDivider()
        }
    }
    GuidanceSettings(engine)
}

/** Kind, size and width of a guidance pattern. */
@Composable
private fun lineText(l: AbLine): String = when {
    l.closed -> stringResource(R.string.guide_headland_value, "%.0f m".format(Locale.US, l.length), l.width)
    l.isCurve -> stringResource(R.string.guide_curve_value, "%.0f m".format(Locale.US, l.length), l.width)
    else -> stringResource(R.string.guide_line_value, l.bearing, l.width)
}

/** A curve pattern from the path driven: record the first pass, then follow parallel passes. */
@Composable
private fun CurveSetup(engine: GnssEngine, name: String, onDone: () -> Unit) {
    val s by engine.store.settings.collectAsState()
    val fix by engine.fix.collectAsState()
    val rec by engine.guidance.curveRecording.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val noFix = stringResource(R.string.field_no_fix)
    val short = stringResource(R.string.guide_curve_short)
    Section(stringResource(R.string.guide_curve_title)) {
        Text(stringResource(R.string.guide_curve_help), style = MaterialTheme.typography.bodySmall, color = muted)
        if (!rec.active) ActionButton(onClick = { if (fix == null) engine.toast(noFix) else engine.guidance.startCurve() }, compact = true) {
            Text(stringResource(R.string.guide_curve_start))
        } else {
            Text(stringResource(R.string.guide_curve_recording, "%.1f m".format(Locale.US, rec.length)), color = SafeGreen,
                fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(onClick = {
                    val l = engine.guidance.finishCurve(name.ifBlank { "Curve" }, s.guideWidth, System.currentTimeMillis())
                    if (l == null) engine.toast(short) else onDone()
                }, compact = true, emphasis = true) { Text(stringResource(R.string.guide_curve_finish)) }
                ActionButton(onClick = { engine.guidance.cancelCurve() }, compact = true) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

/** Headland passes round a saved field boundary. */
@Composable
private fun HeadlandSetup(engine: GnssEngine) {
    val s by engine.store.settings.collectAsState()
    val fields by engine.fields.collectAsState()
    val fix by engine.fix.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val usable = fields.filter { it.vertices.size >= 3 }
    // the field under the vehicle first
    val here = fix?.let { f -> usable.firstOrNull { com.rfsat.pocketgnss.core.insideField(it, f.latitude, f.longitude) } }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val field = usable.firstOrNull { it.key == chosen } ?: here ?: usable.firstOrNull()
    val suffix = stringResource(R.string.guide_headland_suffix)
    Section(stringResource(R.string.guide_headland_title)) {
        Text(stringResource(R.string.guide_headland_help), style = MaterialTheme.typography.bodySmall, color = muted)
        if (field == null) Text(stringResource(R.string.guide_no_fields), style = MaterialTheme.typography.bodySmall, color = muted)
        else {
            Picker(stringResource(R.string.guide_headland_field), usable, field, { it.name }, { chosen = it.key }, Modifier.fillMaxWidth())
            ActionButton(onClick = {
                val l = AbLine.headland(field.name + " " + suffix, field.vertices.map { it.latitude to it.longitude }, s.guideWidth, System.currentTimeMillis())
                engine.guidance.add(l); engine.guidance.activate(l)
            }, emphasis = true) { Text(stringResource(R.string.guide_headland_start)) }
        }
    }
}
