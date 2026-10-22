package com.rfsat.pocketgnss.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tools > App log: what the app did and what went wrong, newest first, to share for debugging. */
@Composable
fun AppLogTool() {
    val context = LocalContext.current
    val entries by AppLog.entries.collectAsState()
    var minLevel by rememberSaveable { mutableStateOf(2) }      // 0 errors, 1 + warnings, 2 everything
    var confirmClear by remember { mutableStateOf(false) }
    val time = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.US) }
    val shown = entries.asReversed().filter { it.level.ordinal <= minLevel }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.applog_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceRow(stringResource(R.string.applog_show), listOf(0 to stringResource(R.string.applog_errors), 1 to stringResource(R.string.applog_warnings),
            2 to stringResource(R.string.applog_all)), minLevel) { minLevel = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(onClick = {
                val name = "pocketgnss_log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
                share(context, export(context, name, AppLog.text()), "text/plain")
            }, compact = true, enabled = entries.isNotEmpty()) { Text(stringResource(R.string.applog_share)) }
            ActionButton(onClick = { confirmClear = true }, compact = true, enabled = entries.isNotEmpty()) { Text(stringResource(R.string.applog_clear)) }
        }
        if (shown.isEmpty()) Text(stringResource(R.string.applog_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(shown) { e ->
            val color = when (e.level) { AppLog.Level.ERROR -> AlarmRed; AppLog.Level.WARN -> WarnAmber; AppLog.Level.INFO -> MaterialTheme.colorScheme.onSurface }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("${time.format(Date(e.timeMs))}  ${e.level.letter}  ${e.tag}", style = MaterialTheme.typography.labelSmall, color = color)
                Text(e.message, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), fontFamily = if (e.message.contains('\n')) FontFamily.Monospace else null,
                    maxLines = 30)
            }
            HorizontalDivider()
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        confirmButton = { TextButton(onClick = { AppLog.clear(); confirmClear = false }) { Text(stringResource(R.string.applog_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        text = { Text(stringResource(R.string.applog_clear_q)) })
}
