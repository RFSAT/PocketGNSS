package com.rfsat.pocketgnss.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.Connection
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.core.NtripStatus
import com.rfsat.pocketgnss.core.UbxResult
import com.rfsat.pocketgnss.protocol.Geo
import com.rfsat.pocketgnss.protocol.Mountpoint
import com.rfsat.pocketgnss.protocol.NmeaMsg
import com.rfsat.pocketgnss.protocol.Rtcm
import com.rfsat.pocketgnss.protocol.Ubx
import com.rfsat.pocketgnss.protocol.UbxLayer
import com.rfsat.pocketgnss.protocol.UbxPort
import kotlinx.coroutines.launch
import java.util.Locale

/** Presets: the factory set of the AgriBIT receiver (D3.3 §4.7.22) and AGENSO's minimum (D3.5 §4.3). */
private val PRESET_AGRIBIT = setOf(NmeaMsg.DTM, NmeaMsg.GGA, NmeaMsg.GLL, NmeaMsg.GNS, NmeaMsg.GSA, NmeaMsg.RMC)
private val PRESET_AGENSO = setOf(NmeaMsg.GGA, NmeaMsg.RMC)
private val PRESET_FULL = setOf(NmeaMsg.GGA, NmeaMsg.RMC, NmeaMsg.GSA, NmeaMsg.GSV, NmeaMsg.GST, NmeaMsg.GNS, NmeaMsg.VTG, NmeaMsg.DTM)

/**
 * Replaces the u-center procedure of D3.3 §4.7.22: choose the NMEA messages, the ports (I2C, UART1, USB, SPI as
 * in the guide) and the rate, send them with UBX CFG-VALSET and save them to BBR + Flash.
 */
