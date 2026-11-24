package com.xray99.client.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class TrafficSample(
    val timestampMs: Long,
    val downloadBps: Long, // bits per second
    val uploadBps: Long    // bits per second
) {
    val downloadMbps: Double get() = downloadBps / 1_000_000.0
    val uploadMbps: Double get() = uploadBps / 1_000_000.0
}

enum class SpeedTestPhase(val label: String) {
    IDLE("Ready"),
    PING("Measuring Ping & Jitter"),
    DOWNLOAD("Multi-Stream Download"),
    UPLOAD("Multi-Stream Upload"),
    COMPLETED("Benchmark Complete"),
    ERROR("Test Failed")
}

data class SpeedTestProgress(
    val phase: SpeedTestPhase = SpeedTestPhase.IDLE,
    val progressFraction: Float = 0f,
    val currentMbps: Double = 0.0,
    val peakMbps: Double = 0.0,
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val pingMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val stabilityPercent: Double = 0.0,
    val activeStreams: Int = 0,
    val bytesTransferred: Long = 0L,
    val liveSamplesMbps: List<Double> = emptyList(),
    val statusDetail: String = "Tap Start to run high-accuracy benchmark",
    val errorMessage: String? = null
)

@Serializable
data class SpeedTestRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val profileId: String? = null,
    val profileName: String = "Direct / Unselected",
    val viaProxy: Boolean = false,
    val downloadMbps: Double = 0.0,
    val peakDownloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val pingMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val stabilityPercent: Double = 0.0,
    val streams: Int = 4,
    val endpointHost: String = "speed.cloudflare.com"
)
