package com.v2vex.client.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2vex.client.core.BugHostProbeResult
import com.v2vex.client.core.BugHostSniper
import com.v2vex.client.core.SpeedMath
import com.v2vex.client.core.UriParser
import com.v2vex.client.model.AppSettings
import com.v2vex.client.model.ProxyProtocol
import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.Subscription
import com.v2vex.client.ui.components.QrCodeDialog
import com.v2vex.client.ui.theme.XrayCoral
import com.v2vex.client.ui.theme.XrayEmerald
import kotlinx.coroutines.launch

@Composable
fun NodesScreen(
    profiles: List<ServerProfile>,
    subscriptions: List<Subscription>,
    selectedProfileId: String?,
    settings: AppSettings,
    isTestingBatch: Boolean,
    isGeneratingWarp: Boolean,
    onSelectProfile: (String) -> Unit,
    onUpsertProfile: (ServerProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onImportRaw: (String) -> Int,
    onPingAll: () -> Unit,
    onSelectFastest: () -> Unit,
    onGenerateWarp: () -> Unit,
    onAddSubscription: (name: String, url: String) -> Unit,
    onRefreshSubscription: (Subscription) -> Unit,
    onDeleteSubscription: (String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var searchQuery by remember { mutableStateOf("") }
    var filterProtocol by remember { mutableStateOf<ProxyProtocol?>(null) }
    var editingProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var qrProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showSubDialog by remember { mutableStateOf(false) }
    var showBugSniperDialog by remember { mutableStateOf(false) }

    val selectedProfile = remember(profiles, selectedProfileId) {
        profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
    }

    val filtered = remember(profiles, searchQuery, filterProtocol) {
        profiles.filter { p ->
            val matchProto = filterProtocol == null || p.protocol == filterProtocol
            val matchQuery = searchQuery.isBlank() ||
                p.name.contains(searchQuery, ignoreCase = true) ||
                p.address.contains(searchQuery, ignoreCase = true) ||
                p.sni.contains(searchQuery, ignoreCase = true)
            matchProto && matchQuery
        }
    }

    qrProfile?.let { prof ->
        val uri = remember(prof) { UriParser.exportUri(prof) }
        QrCodeDialog(
            title = prof.name,
            payload = uri,
            onCopyUri = {
                clipboard.setText(AnnotatedString(uri))
                qrProfile = null
                onShowMessage("Copied ${prof.protocol.label} URI")
            },
            onDismiss = { qrProfile = null }
        )
    }

    editingProfile?.let { prof ->
        ProfileEditorDialog(
            initial = prof,
            settings = settings,
            onDismiss = { editingProfile = null },
            onSave = {
                onUpsertProfile(it)
                editingProfile = null
                onShowMessage("Saved '${it.name}'")
            }
        )
    }

    if (showImportDialog) {
        var rawText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Universal Import") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Supports VLESS, VMess, Trojan, SS, Hysteria2, TUIC, WireGuard, Base64, Clash/Mihomo YAML, or Sing-box/Xray JSON.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = rawText,
                        onValueChange = { rawText = it },
                        label = { Text("Paste URI(s), YAML, or JSON") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val count = onImportRaw(rawText)
                    showImportDialog = false
                    onShowMessage(if (count > 0) "Imported $count node(s)" else "No valid nodes found")
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showSubDialog) {
        SubscriptionsDialog(
            subscriptions = subscriptions,
            onDismiss = { showSubDialog = false },
            onAdd = onAddSubscription,
            onRefresh = onRefreshSubscription,
            onDelete = onDeleteSubscription
        )
    }

    if (showBugSniperDialog) {
        BugHostSniperDialog(
            activeProfile = selectedProfile,
            onDismiss = { showBugSniperDialog = false },
            onApplyAsAddress = { bugHost ->
                val target = selectedProfile ?: return@BugHostSniperDialog
                onUpsertProfile(target.copy(address = bugHost))
                showBugSniperDialog = false
                onShowMessage("Applied '$bugHost' as CDN Front Address on '${target.name}'")
            },
            onApplyAsSni = { bugHost ->
                val target = selectedProfile ?: return@BugHostSniperDialog
                val pinCert = if (target.address.isNotBlank() && target.address != bugHost) target.address else target.verifyPeerCertByName
                onUpsertProfile(target.copy(sni = bugHost, host = bugHost, verifyPeerCertByName = pinCert))
                showBugSniperDialog = false
                onShowMessage("Applied '$bugHost' as Safe Spoof SNI on '${target.name}'")
            }
        )
    }

    val primaryTone = MaterialTheme.colorScheme.primary
    val signalTone = MaterialTheme.colorScheme.secondary
    val outlineTone = MaterialTheme.colorScheme.outline

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Minimalist Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Nodes (${profiles.size})",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${subscriptions.size} subscription(s) · Pro Tools",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(onClick = { showSubDialog = true }) {
                    Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "Subscriptions")
                }
                IconButton(onClick = {
                    val clipText = clipboard.getText()?.text.orEmpty()
                    if (clipText.isNotBlank()) {
                        val count = onImportRaw(clipText)
                        onShowMessage(if (count > 0) "Imported $count node(s) from clipboard" else "Clipboard has no valid URI/YAML")
                    } else {
                        showImportDialog = true
                    }
                }) {
                    Icon(Icons.Rounded.Create, contentDescription = "Paste Clipboard")
                }
                IconButton(onClick = { editingProfile = ServerProfile() }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Create Node", tint = signalTone)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search nodes, hosts, or SNI...") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Action Bar Row 1: RTT Scan + Auto Fastest + Import
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = { if (!isTestingBatch) onPingAll() },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, outlineTone),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isTestingBatch) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan RTT", style = MaterialTheme.typography.labelMedium)
                }
            }

            Surface(
                onClick = onSelectFastest,
                shape = RoundedCornerShape(10.dp),
                color = signalTone.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, signalTone.copy(alpha = 0.5f)),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(14.dp), tint = signalTone)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Fastest", style = MaterialTheme.typography.labelMedium, color = signalTone)
                }
            }

            Surface(
                onClick = { showImportDialog = true },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, outlineTone)
            ) {
                Box(modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp)) {
                    Text("Import", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Action Bar Row 2 (v3.0 Pro Tools): BugHost Sniper + 1-Click Free Cloudflare WARP
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = { showBugSniperDialog = true },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, outlineTone),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp)
                ) {
                    Text("BugHost / CDN Sniper", style = MaterialTheme.typography.labelSmall, color = primaryTone)
                }
            }

            Surface(
                onClick = { if (!isGeneratingWarp) onGenerateWarp() },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, outlineTone),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isGeneratingWarp) {
                        CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.8.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("+ Free WARP Clean IP", style = MaterialTheme.typography.labelSmall, color = primaryTone)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Protocol Filter Chips
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = filterProtocol == null,
                onClick = { filterProtocol = null },
                label = { Text("All") }
            )
            ProxyProtocol.entries.forEach { proto ->
                FilterChip(
                    selected = filterProtocol == proto,
                    onClick = { filterProtocol = if (filterProtocol == proto) null else proto },
                    label = { Text(proto.label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it.id }) { profile ->
                val isSelected = profile.id == selectedProfileId
                NodeCard(
                    profile = profile,
                    selected = isSelected,
                    onSelect = { onSelectProfile(profile.id) },
                    onEdit = { editingProfile = profile },
                    onShare = { qrProfile = profile },
                    onDelete = { onDeleteProfile(profile.id) }
                )
            }
        }
    }
}

