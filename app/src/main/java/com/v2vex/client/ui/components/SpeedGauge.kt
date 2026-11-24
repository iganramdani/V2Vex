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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2vex.client.model.SpeedTestPhase
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Two-Tone Minimalist Logarithmic Speedometer (0 -> 1000+ Mbps).
 * Pure monochrome precision ticks + single surgical active arc.
 */
@Composable
fun SpeedGauge(
    mbps: Double,
    phase: SpeedTestPhase,
    modifier: Modifier = Modifier
) {
    val normalizedTarget = if (mbps <= 0.0) 0f else {
        (ln(1.0 + mbps) / ln(1.0 + 1000.0)).toFloat().coerceIn(0f, 1f)
    }

    val animatedFraction by animateFloatAsState(
        targetValue = normalizedTarget,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 140f),
        label = "gaugeNeedle"
    )

    val primaryTone = MaterialTheme.colorScheme.primary
    val signalTone = MaterialTheme.colorScheme.secondary
    val trackColor = MaterialTheme.colorScheme.outline

    val activeColor = when (phase) {
        SpeedTestPhase.UPLOAD, SpeedTestPhase.COMPLETED -> signalTone
        else -> primaryTone
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(228.dp)
    ) {
        Canvas(modifier = Modifier.size(228.dp)) {
            val strokeW = 8.dp.toPx()
            val inset = strokeW + 14.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val startAngle = 140f
            val totalSweep = 260f

            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )

            val activeSweep = totalSweep * animatedFraction
            if (activeSweep > 0.5f) {
                drawArc(
                    color = activeColor,
                    startAngle = startAngle,
                    sweepAngle = activeSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )
            }

            // Minimalist architectural tick marks
            val center = Offset(size.width / 2f, size.height / 2f)
            val tickOuter = size.minDimension / 2f - 3.dp.toPx()
            val tickInner = tickOuter - 6.dp.toPx()
            for (i in 0..24) {
                val frac = i / 24f
                val rad = Math.toRadians((startAngle + totalSweep * frac).toDouble())
                val p1 = Offset(
                    center.x + (tickInner * cos(rad)).toFloat(),
                    center.y + (tickInner * sin(rad)).toFloat()
                )
                val p2 = Offset(
                    center.x + (tickOuter * cos(rad)).toFloat(),
                    center.y + (tickOuter * sin(rad)).toFloat()
                )
                drawLine(
                    color = if (frac <= animatedFraction) activeColor else trackColor,
                    start = p1,
                    end = p2,
                    strokeWidth = if (i % 6 == 0) 2.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = phase.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.2.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (mbps >= 100.0) String.format("%.1f", mbps) else String.format("%.2f", mbps),
                fontFamily = FontFamily.Monospace,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Mbps",
                style = MaterialTheme.typography.labelMedium,
                color = activeColor
            )
        }
    }
}
