package com.v2vex.client.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.v2vex.client.core.SingBoxConfigGenerator
import com.v2vex.client.core.XrayConfigGenerator
import com.v2vex.client.model.AppSettings
import com.v2vex.client.model.ProxyProtocol
import com.v2vex.client.model.SecurityType
import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.TransportType
import com.v2vex.client.ui.theme.XrayCoral
import com.v2vex.client.ui.theme.XrayEmerald

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
        val isSingBoxProto = draft.protocol == ProxyProtocol.HYSTERIA2 || draft.protocol == ProxyProtocol.TUIC
        val generated = remember(draft, settings) {
            if (isSingBoxProto) SingBoxConfigGenerator.generate(draft, settings)
            else XrayConfigGenerator.generate(draft, settings)
        }
        AlertDialog(
            onDismissRequest = { showPreviewJson = false },
            title = { Text(if (isSingBoxProto) "Generated Sing-box JSON" else "Generated Xray-core JSON") },
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
                        label = { Text("Raw Engine JSON") },
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
                            label = { Text("Server Host / CDN IP") },
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

                    if (draft.protocol == ProxyProtocol.SOCKS ||
                        draft.protocol == ProxyProtocol.HTTP ||
                        draft.protocol == ProxyProtocol.TUIC
                    ) {
                        OutlinedTextField(
                            value = draft.username,
                            onValueChange = { draft = draft.copy(username = it.trim()) },
                            label = {
                                Text(if (draft.protocol == ProxyProtocol.TUIC) "TUIC UUID" else "Username (optional)")
                            },
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

                    if (draft.protocol == ProxyProtocol.HYSTERIA2) {
                        OutlinedTextField(
                            value = draft.sni,
                            onValueChange = { draft = draft.copy(sni = it.trim()) },
                            label = { Text("TLS SNI") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = draft.obfsType,
                                onValueChange = { draft = draft.copy(obfsType = it.trim()) },
                                label = { Text("Obfs (salamander)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = draft.obfsPassword,
                                onValueChange = { draft = draft.copy(obfsPassword = it.trim()) },
                                label = { Text("Obfs Password") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else if (draft.protocol == ProxyProtocol.TUIC) {
                        OutlinedTextField(
                            value = draft.sni,
                            onValueChange = { draft = draft.copy(sni = it.trim()) },
                            label = { Text("TLS SNI") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = draft.congestionControl,
                            onValueChange = { draft = draft.copy(congestionControl = it.trim()) },
                            label = { Text("Congestion Control (bbr / cubic)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (draft.protocol == ProxyProtocol.WIREGUARD) {
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
                            label = { Text("Local Address (e.g. 172.16.0.2/32)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = draft.wgReserved,
                            onValueChange = { draft = draft.copy(wgReserved = it.trim()) },
                            label = { Text("Reserved Bytes (e.g. 12,34,56)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
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
                                label = { Text("SNI (Bug Host / Server Name)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = draft.fingerprint,
                                onValueChange = { draft = draft.copy(fingerprint = it.trim()) },
                                label = { Text("uTLS Fingerprint (chrome/android/firefox)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (draft.security == SecurityType.TLS) {
                            OutlinedTextField(
                                value = draft.verifyPeerCertByName,
                                onValueChange = { draft = draft.copy(verifyPeerCertByName = it.trim()) },
                                label = { Text("Verify Real Cert Domain (Safe SNI Spoof)") },
                                placeholder = { Text("e.g. your-vps.domain.com") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (draft.sni.isNotBlank() &&
                                draft.address.isNotBlank() &&
                                draft.sni != draft.address &&
                                draft.verifyPeerCertByName.isBlank()
                            ) {
                                TextButton(
                                    onClick = { draft = draft.copy(verifyPeerCertByName = draft.address) }
                                ) {
                                    Text("Pin cert to '${draft.address}' (Fix SNI Spoof x509 error)")
                                }
                            }
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
                        Text("Preview Engine JSON")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = rawJsonValidation.isSuccess,
                onClick = { onSave(draft) }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
