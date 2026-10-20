package com.rfsat.pocketgnss.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

/** Numeric text field that validates and commits on Done or focus loss. */
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
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused) text = show(value) }
    val focus = LocalFocusManager.current
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = parsed != null && parsed in min..max
    fun commit() { if (valid) onCommit(parsed!!) else text = show(value) }
    OutlinedTextField(
        value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true, isError = !valid,
        suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimals == 0 && min >= 0) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
        modifier = modifier.onFocusChanged { if (focused && !it.isFocused) commit(); focused = it.isFocused },
    )
}

/** Text field that commits on Done or focus loss (host names, mountpoints, user names...). */
@Composable
fun TextSetting(label: String, value: String, onCommit: (String) -> Unit, modifier: Modifier = Modifier, password: Boolean = false,
                keyboard: KeyboardType = KeyboardType.Text) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused) text = value }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit(text.trim()); focus.clearFocus() }),
        modifier = modifier.fillMaxWidth().onFocusChanged { if (focused && !it.isFocused) onCommit(text.trim()); focused = it.isFocused },
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

/** Key and value; live = true keeps a changing value on one line so rows below never jump. */
@Composable
fun KeyValue(key: String, value: String, live: Boolean = false, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, Modifier.weight(0.42f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (live) FitText(value, Modifier.weight(0.58f), MaterialTheme.typography.bodySmall, color = valueColor)
        else Text(value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodySmall, color = valueColor)
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
