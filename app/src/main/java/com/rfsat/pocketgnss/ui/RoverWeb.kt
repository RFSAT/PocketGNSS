package com.rfsat.pocketgnss.ui

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.link.ReceiverVariant

/**
 * Where the settings page of an RFSAT LG290P rover is (master documentation P-2 item 6, Part U):
 * [ssidPrefix] of its setup network, [setupIp] on that network, [homeHost] on a shared Wi-Fi (mDNS name, or null when
 * only the IP shown by the device works).
 */
data class RoverWeb(val ssidPrefix: String, val setupIp: String, val homeHost: String?)

fun roverWeb(v: ReceiverVariant): RoverWeb? = when (v) {
    ReceiverVariant.PICORTK -> RoverWeb("PicoRTK-Setup", "192.168.42.1", "picortk.local")
    ReceiverVariant.LG290P_C6, ReceiverVariant.POSTCARDRTK -> RoverWeb("PostcardRTK-Setup", "192.168.4.1", "postcardrtk.local")
    ReceiverVariant.RTK_EVERYWHERE -> RoverWeb("RTK Config", "192.168.4.1", null)
    else -> null
}

private enum class RoverPhase { GUIDE, JOINING, OPEN, FAILED }

/** Opens a rover's own settings page: through its setup network, or on the Wi-Fi the phone shares with it. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RoverWebTool(engine: GnssEngine) {
    val context = LocalContext.current
    val s by engine.store.settings.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val web = roverWeb(s.receiverVariant)
    if (web == null) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.rover_web_title)) {
                VariantPicker(engine, explain = false)
                Text(stringResource(R.string.rover_web_none), style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    val cm = remember { context.getSystemService(ConnectivityManager::class.java) }
    var phase by remember { mutableStateOf(RoverPhase.GUIDE) }
    var message by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var callback by remember { mutableStateOf<ConnectivityManager.NetworkCallback?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var host by remember(s.receiverVariant) { mutableStateOf(s.roverHost.ifBlank { web.homeHost ?: "" }) }
    val txtNoWifi = stringResource(R.string.web_no_wifi); val txtNotFound = stringResource(R.string.web_not_found)
    val txtLost = stringResource(R.string.web_lost)

    fun release() {
        try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
    }
    DisposableEffect(Unit) { onDispose { release() } }

    fun opened(n: Network?, address: String) {
        if (n != null) cm.bindProcessToNetwork(n)
        url = "http://$address/"
        phase = RoverPhase.OPEN
    }

    fun joinSetup() {
        release(); phase = RoverPhase.JOINING; message = ""
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { webView?.post { opened(network, web.setupIp) } ?: opened(network, web.setupIp) }
            override fun onUnavailable() { phase = RoverPhase.FAILED; message = txtNotFound }
            override fun onLost(network: Network) { if (phase == RoverPhase.OPEN) { phase = RoverPhase.FAILED; message = txtLost } }
        }
        callback = cb
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val spec = WifiNetworkSpecifier.Builder().setSsidPattern(PatternMatcher(web.ssidPrefix, PatternMatcher.PATTERN_PREFIX)).build()
                val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build()
                cm.requestNetwork(req, cb, 60_000)
            } else {
                @Suppress("DEPRECATION")
                val n = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                if (n != null) opened(n, web.setupIp) else { phase = RoverPhase.FAILED; message = txtNoWifi }
            }
        } catch (e: Exception) { phase = RoverPhase.FAILED; message = e.message ?: "" }
    }

    fun openHome() {
        val h = host.trim().removePrefix("http://").removeSuffix("/")
        if (h.isEmpty()) return
        engine.store.update { it.copy(roverHost = if (h == web.homeHost) "" else h) }
        release(); opened(null, h)
    }

    if (phase == RoverPhase.OPEN) {
        Column(Modifier.fillMaxSize()) {
            FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(url, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = muted)
                ActionButton(onClick = { webView?.reload() }, compact = true) { Text(stringResource(R.string.web_reload)) }
                ActionButton(onClick = { webView?.destroy(); webView = null; release(); phase = RoverPhase.GUIDE }, compact = true) { Text(stringResource(R.string.web_close)) }
            }
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.builtInZoomControls = true; settings.displayZoomControls = false
                    settings.useWideViewPort = true; settings.loadWithOverviewMode = true
                    webViewClient = WebViewClient()
                    loadUrl(url)
                    webView = this
                }
            }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
        return
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.rover_web_title)) {
            VariantPicker(engine, explain = false)
        }
        Section(stringResource(R.string.rover_web_setup_title)) {
            Text(stringResource(when (s.receiverVariant) {
                ReceiverVariant.PICORTK -> R.string.rover_web_setup_pico
                ReceiverVariant.RTK_EVERYWHERE -> R.string.rover_web_setup_rtke
                else -> R.string.rover_web_setup_postcard
            }, web.ssidPrefix, web.setupIp), style = MaterialTheme.typography.bodyMedium)
            ActionButton(onClick = { joinSetup() }, emphasis = true, enabled = phase != RoverPhase.JOINING) {
                Text(stringResource(if (phase == RoverPhase.JOINING) R.string.web_joining else R.string.rover_web_join, web.ssidPrefix))
            }
            if (phase == RoverPhase.FAILED) Text(message.ifBlank { txtNotFound }, color = AlarmRed, style = MaterialTheme.typography.bodySmall)
        }
        Section(stringResource(R.string.rover_web_home_title)) {
            Text(stringResource(if (web.homeHost != null) R.string.rover_web_home_help else R.string.rover_web_home_help_ip),
                style = MaterialTheme.typography.bodySmall, color = muted)
            OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.rover_web_address)) },
                placeholder = { Text(web.homeHost ?: "192.168.1.…") })
            ActionButton(onClick = { openHome() }, enabled = host.isNotBlank()) { Text(stringResource(R.string.rover_web_open)) }
        }
    }
}
