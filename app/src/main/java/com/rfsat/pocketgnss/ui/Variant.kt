package com.rfsat.pocketgnss.ui

import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssEngine
import com.rfsat.pocketgnss.link.ReceiverVariant

/** Name of a receiver hardware version. */
@StringRes fun variantName(v: ReceiverVariant): Int = when (v) {
    ReceiverVariant.LTE -> R.string.variant_lte
    ReceiverVariant.WIFI -> R.string.variant_wifi
    ReceiverVariant.UNSET -> R.string.variant_unset
    ReceiverVariant.PICORTK -> R.string.variant_picortk
    ReceiverVariant.LG290P_C6 -> R.string.variant_c6
    ReceiverVariant.POSTCARDRTK -> R.string.variant_postcard
    ReceiverVariant.RTK_EVERYWHERE -> R.string.variant_rtke
    ReceiverVariant.LG290P_WIRED -> R.string.variant_wired
}

/** How that version's own NTRIP client reaches the RTK service. */
@StringRes fun variantRoute(v: ReceiverVariant): Int = when (v) {
    ReceiverVariant.LTE -> R.string.route_lte
    ReceiverVariant.WIFI -> R.string.route_wifi
    ReceiverVariant.UNSET -> R.string.route_unset
    ReceiverVariant.PICORTK -> R.string.route_picortk
    ReceiverVariant.LG290P_C6 -> R.string.route_c6
    ReceiverVariant.POSTCARDRTK -> R.string.route_postcard
    ReceiverVariant.RTK_EVERYWHERE -> R.string.route_rtke
    ReceiverVariant.LG290P_WIRED -> R.string.route_wired
}

/** What to check when the receiver gets no corrections (matching Part U of the master documentation for the rovers). */
@StringRes fun variantTroubleshoot(v: ReceiverVariant): Int = when (v) {
    ReceiverVariant.LTE -> R.string.trouble_lte
    ReceiverVariant.WIFI -> R.string.trouble_wifi
    ReceiverVariant.UNSET -> R.string.trouble_unset
    ReceiverVariant.PICORTK -> R.string.trouble_picortk
    ReceiverVariant.LG290P_C6 -> R.string.trouble_c6
    ReceiverVariant.POSTCARDRTK -> R.string.trouble_postcard
    ReceiverVariant.RTK_EVERYWHERE -> R.string.trouble_rtke
    ReceiverVariant.LG290P_WIRED -> R.string.trouble_wired
}

/** Chips to choose the receiver: RFSAT AgriBIT (u-blox) or one of RFSAT's LG290P rovers, with the chosen one explained. */
@Composable
fun VariantPicker(engine: GnssEngine, explain: Boolean = true, rovers: Boolean = true) {
    val s by engine.store.settings.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.variant_group_agribit), style = MaterialTheme.typography.labelMedium, color = muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in ReceiverVariant.AGRIBIT)
                ChoiceChip(s.receiverVariant == v, stringResource(variantName(v)), { engine.store.update { it.copy(receiverVariant = v) } })
        }
        if (rovers) {
            Text(stringResource(R.string.variant_group_rovers), style = MaterialTheme.typography.labelMedium, color = muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (v in ReceiverVariant.ROVERS)
                    ChoiceChip(s.receiverVariant == v, stringResource(variantName(v)), { engine.store.update { it.copy(receiverVariant = v) } })
            }
        }
        if (explain) Text(stringResource(if (s.receiverVariant == ReceiverVariant.UNSET) R.string.variant_help else variantRoute(s.receiverVariant)),
            style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

/** Opens Android's hotspot (tethering) settings, falling back to the wireless settings. */
@Composable
fun HotspotButton() {
    val context = LocalContext.current
    ActionButton(onClick = {
        val tries = listOf(Intent("android.settings.TETHER_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
        for (i in tries) try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); break } catch (_: Exception) {}
    }, compact = true) { Text(stringResource(R.string.hotspot_open)) }
}
