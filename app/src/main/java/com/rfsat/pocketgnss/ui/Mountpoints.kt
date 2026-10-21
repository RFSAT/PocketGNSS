package com.rfsat.pocketgnss.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.data.CasterProfiles
import com.rfsat.pocketgnss.protocol.Geo
import com.rfsat.pocketgnss.protocol.Mountpoint

/**
 * A caster's mountpoints: network / nearest-base streams first, then single stations nearest first; streams that are
 * not RTCM 3 are greyed out. [onPick] receives the chosen mountpoint.
 */
@Composable
fun MountpointList(list: List<Mountpoint>, lat: Double?, lon: Double?, selected: String, onPick: (Mountpoint) -> Unit, max: Int = 80) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val txtNetwork = stringResource(R.string.corr_mount_network)
    val txtNotRtcm3 = stringResource(R.string.corr_mount_not_rtcm3)
    for (m in CasterProfiles.sortMountpoints(list, lat, lon).take(max)) {
        val d = if (lat != null && lon != null && m.latitude != null && m.longitude != null) Geo.distance(lat, lon, m.latitude, m.longitude) else null
        val rtcm3 = CasterProfiles.isRtcm3(m.format)
        Column(Modifier.fillMaxWidth().clickable { onPick(m) }.padding(vertical = 6.dp)) {
            Row {
                Text(m.name, Modifier.weight(1f), fontWeight = if (m.name == selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = when { m.name == selected -> SafeGreen; !rtcm3 -> muted; else -> MaterialTheme.colorScheme.onSurface })
                // A network stream's coordinates are only nominal, so its distance means nothing.
                Text(if (m.needsGga) txtNetwork else d?.let { formatDistance(it) } ?: "", style = MaterialTheme.typography.bodySmall)
            }
            Text(listOf(CasterProfiles.mountNote(m.name) ?: m.identifier, m.format, m.navSystems, m.country,
                if (m.needsGga) "GGA" else "", if (m.fee) "fee" else "", if (!rtcm3) txtNotRtcm3 else "")
                .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        HorizontalDivider()
    }
}

/**
 * The usual NTRIP set-up step: query the caster's sourcetable, pick an endpoint, then connect. Downloads the
 * sourcetable when shown; picking stores the mountpoint and the GGA setting the sourcetable asks for, then calls
 * [onPicked] (for example to switch the relay on).
 */
@Composable
fun MountpointDialog(engine: GnssEngine, onDismiss: () -> Unit, onPicked: (Mountpoint) -> Unit = {}) {
    val s by engine.store.settings.collectAsState()
    val fix by engine.fix.collectAsState()
    var list by remember { mutableStateOf<List<Mountpoint>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.ntrip.host, s.ntrip.port, s.ntrip.tls) {
        list = null; err = null
        try { list = engine.ntripClient.sourcetable(s.ntrip.copy(mountpoint = "")) } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
    }
    val here = fix?.let { it.latitude to it.longitude } ?: engine.raw.location?.let { it.latitude to it.longitude }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mount_dialog_title, s.ntrip.host, s.ntrip.port)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                val l = list
                when {
                    err != null -> Text(stringResource(R.string.mount_dialog_error, err ?: ""), color = AlarmRed, style = MaterialTheme.typography.bodySmall)
                    l == null -> Text(stringResource(R.string.mount_dialog_loading), style = MaterialTheme.typography.bodySmall)
                    l.isEmpty() -> Text(stringResource(R.string.mount_dialog_empty), style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text(stringResource(R.string.mount_dialog_help, l.size), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MountpointList(l, here?.first, here?.second, s.ntrip.mountpoint, { m ->
                            engine.store.update { it.copy(ntrip = it.ntrip.copy(mountpoint = m.name, ggaIntervalSec = if (m.needsGga) 10 else 0)) }
                            onDismiss(); onPicked(m)
                        })
                    }
                }
            }
        },
        confirmButton = { ActionButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
