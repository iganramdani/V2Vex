package com.v2vex.client.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.v2vex.client.core.LogBuffer
import com.v2vex.client.core.LogLevel
import com.v2vex.client.model.RuleTarget
import com.v2vex.client.ui.theme.XrayAmber
import com.v2vex.client.ui.theme.XrayCoral
import com.v2vex.client.ui.theme.XrayEmerald
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogTab(val label: String) {
    ALL("All Logs"),
    SYSTEM("System (Pinned)"),
    FIREWALL("Live Connections")
}

@Composable
fun LogsScreen(
    xrayLogLevel: String,
    extraReportHeader: String,
    onChangeXrayLogLevel: (String) -> Unit,
    onQuickDomainRule: (domain: String, target: RuleTarget) -> Unit,
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val logs by LogBuffer.logs.collectAsState()
    val connections by LogBuffer.recentConnections.collectAsState()

    var activeTab by remember { mutableStateOf(LogTab.ALL) }
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }

    // SAF .txt file saver
    val saveTxtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val report = LogBuffer.buildFullBugReport(context, extraReportHeader)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(report.toByteArray(Charsets.UTF_8))
            }
            onMessage("Saved diagnostic log (.txt) successfully")
        }.onFailure { err ->
            onMessage("Failed to save .txt: ${err.message}")
        }
    }

    fun shareTxtFile() {
        runCatching {
            val txtFile = LogBuffer.exportTxtReportFile(context, extraReportHeader)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                txtFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "logV2Vex.txt")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share logV2Vex.txt"))
        }.onFailure { err ->
            onMessage("Share .txt failed: ${err.message}")
        }
    }

    val filtered = remember(logs, activeTab, levelFilter, searchQuery) {
        logs.filter { entry ->
            val matchTab = if (activeTab == LogTab.SYSTEM) entry.isSystem else true
            val matchLevel = levelFilter == null || entry.level == levelFilter
            val matchSearch = searchQuery.isBlank() ||
                entry.message.contains(searchQuery, ignoreCase = true) ||
                entry.source.contains(searchQuery, ignoreCase = true)
            matchTab && matchLevel && matchSearch
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(filtered.size, autoScroll, activeTab) {
        if (activeTab != LogTab.FIREWALL && autoScroll && filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // Minimalist Header
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
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (autoScroll) XrayEmerald else XrayAmber)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Diagnostic Log (.txt)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "${filtered.size} lines · Xray:$xrayLogLevel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row {
                IconButton(onClick = { shareTxtFile() }) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share logV2Vex.txt")
                }
                IconButton(onClick = {
                    LogBuffer.clear()
                    onMessage("Logs cleared")
                }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Clear Logs", tint = XrayCoral)
                }
            }
        }

        // Primary .txt Export Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                    saveTxtLauncher.launch("logV2Vex_$stamp.txt")
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Save .txt File", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = { shareTxtFile() },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Share .txt", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = { autoScroll = !autoScroll },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (autoScroll) "LIVE" else "PAUSE", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Mode Tabs: All | System (Pinned) | Live Connections Firewall
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LogTab.entries.forEach { tab ->
                FilterChip(
                    selected = activeTab == tab,
                    onClick = { activeTab = tab },
                    label = {
                        val suffix = if (tab == LogTab.FIREWALL) " (${connections.size})" else ""
                        Text(tab.label + suffix)
                    }
                )
            }
        }

        if (activeTab == LogTab.FIREWALL) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxSize()
            ) {
                if (connections.isEmpty()) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(20.dp)) {
                        Text(
                            text = "Set Xray verbosity to 'info' or 'debug' while connected to inspect live domains & apply 1-tap firewall rules.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(connections, key = { it.id }) { conn ->
                            val cleanHost = conn.destination.substringBefore(" ").substringBefore(":")
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = conn.destination,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${conn.timestamp} · ${conn.network} -> ${conn.outbound}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (cleanHost.any { it.isLetter() }) {
                                        TextButton(onClick = { onQuickDomainRule(cleanHost, RuleTarget.DIRECT) }) {
                                            Text("Direct", style = MaterialTheme.typography.labelSmall)
                                        }
                                        TextButton(onClick = { onQuickDomainRule(cleanHost, RuleTarget.BLOCK) }) {
                                            Text("Block", style = MaterialTheme.typography.labelSmall, color = XrayCoral)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter log stream...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("warning", "info", "debug").forEach { lvl ->
                    FilterChip(
                        selected = xrayLogLevel == lvl,
                        onClick = { onChangeXrayLogLevel(lvl) },
                        label = { Text("Xray:$lvl") }
                    )
                }
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

            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
                            LogLevel.INFO -> if (entry.isSystem) XrayEmerald else MaterialTheme.colorScheme.onSurface
                            LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
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
}