@Composable
private fun NodeCard(
    profile: ServerProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val signalTone = MaterialTheme.colorScheme.secondary
    val outlineTone = MaterialTheme.colorScheme.outline
    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else outlineTone
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${profile.shortBadge()} · ${profile.address}:${profile.port}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    profile.lastDownloadMbps?.let { mbps ->
                        Text(
                            text = String.format("%.1fM", mbps),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    profile.latencyMs?.let { ms ->
                        val color = when {
                            ms < 0 -> XrayCoral
                            ms < 150 -> signalTone
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                            text = if (ms < 0) "ERR" else "${ms}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Share, contentDescription = "QR / URI", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = XrayCoral, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun BugHostSniperDialog(
    activeProfile: ServerProfile?,
    onDismiss: () -> Unit,
    onApplyAsAddress: (String) -> Unit,
    onApplyAsSni: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var rawHosts by remember {
        mutableStateOf(BugHostSniper.DEFAULT_CANDIDATES.joinToString("\n"))
    }
    var scanning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<BugHostProbeResult>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BugHost / CDN Sniper Studio") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Probes TLS SNI handshake, RTT, HTTP code & CDN Server header, then 1-tap injects into '${activeProfile?.name ?: "selected node"}'.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = rawHosts,
                    onValueChange = { rawHosts = it },
                    label = { Text("Candidate Hosts / CDN IPs (1 per line)") },
                    textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                )
                Button(
                    enabled = !scanning,
                    onClick = {
                        scanning = true
                        results = emptyList()
                        scope.launch {
                            results = BugHostSniper.scanBatch(rawHosts.lines())
                            scanning = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (scanning) "Scanning TLS & CDN Headers..." else "Start Sniper Scan")
                }

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(results, key = { it.host }) { r ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = r.host,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = if (r.reachable) "${r.rttMs}ms" else "FAIL",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (r.reachable) XrayEmerald else XrayCoral
                                    )
                                }
                                Text(
                                    text = if (r.reachable) {
                                        "HTTP ${r.httpCode} · ${r.serverHeader.ifBlank { "No-Server-Hdr" }} · ${r.tlsProtocol}"
                                    } else {
                                        r.error ?: "Unreachable"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (r.reachable && activeProfile != null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { onApplyAsAddress(r.host) }) {
                                            Text("Set CDN Address", style = MaterialTheme.typography.labelSmall)
                                        }
                                        TextButton(onClick = { onApplyAsSni(r.host) }) {
                                            Text("Set Safe SNI", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun SubscriptionsDialog(
    subscriptions: List<Subscription>,
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String) -> Unit,
    onRefresh: (Subscription) -> Unit,
    onDelete: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subscriptions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text("URL (Base64 / Clash YAML / Sing-box JSON)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = url.startsWith("http"),
                    onClick = {
                        onAdd(name.ifBlank { "Sub ${subscriptions.size + 1}" }, url)
                        name = ""
                        url = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add & Sync")
                }

                subscriptions.forEach { sub ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(sub.name, style = MaterialTheme.typography.titleMedium)
                                val quota = if (sub.totalBytes != null && sub.totalBytes > 0) {
                                    val used = (sub.uploadBytes ?: 0L) + (sub.downloadBytes ?: 0L)
                                    "${SpeedMath.formatBytes(used)} / ${SpeedMath.formatBytes(sub.totalBytes)}"
                                } else sub.url
                                Text(
                                    text = quota,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onRefresh(sub) }) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Sync")
                            }
                            IconButton(onClick = { onDelete(sub.id) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = XrayCoral)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
