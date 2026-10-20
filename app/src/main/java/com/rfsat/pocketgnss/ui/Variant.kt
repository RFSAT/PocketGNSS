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
}

/** How that version's own NTRIP client reaches the RTK service. */
@StringRes fun variantRoute(v: ReceiverVariant): Int = when (v) {
    ReceiverVariant.LTE -> R.string.route_lte
    ReceiverVariant.WIFI -> R.string.route_wifi
    ReceiverVariant.UNSET -> R.string.route_unset
}

/** What to check when the receiver gets no corrections. */
@StringRes fun variantTroubleshoot(v: ReceiverVariant): Int = when (v) {
    ReceiverVariant.LTE -> R.string.trouble_lte
    ReceiverVariant.WIFI -> R.string.trouble_wifi
    ReceiverVariant.UNSET -> R.string.trouble_unset
}

/** Chips to choose the hardware version, with the explanation of the chosen one. */
@Composable
fun VariantPicker(engine: GnssEngine, explain: Boolean = true) {
    val s by engine.store.settings.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in listOf(ReceiverVariant.LTE, ReceiverVariant.WIFI))
                ChoiceChip(s.receiverVariant == v, stringResource(variantName(v)), { engine.store.update { it.copy(receiverVariant = v) } })
        }
        if (explain) Text(stringResource(if (s.receiverVariant == ReceiverVariant.UNSET) R.string.variant_help else variantRoute(s.receiverVariant)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
