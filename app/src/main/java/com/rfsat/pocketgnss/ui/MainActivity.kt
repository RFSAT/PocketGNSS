package com.rfsat.pocketgnss.ui

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.GnssApp
import com.rfsat.pocketgnss.core.Connection
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.core.LinkService
import com.rfsat.pocketgnss.link.LinkKind
import com.rfsat.pocketgnss.link.UsbSerialLink
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val engine: GnssEngine get() = (application as GnssApp).engine
    private val usbManager: UsbManager get() = getSystemService(Context.USB_SERVICE) as UsbManager

    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) connectSource() else engine.disconnect(getString(R.string.conn_bt_permission))
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val phonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) engine.connectPhone() else engine.disconnect(getString(R.string.conn_phone_permission))
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) engine.connectUsb(usbManager, device)
                    else engine.disconnect(getString(R.string.conn_usb_refused))
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && engine.linkInfo?.kind == LinkKind.USB) engine.disconnect(getString(R.string.conn_unplugged))
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device != null && UsbSerialLink.isCandidate(device)) useUsb(device)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ContextCompat.registerReceiver(this, usbReceiver, IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(engine.connection, engine.store.settings) { c, s -> c is Connection.Ready && s.keepScreenOn }.collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        // Background service whenever a real receiver feeds a recording, NTRIP, mock location or the NMEA server.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(engine.connection, engine.recording, engine.ntripClient.ntrip, engine.store.settings, engine.raw.state) { _, _, _, _, _ -> engine.needsBackground() }
                    .collect { need -> if (need) { askNotifications(); LinkService.start(this@MainActivity, engine) } }
            }
        }

        setContent {
            RtkTheme {
                AppRoot(engine, onReconnect = { connectSource() }, onExit = {
                    LinkService.stop(this); engine.shutdown(); finishAndRemoveTask()
                }, onNeedLocation = { askLocation() })
            }
        }
        if (!handleIntent(intent) && engine.connection.value !is Connection.Ready) connectSource()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?): Boolean {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return false
        if (!UsbSerialLink.isCandidate(device)) return false
        useUsb(device)
        return true
    }

    /** A receiver plugged in always wins: switch the source to USB and connect (launch via the filter grants permission). */
    private fun useUsb(device: UsbDevice) {
        if (engine.store.value.source != LinkKind.USB) engine.store.update { it.copy(source = LinkKind.USB) }
        connectUsbDevice(device)
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun askLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Connects whatever Setup selects. */
    fun connectSource() {
        val s = engine.store.value
        when (s.source) {
            LinkKind.SIMULATOR -> engine.connectSimulator()
            LinkKind.REPLAY -> {}
            LinkKind.PHONE -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) engine.connectPhone()
                else phonePermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            LinkKind.TCP -> engine.connectTcp(s.tcpHost, s.tcpPort)
            LinkKind.USB -> {
                val device = usbManager.deviceList.values.firstOrNull { UsbSerialLink.isCandidate(it) }
                if (device == null) engine.disconnect() else connectUsbDevice(device)
            }
            LinkKind.BLUETOOTH -> {
                if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
                }
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                when {
                    adapter == null -> engine.disconnect(getString(R.string.conn_no_bt))
                    !adapter.isEnabled -> engine.disconnect(getString(R.string.conn_bt_off))
                    s.btAddress.isBlank() -> engine.disconnect(getString(R.string.conn_bt_pick))
                    else -> try { engine.connectBluetooth(adapter.getRemoteDevice(s.btAddress)) } catch (_: IllegalArgumentException) {
                        engine.disconnect(getString(R.string.conn_bt_pick))
                    }
                }
            }
        }
    }

    private fun connectUsbDevice(device: UsbDevice) {
        if (usbManager.hasPermission(device)) { engine.connectUsb(usbManager, device); return }
        engine.awaitingPermission()
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags))
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.rfsat.pocketgnss.USB_PERMISSION"
    }
}

enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Filled.Home),
    LIVE(R.string.tab_live, Icons.Filled.GpsFixed),
    MAP(R.string.tab_map, Icons.Filled.Map),
    TOOLS(R.string.tab_tools, Icons.Filled.Build),
    LOG(R.string.tab_log, Icons.AutoMirrored.Filled.ListAlt),
    SETUP(R.string.tab_setup, Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppRoot(
    engine: GnssEngine,
    onReconnect: () -> Unit,
    onExit: () -> Unit = {},
    onNeedLocation: () -> Unit = {},
    initialTab: Int = 0,
    initialTool: ToolId? = null,
    showExitDialog: Boolean = false,
) {
    var tabName by rememberSaveable { mutableStateOf(Tab.entries[initialTab].name) }
    var confirmExit by remember { mutableStateOf(showExitDialog) }
    // Set by Home's "Phone as GNSS receiver" card: open Setup at the Bluetooth sharing section.
    var setupFocusBt by remember { mutableStateOf(false) }
    val conn by engine.connection.collectAsState()
    val fix by engine.fix.collectAsState()
    val tab = Tab.entries.firstOrNull { it.name == tabName } ?: Tab.HOME
    val snack = remember { SnackbarHostState() }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    }
    LaunchedEffect(Unit) { engine.messages.collect { snack.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text(stringResource(R.string.app_name))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val f = fix
                        if (conn is Connection.Ready) Text("● ", color = fixColor(f?.quality ?: com.rfsat.pocketgnss.protocol.FixQuality.NONE), fontSize = 13.sp)
                        Text(
                            when (val c = conn) {
                                is Connection.Ready -> c.info.name + (if (f != null) " · " + stringResource(fixLabel(f.quality)) + " " + formatAccuracy(f.horizontalAccuracy)
                                    else " · " + stringResource(R.string.top_no_fix))
                                is Connection.Connecting -> stringResource(R.string.conn_connecting, c.label)
                                Connection.AwaitingPermission -> stringResource(R.string.top_allow_usb)
                                Connection.NoDevice -> stringResource(R.string.top_no_receiver)
                                is Connection.Failed -> c.message
                            },
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            })
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tabName = t.name }, icon = { Icon(t.icon, stringResource(t.label)) },
                        label = { FitText(stringResource(t.label), fontSize = 10.sp) })
                }
                NavigationBarItem(selected = false, onClick = { confirmExit = true },
                    icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, stringResource(R.string.tab_exit)) },
                    label = { FitText(stringResource(R.string.tab_exit), fontSize = 10.sp) })
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        if (confirmExit) {
            val rec by engine.recording.collectAsState()
            AlertDialog(
                onDismissRequest = { confirmExit = false },
                title = { Text(stringResource(R.string.exit_title)) },
                text = { Text(if (rec.active) stringResource(R.string.exit_logging, rec.fixes) else stringResource(R.string.exit_idle)) },
                confirmButton = { ActionButton(onClick = { confirmExit = false; onExit() }, emphasis = true) { Text(stringResource(R.string.tab_exit)) } },
                dismissButton = { ActionButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            when (tab) {
                Tab.HOME -> HomeScreen(engine, onOpenTab = { tabName = it.name }, onReconnect = onReconnect,
                    onConfigureBt = { setupFocusBt = true; tabName = Tab.SETUP.name })
                Tab.LIVE -> LiveScreen(engine, onReconnect)
                Tab.MAP -> MapScreen(engine)
                Tab.TOOLS -> ToolsTab(engine, initialTool)
                Tab.LOG -> LogScreen(engine)
                Tab.SETUP -> SetupScreen(engine, onReconnect, onNeedLocation, focusBluetooth = setupFocusBt, onFocused = { setupFocusBt = false })
            }
        }
    }
}

/** Shown on screens that need a receiver while none is connected. */
@Composable
fun NotConnected(conn: Connection, onReconnect: () -> Unit) {
    Column(Modifier.padding(24.dp)) {
        Text(when (conn) {
            is Connection.Connecting -> stringResource(R.string.conn_connecting, conn.label)
            Connection.AwaitingPermission -> stringResource(R.string.nc_awaiting)
            is Connection.Failed -> stringResource(R.string.nc_failed, conn.message)
            else -> stringResource(R.string.nc_none)
        }, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.nc_help), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        ActionButton(onClick = onReconnect, modifier = Modifier.padding(top = 16.dp), emphasis = true) { Text(stringResource(R.string.action_connect)) }
    }
}

