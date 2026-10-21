package com.rfsat.pocketgnss.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.raw.RawReport
import com.rfsat.pocketgnss.raw.RawVerdict
import kotlinx.coroutines.delay
import java.util.Locale

private fun verdictText(v: RawVerdict) = when (v) {
    RawVerdict.WAITING -> R.string.raw_v_waiting
    RawVerdict.NO_RAW -> R.string.raw_v_none
    RawVerdict.CODE_ONLY -> R.string.raw_v_code
    RawVerdict.PHASE_L1 -> R.string.raw_v_l1
    RawVerdict.PHASE_DUAL -> R.string.raw_v_dual
}

private fun verdictColor(v: RawVerdict) = when (v) {
    RawVerdict.PHASE_DUAL -> SafeGreen
    RawVerdict.PHASE_L1, RawVerdict.CODE_ONLY -> WarnAmber
    RawVerdict.NO_RAW -> AlarmRed
    RawVerdict.WAITING -> Color.Unspecified
}

private fun sysName(c: Char) = when (c) { 'G' -> "GPS"; 'R' -> "GLONASS"; 'E' -> "Galileo"; 'C' -> "BeiDou"; 'J' -> "QZSS"; 'I' -> "NavIC"; 'S' -> "SBAS"; else -> "$c" }

private fun yesNo(b: Boolean?, yes: String, no: String, unknown: String) = when (b) { true -> yes; false -> no; null -> unknown }

/**
 * Phone raw GNSS: what this phone's chip delivers (code, carrier phase, bands) and recording of RINEX + GnssLogger +
 * RTCM for post-processing (RTKLIB, Google's MATLAB GNSS Measurement Tools). Stage A of phone RTK.
 */
