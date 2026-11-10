package com.rfsat.pocketgnss.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.link.BleNusLink

private data class BleSeen(val address: String, val name: String?, val rssi: Int, val nus: Boolean)

/** Names of RFSAT's rovers and their firmware (a device may not put the NUS service in its advertisement). */
private fun looksLikeRover(name: String?): Boolean = name != null &&
    listOf("picortk", "postcard", "rtk", "rfsat", "lg290").any { name.lowercase().contains(it) }

/**
 * Bluetooth LE devices nearby (P-2 item 2): a 15 s scan, rovers with the Nordic UART Service first. Tapping one saves
 * it as the Bluetooth source (Bluetooth LE). Needs BLUETOOTH_SCAN (Android 12+, "never for location") or, before
 * Android 12, the location permission.
 */
@SuppressLint("MissingPermission")
@Composable
fun BleScanList(engine: GnssEngine) {
    val context = LocalContext.current
    val s by engine.store.settings.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var seen by remember { mutableStateOf<Map<String, BleSeen>>(emptyMap()) }
    val scanner = remember { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner }
    val txtOff = stringResource(R.string.conn_bt_off)
    val txtFailed = stringResource(R.string.ble_scan_failed)
    val txtPermission = stringResource(R.string.ble_permission)
    val callback = remember {
        object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val nus = r.scanRecord?.serviceUuids?.any { it.uuid == BleNusLink.NUS_SERVICE } == true
                val name = r.scanRecord?.deviceName ?: try { r.device.name } catch (_: SecurityException) { null }
                val old = seen[r.device.address]
                seen = seen + (r.device.address to BleSeen(r.device.address, name ?: old?.name, r.rssi, nus || old?.nus == true))
            }
            override fun onScanFailed(code: Int) { scanning = false; error = "$txtFailed ($code)" }
        }
    }
    fun stop() { if (scanning) try { scanner?.stopScan(callback) } catch (_: Exception) {}; scanning = false }
    DisposableEffect(Unit) { onDispose { stop() } }
    val needed = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun granted() = needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    fun start() {
        error = null
        if (scanner == null) { error = txtOff; return }
        seen = emptyMap()
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            scanning = true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stop() }, 15_000)
        } catch (e: Exception) { error = e.message ?: txtFailed }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) start() else error = txtPermission
    }

    Column {
        ActionButton(onClick = { if (scanning) stop() else if (granted()) start() else permission.launch(needed) }, compact = true, emphasis = !scanning) {
            Text(stringResource(if (scanning) R.string.ble_scan_stop else R.string.ble_scan))
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AlarmRed) }
        val list = seen.values.sortedWith(compareByDescending<BleSeen> { it.nus }.thenByDescending { looksLikeRover(it.name) }.thenByDescending { it.rssi })
            .filter { it.nus || it.name != null }
        if (list.isEmpty() && s.btBle && s.btAddress.isNotBlank())
            Text("● " + s.btName.ifBlank { s.btAddress }, fontWeight = FontWeight.Bold, color = SafeGreen)
        for (d in list.take(20)) {
            val sel = s.btBle && d.address == s.btAddress
            Row(Modifier.fillMaxWidth().clickable {
                engine.store.update { it.copy(btAddress = d.address, btName = d.name ?: d.address, btBle = true) }; stop()
            }.padding(vertical = 4.dp)) {
                Text((if (sel) "● " else "○ ") + (d.name ?: d.address), Modifier.weight(1f), fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    color = if (sel) SafeGreen else MaterialTheme.colorScheme.onSurface)
                Text((if (d.nus) "NUS · " else "") + "${d.rssi} dBm", style = MaterialTheme.typography.bodySmall,
                    color = if (d.nus) SafeGreen else muted)
            }
        }
        Text(stringResource(R.string.ble_help), style = MaterialTheme.typography.bodySmall, color = muted)
    }
}
