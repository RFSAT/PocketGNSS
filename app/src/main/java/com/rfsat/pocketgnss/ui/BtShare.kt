package com.rfsat.pocketgnss.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.BtServerState
import com.rfsat.pocketgnss.core.BtServerStatus
import com.rfsat.pocketgnss.core.Connection
import com.rfsat.pocketgnss.core.GnssEngine

/** Switches Bluetooth sharing on: permission, then Bluetooth on, then the server; marks the feature as configured. */
class BtStarter(val start: () -> Unit)

@Composable
fun rememberBtStarter(engine: GnssEngine): BtStarter {
    val context = LocalContext.current
    fun turnOn() = engine.store.update { it.copy(btServer = true, btServerConfigured = true) }.also { engine.restartBtServer() }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { turnOn() }
    fun withBluetoothOn() {
        val adapter = (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        val on = try { adapter?.isEnabled == true } catch (_: SecurityException) { false }
        if (adapter != null && !on) {
            try { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) { turnOn() }
        } else turnOn()
    }
    val needPermission = stringResource(R.string.bt_share_need_permission)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) withBluetoothOn() else engine.toast(needPermission)
    }
    return remember {
        BtStarter {
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            else withBluetoothOn()
        }
    }
}

@Composable
private fun statusText(st: BtServerState, sourceReady: Boolean): Pair<String, Color> = when (st.status) {
    BtServerStatus.OFF -> stringResource(R.string.bt_share_off) to Color.Unspecified
    BtServerStatus.LISTENING -> when {
        st.clients.isNotEmpty() -> stringResource(R.string.bt_share_connected, st.clients.joinToString(", ")) to (if (sourceReady) SafeGreen else WarnAmber)
        else -> stringResource(R.string.bt_share_waiting, st.phoneName ?: "") to WarnAmber
    }
    BtServerStatus.NO_BLUETOOTH -> stringResource(R.string.bt_share_no_bt) to AlarmRed
    BtServerStatus.BLUETOOTH_OFF -> stringResource(R.string.bt_share_bt_off) to AlarmRed
    BtServerStatus.NO_PERMISSION -> stringResource(R.string.bt_share_need_permission) to AlarmRed
    BtServerStatus.ERROR -> stringResource(R.string.bt_share_error, st.message ?: "") to AlarmRed
}

/**
 * Home: "Phone as GNSS receiver". Configured: one button switches Bluetooth sharing on and off. Not yet configured:
 * the button opens its set-up in Setup.
 */
@Composable
fun BtShareCard(engine: GnssEngine, onConfigure: () -> Unit) {
    val s by engine.store.settings.collectAsState()
    val st by engine.btServer.state.collectAsState()
    val conn by engine.connection.collectAsState()
    val fix by engine.fix.collectAsState()
    val starter = rememberBtStarter(engine)
    val on = s.btServer && st.status == BtServerStatus.LISTENING
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bluetooth, null, tint = if (on) SafeGreen else MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.bt_share_title), Modifier.padding(start = 8.dp).weight(1f), fontWeight = FontWeight.SemiBold)
        }
        val (text, color) = statusText(st, conn is Connection.Ready)
        if (s.btServer || st.status != BtServerStatus.OFF) Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        else Text(stringResource(if (s.btServerConfigured) R.string.bt_share_home_ready else R.string.bt_share_home_help), style = MaterialTheme.typography.bodySmall, color = muted)
        if (on && conn !is Connection.Ready) Text(stringResource(R.string.bt_share_no_source), style = MaterialTheme.typography.bodySmall, color = WarnAmber)
        else if (on && fix == null) Text(stringResource(R.string.bt_share_no_fix), style = MaterialTheme.typography.bodySmall, color = WarnAmber)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.btServerConfigured) {
                ToggleButton(on = s.btServer, label = stringResource(if (s.btServer) R.string.bt_share_on else R.string.bt_share_off_btn),
                    onClick = { if (s.btServer) engine.store.update { it.copy(btServer = false) } else starter.start() })
                ActionButton(onClick = onConfigure) { Text(stringResource(R.string.tab_setup)) }
            } else {
                ActionButton(onClick = onConfigure, emphasis = true) { Text(stringResource(R.string.bt_share_configure)) }
            }
        }
    }
}

/** Setup: full configuration of the phone as a Bluetooth GNSS receiver. */
@Composable
fun BtShareSection(engine: GnssEngine, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val s by engine.store.settings.collectAsState()
    val st by engine.btServer.state.collectAsState()
    val conn by engine.connection.collectAsState()
    val starter = rememberBtStarter(engine)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val advertise = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) try { discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)) } catch (_: Exception) {}
    }
    Column(modifier) {
        Section(stringResource(R.string.bt_share_title)) {
            Text(stringResource(R.string.bt_share_help), style = MaterialTheme.typography.bodySmall, color = muted)
            SwitchRow(stringResource(R.string.bt_share_switch), s.btServer, { v -> if (v) starter.start() else engine.store.update { it.copy(btServer = false) } })
            val (text, color) = statusText(st, conn is Connection.Ready)
            if (st.status != BtServerStatus.OFF) Text(text, style = MaterialTheme.typography.bodySmall, color = color)
            if (st.status == BtServerStatus.BLUETOOTH_OFF || st.status == BtServerStatus.NO_PERMISSION)
                ActionButton(onClick = { starter.start() }, compact = true) { Text(stringResource(R.string.action_retry)) }
            ChoiceRow(stringResource(R.string.bt_share_sentences), listOf(false to stringResource(R.string.bt_share_all), true to stringResource(R.string.bt_share_position)),
                s.btServerPositionOnly) { v -> engine.store.update { it.copy(btServerPositionOnly = v) } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = {
                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                    if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
                        advertise.launch(Manifest.permission.BLUETOOTH_ADVERTISE)
                    else try { discoverable.launch(intent) } catch (_: Exception) {}
                }, compact = true) { Text(stringResource(R.string.bt_share_visible)) }
                ActionButton(onClick = { try { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {} }, compact = true) {
                    Text(stringResource(R.string.setup_bt_pair))
                }
            }
            Text(stringResource(R.string.bt_share_steps), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}