@Composable
fun RawTool(engine: GnssEngine) {
    val context = LocalContext.current
    val st by engine.raw.state.collectAsState()
    val s by engine.store.settings.collectAsState()
    val n by engine.ntripClient.ntrip.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var withRtcm by rememberSaveable { mutableStateOf(true) }
    var marker by rememberSaveable { mutableStateOf("PHONE") }
    var pending by remember { mutableStateOf<Boolean?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st.running) { while (st.running) { now = System.currentTimeMillis(); delay(1000) } }
    val caps = remember { engine.raw.capabilities() }
    val txtYes = stringResource(R.string.raw_yes); val txtNo = stringResource(R.string.raw_no); val txtUnknown = stringResource(R.string.raw_unknown)

    fun begin(record: Boolean) = engine.raw.start(record, record && withRtcm && s.ntrip.usable, marker)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pending; pending = null
        if (granted && p != null) begin(p)
    }
    fun request(record: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) begin(record)
        else { pending = record; permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (!engine.phoneHasGnss) Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { NoGnssWarning() }
        Section(stringResource(R.string.raw_phone)) {
            Text(stringResource(R.string.raw_intro), style = MaterialTheme.typography.bodySmall, color = muted)
            KeyValue(stringResource(R.string.raw_chip), listOfNotNull(caps.hardware, caps.year?.toString()).joinToString(" · ").ifBlank { txtUnknown })
            KeyValue(stringResource(R.string.raw_android), "SDK ${caps.androidSdk}")
            KeyValue(stringResource(R.string.raw_api_meas), yesNo(caps.measurements, txtYes, txtNo, txtUnknown))
            KeyValue(stringResource(R.string.raw_api_adr), yesNo(caps.accumulatedDeltaRange, txtYes, txtNo, txtUnknown))
            KeyValue(stringResource(R.string.raw_api_nav), yesNo(caps.navigationMessages, txtYes, txtNo, txtUnknown))
            KeyValue(stringResource(R.string.raw_full_tracking), if (caps.fullTrackingRequest) stringResource(R.string.raw_full_auto) else stringResource(R.string.raw_full_dev))
        }

        Section(stringResource(R.string.raw_test)) {
            Text(stringResource(R.string.raw_test_help), style = MaterialTheme.typography.bodySmall, color = muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = { if (st.running) engine.raw.stop() else request(false) }, enabled = !st.recording && engine.phoneHasGnss, emphasis = !st.running) {
                    Text(stringResource(if (st.running && !st.recording) R.string.raw_stop else R.string.raw_start_test))
                }
            }
            st.error?.let { Text(it, color = AlarmRed, style = MaterialTheme.typography.bodySmall) }
            st.status?.let { Text(stringResource(R.string.raw_api_status, it), color = WarnAmber, style = MaterialTheme.typography.bodySmall) }
            st.report?.let { r -> Report(r, st.running, muted) }
        }

        Section(stringResource(R.string.raw_record)) {
            Text(stringResource(R.string.raw_record_help), style = MaterialTheme.typography.bodySmall, color = muted)
            TextSetting(stringResource(R.string.raw_marker), marker, { marker = it.uppercase(Locale.US).filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(20) })
            SwitchRow(stringResource(R.string.raw_with_rtcm), withRtcm && s.ntrip.usable, { withRtcm = it },
                if (s.ntrip.usable) "${s.ntrip.host}:${s.ntrip.port}/${s.ntrip.mountpoint}" else stringResource(R.string.raw_rtcm_setup),
                enabled = s.ntrip.usable && !st.running)
            ToggleButton(on = st.recording, label = stringResource(if (st.recording) R.string.raw_rec_stop else R.string.raw_rec_start),
                onClick = { if (st.recording) engine.raw.stop() else request(true) }, enabled = st.recording || (!st.running && engine.phoneHasGnss))
            if (st.recording || st.dir != null) {
                val elapsed = ((if (st.running) now else System.currentTimeMillis()) - st.startedMs) / 1000
                if (st.recording) KeyValue(stringResource(R.string.raw_elapsed), "%d:%02d".format(Locale.US, elapsed / 60, elapsed % 60), live = true,
                    valueColor = if (elapsed >= 20 * 60) SafeGreen else Color.Unspecified)
                KeyValue(stringResource(R.string.raw_epochs), "${st.rinexEpochs} · ${st.rawLines} raw", live = true)
                if (st.recording && withRtcm && s.ntrip.usable) KeyValue(stringResource(R.string.raw_rtcm),
                    "%.1f kB · %s".format(Locale.US, st.rtcmBytes / 1024.0, n.status.name.lowercase()), live = true,
                    valueColor = if (st.rtcmBytes > 0) SafeGreen else WarnAmber)
                st.dir?.let { KeyValue(stringResource(R.string.raw_folder), it.name) }
                if (!st.recording) Text(stringResource(R.string.raw_saved), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }

        Section(stringResource(R.string.raw_post)) {
            Text(stringResource(R.string.raw_post_help), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun Report(r: RawReport, running: Boolean, muted: Color) {
    Text(stringResource(verdictText(r.verdict)), fontWeight = FontWeight.SemiBold, color = verdictColor(r.verdict))
    if (r.dutyCyclingSuspected && r.verdict != RawVerdict.WAITING && r.verdict != RawVerdict.NO_RAW)
        Text(stringResource(R.string.raw_duty), style = MaterialTheme.typography.bodySmall, color = WarnAmber)
    KeyValue(stringResource(R.string.raw_epochs), "${r.epochs} · %.0f s".format(Locale.US, r.seconds), live = running)
    r.interval?.let { KeyValue(stringResource(R.string.raw_interval), "%.2f s · gaps %.0f %%".format(Locale.US, it, r.gapShare * 100), live = running) }
    KeyValue(stringResource(R.string.raw_clock), if (r.hasFullBias) pluralStringResource(R.plurals.raw_clock_ok, r.clockDiscontinuities, r.clockDiscontinuities) else stringResource(R.string.raw_clock_missing),
        live = running)
    if (r.bands.isNotEmpty()) {
        Row(Modifier.fillMaxWidth()) {
            for ((w, h) in listOf(0.34f to R.string.raw_col_signal, 0.13f to R.string.raw_col_sats, 0.17f to R.string.raw_col_code,
                0.17f to R.string.raw_col_phase, 0.19f to R.string.raw_col_cn0))
                Text(stringResource(h), Modifier.weight(w), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        for (b in r.bands) Row(Modifier.fillMaxWidth()) {
            val phaseColor = when { b.phaseOk >= 0.7 && b.slipsPerSatMin < 2 -> SafeGreen; b.phaseOk > 0.05 -> WarnAmber; else -> muted }
            Text("${sysName(b.system)} ${b.band.label.substringBefore('/')}", Modifier.weight(0.34f), style = MaterialTheme.typography.bodySmall)
            Text("${b.satsNow}", Modifier.weight(0.13f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text("%.0f%%".format(Locale.US, b.codeOk * 100), Modifier.weight(0.17f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text("%.0f%%".format(Locale.US, b.phaseOk * 100), Modifier.weight(0.17f), style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, color = phaseColor)
            Text("%.0f".format(Locale.US, b.meanCn0), Modifier.weight(0.19f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
