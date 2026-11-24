package com.v2vex.client.ui.screens

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.material3.Surface
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
import com.v2vex.client.core.SpeedMath
import com.v2vex.client.model.AppSettings
import com.v2vex.client.model.CoreEnginePreference
import com.v2vex.client.model.CoreInfo
import com.v2vex.client.model.CustomRoutingRule
import com.v2vex.client.model.RemoteReleaseInfo
import com.v2vex.client.model.RoutingPreset
import com.v2vex.client.model.RuleMatchType
import com.v2vex.client.model.RuleTarget
import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.SpeedUnit
import com.v2vex.client.model.SplitTunnelMode
import com.v2vex.client.model.ThemeMode
import com.v2vex.client.ui.theme.XrayAmber
import com.v2vex.client.ui.theme.XrayCoral
import com.v2vex.client.ui.theme.XrayEmerald

private data class UpstreamCredit(
    val project: String,
    val author: String,
    val license: String,
    val role: String,
    val url: String
)

private val UPSTREAM_CREDITS = listOf(
    UpstreamCredit(
        project = "XTLS / Xray-core",
        author = "Project X · @RPRX & Contributors",
        license = "MPL-2.0",
        role = "Core proxy engine (VLESS, XTLS-Vision, REALITY, XHTTP, Observatory)",
        url = "https://github.com/XTLS/Xray-core"
    ),
    UpstreamCredit(
        project = "hev-socks5-tunnel",
        author = "Wanli He (@heiher)",
        license = "MIT",
        role = "High-speed native C L3 TUN-to-SOCKS5 engine & exact packet stats",
        url = "https://github.com/heiher/hev-socks5-tunnel"
    ),
    UpstreamCredit(
        project = "SagerNet / sing-box",
        author = "nekohasekai (@nekohasekai)",
        license = "GPL-3.0",
        role = "Universal proxy platform & Modular Dual-Core Hysteria2 / TUIC schema",
        url = "https://github.com/SagerNet/sing-box"
    ),
    UpstreamCredit(
        project = "v2ray-rules-dat",
        author = "Loyalsoldier (@Loyalsoldier)",
        license = "GPL-3.0",
        role = "Continuously maintained GeoIP & GeoSite routing datasets",
        url = "https://github.com/Loyalsoldier/v2ray-rules-dat"
    ),
    UpstreamCredit(
        project = "v2rayNG & NekoBoxForAndroid",
        author = "@2dust & @MatsuriDayo",
        license = "GPL-3.0",
        role = "Pioneering Android V2Ray/Xray URI conventions & plugin ecosystem",
        url = "https://github.com/2dust/v2rayNG"
    ),
    UpstreamCredit(
        project = "WireGuard & Cloudflare Edge",
        author = "Jason A. Donenfeld & Cloudflare",
        license = "MIT / Apache-2.0",
        role = "Modern Curve25519 cryptography, WARP clean-IP network & SpeedLab edge",
        url = "https://www.wireguard.com"
    )
)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    profiles: List<ServerProfile>,
    coreInfo: CoreInfo,
    geoSiteTags: List<String>,
    geoIpTags: List<String>,
    coreUpdateBusy: Boolean,
    coreUpdateProgress: Float,
    coreUpdateStatus: String,
    appUpdateRelease: RemoteReleaseInfo?,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onCheckAndUpdateCore: () -> Unit,
    onUpdateGeoData: () -> Unit,
    onImportLocalCore: () -> Unit,
    onOpenAppReleaseUrl: (String) -> Unit,
    onExportBackup: () -> String,
    onImportBackup: (String) -> Result<Int>,
    onOpenLogs: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<CustomRoutingRule?>(null) }
    var showAppPickerDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }

    val pm = remember { context.getSystemService(PowerManager::class.java) }
    val batteryExempt = remember(coreInfo) {
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    if (showAddRuleDialog || editingRule != null) {
        AddRoutingRuleDialog(
            initialRule = editingRule,
            geoSiteTags = geoSiteTags,
            geoIpTags = geoIpTags,
            onDismiss = {
                showAddRuleDialog = false
                editingRule = null
            },
            onSave = { rule ->
                onUpdateSettings { s ->
                    val exists = s.customRules.any { it.id == rule.id }
                    val next = if (exists) {
                        s.customRules.map { if (it.id == rule.id) rule else it }
                    } else {
                        s.customRules + rule
                    }
                    s.copy(customRules = next)
                }
                showAddRuleDialog = false
                editingRule = null
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
            title = { Text("Restore Backup") },
            text = {
                OutlinedTextField(
                    value = jsonInput,
                    onValueChange = { jsonInput = it },
                    label = { Text("Paste V2Vex Backup JSON") },
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

        // MIUI / HyperOS / OEM Battery Saver Exemption Banner
        if (!batteryExempt) {
            Surface(
                onClick = {
                    runCatching {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, XrayAmber.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Prevent Background Kill (MIUI / HyperOS / OEM)",
                        style = MaterialTheme.typography.titleMedium,
                        color = XrayAmber
                    )
                    Text(
                        text = "Tap to exempt V2Vex from aggressive battery killing while screen is locked.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 1. MODULAR DUAL-CORE & GEODATA MANAGER
        SettingsSectionCard(title = "Modular Dual-Core & GeoData") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Xray-core", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = coreInfo.installedVersion.take(26),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (coreInfo.executable) XrayEmerald else XrayAmber,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sing-box", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = coreInfo.singBoxVersion.take(26),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (coreInfo.singBoxExecutable) XrayEmerald else XrayAmber,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Source · ABI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${coreInfo.binarySource} · ${coreInfo.abi}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("GeoIP / GeoSite", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${SpeedMath.formatBytes(coreInfo.geoIpSizeBytes)} (${geoIpTags.size}) / ${SpeedMath.formatBytes(coreInfo.geoSiteSizeBytes)} (${geoSiteTags.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (coreUpdateBusy) {
                LinearProgressIndicator(
                    progress = { coreUpdateProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(coreUpdateStatus, style = MaterialTheme.typography.labelSmall)
            } else if (coreUpdateStatus.isNotBlank()) {
                Text(coreUpdateStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("Core Engine", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CoreEnginePreference.entries.forEach { engine ->
                    FilterChip(
                        selected = settings.coreEngine == engine,
                        onClick = { onUpdateSettings { it.copy(coreEngine = engine) } },
                        label = { Text(engine.label) }
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !coreUpdateBusy,
                    onClick = onCheckAndUpdateCore,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Check Core")
                }
                OutlinedButton(
                    enabled = !coreUpdateBusy,
                    onClick = onUpdateGeoData,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Update Geo")
                }
            }
            OutlinedButton(
                enabled = !coreUpdateBusy,
                onClick = onImportLocalCore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Import Custom Core .zip / Binary")
            }
            appUpdateRelease?.let { rel ->
                Button(
                    onClick = { onOpenAppReleaseUrl(rel.downloadUrl) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download V2Vex ${rel.tagName} APK")
                }
            }
        }

        // 2. NETWORK STABILITY, QUIC GUARD & CARRIER NAT64 FIX
        SettingsSectionCard(title = "Network Stability & NAT64 Protection") {
            SettingToggleRow(
                title = "Block QUIC (UDP:443)",
                subtitle = "Prevents UDP-over-TCP closed-pipe storms on HTTPUpgrade/WS & speeds up YouTube",
                checked = settings.blockQuic,
                onCheckedChange = { on -> onUpdateSettings { it.copy(blockQuic = on) } }
            )

            Text("Outbound Dial Strategy (Carrier DNS64 / NAT64 Fix)", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("AsIs" to "AsIs", "UseIPv4" to "UseIPv4 (Fix NAT64)", "UseIPv6" to "UseIPv6").forEach { (valKey, label) ->
                    FilterChip(
                        selected = settings.outboundDomainStrategy == valKey,
                        onClick = { onUpdateSettings { it.copy(outboundDomainStrategy = valKey) } },
                        label = { Text(label) }
                    )
                }
            }

            HorizontalDivider()
            SettingToggleRow(
                title = "Observatory Auto-Failover (Least-Ping)",
                subtitle = "Probes healthy backup nodes and switches seamlessly without dropping TUN",
                checked = settings.enableAutoFailover,
                onCheckedChange = { on -> onUpdateSettings { it.copy(enableAutoFailover = on) } }
            )
            if (settings.enableAutoFailover && profiles.size > 1) {
                Text("Select Failover Pool (leave empty for auto-healthy nodes)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    profiles.take(10).forEach { node ->
                        val chosen = node.id in settings.failoverNodeIds
                        FilterChip(
                            selected = chosen,
                            onClick = {
                                onUpdateSettings { s ->
                                    val next = if (chosen) s.failoverNodeIds - node.id else s.failoverNodeIds + node.id
                                    s.copy(failoverNodeIds = next)
                                }
                            },
                            label = { Text(node.name.take(16)) }
                        )
                    }
                }
            }
        }

        // 3. ROUTING ENGINE & CUSTOM RULES
        SettingsSectionCard(title = "Routing Engine") {
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

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("AsIs", "IPIfNonMatch", "IPOnDemand").forEach { strat ->
                    FilterChip(
                        selected = settings.domainStrategy == strat,
                        onClick = { onUpdateSettings { it.copy(domainStrategy = strat) } },
                        label = { Text(strat) }
                    )
                }
            }

            HorizontalDivider()
            Text("1-Tap GeoSite & GeoIP Packs", style = MaterialTheme.typography.labelMedium)
            val quickPacks = remember {
                listOf(
                    Triple("Block Ads", RuleMatchType.DOMAIN, "geosite:category-ads-all") to RuleTarget.BLOCK,
                    Triple("Direct Indonesia", RuleMatchType.IP, "geoip:id") to RuleTarget.DIRECT,
                    Triple("Proxy Streaming", RuleMatchType.DOMAIN, "geosite:youtube,geosite:netflix,geosite:spotify,geosite:disney") to RuleTarget.PROXY,
                    Triple("Proxy AI & Dev", RuleMatchType.DOMAIN, "geosite:openai,geosite:anthropic,geosite:github") to RuleTarget.PROXY,
                    Triple("Proxy Social", RuleMatchType.DOMAIN, "geosite:telegram,geosite:instagram,geosite:tiktok") to RuleTarget.PROXY
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickPacks.forEach { (spec, target) ->
                    val (packName, matchType, values) = spec
                    val existing = settings.customRules.firstOrNull { it.name == packName }
                    val active = existing?.enabled == true
                    FilterChip(
                        selected = active,
                        onClick = {
                            onUpdateSettings { s ->
                                val cur = s.customRules.firstOrNull { it.name == packName }
                                val nextRules = if (cur != null) {
                                    s.customRules.filterNot { it.name == packName }
                                } else {
                                    listOf(
                                        CustomRoutingRule(
                                            name = packName,
                                            matchType = matchType,
                                            values = values,
                                            target = target
                                        )
                                    ) + s.customRules
                                }
                                s.copy(customRules = nextRules)
                            }
                            onShowMessage(if (active) "Removed '$packName'" else "Enabled '$packName' (${target.label})")
                        },
                        label = { Text(packName) }
                    )
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Custom Rules (${settings.customRules.size})", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showAddRuleDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("GeoSite / Rule")
                }
            }

            settings.customRules.forEach { rule ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editingRule = rule },
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
                        Text("${rule.matchType.label}: ${rule.values}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        onUpdateSettings { s -> s.copy(customRules = s.customRules.filterNot { it.id == rule.id }) }
                    }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = XrayCoral)
                    }
                }
            }
        }

        // 4. DPI BYPASS, MUX, SNIFFING & MULTI-HOP CHAIN
        SettingsSectionCard(title = "DPI Fragment · Mux · Multi-Hop Chain") {
            SettingToggleRow(
                title = "TLS Fragmentation (DPI Bypass)",
                subtitle = "Splits ClientHello packets to defeat deep packet inspection",
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
                subtitle = "Multiplex TCP/XUDP streams (auto-skipped on XTLS-Vision)",
                checked = settings.mux.enabled,
                onCheckedChange = { on -> onUpdateSettings { it.copy(mux = it.mux.copy(enabled = on)) } }
            )

            HorizontalDivider()
            SettingToggleRow(
                title = "Traffic Sniffing (HTTP/TLS/QUIC)",
                subtitle = "Extract destination domains for routing & Live Firewall",
                checked = settings.sniffing.enabled,
                onCheckedChange = { on -> onUpdateSettings { it.copy(sniffing = it.sniffing.copy(enabled = on)) } }
            )

            HorizontalDivider()
            Text("Multi-Hop Proxy Chain (e.g. Bug Node -> WARP Clean IP)", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = settings.chainProfileId == null,
                    onClick = { onUpdateSettings { it.copy(chainProfileId = null) } },
                    label = { Text("Disabled") }
                )
                profiles.take(10).forEach { hop ->
                    FilterChip(
                        selected = settings.chainProfileId == hop.id,
                        onClick = { onUpdateSettings { it.copy(chainProfileId = hop.id) } },
                        label = { Text(hop.name.take(18)) }
                    )
                }
            }
        }

        // 5. DNS ENGINE (WITH PORT-53 HIJACK)
        SettingsSectionCard(title = "DNS Engine (Port-53 DoH Hijack Active)") {
            OutlinedTextField(
                value = settings.dns.remoteDns,
                onValueChange = { v -> onUpdateSettings { it.copy(dns = it.dns.copy(remoteDns = v.trim())) } },
                label = { Text("Primary Encrypted DNS (DoH / DoT)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.dns.directDns,
                onValueChange = { v -> onUpdateSettings { it.copy(dns = it.dns.copy(directDns = v.trim())) } },
                label = { Text("Direct / Bootstrap DNS") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SettingToggleRow(
                title = "Enable FakeDNS (198.18.0.0/15)",
                subtitle = "0-RTT synthetic IP mapping for instant domain resolution",
                checked = settings.dns.enableFakeDns,
                onCheckedChange = { on -> onUpdateSettings { it.copy(dns = it.dns.copy(enableFakeDns = on)) } }
            )
        }

        // 6. SPLIT TUNNELING & TWO-TONE APPEARANCE
        SettingsSectionCard(title = "Split Tunneling · Two-Tone Minimalist UI") {
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
                    Icon(Icons.AutoMirrored.Rounded.List, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Select Apps (${settings.splitTunnelPackages.size})")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settings.socksPort.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { p -> onUpdateSettings { it.copy(socksPort = p.coerceIn(1024, 65535)) } } },
                    label = { Text("SOCKS5") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = settings.httpPort.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { p -> onUpdateSettings { it.copy(httpPort = p.coerceIn(1024, 65535)) } } },
                    label = { Text("HTTP") },
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
                subtitle = "Route IPv6 packets through L3 TUN",
                checked = settings.enableIpv6,
                onCheckedChange = { on -> onUpdateSettings { it.copy(enableIpv6 = on) } }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeedUnit.entries.forEach { u ->
                    FilterChip(
                        selected = settings.speedUnit == u,
                        onClick = { onUpdateSettings { it.copy(speedUnit = u) } },
                        label = { Text(u.label) }
                    )
                }
            }

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

        // 7. DIAGNOSTICS (.TXT), ALWAYS-ON VPN & BACKUP
        SettingsSectionCard(title = "Diagnostics (.txt) · System VPN · Backup") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenLogs,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Log (.txt)")
                }
                OutlinedButton(
                    onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Always-On VPN")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val backup = onExportBackup()
                        clipboard.setText(AnnotatedString(backup))
                        onShowMessage("Backup JSON copied")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Backup")
                }
                OutlinedButton(
                    onClick = { showRestoreDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Restore Backup")
                }
            }
        }

        // 8. OPEN-SOURCE CREDITS & RESPECT
        SettingsSectionCard(title = "Open-Source Credits & Respect") {
            Text(
                text = "V2Vex stands on the shoulders of extraordinary open-source engineers. Deepest respect and gratitude to every upstream author:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            UPSTREAM_CREDITS.forEach { credit ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAppReleaseUrl(credit.url) }
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(credit.project, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(credit.license, style = MaterialTheme.typography.labelSmall, color = XrayEmerald)
                        }
                        Text(credit.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(credit.role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
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
    initialRule: CustomRoutingRule?,
    geoSiteTags: List<String>,
    geoIpTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (CustomRoutingRule) -> Unit
) {
    var name by remember(initialRule) { mutableStateOf(initialRule?.name ?: "") }
    var values by remember(initialRule) { mutableStateOf(initialRule?.values ?: "") }
    var matchType by remember(initialRule) { mutableStateOf(initialRule?.matchType ?: RuleMatchType.DOMAIN) }
    var target by remember(initialRule) { mutableStateOf(initialRule?.target ?: RuleTarget.DIRECT) }
    var geoSearch by remember { mutableStateOf("") }

    val popularGeoSites = remember {
        listOf(
            "category-ads-all", "youtube", "google", "netflix", "spotify",
            "openai", "anthropic", "github", "telegram", "instagram",
            "tiktok", "facebook", "whatsapp", "twitter", "discord",
            "steam", "private", "geolocation-!cn"
        )
    }
    val popularGeoIps = remember {
        listOf("private", "id", "sg", "us", "jp", "hk", "cn", "cloudflare", "google", "telegram", "facebook")
    }

    val pickerTags = remember(matchType, geoSearch, geoSiteTags, geoIpTags) {
        when (matchType) {
            RuleMatchType.DOMAIN -> {
                val source = geoSiteTags.ifEmpty { popularGeoSites }
                val q = geoSearch.trim().lowercase()
                if (q.isBlank()) {
                    val existingPop = popularGeoSites.filter { it in source }
                    (existingPop + source.take(30)).distinct().take(24)
                } else {
                    source.filter { it.contains(q) }.take(30)
                }
            }
            RuleMatchType.IP -> {
                val source = geoIpTags.ifEmpty { popularGeoIps }
                val q = geoSearch.trim().lowercase()
                if (q.isBlank()) {
                    val existingPop = popularGeoIps.filter { it in source }
                    (existingPop + source.take(30)).distinct().take(24)
                } else {
                    source.filter { it.contains(q) }.take(30)
                }
            }
            else -> emptyList()
        }
    }

    val currentTokens = remember(values) {
        values.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun toggleToken(token: String) {
        val list = currentTokens.toMutableList()
        val existingIdx = list.indexOfFirst { it.equals(token, ignoreCase = true) }
        if (existingIdx >= 0) {
            list.removeAt(existingIdx)
        } else {
            list.add(token)
        }
        values = list.joinToString(", ")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialRule == null) "GeoSite & Routing Rule" else "Edit Routing Rule") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule Label (optional)") },
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
                            onClick = { matchType = mt; geoSearch = "" },
                            label = { Text(mt.label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleTarget.entries.forEach { t ->
                        FilterChip(
                            selected = target == t,
                            onClick = { target = t },
                            label = { Text(t.label) }
                        )
                    }
                }

                // Visual GeoSite / GeoIP Picker (searches real geosite.dat / geoip.dat tags!)
                if (matchType == RuleMatchType.DOMAIN || matchType == RuleMatchType.IP) {
                    val prefix = if (matchType == RuleMatchType.DOMAIN) "geosite:" else "geoip:"
                    val totalCount = if (matchType == RuleMatchType.DOMAIN) geoSiteTags.size else geoIpTags.size
                    OutlinedTextField(
                        value = geoSearch,
                        onValueChange = { geoSearch = it },
                        label = {
                            Text(
                                if (matchType == RuleMatchType.DOMAIN)
                                    "Search GeoSite tags ($totalCount loaded)..."
                                else
                                    "Search GeoIP tags ($totalCount loaded)..."
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        pickerTags.forEach { tag ->
                            val fullToken = "$prefix$tag"
                            val selected = currentTokens.any { it.equals(fullToken, ignoreCase = true) }
                            FilterChip(
                                selected = selected,
                                onClick = { toggleToken(fullToken) },
                                label = { Text(tag) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = values,
                    onValueChange = { values = it },
                    label = {
                        Text(
                            when (matchType) {
                                RuleMatchType.DOMAIN -> "Selected / Custom (geosite:..., domain:...)"
                                RuleMatchType.IP -> "Selected / Custom (geoip:..., 1.1.1.0/24)"
                                RuleMatchType.PORT -> "Ports (e.g. 53, 443, 8000-9000)"
                                RuleMatchType.PROTOCOL -> "Protocols (http, tls, bittorrent)"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = values.isNotBlank(),
                onClick = {
                    val rule = (initialRule ?: CustomRoutingRule()).copy(
                        name = name.ifBlank { "${target.label} · ${currentTokens.firstOrNull()?.take(20) ?: matchType.label}" },
                        matchType = matchType,
                        values = values.trim(),
                        target = target
                    )
                    onSave(rule)
                }
            ) { Text(if (initialRule == null) "Add" else "Save") }
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
