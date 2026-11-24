package com.v2vex.client.service

import com.v2vex.client.model.TrafficSample
import com.v2vex.client.model.VpnConnectionState
import com.v2vex.client.model.VpnRuntimeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object VpnStateHolder {
    private const val MAX_HISTORY = 60

    private val _snapshot = MutableStateFlow(VpnRuntimeSnapshot())
    val snapshot: StateFlow<VpnRuntimeSnapshot> = _snapshot.asStateFlow()

    fun setConnecting(profileId: String, profileName: String, badge: String) {
        _snapshot.value = VpnRuntimeSnapshot(
            state = VpnConnectionState.CONNECTING,
            activeProfileId = profileId,
            activeProfileName = profileName,
            activeProtocolBadge = badge,
            coreMode = "Starting Xray-core..."
        )
    }

    fun setConnected(profileId: String, profileName: String, badge: String, coreMode: String) {
        _snapshot.update {
            it.copy(
                state = VpnConnectionState.CONNECTED,
                activeProfileId = profileId,
                activeProfileName = profileName,
                activeProtocolBadge = badge,
                connectedSinceMs = System.currentTimeMillis(),
                coreMode = coreMode,
                lastError = null
            )
        }
    }

    fun updateTelemetry(
        downloadBps: Long,
        uploadBps: Long,
        totalDownloadBytes: Long,
        totalUploadBytes: Long
    ) {
        val sample = TrafficSample(
            timestampMs = System.currentTimeMillis(),
            downloadBps = downloadBps,
            uploadBps = uploadBps
        )
        _snapshot.update { cur ->
            val nextHistory = (cur.history + sample).takeLast(MAX_HISTORY)
            cur.copy(
                currentDownloadBps = downloadBps,
                currentUploadBps = uploadBps,
                totalDownloadBytes = totalDownloadBytes,
                totalUploadBytes = totalUploadBytes,
                history = nextHistory
            )
        }
    }

    fun setDisconnected(error: String? = null) {
        _snapshot.value = VpnRuntimeSnapshot(
            state = if (error != null) VpnConnectionState.ERROR else VpnConnectionState.DISCONNECTED,
            lastError = error,
            coreMode = "Idle"
        )
    }
}
