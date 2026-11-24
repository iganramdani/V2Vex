package com.xray99.client.ui.screens

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xray99.client.core.SpeedMath
import com.xray99.client.model.AppSettings
import com.xray99.client.model.CoreInfo
import com.xray99.client.model.CustomRoutingRule
import com.xray99.client.model.RoutingPreset
import com.xray99.client.model.RuleMatchType
import com.xray99.client.model.RuleTarget
import com.xray99.client.model.SpeedUnit
import com.xray99.client.model.SplitTunnelMode
import com.xray99.client.model.ThemeMode
import com.xray99.client.ui.theme.XrayAmber
import com.xray99.client.ui.theme.XrayCoral
import com.xray99.client.ui.theme.XrayCyan
import com.xray99.client.ui.theme.XrayEmerald

@Composable
fun SettingsScreen(
    settings: AppSettings,
    coreInfo: CoreInfo,
    coreUpdateBusy: Boolean,
    coreUpdateProgress: Float,
    coreUpdateStatus: String,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onCheckAndUpdateCore: () -> Unit,
    onUpdateGeoData: () -> Unit,
    onImportLocalCore: () -> Unit,
    onExportBackup: () -> String,
    onImportBackup: (String) -> Result<Int>,
    onOpenLogs: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var showAppPickerDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }

    if (showAddRuleDialog) {
        AddRoutingRuleDialog(
            onDismiss = { showAddRuleDialog = false },
            onAdd = { rule ->
                onUpdateSettings { it.copy(customRules = it.customRules + rule) }
                showAddRuleDialog = false
            }
        )
    }

    if (showAppPickerDialog) {
        SplitTunnelAppPickerDialog(
            selectedPackages = settings.splitTunnelPackages,
            onDismiss = { showAppPickerDialog = false },
            onSave = { pkgs ->
                onUpdateSettings { it.copy(splitTunnelPackages = pkgs) }
                showAppPickerDialog = false
            }
        )
    }

    if (showRestoreDialog) {
        var jsonInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("Restore Full Backup") },
            text = {
                OutlinedTextField(
                    value = jsonInput,
                    onValueChange = { jsonInput = it },
                    label = { Text("Paste XRay99 Backup JSON") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    val res = onImportBackup(jsonInput)
                    showRestoreDialog = false
                    onShowMessage(
                        res.fold(
                            onSuccess = { "Restored $it node(s) & settings" },
                            onFailure = { "Restore failed: ${it.message}" }
                        )
                    )
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Core & Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // 1. HOT-SWAPPABLE CORE MANAGER
        SettingsSectionCard(title = "Xray-core & GeoData Manager", accent = XrayCyan) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Installed Core", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = coreInfo.installedVersion.take(28),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (coreInfo.executable) XrayEmerald else XrayAmber,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Source · ABI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "${coreInfo.binarySource} · ${coreInfo.abi}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("GeoIP / GeoSite", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "${SpeedMath.formatBytes(coreInfo.geoIpSizeBytes)} / ${SpeedMath.formatBytes(coreInfo.geoSiteSizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (coreUpdateBusy) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { coreUpdateProgress.coerceIn(0f, 1f) },
                    color = XrayCyan,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = coreUpdateStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = XrayCyan
                )
            } else if (coreUpdateStatus.isNotBlank()) {
                Text(
                    text = coreUpdateStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = !coreUpdateBusy,
                    onClick = onCheckAndUpdateCore,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Update Core")
                }
                OutlinedButton(
                    enabled = !coreUpdateBusy,
                    onClick = onUpdateGeoData,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Update Geo")
                }
            }
            OutlinedButton(
                enabled = !coreUpdateBusy,
                onClick = onImportLocalCore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Import Custom Xray .zip / Binary")
            }
        }

        // 2. ROUTING ENGINE & CUSTOM RULES
        SettingsSectionCard(title = "Routing Engine", accent = XrayEmerald) {
            Text("Preset Mode", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RoutingPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.routingPreset == preset,
                        onClick = { onUpdateSettings { it.copy(routingPreset = preset) } },
                        label = { Text(preset.label) }
                    )
                }
            }

            Text("Domain Strategy", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("AsIs", "IPIfNonMatch", "IPOnDemand").forEach { strat ->
                    FilterChip(
                        selected = settings.domainStrategy == strat,
                        onClick = { onUpdateSettings { it.copy(domainStrategy = strat) } },
                        label = { Text(strat) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Custom Rules (${settings.customRules.size})", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showAddRuleDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Add Rule")
                }
            }

            settings.customRules.forEach { rule ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { checked ->
                            onUpdateSettings { s ->
                                s.copy(customRules = s.customRules.map { if (it.id == rule.id) it.copy(enabled = checked) else it })
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${rule.name} -> ${rule.target.label}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${rule.matchType.label}: ${rule.values}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        onUpdateSettings { s -> s.copy(customRules = s.customRules.filterNot { it.id == rule.id }) }
                    }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete Rule", tint = XrayCoral)
                    }
                }
            }
        }

        // 3. DPI BYPASS (FRAGMENT), MUX & SNIFFING
        SettingsSectionCard(title = "DPI Bypass · Mux · Sniffing", accent = XrayCyan) {
            SettingToggleRow(
                title = "TLS Fragmentation (DPI Bypass)",
                subtitle = "Splits ClientHello packets to bypass deep packet inspection",
                checked = settings.fragment.enabled,
                onCheckedChange = { on -> onUpdateSettings { it.copy(fragment = it.fragment.copy(enabled = on)) } }
            )
            if (settings.fragment.enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = settings.fragment.packets,
                        onValueChange = { v -> onUpdateSettings { it.copy(fragment = it.fragment.copy(packets = v)) } },
                        label = { Text("Packets") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = settings.fragment.length,
                        onValueChange = { v -> onUpdateSettings { it.copy(fragment = it.fragment.copy(length = v)) } },
                        label = { Text("Length") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = settings.fragment.interval,
                        onValueChange = { v -> onUpdateSettings { it.copy(fragment = it.fragment.copy(interval = v)) } },
                        label = { Text("Interval ms") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider()
            SettingToggleRow(
                title = "Mux.Cool Multiplexing",
                subtitle = "Reduce handshake latency (auto-skipped for XTLS-Vision)",
                checked = settings.mux.enabled,
                onCheckedChange = { on -> onUpdateSettings { it.copy(mux = it.mux.copy(enabled = on)) } }
            )

            HorizontalDivider()
            SettingToggleRow(
                title = "Traffic Sniffing (HTTP/TLS/QUIC)",
                subtitle = "Detect real destination domains for accurate routing",
                checked = settings.sniffing.enabled,
                onCheckedChange = { on -> onUpdateSettings { it.copy(sniffing = it.sniffing.copy(enabled = on)) } }
            )
        }

        // 4. DNS ENGINE
        SettingsSectionCard(title = "DNS Engine", accent = XrayEmerald) {
            OutlinedTextField(
                value = settings.dns.remoteDns,
                onValueChange = { v -> onUpdateSettings { it.copy(dns = it.dns.copy(remoteDns = v.trim())) } },
                label = { Text("Primary Remote DNS (DoH / DoT / UDP)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.dns.directDns,
                onValueChange = { v -> onUpdateSettings { it.copy(dns = it.dns.copy(directDns = v.trim())) } },
                label = { Text("Direct / Local DNS") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SettingToggleRow(
                title = "Enable FakeDNS",
                subtitle = "Zero-RTT synthetic IP mapping for instant domain resolution",
                checked = settings.dns.enableFakeDns,
                onCheckedChange = { on -> onUpdateSettings { it.copy(dns = it.dns.copy(enableFakeDns = on)) } }
            )
        }

        // 5. SPLIT TUNNELING (PER-APP PROXY)
        SettingsSectionCard(title = "Split Tunneling (Per-App)", accent = XrayCyan) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SplitTunnelMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.splitTunnelMode == mode,
                        onClick = { onUpdateSettings { it.copy(splitTunnelMode = mode) } },
                        label = { Text(mode.label) }
                    )
                }
            }
            if (settings.splitTunnelMode != SplitTunnelMode.ALL_APPS) {
                OutlinedButton(
                    onClick = { showAppPickerDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Select Apps (${settings.splitTunnelPackages.size} selected)")
                }
            }
        }

        // 6. VPN, PORTS & APPEARANCE
        SettingsSectionCard(title = "VPN · Ports · Appearance", accent = XrayEmerald) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settings.socksPort.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { p -> onUpdateSettings { it.copy(socksPort = p.coerceIn(1024, 65535)) } } },
                    label = { Text("SOCKS5 Port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = settings.httpPort.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { p -> onUpdateSettings { it.copy(httpPort = p.coerceIn(1024, 65535)) } } },
                    label = { Text("HTTP Port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = settings.vpnMtu.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { m -> onUpdateSettings { it.copy(vpnMtu = m.coerceIn(1280, 9000)) } } },
                    label = { Text("MTU") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            SettingToggleRow(
                title = "Enable IPv6 Tunneling",
                subtitle = "Route IPv6 traffic through TUN interface",
                checked = settings.enableIpv6,
                onCheckedChange = { on -> onUpdateSettings { it.copy(enableIpv6 = on) } }
            )
            SettingToggleRow(
                title = "Allow LAN Connections",
                subtitle = "Bind local SOCKS5/HTTP inbound to 0.0.0.0",
                checked = settings.allowLan,
                onCheckedChange = { on -> onUpdateSettings { it.copy(allowLan = on) } }
            )

            Text("Speed Unit", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeedUnit.entries.forEach { u ->
                    FilterChip(
                        selected = settings.speedUnit == u,
                        onClick = { onUpdateSettings { it.copy(speedUnit = u) } },
                        label = { Text(u.label) }
                    )
                }
            }

            Text("Theme", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ThemeMode.entries.forEach { tm ->
                    FilterChip(
                        selected = settings.themeMode == tm,
                        onClick = { onUpdateSettings { it.copy(themeMode = tm) } },
                        label = { Text(tm.label) }
                    )
                }
            }
        }

        // 7. DIAGNOSTICS & BACKUP
        SettingsSectionCard(title = "Diagnostics & Backup", accent = XrayCyan) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenLogs,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Live Logs")
                }
                OutlinedButton(
                    onClick = {
                        val backup = onExportBackup()
                        clipboard.setText(AnnotatedString(backup))
                        onShowMessage("Full JSON backup copied to clipboard")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Backup")
                }
            }
            OutlinedButton(
                onClick = { showRestoreDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Restore Backup from JSON")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    accent: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AddRoutingRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (CustomRoutingRule) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var values by remember { mutableStateOf("") }
    var matchType by remember { mutableStateOf(RuleMatchType.DOMAIN) }
    var target by remember { mutableStateOf(RuleTarget.DIRECT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Routing Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RuleMatchType.entries.forEach { mt ->
                        FilterChip(
                            selected = matchType == mt,
                            onClick = { matchType = mt },
                            label = { Text(mt.label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = values,
                    onValueChange = { values = it },
                    label = { Text("Comma-separated values (e.g. domain:google.com, geosite:netflix)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleTarget.entries.forEach { t ->
                        FilterChip(
                            selected = target == t,
                            onClick = { target = t },
                            label = { Text(t.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = values.isNotBlank(),
                onClick = {
                    onAdd(
                        CustomRoutingRule(
                            name = name.ifBlank { "${matchType.label} Rule" },
                            matchType = matchType,
                            values = values.trim(),
                            target = target
                        )
                    )
                }
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SplitTunnelAppPickerDialog(
    selectedPackages: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(selectedPackages) }

    val installedApps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName.contains("chrome") }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second.lowercase() }
    }

    val filtered = remember(installedApps, search) {
        if (search.isBlank()) installedApps
        else installedApps.filter {
            it.second.contains(search, ignoreCase = true) || it.first.contains(search, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Apps (${current.size})") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Filter installed apps...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.first }) { (pkg, label) ->
                        val checked = pkg in current
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    current = if (isChecked) current + pkg else current - pkg
                                }
                            )
                            Column {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(current) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
