package com.rfsat.pocketgnss.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.data.AppSettings
import com.rfsat.pocketgnss.data.HeightModel
import com.rfsat.pocketgnss.data.HeightModels
import com.rfsat.pocketgnss.data.HeightRef

fun HeightModel.label(): String = "$area · $system"

private fun sizeLabel(bytes: Long): String = if (bytes >= 1_000_000) "%.1f MB".format(java.util.Locale.US, bytes / 1e6) else "%.0f kB".format(java.util.Locale.US, bytes / 1e3)

/** Setup > Display: the national height model (choice, suggestion for the current position, download, credit). */
@Composable
fun HeightModelSection(engine: GnssEngine) {
    val s by engine.store.settings.collectAsState()
    val dl by engine.geoidDownload.collectAsState()
    val fix by engine.fix.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val upd: ((AppSettings) -> AppSettings) -> Unit = { engine.store.update(it) }
    val suggested = fix?.let { HeightModels.suggest(it.latitude, it.longitude).firstOrNull() }
    val chosen = HeightModels.byId(s.heightModel)
    Picker(stringResource(R.string.height_model), HeightModels.all, chosen ?: suggested ?: HeightModels.all.first(), { it.label() },
        { m -> upd { it.copy(heightModel = m.id) } }, Modifier.fillMaxWidth())
    if (chosen == null && suggested == null) Text(stringResource(R.string.height_model_choose), style = MaterialTheme.typography.bodySmall, color = WarnAmber)
    if (suggested != null && suggested != chosen)
        ActionButton(onClick = { upd { it.copy(heightModel = suggested.id) } }, compact = true) { Text(stringResource(R.string.height_model_use, suggested.label())) }
    val m = chosen ?: suggested
    if (m != null) {
        if (chosen == null) LaunchedSelect(engine, m)
        val installed = dl.let { engine.geoid.installed(m) }
        when {
            dl.running && dl.id == m.id -> {
                Text(stringResource(R.string.height_model_downloading, (dl.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = muted)
                LinearProgressIndicator(progress = { dl.progress }, modifier = Modifier.fillMaxWidth())
            }
            installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.height_model_ready, sizeLabel(m.sizeBytes)), style = MaterialTheme.typography.bodySmall, color = SafeGreen,
                    modifier = Modifier.weight(1f))
                ActionButton(onClick = { engine.deleteHeightModel(m) }, compact = true) { Text(stringResource(R.string.height_model_delete)) }
            }
            else -> ActionButton(onClick = { engine.downloadHeightModel(m) }, compact = true, enabled = !dl.running) {
                Text(stringResource(R.string.height_model_download, sizeLabel(m.sizeBytes)))
            }
        }
        if (dl.error != null && dl.id == m.id) Text(stringResource(R.string.height_model_failed, dl.error ?: ""), style = MaterialTheme.typography.bodySmall, color = WarnAmber)
        Text(stringResource(R.string.height_model_credit, m.credit, m.licence, m.epsg), style = MaterialTheme.typography.bodySmall, color = muted)
        if (m.global) Text(stringResource(R.string.height_model_world), style = MaterialTheme.typography.bodySmall, color = muted)
    }
    Text(stringResource(R.string.height_model_help), style = MaterialTheme.typography.bodySmall, color = muted)
}

/** Stores the suggested model as the choice once, so the download and the heights refer to it. */
@Composable
private fun LaunchedSelect(engine: GnssEngine, m: HeightModel) {
    androidx.compose.runtime.LaunchedEffect(m.id) { engine.store.update { if (it.heightModel.isEmpty()) it.copy(heightModel = m.id) else it } }
}

/** Live > Position: the height in the chosen reference (sea level, ellipsoid or national height system). */
@Composable
fun HeightRow(engine: GnssEngine, s: AppSettings, lat: Double, lon: Double, hEll: Double?, hMsl: Double?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (s.heightRef) {
        HeightRef.MSL -> KeyValue(stringResource(R.string.live_height_msl), formatHeight(hMsl), live = true)
        HeightRef.ELLIPSOID -> KeyValue(stringResource(R.string.live_height_ell), formatHeight(hEll), live = true)
        HeightRef.MODEL -> {
            val dl by engine.geoidDownload.collectAsState()
            val m = HeightModels.byId(s.heightModel)
            val installed = m != null && dl.let { engine.geoid.installed(m) }
            val h = if (installed) engine.geoid.height(m!!, lat, lon, hEll) else null
            KeyValue(if (m != null) stringResource(R.string.height_in, m.system) else stringResource(R.string.disp_height), formatHeight(h), live = true)
            val note = when {
                m == null -> stringResource(R.string.height_model_none)
                !installed -> stringResource(R.string.height_model_missing)
                h == null && hEll != null -> stringResource(R.string.height_model_outside, m.label())
                else -> null
            }
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = WarnAmber)
            else KeyValue(stringResource(R.string.live_height_ell), formatHeight(hEll), live = true, valueColor = muted)
        }
    }
}

/** The height of a position in the national height system, when one is chosen and downloaded (survey results). */
@Composable
fun NationalHeightRow(engine: GnssEngine, lat: Double, lon: Double, hEll: Double?) {
    val dl by engine.geoidDownload.collectAsState()
    val m = dl.let { engine.heightModel() } ?: return
    KeyValue(stringResource(R.string.height_in, m.system), formatHeight(engine.geoid.height(m, lat, lon, hEll)), live = true)
}
