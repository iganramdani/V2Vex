package com.xray99.client.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xray99.client.core.XrayConfigGenerator
import com.xray99.client.model.AppSettings
import com.xray99.client.model.ProxyProtocol
import com.xray99.client.model.SecurityType
import com.xray99.client.model.ServerProfile
import com.xray99.client.model.TransportType
import com.xray99.client.ui.theme.XrayCoral
import com.xray99.client.ui.theme.XrayEmerald

@Composable
fun ProfileEditorDialog(
    initial: ServerProfile,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (ServerProfile) -> Unit
) {
    var draft by remember { mutableStateOf(initial) }
    var portText by remember { mutableStateOf(initial.port.toString()) }
    var showPreviewJson by remember { mutableStateOf(false) }

    if (showPreviewJson) {
        val generated = remember(draft, settings) { XrayConfigGenerator.generate(draft, settings) }
        AlertDialog(
            onDismissRequest = { showPreviewJson = false },
            title = { Text("Generated Xray-core JSON") },
            text = {
                OutlinedTextField(
                    value = generated,
                    onValueChange = {},
                    readOnly = true,
                    textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 420.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { showPreviewJson = false }) { Text("Close") }
            }
        )
    }

    val rawJsonValidation = remember(draft.protocol, draft.rawJson) {
        if (draft.protocol == ProxyProtocol.RAW_JSON) {
            XrayConfigGenerator.validateJson(draft.rawJson)
        } else Result.success(Unit)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.address.isBlank() && initial.rawJson.isBlank()) "Create Node" else "Edit Node") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("Node Name / Remark") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Protocol", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ProxyProtocol.entries.forEach { proto ->
                        FilterChip(
                            selected = draft.protocol == proto,
                            onClick = { draft = draft.copy(protocol = proto) },
                            label = { Text(proto.label) }
                        )
                    }
                }

                if (draft.protocol == ProxyProtocol.RAW_JSON) {
                    OutlinedTextField(
                        value = draft.rawJson,
                        onValueChange = { draft = draft.copy(rawJson = it) },
                        label = { Text("Raw Xray JSON Configuration") },
                        textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                    Text(
                        text = if (rawJsonValidation.isSuccess) "Valid JSON syntax"
                        else "JSON Error: ${rawJsonValidation.exceptionOrNull()?.message}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (rawJsonValidation.isSuccess) XrayEmerald else XrayCoral
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.address,
                            onValueChange = { draft = draft.copy(address = it.trim()) },
                            label = { Text("Server Host / IP") },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = portText,
                            onValueChange = {
                                portText = it.filter { c -> c.isDigit() }
                                portText.toIntOrNull()?.let { p -> draft = draft.copy(port = p.coerceIn(1, 65535)) }
                            },
                            label = { Text("Port") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (draft.protocol == ProxyProtocol.SOCKS || draft.protocol == ProxyProtocol.HTTP) {
                        OutlinedTextField(
                            value = draft.username,
                            onValueChange = { draft = draft.copy(username = it) },
                            label = { Text("Username (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedTextField(
                        value = draft.uuidOrPassword,
                        onValueChange = { draft = draft.copy(uuidOrPassword = it.trim()) },
                        label = {
                            Text(
                                when (draft.protocol) {
                                    ProxyProtocol.VLESS, ProxyProtocol.VMESS -> "UUID"
                                    ProxyProtocol.WIREGUARD -> "Private Key"
                                    else -> "Password"
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (draft.protocol == ProxyProtocol.VLESS) {
                        OutlinedTextField(
                            value = draft.flow,
                            onValueChange = { draft = draft.copy(flow = it.trim()) },
                            label = { Text("Flow (e.g. xtls-rprx-vision)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (draft.protocol == ProxyProtocol.SHADOWSOCKS || draft.protocol == ProxyProtocol.VMESS) {
                        OutlinedTextField(
                            value = draft.encryption,
                            onValueChange = { draft = draft.copy(encryption = it.trim()) },
                            label = { Text("Cipher / Encryption") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (draft.protocol == ProxyProtocol.WIREGUARD) {
                        OutlinedTextField(
                            value = draft.wgPeerPublicKey,
                            onValueChange = { draft = draft.copy(wgPeerPublicKey = it.trim()) },
                            label = { Text("Peer Public Key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = draft.wgLocalAddress,
                            onValueChange = { draft = draft.copy(wgLocalAddress = it.trim()) },
                            label = { Text("Local Address (e.g. 10.0.0.2/32)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = draft.wgReserved,
                            onValueChange = { draft = draft.copy(wgReserved = it.trim()) },
                            label = { Text("Reserved Bytes (optional, e.g. 12,34,56)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        // Transport Selector
                        Text("Transport", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TransportType.entries.forEach { t ->
                                FilterChip(
                                    selected = draft.transport == t,
                                    onClick = { draft = draft.copy(transport = t) },
                                    label = { Text(t.label) }
                                )
                            }
                        }

                        if (draft.transport != TransportType.TCP) {
                            OutlinedTextField(
                                value = draft.host,
                                onValueChange = { draft = draft.copy(host = it.trim()) },
                                label = { Text("Transport Host Header") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = draft.path,
                                onValueChange = { draft = draft.copy(path = it.trim()) },
                                label = {
                                    Text(if (draft.transport == TransportType.GRPC) "gRPC Service Name" else "Path")
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Security Selector
                        Text("Security", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SecurityType.entries.forEach { sec ->
                                FilterChip(
                                    selected = draft.security == sec,
                                    onClick = { draft = draft.copy(security = sec) },
                                    label = { Text(sec.label) }
                                )
                            }
                        }

                        if (draft.security != SecurityType.NONE) {
                            OutlinedTextField(
                                value = draft.sni,
                                onValueChange = { draft = draft.copy(sni = it.trim()) },
                                label = { Text("SNI / Server Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = draft.fingerprint,
                                onValueChange = { draft = draft.copy(fingerprint = it.trim()) },
                                label = { Text("uTLS Fingerprint (chrome/firefox/safari/randomized)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (draft.security == SecurityType.TLS) {
                            OutlinedTextField(
                                value = draft.alpn,
                                onValueChange = { draft = draft.copy(alpn = it.trim()) },
                                label = { Text("ALPN (e.g. h2,http/1.1)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Allow Insecure Certificate", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = draft.allowInsecure,
                                    onCheckedChange = { draft = draft.copy(allowInsecure = it) }
                                )
                            }
                        }

                        if (draft.security == SecurityType.REALITY) {
                            OutlinedTextField(
                                value = draft.realityPublicKey,
                                onValueChange = { draft = draft.copy(realityPublicKey = it.trim()) },
                                label = { Text("REALITY Public Key (pbk)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = draft.realityShortId,
                                onValueChange = { draft = draft.copy(realityShortId = it.trim()) },
                                label = { Text("REALITY Short ID (sid)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = draft.realitySpiderX,
                                onValueChange = { draft = draft.copy(realitySpiderX = it.trim()) },
                                label = { Text("SpiderX (spx, optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { showPreviewJson = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Preview Generated Xray JSON")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = rawJsonValidation.isSuccess,
                onClick = { onSave(draft) }
            ) {
                Text("Save Node")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
