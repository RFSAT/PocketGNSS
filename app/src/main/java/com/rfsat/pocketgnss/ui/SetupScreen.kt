package com.rfsat.pocketgnss.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.Connection
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.core.MockStatus
import com.rfsat.pocketgnss.data.CoordFormat
import com.rfsat.pocketgnss.data.HeightRef
import com.rfsat.pocketgnss.data.CasterProfile
import com.rfsat.pocketgnss.data.CasterProfiles
import com.rfsat.pocketgnss.link.BluetoothSppLink
import com.rfsat.pocketgnss.link.LinkKind
import com.rfsat.pocketgnss.sim.SimMotion

private val bauds = listOf(0, 9600, 38400, 57600, 115200, 230400, 460800)

@SuppressLint("MissingPermission")
@Composable
fun SetupScreen(engine: GnssEngine, onReconnect: () -> Unit, onNeedLocation: () -> Unit) {
    val context = LocalContext.current
    val s by engine.store.settings.collectAsState()
    val conn by engine.connection.collectAsState()
    val mock by engine.mock.status.collectAsState()
    val clients by engine.server.clientCount.collectAsState()
    var btRefresh by remember { mutableIntStateOf(0) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun upd(f: (com.rfsat.pocketgnss.data.AppSettings) -> com.rfsat.pocketgnss.data.AppSettings) = engine.store.update(f)
    val auto = stringResource(R.string.setup_baud_auto)

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.setup_source)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, l) in listOf(LinkKind.USB to R.string.src_usb, LinkKind.BLUETOOTH to R.string.src_bt, LinkKind.TCP to R.string.src_tcp, LinkKind.PHONE to R.string.src_phone,
                    LinkKind.SIMULATOR to R.string.src_sim))
                    ChoiceChip(s.source == k, stringResource(l), { if (s.source != k) { upd { it.copy(source = k) }; engine.disconnect() } })
            }
            Text(stringResource(sourceHelp(s)), style = MaterialTheme.typography.bodySmall, color = muted)
            when (s.source) {
                LinkKind.USB -> {
                    Picker(stringResource(R.string.setup_baud), bauds, s.usbBaud, { if (it == 0) auto else "$it bd" }, { b -> upd { it.copy(usbBaud = b) } })
                    Text(stringResource(R.string.setup_baud_help), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                LinkKind.BLUETOOTH -> {
                    val devices = remember(btRefresh) {
                        try { context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList() ?: emptyList() } catch (_: SecurityException) { null }
                    }
                    if (devices == null) Text(stringResource(R.string.setup_bt_allow), style = MaterialTheme.typography.bodySmall, color = WarnAmber)
                    else if (devices.isEmpty()) Text(stringResource(R.string.setup_bt_none), style = MaterialTheme.typography.bodySmall, color = muted)
                    else for (d in devices.sortedByDescending { BluetoothSppLink.looksLikeReceiver(it.name) }) {
                        val sel = d.address == s.btAddress
                        Row(Modifier.fillMaxWidth().clickable { upd { it.copy(btAddress = d.address, btName = d.name ?: d.address) } }.padding(vertical = 4.dp)) {
                            Text((if (sel) "● " else "○ ") + (d.name ?: d.address), Modifier.weight(1f), fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                color = if (sel) SafeGreen else MaterialTheme.colorScheme.onSurface)
                            if (BluetoothSppLink.looksLikeReceiver(d.name)) Text(stringResource(R.string.setup_bt_receiver), style = MaterialTheme.typography.bodySmall, color = SafeGreen)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(onClick = { try { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {} }, compact = true) {
                            Text(stringResource(R.string.setup_bt_pair))
                        }
                        ActionButton(onClick = { btRefresh++ }, compact = true) { Text(stringResource(R.string.setup_refresh)) }
                    }
                    Text(stringResource(R.string.setup_bt_help), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                LinkKind.TCP -> {
                    TextSetting(stringResource(R.string.setup_tcp_host), s.tcpHost, { v -> upd { it.copy(tcpHost = v) } }, keyboard = KeyboardType.Uri)
                    NumberField(stringResource(R.string.setup_tcp_port), s.tcpPort.toDouble(), { v -> upd { it.copy(tcpPort = v.toInt()) } }, Modifier.fillMaxWidth(), min = 1.0, max = 65535.0)
                }
                LinkKind.PHONE -> {
                    if (!engine.phoneHasGnss) NoGnssWarning()
                    else {
                        SwitchRow(stringResource(R.string.setup_phone_rtk), s.phoneRtk, { v -> upd { it.copy(phoneRtk = v) } },
                            stringResource(if (com.rfsat.pocketgnss.link.PhoneRtkLink.engineAvailable) R.string.setup_phone_rtk_help else R.string.setup_phone_rtk_missing),
                            enabled = com.rfsat.pocketgnss.link.PhoneRtkLink.engineAvailable)
                        if (s.phoneRtk) ChoiceRow(stringResource(R.string.setup_phone_rtk_mode), listOf(false to stringResource(R.string.setup_phone_rtk_moving),
                            true to stringResource(R.string.setup_phone_rtk_static)), s.phoneRtkStatic) { v -> upd { it.copy(phoneRtkStatic = v) } }
                        Text(stringResource(R.string.setup_phone_rtk_apply), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
                LinkKind.SIMULATOR -> {
                    ChoiceRow(stringResource(R.string.sim_motion), listOf(SimMotion.STATIC to stringResource(R.string.sim_static),
                        SimMotion.SWATHS to stringResource(R.string.sim_swaths)), s.simMotion) { v -> upd { it.copy(simMotion = v) } }
                    SwitchRow(stringResource(R.string.sim_corrections), s.simCorrections, { v -> upd { it.copy(simCorrections = v) } }, stringResource(R.string.sim_corrections_help))
                }
            }
            if (s.source == LinkKind.BLUETOOTH || s.source == LinkKind.TCP)
                SwitchRow(stringResource(R.string.setup_autoreconnect), s.autoReconnect, { v -> upd { it.copy(autoReconnect = v) } })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = onReconnect, emphasis = true) { Text(stringResource(if (conn is Connection.Ready) R.string.action_reconnect else R.string.action_connect)) }
                if (conn !is Connection.NoDevice) ActionButton(onClick = { engine.disconnect() }) { Text(stringResource(R.string.action_disconnect)) }
            }
        }

        val noProfile = stringResource(R.string.ntrip_profile_none)
        var profileName by remember(s.activeProfile) { mutableStateOf(s.activeProfile) }
        Section(stringResource(R.string.setup_ntrip)) {
            Text(stringResource(R.string.setup_ntrip_help), style = MaterialTheme.typography.bodySmall, color = muted)
            // Caster profiles: saved accounts first, then public starting points.
            val choices = s.profiles + CasterProfiles.presets.filter { p -> s.profiles.none { it.name == p.name } }
            Picker(stringResource(R.string.ntrip_profile), listOf<CasterProfile?>(null) + choices,
                choices.firstOrNull { it.name == s.activeProfile }, { it?.name ?: noProfile }, { p ->
                    if (p != null) upd { it.copy(ntrip = p.config, activeProfile = p.name) } else upd { it.copy(activeProfile = "") }
                })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextSetting(stringResource(R.string.ntrip_profile_name), profileName, { profileName = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(onClick = {
                    val n = profileName.trim()
                    if (n.isNotEmpty()) upd { st -> st.copy(profiles = st.profiles.filterNot { it.name == n } + CasterProfile(n, st.ntrip), activeProfile = n) }
                }, compact = true, enabled = profileName.isNotBlank() && s.ntrip.host.isNotBlank()) { Text(stringResource(R.string.ntrip_profile_save)) }
                ActionButton(onClick = { upd { st -> st.copy(profiles = st.profiles.filterNot { it.name == st.activeProfile }, activeProfile = "") } },
                    compact = true, enabled = s.profiles.any { it.name == s.activeProfile }) { Text(stringResource(R.string.ntrip_profile_delete)) }
            }
            ChoiceRow(stringResource(R.string.ntrip_kind), listOf(true to stringResource(R.string.ntrip_kind_vrs), false to stringResource(R.string.ntrip_kind_single)),
                s.ntrip.ggaIntervalSec > 0) { vrs -> upd { it.copy(ntrip = it.ntrip.copy(ggaIntervalSec = if (vrs) 10 else 0)) } }
            TextSetting(stringResource(R.string.ntrip_host), s.ntrip.host, { v -> upd { it.copy(ntrip = it.ntrip.copy(host = v)) } }, keyboard = KeyboardType.Uri)
            NumberField(stringResource(R.string.ntrip_port), s.ntrip.port.toDouble(), { v -> upd { it.copy(ntrip = it.ntrip.copy(port = v.toInt())) } }, Modifier.fillMaxWidth(), min = 1.0, max = 65535.0)
            // Usual NTRIP flow: query the caster's sourcetable, pick an endpoint, then connect (or type a known one).
            var choose by remember { mutableStateOf(false) }
            if (choose) MountpointDialog(engine, onDismiss = { choose = false })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextSetting(stringResource(R.string.ntrip_mount), s.ntrip.mountpoint, { v -> upd { it.copy(ntrip = it.ntrip.copy(mountpoint = v)) } }, Modifier.weight(1f))
                ActionButton(onClick = { choose = true }, compact = true, enabled = s.ntrip.host.isNotBlank(), emphasis = s.ntrip.mountpoint.isBlank()) {
                    Text(stringResource(R.string.ntrip_choose))
                }
            }
            TextSetting(stringResource(R.string.ntrip_user), s.ntrip.user, { v -> upd { it.copy(ntrip = it.ntrip.copy(user = v)) } })
            TextSetting(stringResource(R.string.ntrip_pass), s.ntrip.password, { v -> upd { it.copy(ntrip = it.ntrip.copy(password = v)) } }, password = true)
            SwitchRow(stringResource(R.string.ntrip_v2), s.ntrip.version2, { v -> upd { it.copy(ntrip = it.ntrip.copy(version2 = v)) } }, stringResource(R.string.ntrip_v2_help))
            SwitchRow(stringResource(R.string.ntrip_tls), s.ntrip.tls, { v -> upd { it.copy(ntrip = it.ntrip.copy(tls = v)) } })
            NumberField(stringResource(R.string.ntrip_gga), s.ntrip.ggaIntervalSec.toDouble(), { v -> upd { it.copy(ntrip = it.ntrip.copy(ggaIntervalSec = v.toInt())) } },
                Modifier.fillMaxWidth(), min = 0.0, max = 600.0, suffix = "s")
            Text(stringResource(R.string.ntrip_store_note), style = MaterialTheme.typography.bodySmall, color = muted)
        }

        Section(stringResource(R.string.setup_outputs)) {
            SwitchRow(stringResource(R.string.out_mock), s.mockLocation, { v -> upd { it.copy(mockLocation = v) }; if (v) onNeedLocation() },
                stringResource(if (s.source == LinkKind.PHONE) R.string.out_mock_phone else R.string.out_mock_help), enabled = s.source != LinkKind.PHONE)
            if (s.mockLocation) Text(stringResource(when (mock) {
                MockStatus.ACTIVE -> R.string.out_mock_active; MockStatus.NOT_ALLOWED -> R.string.out_mock_not_allowed
                MockStatus.ERROR -> R.string.out_mock_error; MockStatus.OFF -> R.string.out_mock_off
            }), style = MaterialTheme.typography.bodySmall, color = if (mock == MockStatus.ACTIVE) SafeGreen else AlarmRed)
            if (mock == MockStatus.NOT_ALLOWED) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = { try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Exception) {} }, compact = true) {
                    Text(stringResource(R.string.out_mock_open_dev))
                }
                ActionButton(onClick = { engine.mock.stop(); engine.mock.start() }, compact = true) { Text(stringResource(R.string.action_retry)) }
            }
            SwitchRow(stringResource(R.string.out_server), s.nmeaServer, { v -> upd { it.copy(nmeaServer = v) } },
                stringResource(R.string.out_server_help, if (s.nmeaServerLocalOnly) "127.0.0.1" else "0.0.0.0", s.nmeaServerPort, clients))
            NumberField(stringResource(R.string.out_server_port), s.nmeaServerPort.toDouble(), { v -> upd { it.copy(nmeaServerPort = v.toInt(), nmeaServer = false) } },
                Modifier.fillMaxWidth(), min = 1024.0, max = 65535.0)
            SwitchRow(stringResource(R.string.out_server_local), s.nmeaServerLocalOnly, { v -> upd { it.copy(nmeaServerLocalOnly = v, nmeaServer = false) } },
                stringResource(R.string.out_server_local_help))
        }

        Section(stringResource(R.string.setup_receiver)) {
            Text(stringResource(R.string.setup_variant), fontWeight = FontWeight.SemiBold)
            VariantPicker(engine)
            Text(stringResource(R.string.setup_receiver_help), style = MaterialTheme.typography.bodySmall, color = muted)
            TextSetting(stringResource(R.string.setup_receiver_id), s.receiverId, { v -> upd { it.copy(receiverId = v) } })
            TextSetting(stringResource(R.string.setup_device_number), s.deviceNumber, { v -> upd { it.copy(deviceNumber = v) } }, keyboard = KeyboardType.Number)
        }

        Section(stringResource(R.string.setup_display)) {
            ChoiceRow(stringResource(R.string.disp_coords), listOf(CoordFormat.DECIMAL to stringResource(R.string.disp_decimal),
                CoordFormat.DMS to stringResource(R.string.disp_dms)), s.coordFormat) { v -> upd { it.copy(coordFormat = v) } }
            ChoiceRow(stringResource(R.string.disp_height), listOf(HeightRef.MSL to stringResource(R.string.disp_msl),
                HeightRef.ELLIPSOID to stringResource(R.string.disp_ellipsoid)), s.heightRef) { v -> upd { it.copy(heightRef = v) } }
            SwitchRow(stringResource(R.string.disp_screen_on), s.keepScreenOn, { v -> upd { it.copy(keepScreenOn = v) } })
        }
    }
}
