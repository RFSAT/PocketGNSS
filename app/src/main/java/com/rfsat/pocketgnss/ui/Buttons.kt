package com.rfsat.pocketgnss.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * One button language for all RFSAT apps (same as PocketSpectro):
 *  - every button, chip and switch has a clearly visible border, also when disabled;
 *  - two-state controls are green when ON and red when OFF (white text, darker edge of the same colour);
 *  - plain actions are neutral (outlined), with the main action of a screen filled in the theme colour.
 */

val OnGreen = Color(0xFF2E7D32)
val OnGreenEdge = Color(0xFF1B5E20)
val OffRed = Color(0xFFC62828)
val OffRedEdge = Color(0xFF7F0000)

@Composable
private fun disabledEdge() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

/** Two-state button showing its state: green with [label] while ON, red while OFF. Tapping toggles. */
@Composable
fun ToggleButton(
    on: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    compact: Boolean = false,
    /** Narrow side padding at full height, for several buttons sharing one row. */
    narrow: Boolean = false,
) {
    val bg = if (on) OnGreen else OffRed
    val edge = if (on) OnGreenEdge else OffRedEdge
    Button(
        onClick = onClick, modifier = if (compact) modifier.heightIn(min = 0.dp).height(30.dp) else modifier, enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = bg, contentColor = Color.White,
            disabledContainerColor = bg.copy(alpha = 0.40f), disabledContentColor = Color.White.copy(alpha = 0.85f),
        ),
        border = BorderStroke(2.dp, if (enabled) edge else edge.copy(alpha = 0.6f)),
        contentPadding = when {
            compact -> PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            narrow -> PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            else -> ButtonDefaults.ContentPadding
        },
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label, fontWeight = FontWeight.SemiBold, style = if (compact) MaterialTheme.typography.labelMedium else LocalTextStyle.current,
            maxLines = 1, softWrap = false)
    }
}

/** Plain action. [emphasis] fills it in the theme colour (the main action on a screen). Always bordered. */
@Composable
fun ActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasis: Boolean = false,
    compact: Boolean = false,
    narrow: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick, modifier = modifier, enabled = enabled,
        border = BorderStroke(1.5.dp, when {
            !enabled -> disabledEdge()
            emphasis -> cs.primary
            else -> cs.onSurfaceVariant
        }),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (emphasis) cs.primary else Color.Transparent,
            contentColor = if (emphasis) cs.onPrimary else cs.primary,
            disabledContainerColor = cs.onSurface.copy(alpha = 0.06f),
            disabledContentColor = cs.onSurface.copy(alpha = 0.50f),
        ),
        contentPadding = when {
            compact -> PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            narrow -> PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            else -> ButtonDefaults.ContentPadding
        },
        content = content,
    )
}

/** Single-choice chip: the chosen option is green; the others are neutral. Always bordered. */
@Composable
fun ChoiceChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    container: Color = Color.Transparent,
) {
    val cs = MaterialTheme.colorScheme
    FilterChip(
        selected = selected, onClick = onClick, enabled = enabled,
        label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = container, labelColor = cs.onSurface,
            selectedContainerColor = OnGreen, selectedLabelColor = Color.White,
            disabledContainerColor = cs.onSurface.copy(alpha = 0.06f), disabledLabelColor = cs.onSurface.copy(alpha = 0.5f),
            disabledSelectedContainerColor = OnGreen.copy(alpha = 0.4f),
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled, selected = selected,
            borderColor = cs.onSurfaceVariant, selectedBorderColor = OnGreenEdge,
            disabledBorderColor = disabledEdge(), disabledSelectedBorderColor = OnGreenEdge.copy(alpha = 0.6f),
            borderWidth = 1.5.dp, selectedBorderWidth = 2.dp,
        ),
    )
}

/** Switch in the same colours: green track when ON, red when OFF, bordered in every state. */
@Composable
fun OnOffSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked, onCheckedChange = onChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = OnGreen, checkedBorderColor = OnGreenEdge,
            uncheckedThumbColor = Color.White, uncheckedTrackColor = OffRed, uncheckedBorderColor = OffRedEdge,
            disabledCheckedThumbColor = Color.White, disabledCheckedTrackColor = OnGreen.copy(alpha = 0.4f),
            disabledCheckedBorderColor = OnGreenEdge.copy(alpha = 0.6f),
            disabledUncheckedThumbColor = Color.White, disabledUncheckedTrackColor = OffRed.copy(alpha = 0.4f),
            disabledUncheckedBorderColor = OffRedEdge.copy(alpha = 0.6f),
        ),
    )
}
