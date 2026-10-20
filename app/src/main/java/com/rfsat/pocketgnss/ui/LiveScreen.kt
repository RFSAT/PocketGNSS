package com.rfsat.pocketgnss.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.Connection
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.core.NtripStatus
import com.rfsat.pocketgnss.core.SatInfo
import com.rfsat.pocketgnss.data.HeightRef
import com.rfsat.pocketgnss.protocol.Constellation
import com.rfsat.pocketgnss.protocol.FixQuality
import com.rfsat.pocketgnss.protocol.Geo
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LiveScreen(engine: GnssEngine, onReconnect: () -> Unit) {
    val conn by engine.connection.collectAsState()
    if (conn !is Connection.Ready) { NotConnected(conn, onReconnect); return }
    val fix by engine.fix.collectAsState()
    val sats by engine.satellites.collectAsState()
    val s by engine.store.settings.collectAsState()
    val ntrip by engine.ntripClient.ntrip.collectAsState()
    val rec by engine.recording.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val f = fix
    val q = f?.quality ?: FixQuality.NONE
    val phoneChip = (conn as? Connection.Ready)?.info?.kind == com.rfsat.pocketgnss.link.LinkKind.PHONE

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        // Fix banner: the colour and the expected accuracy class, readable at arm's length on a tractor.
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(fixColor(q), RoundedCornerShape(14.dp)).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(fixLabel(q)), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.weight(1f))
                Text(if (f != null) formatAccuracy(f.horizontalAccuracy) else "—", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            }
            Row {
                Text(stringResource(fixHint(q)), color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (f != null) Text(stringResource(if (f.measuredAccuracy) R.string.acc_measured else R.string.acc_estimated),
                    color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val info = (conn as Connection.Ready).info
                Text(info.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(info.detail, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
            }
            ActionButton(onClick = { engine.disconnect() }, compact = true) { Text(stringResource(R.string.action_disconnect)) }
        }

        Section(stringResource(R.string.live_position)) {
            if (f == null) Text(stringResource(R.string.live_waiting), color = muted)
            else {
                KeyValue(stringResource(R.string.live_lat), formatLat(f.latitude, s.coordFormat), live = true)
                KeyValue(stringResource(R.string.live_lon), formatLon(f.longitude, s.coordFormat), live = true)
                KeyValue(stringResource(if (s.heightRef == HeightRef.MSL) R.string.live_height_msl else R.string.live_height_ell),
                    formatHeight(heightOf(f, s.heightRef)), live = true)
                KeyValue(stringResource(R.string.live_accuracy), stringResource(R.string.live_accuracy_value, formatAccuracy(f.horizontalAccuracy),
                    formatAccuracy(f.verticalAccuracy)), live = true)
                KeyValue(stringResource(R.string.live_sats), stringResource(R.string.live_sats_value, f.satellitesUsed ?: sats.count { it.used }, sats.size), live = true)
                KeyValue(stringResource(R.string.live_dop), "HDOP %s · VDOP %s · PDOP %s".format(Locale.US, d2(f.hdop), d2(f.vdop), d2(f.pdop)), live = true)
                KeyValue(stringResource(R.string.live_speed), (f.speedMs?.let { "%.2f km/h".format(Locale.US, it * 3.6) } ?: "—") +
                    (f.courseDeg?.let { " · %.1f°".format(Locale.US, it) } ?: ""), live = true)
                KeyValue(stringResource(R.string.live_time), (f.time?.toString() ?: "—") + " UTC", live = true)
            }
        }

        Section(stringResource(R.string.live_corrections)) {
            val ageText = f?.diffAgeSec?.let { stringResource(R.string.live_age_value, it, f.diffStation ?: "—") } ?: stringResource(R.string.live_age_none)
            KeyValue(stringResource(R.string.live_age), ageText, live = true)
            if (!phoneChip) KeyValue(stringResource(R.string.live_route), stringResource(variantName(s.receiverVariant)), live = true)
            if (!phoneChip && f != null && f.diffAgeSec == null && f.quality.rank < FixQuality.RTK_FLOAT.rank && !s.ntripEnabled)
                Text(stringResource(variantTroubleshoot(s.receiverVariant)), style = MaterialTheme.typography.bodySmall, color = WarnAmber)
            f?.modes?.takeIf { it.length >= 4 }?.let { m ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(Constellation.GPS, Constellation.GLONASS, Constellation.GALILEO, Constellation.BEIDOU, Constellation.QZSS, Constellation.NAVIC)
                        .forEachIndexed { i, c ->
                            val mq = FixQuality.fromMode(m.getOrNull(i))
                            Text("${c.short} ${m.getOrNull(i) ?: ' '}", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.background(fixColor(mq), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                }
            }
            val phone = s.ntripEnabled || ntrip.status != NtripStatus.OFF
            KeyValue(stringResource(R.string.live_ntrip), if (!phone) stringResource(R.string.live_ntrip_receiver) else
                stringResource(R.string.live_ntrip_value, ntrip.status.name.lowercase(), ntrip.mountpoint, ntrip.bytesPerSec), live = true)
            ntrip.base?.let { b ->
                val g = b.geodetic
                val dist = f?.let { Geo.distance(it.latitude, it.longitude, g.latitude, g.longitude) }
                KeyValue(stringResource(R.string.live_base), stringResource(R.string.live_base_value, b.stationId, dist?.let { formatDistance(it) } ?: "—"), live = true)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleButton(on = s.ntripEnabled, label = stringResource(if (s.ntripEnabled) R.string.live_ntrip_on else R.string.live_ntrip_off),
                    onClick = { engine.setNtrip(!s.ntripEnabled) }, enabled = phoneChip.not() && (s.ntrip.usable || s.ntripEnabled))
                ToggleButton(on = rec.active, label = stringResource(if (rec.active) R.string.log_recording_on else R.string.log_recording_off),
                    onClick = { if (rec.active) engine.stopRecording() else engine.startRecording() })
                ActionButton(onClick = { engine.markPoint(engine.nextPointName())?.let { engine.toast(it.name) } }, enabled = f != null) {
                    Text(stringResource(R.string.live_mark))
                }
            }
            if (phoneChip) Text(stringResource(R.string.live_phone_no_rtk), style = MaterialTheme.typography.bodySmall, color = muted)
            else if (!s.ntrip.usable && !s.ntripEnabled) Text(stringResource(R.string.live_ntrip_setup), style = MaterialTheme.typography.bodySmall, color = muted)
        }

        Section(stringResource(R.string.live_sky)) {
            if (sats.isEmpty()) Text(stringResource(R.string.live_no_gsv), style = MaterialTheme.typography.bodySmall, color = muted)
            else {
                SkyPlot(sats, Modifier.fillMaxWidth().aspectRatio(1f))
                Legend(sats)
                Cn0Bars(sats, Modifier.fillMaxWidth().height(150.dp))
            }
        }
    }
}

private fun d2(v: Double?) = v?.let { "%.2f".format(Locale.US, it) } ?: "—"

@Composable
private fun Legend(sats: List<SatInfo>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((c, list) in sats.groupBy { it.constellation }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 4.dp).background(constellationColor(c), RoundedCornerShape(50)).padding(5.dp))
                Text(stringResource(R.string.live_legend, stringResource(constellationName(c)), list.count { it.used }, list.size),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Polar plot: zenith in the centre, horizon on the outer ring; filled dots are used in the solution. */
@Composable
fun SkyPlot(sats: List<SatInfo>, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val text = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val r = size.minDimension / 2 * 0.9f
        val c = Offset(size.width / 2, size.height / 2)
        for (e in listOf(0, 30, 60)) drawCircle(grid, r * (90 - e) / 90f, c, style = Stroke(1.5f))
        drawLine(grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y)); drawLine(grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r))
        val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 11.sp.toPx(); color = text.toArgb(); textAlign = android.graphics.Paint.Align.CENTER }
        drawContext.canvas.nativeCanvas.apply {
            drawText("N", c.x, c.y - r - 4, paint); drawText("S", c.x, c.y + r + paint.textSize, paint)
            drawText("E", c.x + r + paint.textSize * 0.6f, c.y + paint.textSize / 3, paint); drawText("W", c.x - r - paint.textSize * 0.6f, c.y + paint.textSize / 3, paint)
        }
        for (s in sats) {
            val el = s.elevationDeg ?: continue; val az = s.azimuthDeg ?: continue
            val rr = r * (90 - el.coerceIn(0, 90)) / 90f
            val a = Math.toRadians(az.toDouble())
            val p = Offset(c.x + (rr * sin(a)).toFloat(), c.y - (rr * cos(a)).toFloat())
            val col = constellationColor(s.constellation)
            val dot = 7.dp.toPx()
            if (s.used) drawCircle(col, dot, p) else drawCircle(col, dot, p, style = Stroke(2.5f))
            if (s.dualFrequency) drawCircle(col, dot + 3.5f, p, style = Stroke(1.2f))
            drawContext.canvas.nativeCanvas.drawText(s.label, p.x, p.y - dot - 3, paint)
        }
    }
}

