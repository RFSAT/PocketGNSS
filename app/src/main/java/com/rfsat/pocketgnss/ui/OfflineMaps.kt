package com.rfsat.pocketgnss.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.ui.map.OwnMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Setup > Offline maps: how many viewed tiles are kept, and the user's own MBTiles map. */
@Composable
fun OfflineMapsSection(engine: GnssEngine) {
    val context = LocalContext.current
    val s by engine.store.settings.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val bad = stringResource(R.string.offline_bad)
    val cleared = stringResource(R.string.offline_cleared)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val path = withContext(Dispatchers.IO) { OwnMap.import(context, uri) }
            busy = false
            if (path == null) engine.toast(bad) else engine.store.update { it.copy(ownMap = path, ownMapOn = true) }
        }
    }
    Section(stringResource(R.string.offline_title)) {
        Text(stringResource(R.string.offline_help), style = MaterialTheme.typography.bodySmall, color = muted)
        ChoiceRow(stringResource(R.string.offline_cache), listOf(50 to "50", 300 to "300", 1000 to "1000", 2000 to "2000"), s.tileCacheMb) { v ->
            engine.store.update { it.copy(tileCacheMb = v) }
        }
        ActionButton(onClick = {
            try {
                org.maplibre.android.MapLibre.getInstance(context)
                org.maplibre.android.offline.OfflineManager.getInstance(context).clearAmbientCache(object : org.maplibre.android.offline.OfflineManager.FileSourceCallback {
                    override fun onSuccess() { engine.toast(cleared) }
                    override fun onError(message: String) {}
                })
            } catch (_: Throwable) {}
        }, compact = true) { Text(stringResource(R.string.offline_clear)) }
        val own = s.ownMap.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
        val info = remember(s.ownMap) { own?.let { OwnMap.info(it) } }
        if (own != null && info != null) {
            KeyValue(stringResource(R.string.offline_own), stringResource(R.string.offline_own_value, info.name, info.minZoom, info.maxZoom,
                "%.0f MB".format(Locale.US, own.length() / 1e6)))
            SwitchRow(stringResource(R.string.offline_show), s.ownMapOn, { v -> engine.store.update { it.copy(ownMapOn = v) } })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(onClick = { pick.launch(arrayOf("*/*")) }, compact = true, enabled = !busy) {
                Text(stringResource(if (busy) R.string.offline_loading else if (own != null) R.string.offline_replace else R.string.offline_load))
            }
            if (own != null) ActionButton(onClick = { OwnMap.remove(context); engine.store.update { it.copy(ownMap = "") } }, compact = true) {
                Text(stringResource(R.string.offline_remove))
            }
        }
        Text(stringResource(R.string.offline_own_help), style = MaterialTheme.typography.bodySmall, color = muted)
    }
}
