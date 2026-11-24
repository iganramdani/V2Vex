package com.v2vex.client.core

import com.v2vex.client.model.SpeedUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure statistical math for high-accuracy Mbps throughput, ping, jitter, and stability.
 */
object SpeedMath {

    /**
     * Converts bytes transferred over elapsed nanoseconds into Megabits per second (Mbps).
     * Uses decimal networking SI definition: 1 Mbps = 1,000,000 bits/sec.
     */
    fun bytesToMbps(bytes: Long, elapsedNanos: Long): Double {
        if (bytes <= 0L || elapsedNanos <= 0L) return 0.0
        val seconds = elapsedNanos / 1_000_000_000.0
        return (bytes * 8.0) / seconds / 1_000_000.0
    }

    /**
     * Computes a trimmed mean over interval Mbps samples to eliminate TCP slow-start ramps
     * and transient spikes. Drops the lowest [dropLowFraction] and highest [dropHighFraction].
     */
    fun trimmedMeanMbps(
        samples: List<Double>,
        dropLowFraction: Double = 0.10,
        dropHighFraction: Double = 0.05
    ): Double {
        val valid = samples.filter { it.isFinite() && it >= 0.0 }.sorted()
        if (valid.isEmpty()) return 0.0
        if (valid.size < 4) return valid.average()

        val lowIdx = (valid.size * dropLowFraction).toInt().coerceIn(0, valid.size - 1)
        val highCount = (valid.size * dropHighFraction).toInt().coerceIn(0, valid.size - lowIdx - 1)
        val endIdx = (valid.size - highCount).coerceAtLeast(lowIdx + 1)
        return valid.subList(lowIdx, endIdx).average()
    }

    /**
     * Computes jitter in milliseconds using mean absolute consecutive difference (RFC 3550 style).
     */
    fun computeJitterMs(rttSamplesMs: List<Double>): Double {
        if (rttSamplesMs.size < 2) return 0.0
        var sumDiff = 0.0
        for (i in 1 until rttSamplesMs.size) {
            sumDiff += abs(rttSamplesMs[i] - rttSamplesMs[i - 1])
        }
        return sumDiff / (rttSamplesMs.size - 1)
    }

    /**
     * Computes connection stability (0..100%) from coefficient of variation of steady-state samples.
     */
    fun computeStabilityPercent(samples: List<Double>): Double {
        val positive = samples.filter { it.isFinite() && it > 0.0 }
        if (positive.size < 2) return if (positive.isNotEmpty()) 100.0 else 0.0
        val mean = positive.average()
        if (mean <= 0.0) return 0.0
        val variance = positive.sumOf { (it - mean) * (it - mean) } / positive.size
        val cv = sqrt(variance) / mean
        return (100.0 * (1.0 - cv.coerceIn(0.0, 1.0))).coerceIn(0.0, 100.0)
    }

    fun formatSpeed(bps: Long, unit: SpeedUnit): String {
        val mbps = max(0L, bps) / 1_000_000.0
        return when (unit) {
            SpeedUnit.MBPS -> when {
                mbps >= 100.0 -> String.format("%.1f Mbps", mbps)
                mbps >= 10.0 -> String.format("%.2f Mbps", mbps)
                mbps >= 0.01 -> String.format("%.2f Mbps", mbps)
                else -> "0.00 Mbps"
            }
            SpeedUnit.MB_S -> {
                val mbPerSec = bps / 8.0 / 1_000_000.0
                when {
                    mbPerSec >= 10.0 -> String.format("%.1f MB/s", mbPerSec)
                    mbPerSec >= 1.0 -> String.format("%.2f MB/s", mbPerSec)
                    else -> String.format("%.0f KB/s", bps / 8.0 / 1000.0)
                }
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        val b = max(0L, bytes).toDouble()
        return when {
            b >= 1_073_741_824.0 -> String.format("%.2f GB", b / 1_073_741_824.0)
            b >= 1_048_576.0 -> String.format("%.1f MB", b / 1_048_576.0)
            b >= 1024.0 -> String.format("%.1f KB", b / 1024.0)
            else -> "${bytes} B"
        }
    }
}