/** C/N0 per satellite (strongest signal), coloured by constellation; faded when not used. */
@Composable
fun Cn0Bars(sats: List<SatInfo>, modifier: Modifier) {
    val text = MaterialTheme.colorScheme.onSurfaceVariant
    val list = sats.filter { it.bestCn0 != null }
    Canvas(modifier) {
        if (list.isEmpty()) return@Canvas
        val label = 12.dp.toPx()
        val w = size.width / list.size
        val h = size.height - label
        val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 8.sp.toPx(); color = text.toArgb(); textAlign = android.graphics.Paint.Align.CENTER }
        for (y in listOf(20, 30, 40, 50)) {
            val yy = h - h * y / 55f
            drawLine(text.copy(alpha = 0.25f), Offset(0f, yy), Offset(size.width, yy))
        }
        list.forEachIndexed { i, s ->
            val v = (s.bestCn0 ?: 0).coerceIn(0, 55)
            val bh = h * v / 55f
            val col = constellationColor(s.constellation).let { if (s.used) it else it.copy(alpha = 0.35f) }
            drawRect(col, Offset(i * w + w * 0.12f, h - bh), androidx.compose.ui.geometry.Size(w * 0.76f, bh))
            drawContext.canvas.nativeCanvas.drawText(s.label, i * w + w / 2, size.height - 2, paint)
        }
    }
}
