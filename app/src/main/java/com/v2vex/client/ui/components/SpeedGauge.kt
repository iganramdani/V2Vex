package com.v2vex.client.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2vex.client.model.SpeedTestPhase
import com.v2vex.client.ui.theme.XrayAmber
import com.v2vex.client.ui.theme.XrayCyan
import com.v2vex.client.ui.theme.XrayEmerald
import com.v2vex.client.ui.theme.XrayViolet
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * 60fps Canvas Arc Speedometer Gauge using logarithmic scale (0 -> 1000+ Mbps)
 * so both 5 Mbps cellular and 500+ Mbps fiber read with crisp visual precision.
 */
@Composable
fun SpeedGauge(
    mbps: Double,
    phase: SpeedTestPhase,
    modifier: Modifier = Modifier
) {
    // Logarithmic normalization: 0 -> 0.0, 10 Mbps -> ~0.35, 100 Mbps -> ~0.68, 1000 Mbps -> 1.0
    val normalizedTarget = if (mbps <= 0.0) 0f else {
        (ln(1.0 + mbps) / ln(1.0 + 1000.0)).toFloat().coerceIn(0f, 1f)
    }

    val animatedFraction by animateFloatAsState(
        targetValue = normalizedTarget,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 140f),
        label = "gaugeNeedle"
    )

    val phaseColor = when (phase) {
        SpeedTestPhase.UPLOAD -> XrayEmerald
        SpeedTestPhase.PING -> XrayAmber
        else -> XrayCyan
    }
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(240.dp)
    ) {
        Canvas(modifier = Modifier.size(240.dp)) {
            val strokeW = 14.dp.toPx()
            val inset = strokeW + 10.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val startAngle = 140f
            val totalSweep = 260f

            // Background arc
            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )

            // Active gradient arc
            val activeSweep = totalSweep * animatedFraction
            if (activeSweep > 0.5f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(XrayCyan, phaseColor, XrayViolet, XrayEmerald)
                    ),
                    startAngle = startAngle,
                    sweepAngle = activeSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )
            }

            // Tick marks around outer edge
            val center = Offset(size.width / 2f, size.height / 2f)
            val tickOuter = size.minDimension / 2f - 4.dp.toPx()
            val tickInner = tickOuter - 6.dp.toPx()
            for (i in 0..20) {
                val frac = i / 20f
                val deg = startAngle + totalSweep * frac
                val rad = Math.toRadians(deg.toDouble())
                val p1 = Offset(
                    center.x + (tickInner * cos(rad)).toFloat(),
                    center.y + (tickInner * sin(rad)).toFloat()
                )
                val p2 = Offset(
                    center.x + (tickOuter * cos(rad)).toFloat(),
                    center.y + (tickOuter * sin(rad)).toFloat()
                )
                drawLine(
                    color = if (frac <= animatedFraction) phaseColor else trackColor,
                    start = p1,
                    end = p2,
                    strokeWidth = if (i % 5 == 0) 2.5.dp.toPx() else 1.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Glowing indicator dot on arc tip
            val tipDeg = startAngle + activeSweep
            val tipRad = Math.toRadians(tipDeg.toDouble())
            val arcRadius = arcSize.width / 2f
            val tipPos = Offset(
                center.x + (arcRadius * cos(tipRad)).toFloat(),
                center.y + (arcRadius * sin(tipRad)).toFloat()
            )
            drawCircle(
                color = phaseColor.copy(alpha = 0.35f),
                radius = 12.dp.toPx(),
                center = tipPos
            )
            drawCircle(
                color = Color.White,
                radius = 5.dp.toPx(),
                center = tipPos
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = phase.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = phaseColor,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    mbps >= 100.0 -> String.format("%.1f", mbps)
                    else -> String.format("%.2f", mbps)
                },
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Mbps",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
