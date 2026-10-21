package com.rfsat.pocketgnss.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.data.AppSettings
import com.rfsat.pocketgnss.protocol.Grid
import com.rfsat.pocketgnss.protocol.GridSystem
import java.util.Locale

fun formatGridValue(m: Double): String = "%.3f m".format(Locale.US, m)

/** Grid (plane) coordinates of a position in the chosen [AppSettings.gridSystem]; nothing when none is chosen. */
@Composable
fun GridRows(s: AppSettings, lat: Double, lon: Double, h: Double?) {
    if (s.gridSystem == GridSystem.NONE) return
    val g = Grid.of(s.gridSystem, lat, lon, h, s.utmZone) ?: return
    KeyValue(stringResource(R.string.grid_system), g.label, live = true)
    KeyValue(stringResource(R.string.grid_easting), formatGridValue(g.easting), live = true)
    KeyValue(stringResource(R.string.grid_northing), formatGridValue(g.northing), live = true)
    val warn = when {
        s.gridSystem.area?.contains(lat, lon) == false -> stringResource(R.string.grid_outside, s.gridSystem.label)
        s.gridSystem.metreLevel -> stringResource(R.string.grid_metre, s.gridSystem.label)
        else -> null
    }
    if (warn != null) Text(warn, style = MaterialTheme.typography.bodySmall, color = WarnAmber)
}
