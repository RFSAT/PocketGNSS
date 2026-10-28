package com.rfsat.pocketgnss.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.rfsat.pocketgnss.R
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/**
 * Numeric text field. What the user types is committed on Done, on leaving the field, after a short pause in typing and
 * when the screen closes; only real edits are committed (a value changed elsewhere, e.g. by choosing another caster
 * profile, replaces the text instead of being overwritten by it).
 */
@Composable
fun NumberField(
    label: String,
    value: Double,
    onCommit: (Double) -> Unit,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
    min: Double = Double.NEGATIVE_INFINITY,
    max: Double = Double.POSITIVE_INFINITY,
    suffix: String = "",
) {
    fun show(v: Double) = if (decimals == 0) v.toLong().toString() else "%.${decimals}f".format(java.util.Locale.US, v)
    var text by remember { mutableStateOf(show(value)) }
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dirty) text = show(value) }
    val focus = LocalFocusManager.current
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = parsed != null && parsed in min..max
    val latest by rememberUpdatedState({
        if (dirty) { dirty = false; if (valid) onCommit(parsed!!) else text = show(value) }
    })
    LaunchedEffect(text, dirty) { if (dirty) { delay(COMMIT_PAUSE_MS); latest() } }
    DisposableEffect(Unit) { onDispose { latest() } }
    OutlinedTextField(
        value = text, onValueChange = { text = it; dirty = true }, label = { Text(label) }, singleLine = true, isError = !valid,
        suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimals == 0 && min >= 0) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { latest(); focus.clearFocus() }),
        modifier = modifier.onFocusChanged { if (!it.isFocused) latest() },
    )
}

/** Pause in typing after which a text or number field commits what was typed (ms). */
const val COMMIT_PAUSE_MS = 1500L

/**
 * Text field (host names, mountpoints, user names, passwords...). Committed like [NumberField]: on Done, on leaving the
 * field, after a short pause in typing and when the screen closes, and only when the user edited it. Passwords are
 * hidden behind dots with an eye button to show them, and are not trimmed.
 */
@Composable
fun TextSetting(label: String, value: String, onCommit: (String) -> Unit, modifier: Modifier = Modifier, password: Boolean = false,
                keyboard: KeyboardType = KeyboardType.Text) {
    var text by remember { mutableStateOf(value) }
    var dirty by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dirty) text = value }
    val focus = LocalFocusManager.current
    val latest by rememberUpdatedState({ if (dirty) { dirty = false; onCommit(if (password) text else text.trim()) } })
    LaunchedEffect(text, dirty) { if (dirty) { delay(COMMIT_PAUSE_MS); latest() } }
    DisposableEffect(Unit) { onDispose { latest() } }
    OutlinedTextField(
        value = text, onValueChange = { text = it; dirty = true }, label = { Text(label) }, singleLine = true,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { latest(); focus.clearFocus() }),
        trailingIcon = if (password) ({
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (visible) R.string.password_hide else R.string.password_show))
            }
        }) else null,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) latest() },
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, detail: String? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OnOffSwitch(checked, onChange, enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Picker(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = text(selected), onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (o in options) DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); open = false })
        }
    }
}

/** Key (left column, muted) and value (right column); live = true keeps a changing value on one line so rows below never jump. */
@Composable
fun KeyValue(key: String, value: String, live: Boolean = false, valueColor: Color = Color.Unspecified) {
    val keyWidth = LocalKeyWidth.current
    Row(Modifier.fillMaxWidth()) {
        val keyMod = if (keyWidth != null) Modifier.width(keyWidth).padding(end = 8.dp) else Modifier.weight(0.42f)
        val valMod = if (keyWidth != null) Modifier.weight(1f) else Modifier.weight(0.58f)
        Text(key, keyMod, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // label muted on the left, value in the normal text colour and a heavier weight on the right
        val vc = if (valueColor != Color.Unspecified) valueColor else MaterialTheme.colorScheme.onSurface
        if (live) FitText(value, valMod, MaterialTheme.typography.bodySmall, color = vc, fontWeight = FontWeight.Medium)
        else Text(value, valMod, style = MaterialTheme.typography.bodySmall, color = vc, fontWeight = FontWeight.Medium)
    }
}

/**
 * Width of the label column of [KeyValue] rows: a fixed width so values start right after their labels and use the
 * rest of the line. A screen can provide null to split rows 42 / 58 % instead.
 */
val LocalKeyWidth = androidx.compose.runtime.compositionLocalOf<androidx.compose.ui.unit.Dp?> { KEY_WIDTH }

/** Label column width used app-wide: fits labels such as "Receiver version"; longer ones wrap. */
val KEY_WIDTH = 112.dp

/** A labelled single-choice row: the label in the label column, the options as chips beside it (one line). */
@Composable
fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(LocalKeyWidth.current ?: KEY_WIDTH).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
        // Chips that do not fit go to the next line whole, rather than squeezing their text (long translations).
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((v, text) in options) ChoiceChip(selected == v, text, { onSelect(v) })
        }
    }
}

/** Single-line text for live values: shrinks (down to 60 %) instead of wrapping. */
@Composable
fun FitText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current,
            color: Color = Color.Unspecified, fontWeight: FontWeight? = null, fontSize: TextUnit = TextUnit.Unspecified,
            textAlign: TextAlign? = null) {
    val base = style.merge(TextStyle(fontWeight = fontWeight, fontSize = fontSize, textAlign = textAlign ?: TextAlign.Unspecified))
    val c = if (color != Color.Unspecified) color else base.color.takeOrElse { LocalContentColor.current }
    val max = if (base.fontSize.isSpecified) base.fontSize else 14.sp
    BasicText(text, modifier, style = base.copy(color = c), maxLines = 1, softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = max * 0.6f, maxFontSize = max, stepSize = 0.5.sp))
}

/** Keeps the space of content that comes and goes with the data, drawing it only when shown. */
@Composable
fun Reserve(show: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.alpha(if (show) 1f else 0f)) { content() }
}
