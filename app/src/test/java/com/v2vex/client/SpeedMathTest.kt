package com.v2vex.client

import com.v2vex.client.core.SpeedMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedMathTest {

    @Test
    fun bytesToMbpsUsesExactSiDecimalBits() {
        // 12,500,000 bytes in 1 second = 100,000,000 bits/s = 100.0 Mbps
        val mbps = SpeedMath.bytesToMbps(12_500_000L, 1_000_000_000L)
        assertEquals(100.0, mbps, 0.0001)
    }

    @Test
    fun trimmedMeanRejectsOutliers() {
        // 10 samples with one slow-start outlier (1.0) and one burst spike (999.0)
        val samples = listOf(1.0, 48.0, 50.0, 51.0, 49.0, 50.5, 50.0, 49.5, 52.0, 999.0)
        val trimmed = SpeedMath.trimmedMeanMbps(samples, dropLowFraction = 0.10, dropHighFraction = 0.10)
        assertEquals(50.0, trimmed, 0.25)
    }

    @Test
    fun jitterCalculatesMeanConsecutiveDifference() {
        // Consecutive diffs: |25-20|=5, |15-25|=10, |20-15|=5 -> mean = 20/3 = 6.6667ms
        val jitter = SpeedMath.computeJitterMs(listOf(20.0, 25.0, 15.0, 20.0))
        assertEquals(6.6667, jitter, 0.01)
    }

    @Test
    fun stabilityIsHighForSteadyStream() {
        val stability = SpeedMath.computeStabilityPercent(listOf(100.0, 101.0, 99.0, 100.5, 99.5))
        assertTrue(stability > 98.0)
    }
}
