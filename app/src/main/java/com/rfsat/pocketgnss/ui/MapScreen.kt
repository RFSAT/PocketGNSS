package com.rfsat.pocketgnss.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.data.SurveyPoint
import com.rfsat.pocketgnss.protocol.Geo
import com.rfsat.pocketgnss.ui.map.MapLayer
import com.rfsat.pocketgnss.ui.map.RtkMap

@Composable
fun MapScreen(engine: GnssEngine) {
    val s by engine.store.settings.collectAsState()
    val fix by engine.fix.collectAsState()
    val track by engine.track.collectAsState()
    val points by engine.points.collectAsState()
    val fields by engine.fields.collectAsState()
    val cap by engine.fieldCapture.state.collectAsState()
    val guide by engine.guidance.state.collectAsState()
    val guideLines = guide.line?.let { l -> guide.solution?.let { com.rfsat.pocketgnss.core.Guidance.mapLines(l, it) } } ?: emptyList()
    val ntrip by engine.ntripClient.ntrip.collectAsState()
    var fitKey by remember { mutableIntStateOf(0) }
    var recenterKey by remember { mutableIntStateOf(0) }
    var follow by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SurveyPoint?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(s.tileCacheMb) {
        try {
            org.maplibre.android.MapLibre.getInstance(context)
            org.maplibre.android.offline.OfflineManager.getInstance(context).setMaximumAmbientCacheSize(s.tileCacheMb * 1_000_000L,
                object : org.maplibre.android.offline.OfflineManager.FileSourceCallback { override fun onSuccess() {}; override fun onError(message: String) {} })
        } catch (_: Throwable) {}
    }

    Box(Modifier.fillMaxSize()) {
        RtkMap(s.mapLayer, track, points, fix, ntrip.base?.geodetic, selected, follow, fitKey, recenterKey,
            onPointTap = { selected = it }, modifier = Modifier.fillMaxSize(), fields = fields, capture = cap.vertices, guide = guideLines,
            ownMap = s.ownMap.takeIf { s.ownMapOn && it.isNotBlank() && java.io.File(it).isFile })

        Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in MapLayer.entries) ChoiceChip(s.mapLayer == l, stringResource(l.label), { engine.store.update { it.copy(mapLayer = l) } },
                container = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            if (s.ownMap.isNotBlank()) ChoiceChip(s.ownMapOn, stringResource(R.string.map_own), { engine.store.update { it.copy(ownMapOn = !it.ownMapOn) } },
                container = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallFloatingActionButton(onClick = { follow = false; recenterKey++ }) { Icon(Icons.Filled.MyLocation, stringResource(R.string.map_recenter)) }
            SmallFloatingActionButton(onClick = { follow = !follow },
                containerColor = if (follow) OnGreen else MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Filled.Navigation, stringResource(R.string.map_follow)) }
            SmallFloatingActionButton(onClick = { follow = false; fitKey++ }) { Icon(Icons.Filled.FitScreen, stringResource(R.string.map_fit)) }
            SmallFloatingActionButton(onClick = { engine.markPoint(engine.nextPointName())?.let { engine.toast(it.name) } }) {
                Icon(Icons.Filled.AddLocationAlt, stringResource(R.string.live_mark))
            }
            SmallFloatingActionButton(onClick = { engine.clearTrack() }) { Icon(Icons.Filled.DeleteSweep, stringResource(R.string.map_clear_track)) }
        }

        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val f = fix
                if (f == null) Text(stringResource(R.string.top_no_fix))
                else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("● ", color = fixColor(f.quality))
                        FitText(stringResource(fixLabel(f.quality)) + "  " + formatAccuracy(f.horizontalAccuracy) + "  ·  " +
                            formatLat(f.latitude, s.coordFormat) + "  " + formatLon(f.longitude, s.coordFormat), fontWeight = FontWeight.SemiBold)
                    }
                }
                val p = selected
                if (p != null) {
                    KeyValue(p.name, if (f != null) stringResource(R.string.map_to_point, formatDistance(Geo.distance(f.latitude, f.longitude, p.latitude, p.longitude)),
                        Geo.bearing(f.latitude, f.longitude, p.latitude, p.longitude)) else formatLat(p.latitude, s.coordFormat), live = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(onClick = { selected = null }, compact = true) { Text(stringResource(R.string.action_close)) }
                    }
                } else if (points.isNotEmpty()) Text(stringResource(R.string.map_tap_point), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
