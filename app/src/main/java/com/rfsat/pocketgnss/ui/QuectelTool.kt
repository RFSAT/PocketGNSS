package com.rfsat.pocketgnss.ui

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
import com.rfsat.pocketgnss.link.LinkKind
import com.rfsat.pocketgnss.link.ReceiverVariant
import com.rfsat.pocketgnss.protocol.Quectel
import com.rfsat.pocketgnss.protocol.QuectelProfile
import kotlinx.coroutines.launch

/**
 * LG290P receiver configuration with the profiles of the master documentation (V5 "Profiles", P-2 item 4): General,
 * Fleet and Tractor, Galileo HAS on or off, for the receiver's 4-pin socket (UART3) or for the port in use; then save
 * and restart. Each command is shown with the receiver's reply (OK, ERROR, or no reply).
 */
@Composable
fun QuectelNmeaTool(engine: GnssEngine) {
    val conn by engine.connection.collectAsState()
    val rx by engine.receiver.collectAsState()
    val s by engine.store.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(QuectelProfile.GENERAL) }
    var uart3 by remember { mutableStateOf(true) }
    var has by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    /** Command body -> reply text, in the order sent. */
    var replies by remember { mutableStateOf<List<Pair<String, String?>>>(emptyList()) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ready = conn is Connection.Ready
    val info = engine.linkInfo
    val txtOk = stringResource(R.string.lg_ok); val txtNone = stringResource(R.string.lg_no_reply)
    val txtError = stringResource(R.string.lg_error)
    fun text(r: Quectel.Reply?): String = when (r) {
        null -> txtNone
        Quectel.Reply.Ok -> txtOk
        is Quectel.Reply.Error -> txtError + (r.code?.let { " $it" } ?: "")
        is Quectel.Reply.Value -> r.fields.joinToString(",")
    }
    suspend fun send(bodies: List<String>) {
        busy = true
        val out = ArrayList<Pair<String, String?>>()
        replies = emptyList()
        for (b in bodies) {
            out.add(b to null); replies = out.toList()
            val r = engine.quectel(b, if (b == QuectelProfile.SAVE) 3000 else 1500)
            out[out.size - 1] = b to text(r); replies = out.toList()
            if (r == null && out.size == 1) break // nothing answers: the commands do not reach the receiver
        }
        busy = false
    }
    val commands = profile.commands(uart3, has)

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.ubx_receiver)) {
            KeyValue(stringResource(R.string.home_module), rx.module?.let { it + (rx.firmware?.let { f -> " · $f" } ?: "") } ?: "—")
            KeyValue(stringResource(R.string.home_link), info?.detail ?: "—")
            val direct = s.receiverVariant == ReceiverVariant.LG290P_WIRED || (info?.kind == LinkKind.USB && s.receiverVariant.defaultUsbPort == 1)
            Text(stringResource(if (direct) R.string.lg_direct else R.string.lg_via_firmware), style = MaterialTheme.typography.bodySmall,
                color = if (direct) SafeGreen else InfoViolet)
            ActionButton(onClick = { scope.launch { send(listOf(QuectelProfile.VERSION)) } }, enabled = ready && !busy) {
                Text(stringResource(R.string.lg_read_version))
            }
        }
        Section(stringResource(R.string.lg_profile)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChip(profile == QuectelProfile.GENERAL, stringResource(R.string.lg_profile_general), { profile = QuectelProfile.GENERAL })
                ChoiceChip(profile == QuectelProfile.FLEET, stringResource(R.string.lg_profile_fleet), { profile = QuectelProfile.FLEET })
                ChoiceChip(profile == QuectelProfile.TRACTOR, stringResource(R.string.lg_profile_tractor), { profile = QuectelProfile.TRACTOR })
            }
            Text(stringResource(when (profile) {
                QuectelProfile.GENERAL -> R.string.lg_profile_general_help
                QuectelProfile.FLEET -> R.string.lg_profile_fleet_help
                QuectelProfile.TRACTOR -> R.string.lg_profile_tractor_help
            }), style = MaterialTheme.typography.bodySmall, color = muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChip(uart3, stringResource(R.string.lg_target_uart3), { uart3 = true })
                ChoiceChip(!uart3, stringResource(R.string.lg_target_this), { uart3 = false })
            }
            Text(stringResource(R.string.lg_target_help), style = MaterialTheme.typography.bodySmall, color = muted)
            SwitchRow(stringResource(R.string.lg_has), has, { has = it }, stringResource(R.string.lg_has_help))
        }
        Section(stringResource(R.string.lg_commands)) {
            val shown = if (replies.isNotEmpty()) replies else commands.map { it to null }
            for ((b, r) in shown) Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(Quectel.command(b), Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (r != null) Text(r, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    color = if (r == txtOk || (r != txtNone && !r.startsWith(txtError))) SafeGreen else AlarmRed)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(onClick = { scope.launch { send(commands) } }, enabled = ready && !busy, emphasis = true) { Text(stringResource(R.string.lg_apply)) }
                ActionButton(onClick = { scope.launch { send(listOf(QuectelProfile.SAVE)) } }, enabled = ready && !busy) { Text(stringResource(R.string.lg_save)) }
                ActionButton(onClick = { scope.launch { send(listOf(QuectelProfile.RESTART)) } }, enabled = ready && !busy) { Text(stringResource(R.string.lg_restart)) }
            }
            Text(stringResource(R.string.lg_apply_help), style = MaterialTheme.typography.bodySmall, color = muted)
            if (profile == QuectelProfile.TRACTOR && uart3) Text(stringResource(R.string.lg_tractor_baud), style = MaterialTheme.typography.bodySmall, color = InfoViolet)
        }
    }
}
