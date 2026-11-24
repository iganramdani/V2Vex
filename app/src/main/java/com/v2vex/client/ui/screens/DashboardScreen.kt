package com.v2vex.client.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2vex.client.core.SpeedMath
import com.v2vex.client.model.AppSettings
import com.v2vex.client.model.CoreInfo
import com.v2vex.client.model.RoutingPreset
import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.VpnConnectionState
import com.v2vex.client.model.VpnRuntimeSnapshot
import com.v2vex.client.ui.components.ConnectionOrb
import com.v2vex.client.ui.components.LiveTrafficChart
import com.v2vex.client.ui.theme.XrayAmber
import com.v2vex.client.ui.theme.XrayCoral
import com.v2vex.client.ui.theme.XrayEmerald
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    snapshot: VpnRuntimeSnapshot,
    selectedProfile: ServerProfile?,
    settings: AppSettings,
    coreInfo: CoreInfo,
    onToggleVpn: () -> Unit,
    onOpenNodes: () -> Unit,
    onOpenSpeedLab: () -> Unit,
    onOpenCoreSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onPingSelected: () -> Unit,
    onToggleBlockQuic: () -> Unit,
    onToggleFragment: () -> Unit,
    onToggleMux: () -> Unit,
    onCycleRouting: () -> Unit
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.state) {
        while (snapshot.state == VpnConnectionState.CONNECTED) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val durationStr = remember(snapshot.connectedSinceMs, nowMs, snapshot.state) {
        if (snapshot.state == VpnConnectionState.CONNECTED && snapshot.connectedSinceMs > 0L) {
            val sec = ((nowMs - snapshot.connectedSinceMs) / 1000L).coerceAtLeast(0L)
            String.format("%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
        } else ""
    }

    val primaryTone = MaterialTheme.colorScheme.primary
    val signalTone = MaterialTheme.colorScheme.secondary
    val outlineTone = MaterialTheme.colorScheme.outline

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Two-Tone Minimalist Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "V2Vex",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "v3.0 · 99% FAST & LIGHT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = onOpenLogs,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, outlineTone)
                ) {
                    Text(
                        text = "LOG .TXT",
                        style = MaterialTheme.typography.labelSmall,
                        color = primaryTone,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                Surface(
                    onClick = onOpenCoreSettings,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, outlineTone)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (coreInfo.executable) signalTone else XrayAmber)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (coreInfo.executable) "CORE OK" else "NO CORE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Geometric Minimalist Dial
        ConnectionOrb(
            state = snapshot.state,
            durationLabel = durationStr,
            onToggle = onToggleVpn
        )

        Spacer(modifier = Modifier.height(6.dp))
        if (!snapshot.lastError.isNullOrBlank()) {
            Text(
                text = snapshot.lastError,
                style = MaterialTheme.typography.labelSmall,
                color = XrayCoral
            )
        } else {
            Text(
                text = if (snapshot.state == VpnConnectionState.CONNECTED) {
                    "${snapshot.coreMode} · PURE L3 TELEMETRY"
                } else {
                    "STANDBY · ZERO BACKGROUND DRAIN"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Active Node Minimalist Selector Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenNodes),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, outlineTone)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (snapshot.state == VpnConnectionState.CONNECTED) signalTone
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedProfile?.name ?: "Select Server Node",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = selectedProfile?.let { "${it.shortBadge()} · ${it.address}:${it.port}" }
                            ?: "Tap to import or create a node",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                selectedProfile?.latencyMs?.let { ms ->
                    val pillColor = when {
                        ms < 0 -> XrayCoral
                        ms < 150 -> signalTone
                        else -> primaryTone
                    }
                    Text(
                        text = if (ms < 0) "ERR" else "${ms}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = pillColor,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
                IconButton(onClick = onPingSelected, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Test Delay",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Tabular Monospace Speed Tiles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricTile(
                title = "DOWNLOAD",
                value = SpeedMath.formatSpeed(snapshot.currentDownloadBps, settings.speedUnit),
                subtitle = "Σ ${SpeedMath.formatBytes(snapshot.totalDownloadBytes)}",
                icon = Icons.Rounded.KeyboardArrowDown,
                accent = primaryTone,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "UPLOAD",
                value = SpeedMath.formatSpeed(snapshot.currentUploadBps, settings.speedUnit),
                subtitle = "Σ ${SpeedMath.formatBytes(snapshot.totalUploadBytes)}",
                icon = Icons.Rounded.KeyboardArrowUp,
                accent = signalTone,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Two-Tone Minimalist Throughput Chart
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, outlineTone)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                LiveTrafficChart(
                    samples = snapshot.history,
                    unit = settings.speedUnit
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4 Minimalist Engine Pills (Hot-Reload)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MinimalToggleTile(
                label = "ROUTING",
                value = when (settings.routingPreset) {
                    RoutingPreset.GLOBAL -> "Global"
                    RoutingPreset.BYPASS_LAN -> "Bypass"
                    RoutingPreset.ADBLOCK_BYPASS_LAN -> "AdBlock"
                    RoutingPreset.CUSTOM -> "Custom"
                },
                active = true,
                onClick = onCycleRouting,
                modifier = Modifier.weight(1f)
            )
            MinimalToggleTile(
                label = "QUIC 443",
                value = if (settings.blockQuic) "BLOCK" else "ALLOW",
                active = settings.blockQuic,
                onClick = onToggleBlockQuic,
                modifier = Modifier.weight(1f)
            )
            MinimalToggleTile(
                label = "DPI FRAG",
                value = if (settings.fragment.enabled) "ON" else "OFF",
                active = settings.fragment.enabled,
                onClick = onToggleFragment,
                modifier = Modifier.weight(1f)
            )
            MinimalToggleTile(
                label = "MUX",
                value = if (settings.mux.enabled) "x${settings.mux.concurrency}" else "OFF",
                active = settings.mux.enabled,
                onClick = onToggleMux,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // SpeedLab Minimalist Launch Strip
        Surface(
            onClick = onOpenSpeedLab,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, outlineTone),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SpeedLab Benchmark",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Keep-Alive True RTT · Server-ACK Upload · Multi-Stream Mbps",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = primaryTone
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun MetricTile(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MinimalToggleTile(
    label: String,
    value: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val signalTone = MaterialTheme.colorScheme.secondary
    val outlineTone = MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (active) signalTone.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (active) signalTone.copy(alpha = 0.55f) else outlineTone
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (active) signalTone else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}
