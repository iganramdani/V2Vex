package com.v2vex.client.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.v2vex.client.core.SpeedMath
import com.v2vex.client.model.SpeedUnit
import com.v2vex.client.model.TrafficSample
import com.v2vex.client.ui.theme.XrayCyan
import com.v2vex.client.ui.theme.XrayEmerald

/**
 * Interactive touch-scrubbable dual-series (Download & Upload) live traffic chart.
 */
@Composable
fun LiveTrafficChart(
    samples: List<TrafficSample>,
    unit: SpeedUnit,
    modifier: Modifier = Modifier
) {
    var inspectedIndex by remember { mutableStateOf<Int?>(null) }
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val inspected = inspectedIndex?.let { samples.getOrNull(it) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (inspected != null) {
                    "Inspected · ↓ ${SpeedMath.formatSpeed(inspected.downloadBps, unit)} · ↑ ${SpeedMath.formatSpeed(inspected.uploadBps, unit)}"
                } else {
                    "Live Throughput (drag to inspect)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val maxMbps = (samples.maxOfOrNull { maxOf(it.downloadMbps, it.uploadMbps) } ?: 1.0).coerceAtLeast(1.0)
            Text(
                text = "Scale: ${String.format("%.1f", maxMbps)} Mbps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .pointerInput(samples.size) {
                    detectHorizontalDragGestures(
                        onDragEnd = { inspectedIndex = null },
                        onDragCancel = { inspectedIndex = null }
                    ) { change, _ ->
                        if (samples.size >= 2) {
                            val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            inspectedIndex = (frac * (samples.size - 1)).toInt()
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height

            // Subtle horizontal grid lines
            for (i in 1..3) {
                val y = h * (i / 4f)
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
            }

            if (samples.size < 2) return@Canvas

            val maxVal = samples.maxOf { maxOf(it.downloadBps, it.uploadBps) }.coerceAtLeast(100_000L).toFloat()
            val stepX = w / (samples.size - 1).coerceAtLeast(1)

            fun drawSeries(values: List<Long>, color: Color, fillArea: Boolean) {
                val linePath = Path()
                val fillPath = Path()
                values.forEachIndexed { idx, v ->
                    val x = idx * stepX
                    val y = h - (v / maxVal).coerceIn(0f, 1f) * (h - 8.dp.toPx()) - 4.dp.toPx()
                    if (idx == 0) {
                        linePath.moveTo(x, y)
                        if (fillArea) {
                            fillPath.moveTo(x, h)
                            fillPath.lineTo(x, y)
                        }
                    } else {
                        linePath.lineTo(x, y)
                        if (fillArea) fillPath.lineTo(x, y)
                    }
                }
                if (fillArea) {
                    fillPath.lineTo((values.size - 1) * stepX, h)
                    fillPath.close()
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(color.copy(alpha = 0.28f), Color.Transparent)
                        )
                    )
                }
                drawPath(
                    path = linePath,
                    color = color,
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            drawSeries(samples.map { it.uploadBps }, XrayEmerald, fillArea = false)
            drawSeries(samples.map { it.downloadBps }, XrayCyan, fillArea = true)

            // Vertical scrub cursor
            inspectedIndex?.let { idx ->
                val x = idx * stepX
                drawLine(
                    color = Color.White.copy(alpha = 0.7f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
        }
    }
}
