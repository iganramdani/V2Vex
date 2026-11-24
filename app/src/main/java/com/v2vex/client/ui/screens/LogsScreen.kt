package com.v2vex.client.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2vex.client.core.LogBuffer
import com.v2vex.client.core.LogLevel
import com.v2vex.client.ui.theme.XrayAmber
import com.v2vex.client.ui.theme.XrayCoral
import com.v2vex.client.ui.theme.XrayCyan
import com.v2vex.client.ui.theme.XrayEmerald

@Composable
fun LogsScreen(
    xrayLogLevel: String,
    extraReportHeader: String,
    onChangeXrayLogLevel: (String) -> Unit,
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val logs by LogBuffer.logs.collectAsState()

    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }

    val sources = remember(logs) {
        logs.map { it.source }.distinct().sorted()
    }

    val filtered = remember(logs, levelFilter, sourceFilter, searchQuery) {
        logs.filter { entry ->
            val matchLevel = levelFilter == null || entry.level == levelFilter
            val matchSrc = sourceFilter == null || entry.source.equals(sourceFilter, ignoreCase = true)
            val matchSearch = searchQuery.isBlank() ||
                entry.message.contains(searchQuery, ignoreCase = true) ||
                entry.source.contains(searchQuery, ignoreCase = true)
            matchLevel && matchSrc && matchSearch
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Header with LIVE indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (autoScroll) XrayEmerald else XrayAmber)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Real-Time Full Log",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "${filtered.size} / ${logs.size} lines · Xray=$xrayLogLevel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row {
                IconButton(onClick = {
                    val report = LogBuffer.buildFullBugReport(context, extraReportHeader)
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "V2Vex Real-Time Diagnostic Log")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Share V2Vex Bug Report"))
                }) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share Bug Report", tint = XrayCyan)
                }
                IconButton(onClick = {
                    LogBuffer.clear()
                    onMessage("Logs cleared")
                }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Clear Logs", tint = XrayCoral)
                }
            }
        }

        // Quick Action Row: Copy Full Bug Report + Auto-Scroll + Xray Verbosity
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val report = LogBuffer.buildFullBugReport(context, extraReportHeader)
                    clipboard.setText(AnnotatedString(report))
                    onMessage("Copied full diagnostic bug report (${report.length} chars)")
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Copy Full Bug Report", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = { autoScroll = !autoScroll },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (autoScroll) "LIVE: ON" else "PAUSED", style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Search box
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search logs (e.g. error, tun, reality, dns)...") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Xray Verbosity + Level Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("debug", "info", "warning").forEach { lvl ->
                FilterChip(
                    selected = xrayLogLevel == lvl,
                    onClick = { onChangeXrayLogLevel(lvl) },
                    label = { Text("Xray:$lvl") }
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            FilterChip(
                selected = levelFilter == null,
                onClick = { levelFilter = null },
                label = { Text("All") }
            )
            LogLevel.entries.forEach { lvl ->
                FilterChip(
                    selected = levelFilter == lvl,
                    onClick = { levelFilter = if (levelFilter == lvl) null else lvl },
                    label = { Text(lvl.tag) }
                )
            }
        }

        // Source Filter Chips
        if (sources.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = sourceFilter == null,
                    onClick = { sourceFilter = null },
                    label = { Text("All Sources") }
                )
                sources.forEach { src ->
                    FilterChip(
                        selected = sourceFilter == src,
                        onClick = { sourceFilter = if (sourceFilter == src) null else src },
                        label = { Text(src) }
                    )
                }
            }
        }

        // Real-Time Log Stream
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(filtered, key = { it.id }) { entry ->
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
}
