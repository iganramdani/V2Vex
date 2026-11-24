package com.xray99.client.ui.screens

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
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Speed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xray99.client.core.SpeedMath
import com.xray99.client.model.AppSettings
import com.xray99.client.model.CoreInfo
import com.xray99.client.model.RoutingPreset
import com.xray99.client.model.ServerProfile
import com.xray99.client.model.VpnConnectionState
import com.xray99.client.model.VpnRuntimeSnapshot
import com.xray99.client.ui.components.ConnectionOrb
import com.xray99.client.ui.components.LiveTrafficChart
import com.xray99.client.ui.theme.XrayAmber
import com.xray99.client.ui.theme.XrayCoral
import com.xray99.client.ui.theme.XrayCyan
import com.xray99.client.ui.theme.XrayEmerald
import com.xray99.client.ui.theme.XrayViolet
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
    onPingSelected: () -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header with Philosophy Badge & Core Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "XRay99",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "99% Fast & Light · Xray Engine",
                    style = MaterialTheme.typography.labelSmall,
                    color = XrayCyan
                )
            }

            Surface(
                onClick = onOpenCoreSettings,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(
                    1.dp,
                    if (coreInfo.executable) XrayEmerald.copy(alpha = 0.5f) else XrayAmber.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (coreInfo.executable) XrayEmerald else XrayAmber)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (coreInfo.executable) "Core Ready" else "Install Core",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Interactive Power Orb
        ConnectionOrb(
            state = snapshot.state,
            durationLabel = durationStr,
            onToggle = onToggleVpn
        )

        if (!snapshot.lastError.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = snapshot.lastError,
                style = MaterialTheme.typography.labelSmall,
                color = XrayCoral
            )
        } else {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (snapshot.state == VpnConnectionState.CONNECTED) {
                    "${snapshot.coreMode} · SOCKS :${settings.socksPort} · HTTP :${settings.httpPort}"
                } else {
                    "Zero-bloat standby · Tap orb to establish tunnel"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Active Node Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenNodes),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = XrayCyan.copy(alpha = 0.14f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Dns,
                            contentDescription = null,
                            tint = XrayCyan
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedProfile?.name ?: "No Node Selected",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = selectedProfile?.let { "${it.shortBadge()} · ${it.address}:${it.port}" }
                            ?: "Tap to add or import a server node",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                selectedProfile?.latencyMs?.let { ms ->
                    val pillColor = when {
                        ms < 0 -> XrayCoral
                        ms < 90 -> XrayEmerald
                        ms < 220 -> XrayAmber
                        else -> XrayCoral
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = pillColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (ms < 0) "ERR" else "${ms}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = pillColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                IconButton(onClick = onPingSelected) {
                    Icon(
                        imageVector = Icons.Rounded.NetworkCheck,
                        contentDescription = "Ping Node",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Real-Time Speed Metrics Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricTile(
                title = "Download",
                value = SpeedMath.formatSpeed(snapshot.currentDownloadBps, settings.speedUnit),
                subtitle = "Total: ${SpeedMath.formatBytes(snapshot.totalDownloadBytes)}",
                icon = Icons.Rounded.ArrowDownward,
                accent = XrayCyan,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = "Upload",
                value = SpeedMath.formatSpeed(snapshot.currentUploadBps, settings.speedUnit),
                subtitle = "Total: ${SpeedMath.formatBytes(snapshot.totalUploadBytes)}",
                icon = Icons.Rounded.ArrowUpward,
                accent = XrayEmerald,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Interactive Live Throughput Chart
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                LiveTrafficChart(
                    samples = snapshot.history,
                    unit = settings.speedUnit
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Power-User Quick Controls Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickToggleChip(
                label = "Routing",
                value = when (settings.routingPreset) {
                    RoutingPreset.GLOBAL -> "Global"
                    RoutingPreset.BYPASS_LAN -> "Bypass LAN"
                    RoutingPreset.ADBLOCK_BYPASS_LAN -> "AdBlock+"
                    RoutingPreset.CUSTOM -> "Custom"
                },
                active = true,
                accent = XrayCyan,
                icon = Icons.Rounded.Route,
                onClick = onCycleRouting,
                modifier = Modifier.weight(1f)
            )
            QuickToggleChip(
                label = "DPI Fragment",
                value = if (settings.fragment.enabled) "ON" else "OFF",
                active = settings.fragment.enabled,
                accent = XrayViolet,
                icon = Icons.Rounded.AutoFixHigh,
                onClick = onToggleFragment,
                modifier = Modifier.weight(1f)
            )
            QuickToggleChip(
                label = "Mux.Cool",
                value = if (settings.mux.enabled) "x${settings.mux.concurrency}" else "OFF",
                active = settings.mux.enabled,
                accent = XrayEmerald,
                icon = Icons.Rounded.Memory,
                onClick = onToggleMux,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // SpeedLab Launch Banner
        Surface(
            onClick = onOpenSpeedLab,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, XrayCyan.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = XrayCyan)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Open SpeedLab Benchmark",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Multi-stream trimmed-mean Mbps + RFC 3550 Jitter",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = XrayCyan)
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
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
private fun QuickToggleChip(
    label: String,
    value: String,
    active: Boolean,
    accent: Color,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (active) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (active) accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (active) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}
