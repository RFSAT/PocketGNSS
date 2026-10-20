package com.rfsat.pocketgnss.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.http.SslError
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import android.webkit.SslErrorHandler
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.viewinterop.AndroidView
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssEngine

private const val RECEIVER_IP = "192.168.4.1"
private const val SSID_PREFIX = "ESPrtk"

private enum class WebPhase { GUIDE, JOINING, OPEN, FAILED }

/**
 * The receiver's embedded ESPrtk web server (D3.3 §4.7.2-4.7.21). Hold the top button for 5 s: the LCD shows
 * the hotspot "ESPrtk : <ID>". The app asks Android to join that hotspot (it has no internet, so the app binds
 * to it while this screen is open) and opens http(s)://192.168.4.1 in an embedded browser.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReceiverWebTool(engine: GnssEngine) {
    val context = LocalContext.current
    val s by engine.store.settings.collectAsState()
    val cm = remember { context.getSystemService(ConnectivityManager::class.java) }
    var phase by remember { mutableStateOf(WebPhase.GUIDE) }
    var message by remember { mutableStateOf("") }
    var https by remember { mutableStateOf(false) }
    var callback by remember { mutableStateOf<ConnectivityManager.NetworkCallback?>(null) }
    var sslPending by remember { mutableStateOf<SslErrorHandler?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ntripWasOn = remember { s.ntripEnabled }
    val txtNoWifi = stringResource(R.string.web_no_wifi); val txtNotFound = stringResource(R.string.web_not_found)
    val txtLost = stringResource(R.string.web_lost); val txtCopied = stringResource(R.string.web_copied)

    fun release() {
        try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
    }
    DisposableEffect(Unit) { onDispose { release(); if (ntripWasOn && !engine.ntripClient.running) engine.setNtrip(true) } }

    fun opened(n: Network) {
        cm.bindProcessToNetwork(n)
        phase = WebPhase.OPEN
    }

    fun useCurrentWifi() {
        @Suppress("DEPRECATION")
        val n = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        if (n != null) opened(n) else { phase = WebPhase.FAILED; message = txtNoWifi }
    }

    fun join() {
        release(); phase = WebPhase.JOINING; message = ""
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { webView?.post { opened(network) } ?: opened(network) }
            override fun onUnavailable() { phase = WebPhase.FAILED; message = txtNotFound }
            override fun onLost(network: Network) { if (phase == WebPhase.OPEN) { phase = WebPhase.FAILED; message = txtLost } }
        }
        callback = cb
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val spec = WifiNetworkSpecifier.Builder().setSsidPattern(PatternMatcher(SSID_PREFIX, PatternMatcher.PATTERN_PREFIX)).build()
                val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build()
                cm.requestNetwork(req, cb, 60_000)
            } else useCurrentWifi()
        } catch (e: Exception) { phase = WebPhase.FAILED; message = e.message ?: "" }
    }

    sslPending?.let { h ->
        AlertDialog(onDismissRequest = { h.cancel(); sslPending = null },
            title = { Text(stringResource(R.string.web_ssl_title)) }, text = { Text(stringResource(R.string.web_ssl_text, RECEIVER_IP)) },
            confirmButton = { ActionButton(onClick = { h.proceed(); sslPending = null }, emphasis = true) { Text(stringResource(R.string.web_ssl_continue)) } },
            dismissButton = { ActionButton(onClick = { h.cancel(); sslPending = null }) { Text(stringResource(R.string.action_cancel)) } })
    }

    if (phase == WebPhase.OPEN) {
        Column(Modifier.fillMaxSize()) {
            FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChip(!https, "http", { https = false; webView?.loadUrl("http://$RECEIVER_IP/") })
                ChoiceChip(https, "https", { https = true; webView?.loadUrl("https://$RECEIVER_IP/") })
                ActionButton(onClick = { webView?.reload() }, compact = true) { Text(stringResource(R.string.web_reload)) }
                ActionButton(onClick = { webView?.destroy(); webView = null; release(); phase = WebPhase.GUIDE }, compact = true) { Text(stringResource(R.string.web_close)) }
            }
            Text(stringResource(R.string.web_logout_reminder), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = WarnAmber)
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.builtInZoomControls = true; settings.displayZoomControls = false
                    settings.useWideViewPort = true; settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
                            // The ESP32 serves a self-signed certificate. Only for the receiver's own address, and only after asking.
                            if (android.net.Uri.parse(error.url).host == RECEIVER_IP) sslPending = handler else handler.cancel()
                        }
                    }
                    loadUrl((if (https) "https" else "http") + "://$RECEIVER_IP/")
                    webView = this
                }
            }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
        return
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.web_steps)) {
            Text(stringResource(R.string.web_step1), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.web_step2, if (s.receiverId.isBlank()) "<ID>" else s.receiverId), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.web_step3), style = MaterialTheme.typography.bodyMedium)
            if (s.ntripEnabled) Text(stringResource(R.string.web_ntrip_pause), style = MaterialTheme.typography.bodySmall, color = WarnAmber)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(onClick = { if (s.ntripEnabled) engine.ntripClient.stop(); join() }, emphasis = true, enabled = phase != WebPhase.JOINING) {
                    Text(stringResource(if (phase == WebPhase.JOINING) R.string.web_joining else R.string.web_join))
                }
                ActionButton(onClick = { if (s.ntripEnabled) engine.ntripClient.stop(); useCurrentWifi() }) { Text(stringResource(R.string.web_use_current)) }
            }
            if (phase == WebPhase.FAILED) Text(message.ifBlank { stringResource(R.string.web_not_found) }, color = AlarmRed, style = MaterialTheme.typography.bodySmall)
        }
        Section(stringResource(R.string.web_login)) {
            val n = s.deviceNumber.trim()
            val login = if (n.isBlank()) "RTKrover<NN>" else "RTKrover" + (n.toIntOrNull()?.let { "%02d".format(it) } ?: n)
            KeyValue(stringResource(R.string.web_user_pass), login)
            Text(stringResource(R.string.web_login_help), style = MaterialTheme.typography.bodySmall, color = muted)
            ActionButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("login", login))
                engine.toast(txtCopied)
            }, enabled = n.isNotBlank()) { Text(stringResource(R.string.web_copy)) }
        }
        Section(stringResource(R.string.web_internet_title)) {
            VariantPicker(engine, explain = false)
            when (s.receiverVariant) {
                com.rfsat.pocketgnss.link.ReceiverVariant.LTE -> {
                    Text(stringResource(R.string.web_lte_steps), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.web_lte_alt_wifi), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                com.rfsat.pocketgnss.link.ReceiverVariant.WIFI -> {
                    Text(stringResource(R.string.web_wifi_steps), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.web_wifi_hotspot), style = MaterialTheme.typography.bodySmall, color = muted)
                    HotspotButton()
                }
                com.rfsat.pocketgnss.link.ReceiverVariant.UNSET -> Text(stringResource(R.string.variant_help), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Text(stringResource(R.string.web_ntrip_steps), style = MaterialTheme.typography.bodySmall)
        }
        Section(stringResource(R.string.web_sections)) {
            val lte = s.receiverVariant != com.rfsat.pocketgnss.link.ReceiverVariant.WIFI
            for (r in listOfNotNull(R.string.web_sec_action, if (lte) R.string.web_sec_internet else R.string.web_sec_internet_wifi, R.string.web_sec_ntrip,
                R.string.web_sec_bt, if (lte) R.string.web_sec_sd else null, if (lte) R.string.web_sec_gsm else null, R.string.web_sec_dont))
                Text(stringResource(r), style = MaterialTheme.typography.bodySmall)
        }
        Section(stringResource(R.string.web_warnings)) {
            Text(stringResource(R.string.web_warn_text), style = MaterialTheme.typography.bodySmall, color = AlarmRed, fontWeight = FontWeight.SemiBold)
        }
    }
}