@Composable
fun NmeaConfigTool(engine: GnssEngine) {
    val conn by engine.connection.collectAsState()
    val rx by engine.receiver.collectAsState()
    val scope = rememberCoroutineScope()
    var msgs by remember { mutableStateOf(PRESET_AGRIBIT + NmeaMsg.GST + NmeaMsg.GSV) }
    var ports by remember { mutableStateOf(setOf(UbxPort.I2C, UbxPort.UART1, UbxPort.USB, UbxPort.SPI)) }
    var rate by remember { mutableStateOf(1) }
    var permanent by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ready = conn is Connection.Ready
    val info = engine.linkInfo
    val txtAck = stringResource(R.string.ubx_ack); val txtNak = stringResource(R.string.ubx_nak); val txtTimeout = stringResource(R.string.ubx_timeout)
    val txtRead = stringResource(R.string.ubx_read_ok); val txtNoVer = stringResource(R.string.ubx_no_version)
    fun say(r: UbxResult) { result = when (r) { UbxResult.Ack, is UbxResult.Data -> txtAck; UbxResult.Nak -> txtNak; UbxResult.Timeout -> txtTimeout } }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.ubx_receiver)) {
            KeyValue(stringResource(R.string.home_module), rx.module?.let { it + (rx.firmware?.let { f -> " · $f" } ?: "") } ?: "—")
            KeyValue(stringResource(R.string.home_link), info?.detail ?: "—")
            Text(stringResource(when {
                info?.kind == com.rfsat.pocketgnss.link.LinkKind.PHONE -> R.string.ubx_phone
                info?.ubxDirect == true -> R.string.ubx_direct
                else -> R.string.ubx_via_esp32
            }), style = MaterialTheme.typography.bodySmall, color = if (info?.ubxDirect == true) SafeGreen else WarnAmber)
            ActionButton(onClick = { scope.launch { busy = true; result = if (engine.pollVersion()) txtAck else txtNoVer; busy = false } }, enabled = ready && !busy) {
                Text(stringResource(R.string.ubx_identify))
            }
        }
        Section(stringResource(R.string.ubx_messages)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChip(msgs == PRESET_AGRIBIT, stringResource(R.string.ubx_preset_agribit), { msgs = PRESET_AGRIBIT })
                ChoiceChip(msgs == PRESET_AGENSO, stringResource(R.string.ubx_preset_agenso), { msgs = PRESET_AGENSO })
                ChoiceChip(msgs == PRESET_FULL, stringResource(R.string.ubx_preset_full), { msgs = PRESET_FULL })
            }
            for (m in NmeaMsg.entries) SwitchRow(m.name, m in msgs, { on -> msgs = if (on) msgs + m else msgs - m }, detail = m.help)
            if (NmeaMsg.GGA !in msgs || NmeaMsg.RMC !in msgs) Text(stringResource(R.string.ubx_keep_gga_rmc), style = MaterialTheme.typography.bodySmall, color = WarnAmber)
        }
        Section(stringResource(R.string.ubx_ports_rate)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in UbxPort.entries) ChoiceChip(p in ports, p.label, { ports = if (p in ports) ports - p else ports + p })
            }
            Text(stringResource(R.string.ubx_ports_help), style = MaterialTheme.typography.bodySmall, color = muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (hz in listOf(1, 2, 5, 10, 20)) ChoiceChip(rate == hz, "$hz Hz", { rate = hz })
            }
            Text(stringResource(R.string.ubx_rate_help), style = MaterialTheme.typography.bodySmall, color = muted)
            SwitchRow(stringResource(R.string.ubx_permanent), permanent, { permanent = it }, stringResource(R.string.ubx_permanent_help))
        }
        Section(stringResource(R.string.ubx_apply_title)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = {
                    scope.launch {
                        busy = true; result = null
                        val r = engine.readNmeaConfig(if (UbxPort.USB in ports || ports.isEmpty()) UbxPort.USB else ports.first())
                        if (r != null) { msgs = r.first; rate = listOf(1, 2, 5, 10, 20).minByOrNull { kotlin.math.abs(it - r.second) } ?: 1; result = txtRead } else result = txtTimeout
                        busy = false
                    }
                }, enabled = ready && !busy) { Text(stringResource(R.string.ubx_read)) }
                ActionButton(onClick = {
                    scope.launch {
                        busy = true; result = null
                        val layers = if (permanent) UbxLayer.ALL else UbxLayer.RAM
                        val r = engine.ubxSet(Ubx.nmeaOutputs(msgs, ports) + Ubx.rateKeys(rate), layers)
                        say(r); busy = false
                    }
                }, enabled = ready && !busy && ports.isNotEmpty(), emphasis = true) { Text(stringResource(R.string.ubx_apply)) }
            }
            result?.let { Text(it, fontWeight = FontWeight.SemiBold, color = if (it == txtAck || it == txtRead) SafeGreen else WarnAmber) }
            Text(stringResource(R.string.ubx_apply_help), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

/** NTRIP client status, RTCM content, base station distance and the caster's mountpoint list. */
@Composable
fun CorrectionsTool(engine: GnssEngine) {
    val s by engine.store.settings.collectAsState()
    val n by engine.ntripClient.ntrip.collectAsState()
    val fix by engine.fix.collectAsState()
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Mountpoint>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.corr_status)) {
            Text(stringResource(R.string.corr_intro), style = MaterialTheme.typography.bodySmall, color = muted)
            KeyValue(stringResource(R.string.home_variant), stringResource(variantName(s.receiverVariant)))
            Text(stringResource(variantRoute(s.receiverVariant)), style = MaterialTheme.typography.bodySmall, color = muted)
            if (s.receiverVariant == com.rfsat.pocketgnss.link.ReceiverVariant.WIFI) HotspotButton()
            KeyValue(stringResource(R.string.corr_caster), if (s.ntrip.host.isBlank()) "—" else "${s.ntrip.host}:${s.ntrip.port}/${s.ntrip.mountpoint}")
            KeyValue(stringResource(R.string.corr_state), n.status.name.lowercase() + (if (n.message.isNotEmpty()) " · ${n.message}" else ""), live = true,
                valueColor = when (n.status) { NtripStatus.STREAMING -> SafeGreen; NtripStatus.ERROR -> AlarmRed; NtripStatus.OFF -> androidx.compose.ui.graphics.Color.Unspecified; else -> WarnAmber })
            KeyValue(stringResource(R.string.corr_data), stringResource(R.string.corr_data_value, n.bytes / 1024.0, n.bytesPerSec, n.frames, n.badFrames), live = true)
            if (n.lastDataMs > 0) KeyValue(stringResource(R.string.corr_last), "%.1f s".format(Locale.US, (System.currentTimeMillis() - n.lastDataMs) / 1000.0), live = true)
            if (n.lastGgaSentMs > 0) KeyValue(stringResource(R.string.corr_gga), "%.0f s".format(Locale.US, (System.currentTimeMillis() - n.lastGgaSentMs) / 1000.0), live = true)
            n.base?.let { b ->
                val g = b.geodetic
                val d = fix?.let { Geo.distance(it.latitude, it.longitude, g.latitude, g.longitude) }
                KeyValue(stringResource(R.string.corr_base), "#${b.stationId} · %.6f, %.6f · %.1f m".format(Locale.US, g.latitude, g.longitude, g.height))
                if (d != null) KeyValue(stringResource(R.string.corr_baseline), formatDistance(d), valueColor = if (d > 50_000) AlarmRed else if (d > 20_000) WarnAmber else SafeGreen)
                if (d != null && d > 50_000) Text(stringResource(R.string.corr_baseline_far), style = MaterialTheme.typography.bodySmall, color = AlarmRed)
            }
            ToggleButton(on = s.ntripEnabled, label = stringResource(if (s.ntripEnabled) R.string.live_ntrip_on else R.string.live_ntrip_off),
                onClick = { engine.setNtrip(!s.ntripEnabled) }, enabled = s.ntrip.usable || s.ntripEnabled)
        }
        if (n.types.isNotEmpty()) Section(stringResource(R.string.corr_messages)) {
            for ((t, c) in n.types.toSortedMap()) Row(Modifier.fillMaxWidth()) {
                Text("$t", Modifier.weight(0.18f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(Rtcm.describe(t), Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall)
                Text("$c", Modifier.weight(0.22f), style = MaterialTheme.typography.bodySmall)
            }
        }
        Section(stringResource(R.string.corr_mountpoints)) {
            Text(stringResource(R.string.corr_mount_help), style = MaterialTheme.typography.bodySmall, color = muted)
            ActionButton(onClick = {
                scope.launch {
                    busy = true; err = null
                    try { list = engine.ntripClient.sourcetable(s.ntrip.copy(mountpoint = "")) } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
                    busy = false
                }
            }, enabled = s.ntrip.host.isNotBlank() && !busy) { Text(stringResource(R.string.corr_fetch)) }
            err?.let { Text(it, color = AlarmRed, style = MaterialTheme.typography.bodySmall) }
            val here = fix
            val sorted = if (here != null) list.sortedBy { m -> if (m.latitude != null && m.longitude != null) Geo.distance(here.latitude, here.longitude, m.latitude, m.longitude) else 1e12 }
                else list
            for (m in sorted.take(60)) {
                val d = if (here != null && m.latitude != null && m.longitude != null) Geo.distance(here.latitude, here.longitude, m.latitude, m.longitude) else null
                Column(Modifier.fillMaxWidth().clickable { engine.store.update { it.copy(ntrip = it.ntrip.copy(mountpoint = m.name)) } }.padding(vertical = 6.dp)) {
                    Row {
                        Text(m.name, Modifier.weight(1f), fontWeight = if (m.name == s.ntrip.mountpoint) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (m.name == s.ntrip.mountpoint) SafeGreen else MaterialTheme.colorScheme.onSurface)
                        Text(d?.let { formatDistance(it) } ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(listOf(m.identifier, m.format, m.navSystems, m.country, if (m.needsGga) "GGA" else "", if (m.fee) "fee" else "")
                        .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                HorizontalDivider()
            }
        }
    }
}
