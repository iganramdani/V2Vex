package com.v2vex.client.model

import kotlinx.serialization.Serializable

@Serializable
data class CoreInfo(
    val installedVersion: String = "Not Installed",
    val binarySource: String = "None", // "Hot-Swapped (filesDir)" | "Bundled (jniLibs)" | "None"
    val binaryPath: String = "",
    val abi: String = "",
    val executable: Boolean = false,
    val geoIpVersion: String = "Not Installed",
    val geoSiteVersion: String = "Not Installed",
    val geoIpSizeBytes: Long = 0L,
    val geoSiteSizeBytes: Long = 0L
)

data class RemoteReleaseInfo(
    val tagName: String,
    val publishedAt: String,
    val downloadUrl: String,
    val assetName: String,
    val assetSizeBytes: Long,
    val releaseNotes: String
)

enum class VpnConnectionState(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    DISCONNECTING("Disconnecting"),
    ERROR("Error")
}

data class VpnRuntimeSnapshot(
    val state: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val activeProfileId: String? = null,
    val activeProfileName: String? = null,
    val activeProtocolBadge: String = "",
    val connectedSinceMs: Long = 0L,
    val currentDownloadBps: Long = 0L,
    val currentUploadBps: Long = 0L,
    val totalDownloadBytes: Long = 0L,
    val totalUploadBytes: Long = 0L,
    val coreMode: String = "Idle",
    val lastError: String? = null,
    val history: List<TrafficSample> = emptyList()
)
