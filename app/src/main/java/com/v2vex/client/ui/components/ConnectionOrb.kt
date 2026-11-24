package com.v2vex.client.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2vex.client.model.VpnConnectionState

/**
 * Two-Tone Minimalist Geometric Connection Dial (Dieter Rams / Nothing OS inspired).
 */
@Composable
fun ConnectionOrb(
    state: VpnConnectionState,
    durationLabel: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 450f),
        label = "dialScale"
    )

    val primaryTone = MaterialTheme.colorScheme.primary
    val signalTone = MaterialTheme.colorScheme.secondary
    val outlineTone = MaterialTheme.colorScheme.outline
    val errorTone = MaterialTheme.colorScheme.error

    val activeColor by animateColorAsState(
        targetValue = when (state) {
            VpnConnectionState.CONNECTED -> signalTone
            VpnConnectionState.ERROR -> errorTone
            else -> primaryTone
        },
        animationSpec = tween(300),
        label = "dialTone"
    )

    val infinite = rememberInfiniteTransition(label = "dialSpin")
    val spinAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(196.dp)
            .scale(scale)
    ) {
        Canvas(modifier = Modifier.size(196.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.minDimension / 2f - 8.dp.toPx()

            // Architectural hairline track ring
            drawCircle(
                color = outlineTone,
                radius = outerRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Minimalist precision arc when connecting or connected
            if (state == VpnConnectionState.CONNECTING || state == VpnConnectionState.CONNECTED) {
                rotate(degrees = if (state == VpnConnectionState.CONNECTING) spinAngle else -90f) {
                    val d = outerRadius * 2f
                    drawArc(
                        color = activeColor,
                        startAngle = 0f,
                        sweepAngle = if (state == VpnConnectionState.CONNECTING) 90f else 360f,
                        useCenter = false,
                        topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                        size = Size(d, d),
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }

        // Tactile Two-Tone Center Button
        val isConnected = state == VpnConnectionState.CONNECTED
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(148.dp)
                .clip(CircleShape)
                .background(
                    if (isConnected) activeColor.copy(alpha = 0.10f)
                    else MaterialTheme.colorScheme.surface
                )
                .border(
                    width = 1.dp,
                    color = if (isConnected) activeColor.copy(alpha = 0.55f) else outlineTone,
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onToggle
                )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (isConnected) Icons.Rounded.Lock else Icons.Rounded.PlayArrow,
                    contentDescription = "Toggle Tunnel",
                    tint = activeColor,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (state) {
                        VpnConnectionState.CONNECTED -> "CONNECTED"
                        VpnConnectionState.CONNECTING -> "STARTING"
                        VpnConnectionState.ERROR -> "RETRY"
                        else -> "CONNECT"
                    },
                    color = activeColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.8.sp
                )
                if (isConnected && durationLabel.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = durationLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
