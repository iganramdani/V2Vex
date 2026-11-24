package com.v2vex.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.v2vex.client.core.LogBuffer
import com.v2vex.client.core.LogLevel
import com.v2vex.client.ui.theme.XrayAmber
import com.v2vex.client.ui.theme.XrayCoral
import com.v2vex.client.ui.theme.XrayCyan
import com.v2vex.client.ui.theme.XrayEmerald

@Composable
fun LogsScreen(
    onBack: () -> Unit,
    onCopied: () -> Unit
) {
    val logs by LogBuffer.logs.collectAsState()
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    val clipboard = LocalClipboardManager.current

    val filtered = remember(logs, levelFilter) {
        if (levelFilter == null) logs.reversed()
        else logs.filter { it.level == levelFilter }.reversed()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Text("Live Engine Logs", style = MaterialTheme.typography.titleMedium)
            }
            Row {
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(LogBuffer.dumpText()))
                    onCopied()
                }) {
                    Icon(Icons.Rounded.Share, contentDescription = "Copy Logs")
                }
                IconButton(onClick = { LogBuffer.clear() }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Clear Logs")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = levelFilter == null,
                onClick = { levelFilter = null },
                label = { Text("All (${logs.size})") }
            )
            LogLevel.entries.forEach { lvl ->
                FilterChip(
                    selected = levelFilter == lvl,
                    onClick = { levelFilter = if (levelFilter == lvl) null else lvl },
                    label = { Text(lvl.tag) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(filtered) { entry ->
                val color = when (entry.level) {
                    LogLevel.ERROR -> XrayCoral
                    LogLevel.WARN -> XrayAmber
                    LogLevel.INFO -> XrayEmerald
                    LogLevel.DEBUG -> XrayCyan
                }
                Text(
                    text = "${entry.timestamp} [${entry.level.tag}][${entry.source}] ${entry.message}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = color
                )
            }
        }
    }
}
