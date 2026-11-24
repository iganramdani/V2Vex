package com.v2vex.client.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2vex.client.model.SpeedTestPhase
import com.v2vex.client.model.SpeedTestProgress
import com.v2vex.client.model.SpeedTestRecord
import com.v2vex.client.model.SpeedTestSettings
import com.v2vex.client.ui.components.SpeedGauge
import com.v2vex.client.ui.theme.XrayAmber
import com.v2vex.client.ui.theme.XrayCoral
import com.v2vex.client.ui.theme.XrayCyan
import com.v2vex.client.ui.theme.XrayEmerald
import com.v2vex.client.ui.theme.XrayViolet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SpeedLabScreen(
    progress: SpeedTestProgress,
    history: List<SpeedTestRecord>,
    settings: SpeedTestSettings,
    isVpnConnected: Boolean,
    activeNodeName: String?,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
    onUpdateSpeedSettings: (SpeedTestSettings) -> Unit,
    onClearHistory: () -> Unit
) {
    val isRunning = progress.phase == SpeedTestPhase.PING ||
        progress.phase == SpeedTestPhase.DOWNLOAD ||
        progress.phase == SpeedTestPhase.UPLOAD

    var showConfigDialog by remember { mutableStateOf(false) }

    if (showConfigDialog) {
        var dlUrl by remember { mutableStateOf(settings.downloadUrl) }
        var ulUrl by remember { mutableStateOf(settings.uploadUrl) }
        var pingUrl by remember { mutableStateOf(settings.pingUrl) }
        var duration by remember { mutableStateOf(settings.durationSeconds.toString()) }

        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            title = { Text("SpeedLab Calibration") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dlUrl,
                        onValueChange = { dlUrl = it.trim() },
                        label = { Text("Download Test URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ulUrl,
                        onValueChange = { ulUrl = it.trim() },
                        label = { Text("Upload Test URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pingUrl,
                        onValueChange = { pingUrl = it.trim() },
                        label = { Text("Ping Probe URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = duration,
                        onValueChange = { duration = it.filter { c -> c.isDigit() } },
                        label = { Text("Phase Duration (seconds, 3-20)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateSpeedSettings(
                        settings.copy(
                            downloadUrl = dlUrl.ifBlank { settings.downloadUrl },
                            uploadUrl = ulUrl.ifBlank { settings.uploadUrl },
                            pingUrl = pingUrl.ifBlank { settings.pingUrl },
                            durationSeconds = (duration.toIntOrNull() ?: 8).coerceIn(3, 20)
                        )
                    )
                    showConfigDialog = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showConfigDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SpeedLab",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isVpnConnected) {
                        "Testing via Tunnel · ${activeNodeName ?: "Active Node"}"
                    } else {
                        "Direct Network · Connect VPN to benchmark node"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isVpnConnected) XrayEmerald else XrayAmber
                )
            }
            IconButton(onClick = { showConfigDialog = true }) {
                Icon(Icons.Rounded.Tune, contentDescription = "Calibrate SpeedLab", tint = XrayCyan)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Interactive 60fps Arc Speedometer
        SpeedGauge(
            mbps = progress.currentMbps,
            phase = progress.phase
        )

        Text(
            text = progress.errorMessage ?: progress.statusDetail,
            style = MaterialTheme.typography.labelSmall,
            color = if (progress.errorMessage != null) XrayCoral else MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (isRunning) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.progressFraction.coerceIn(0f, 1f) },
                color = XrayCyan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Stream Selector + Start/Stop Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(1, 4, 8).forEach { count ->
                FilterChip(
                    selected = settings.parallelStreams == count,
                    enabled = !isRunning,
                    onClick = { onUpdateSpeedSettings(settings.copy(parallelStreams = count)) },
                    label = { Text("${count}x") }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = { if (isRunning) onStopTest() else onStartTest() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) XrayCoral else XrayCyan,
                    contentColor = Color(0xFF001A1E)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isRunning) "Stop" else "Start Benchmark",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // High-Precision Metrics Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LabStatCard("Download", String.format("%.2f Mbps", progress.downloadMbps), XrayCyan, Modifier.weight(1f))
            LabStatCard("Upload", String.format("%.2f Mbps", progress.uploadMbps), XrayEmerald, Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LabStatCard("Ping", String.format("%.1f ms", progress.pingMs), XrayAmber, Modifier.weight(1f))
            LabStatCard("Jitter", String.format("%.1f ms", progress.jitterMs), XrayViolet, Modifier.weight(1f))
            LabStatCard("Stability", String.format("%.0f%%", progress.stabilityPercent), XrayEmerald, Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Benchmark History
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Benchmark History (${history.size})",
                style = MaterialTheme.typography.titleMedium
            )
            if (history.isNotEmpty()) {
                IconButton(onClick = onClearHistory) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear History", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (history.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No benchmark records yet. Run SpeedLab to measure true multi-stream Mbps with warm-up discard and trimmed-mean precision.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else {
            val fmt = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.US) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                history.take(15).forEach { rec ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(rec.profileName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "${fmt.format(Date(rec.timestampMs))} · ${if (rec.viaProxy) "Tunnel" else "Direct"} · ${rec.streams}x streams",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "↓ ${String.format("%.2f", rec.downloadMbps)}  ↑ ${String.format("%.2f", rec.uploadMbps)} Mbps",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = XrayCyan
                                )
                                Text(
                                    text = "${String.format("%.1f", rec.pingMs)}ms · Jitter ${String.format("%.1f", rec.jitterMs)}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun LabStatCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accent)
        }
    }
}
