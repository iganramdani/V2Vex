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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2vex.client.core.SpeedMath
import com.v2vex.client.core.UriParser
import com.v2vex.client.model.AppSettings
import com.v2vex.client.model.ProxyProtocol
import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.Subscription
import com.v2vex.client.ui.theme.XrayAmber
import com.v2vex.client.ui.theme.XrayCoral
import com.v2vex.client.ui.theme.XrayCyan
import com.v2vex.client.ui.theme.XrayEmerald
import java.util.UUID

@Composable
fun NodesScreen(
    profiles: List<ServerProfile>,
    subscriptions: List<Subscription>,
    selectedProfileId: String?,
    settings: AppSettings,
    isTestingBatch: Boolean,
    onSelectProfile: (String) -> Unit,
    onUpsertProfile: (ServerProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onImportRaw: (String) -> Int,
    onPingAll: () -> Unit,
    onSelectFastest: () -> Unit,
    onAddSubscription: (name: String, url: String) -> Unit,
    onRefreshSubscription: (Subscription) -> Unit,
    onDeleteSubscription: (String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var searchQuery by remember { mutableStateOf("") }
    var filterProtocol by remember { mutableStateOf<ProxyProtocol?>(null) }
    var editingProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showSubDialog by remember { mutableStateOf(false) }

    val filtered = remember(profiles, searchQuery, filterProtocol) {
        profiles.filter { p ->
            val matchProto = filterProtocol == null || p.protocol == filterProtocol
            val matchQuery = searchQuery.isBlank() ||
                p.name.contains(searchQuery, ignoreCase = true) ||
                p.address.contains(searchQuery, ignoreCase = true)
            matchProto && matchQuery
        }
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
            title = { Text("Import URIs or Xray JSON") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Supports vless://, vmess://, trojan://, ss://, socks://, wireguard://, Base64 lists, or raw Xray JSON.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = rawText,
                        onValueChange = { rawText = it },
                        label = { Text("Paste URI(s) or JSON") },
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Header Row
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
                    text = "${subscriptions.size} subscription(s) active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(onClick = { showSubDialog = true }) {
                    Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "Subscriptions", tint = XrayCyan)
                }
                IconButton(onClick = {
                    val clipText = clipboard.getText()?.text.orEmpty()
                    if (clipText.isNotBlank()) {
                        val count = onImportRaw(clipText)
                        onShowMessage(if (count > 0) "Imported $count node(s) from clipboard" else "Clipboard has no valid proxy URI")
                    } else {
                        showImportDialog = true
                    }
                }) {
                    Icon(Icons.Rounded.Create, contentDescription = "Paste Clipboard")
                }
                IconButton(onClick = { editingProfile = ServerProfile() }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add Node", tint = XrayEmerald)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search nodes or hosts...") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Action Bar: Batch Ping + Smart Fastest + Manual Import
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = { if (!isTestingBatch) onPingAll() },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 9.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isTestingBatch) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = XrayCyan)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test All RTT", style = MaterialTheme.typography.labelMedium)
                }
            }

            Surface(
                onClick = onSelectFastest,
                shape = RoundedCornerShape(12.dp),
                color = XrayEmerald.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, XrayEmerald.copy(alpha = 0.4f)),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 9.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(16.dp), tint = XrayEmerald)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Auto Fastest", style = MaterialTheme.typography.labelMedium, color = XrayEmerald)
                }
            }

            Surface(
                onClick = { showImportDialog = true },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(modifier = Modifier.padding(vertical = 9.dp, horizontal = 12.dp)) {
                    Text("Import", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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

        // Profile List
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
                    onShare = {
                        val uri = UriParser.exportUri(profile)
                        clipboard.setText(AnnotatedString(uri))
                        onShowMessage("Copied ${profile.protocol.label} URI to clipboard")
                    },
                    onDuplicate = {
                        onUpsertProfile(
                            profile.copy(
                                id = UUID.randomUUID().toString(),
                                name = "${profile.name} (Copy)"
                            )
                        )
                    },
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
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) XrayCyan else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
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

                // Telemetry Pills (Last Speed + Ping)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    profile.lastDownloadMbps?.let { mbps ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = XrayCyan.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = String.format("%.1f Mbps", mbps),
                                style = MaterialTheme.typography.labelSmall,
                                color = XrayCyan,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                    profile.latencyMs?.let { ms ->
                        val color = when {
                            ms < 0 -> XrayCoral
                            ms < 90 -> XrayEmerald
                            ms < 220 -> XrayAmber
                            else -> XrayCoral
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = color.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (ms < 0) "ERR" else "${ms}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = color,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Share, contentDescription = "Copy URI", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = XrayCoral, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
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
                    label = { Text("Subscription URL (https://...)") },
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
                    Text("Add & Sync Subscription")
                }

                subscriptions.forEach { sub ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
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
                                Icon(Icons.Rounded.Refresh, contentDescription = "Sync", tint = XrayCyan)
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
