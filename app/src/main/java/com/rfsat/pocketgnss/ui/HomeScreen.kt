package com.rfsat.pocketgnss.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.pocketgnss.BuildConfig
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.Connection
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.data.AppSettings
import com.rfsat.pocketgnss.link.LinkKind

/** Home / About: identity, connection status and quick actions, description, credits. */
@Composable
fun HomeScreen(engine: GnssEngine, onOpenTab: (Tab) -> Unit, onReconnect: () -> Unit, onConfigureBt: () -> Unit = { onOpenTab(Tab.SETUP) }) {
    val conn by engine.connection.collectAsState()
    val fix by engine.fix.collectAsState()
    val rx by engine.receiver.collectAsState()
    val rec by engine.recording.collectAsState()
    val ntrip by engine.ntripClient.ntrip.collectAsState()
    val s by engine.store.settings.collectAsState()
    val context = LocalContext.current
    fun openUrl(url: String) = try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    // Short labels: a fixed label column (fits "Receiver version") instead of 42 % of the width.
    androidx.compose.runtime.CompositionLocalProvider(LocalKeyWidth provides HOME_KEY_WIDTH) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 28.sp)
            if (!StoreShots.hideVersion) Text(stringResource(R.string.home_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), color = muted)
            Text(stringResource(R.string.home_tagline), fontSize = 13.sp)
        }

        if (!engine.phoneHasGnss) NoGnssWarning()
        val ready = conn as? Connection.Ready
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot = when (conn) {
                    is Connection.Ready -> fixColor(fix?.quality ?: com.rfsat.pocketgnss.protocol.FixQuality.NONE)
                    is Connection.Connecting, Connection.AwaitingPermission -> WarnAmber
                    is Connection.Failed -> AlarmRed
                    Connection.NoDevice -> muted
                }
                Text("●  ", color = dot, fontSize = 18.sp)
                Text(when (val c = conn) {
                    is Connection.Ready -> c.info.name
                    is Connection.Connecting -> stringResource(R.string.conn_connecting, c.label)
                    Connection.AwaitingPermission -> stringResource(R.string.top_allow_usb)
                    Connection.NoDevice -> stringResource(R.string.home_no_receiver)
                    is Connection.Failed -> c.message
                }, fontWeight = FontWeight.SemiBold)
            }
            KeyValue(stringResource(R.string.home_variant), stringResource(variantName(s.receiverVariant)))
            if (s.receiverVariant == com.rfsat.pocketgnss.link.ReceiverVariant.UNSET) VariantPicker(engine)
            if (ready != null) {
                KeyValue(stringResource(R.string.home_link), ready.info.detail)
                rx.module?.let { KeyValue(stringResource(R.string.home_module), it + (rx.firmware?.let { f -> " · $f" } ?: "")) }
                val f = fix
                KeyValue(stringResource(R.string.live_fix), if (f != null) stringResource(fixLabel(f.quality)) + " · " + formatAccuracy(f.horizontalAccuracy)
                    else stringResource(R.string.top_no_fix), live = true)
                KeyValue(stringResource(R.string.home_state), buildList {
                    if (ntrip.status != com.rfsat.pocketgnss.core.NtripStatus.OFF) add(stringResource(R.string.state_ntrip, ntrip.status.name.lowercase()))
                    if (rec.active) add(stringResource(R.string.state_logging, rec.fixes))
                    if (s.mockLocation) add(stringResource(R.string.state_mock))
                    if (s.nmeaServer) add(stringResource(R.string.state_server, s.nmeaServerPort))
                }.ifEmpty { listOf(stringResource(R.string.state_receiving)) }.joinToString(", "), live = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Navigation shortcuts: all neutral (a filled button is kept for a screen's main action, e.g. Connect).
                    ActionButton(onClick = { onOpenTab(Tab.LIVE) }) { Text(stringResource(R.string.tab_live)) }
                    ActionButton(onClick = { onOpenTab(Tab.MAP) }) { Text(stringResource(R.string.tab_map)) }
                    ActionButton(onClick = { onOpenTab(Tab.TOOLS) }) { Text(stringResource(R.string.tab_tools)) }
                }
            } else {
                Text(stringResource(sourceHelp(s)), style = MaterialTheme.typography.bodySmall, color = muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(onClick = onReconnect, emphasis = true) { Text(stringResource(R.string.action_connect)) }
                    ActionButton(onClick = { onOpenTab(Tab.SETUP) }) { Text(stringResource(R.string.tab_setup)) }
                    if (s.source != LinkKind.PHONE && engine.phoneHasGnss) ActionButton(onClick = {
                        engine.store.update { it.copy(source = LinkKind.PHONE) }; onReconnect()
                    }) { Text(stringResource(R.string.home_try_phone)) }
                    if (!s.useSimulator) ActionButton(onClick = {
                        engine.store.update { it.copy(source = LinkKind.SIMULATOR) }; onReconnect()
                    }) { Text(stringResource(R.string.home_try_sim)) }
                }
            }
        }

        // The phone as a (high-precision) GNSS receiver for other devices, one tap away.
        BtShareCard(engine, onConfigure = onConfigureBt)

        Text(stringResource(R.string.home_about), textAlign = TextAlign.Justify, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.home_about_2), textAlign = TextAlign.Justify, style = MaterialTheme.typography.bodyMedium)

        HorizontalDivider()
        LinkRow(stringResource(R.string.home_developer), "RFSAT Limited", "www.rfsat.com") { openUrl("https://www.rfsat.com") }
        Text(stringResource(R.string.home_rfsat), textAlign = TextAlign.Justify, style = MaterialTheme.typography.bodySmall, color = muted)
        KeyValue(stringResource(R.string.home_project), stringResource(R.string.home_project_value))
        KeyValue(stringResource(R.string.home_hardware), stringResource(R.string.home_hardware_value))
        KeyValue(stringResource(R.string.home_interfaces), stringResource(R.string.home_interfaces_value))
        KeyValue(stringResource(R.string.home_licence), "MIT © 2026 RFSAT Limited")
        KeyValue(stringResource(R.string.home_credits), stringResource(R.string.home_credits_value))
        Text(stringResource(R.string.home_not_affiliated), style = MaterialTheme.typography.bodySmall, color = muted.copy(alpha = 0.7f), fontSize = 11.sp)
        HorizontalDivider()
        Text(stringResource(R.string.home_ai_ack), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = muted)
    }
    }
}

private val HOME_KEY_WIDTH = KEY_WIDTH

fun sourceHelp(s: AppSettings) = when (s.source) {
    LinkKind.USB -> R.string.help_usb
    LinkKind.BLUETOOTH -> R.string.help_bt
    LinkKind.TCP -> R.string.help_tcp
    LinkKind.PHONE -> R.string.help_phone
    LinkKind.SIMULATOR, LinkKind.REPLAY -> R.string.help_sim
}

@Composable
private fun LinkRow(label: String, value: String, url: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Text(label, Modifier.width(HOME_KEY_WIDTH).padding(end = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** Test hook: store-listing screenshots leave out the version so they stay valid across releases. */
object StoreShots { @Volatile var hideVersion = false }

/** Shown when the phone has no GNSS receiver of its own. */
@Composable
fun NoGnssWarning() {
    Text(stringResource(R.string.no_gnss_warning), Modifier.fillMaxWidth().background(WarnAmber.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
        .padding(10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
}
