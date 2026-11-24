package com.v2vex.client.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2vex.client.model.VpnConnectionState
import com.v2vex.client.ui.theme.XrayAmber
import com.v2vex.client.ui.theme.XrayCoral
import com.v2vex.client.ui.theme.XrayCyan
import com.v2vex.client.ui.theme.XrayEmerald

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
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f),
        label = "orbScale"
    )

    val accentColor by animateColorAsState(
        targetValue = when (state) {
            VpnConnectionState.CONNECTED -> XrayEmerald
            VpnConnectionState.CONNECTING -> XrayAmber
            VpnConnectionState.ERROR -> XrayCoral
            else -> XrayCyan
        },
        animationSpec = tween(400),
        label = "orbAccent"
    )

    val infinite = rememberInfiniteTransition(label = "orbAnim")
    val pulse by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbPulse"
    )
    val spinAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbSpin"
    )

    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(216.dp)
            .scale(scale)
    ) {
        Canvas(modifier = Modifier.size(216.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension / 2f - 12.dp.toPx()

            // Outer ambient glow ring
            val glowRadius = if (state == VpnConnectionState.CONNECTED) baseRadius * pulse else baseRadius
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = if (state == VpnConnectionState.CONNECTED) 0.28f else 0.12f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = glowRadius + 16.dp.toPx()
                ),
                radius = glowRadius + 16.dp.toPx(),
                center = center
            )

            // Static track ring
            drawCircle(
                color = accentColor.copy(alpha = 0.18f),
                radius = baseRadius - 8.dp.toPx(),
                center = center,
                style = Stroke(width = 4.dp.toPx())
            )

            // Active animated arc
            if (state == VpnConnectionState.CONNECTING || state == VpnConnectionState.CONNECTED) {
                rotate(degrees = if (state == VpnConnectionState.CONNECTING) spinAngle else spinAngle * 0.25f) {
                    val arcSize = (baseRadius - 8.dp.toPx()) * 2f
                    val topLeft = Offset(center.x - arcSize / 2f, center.y - arcSize / 2f)
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(Color.Transparent, accentColor, accentColor.copy(alpha = 0.4f))
                        ),
                        startAngle = 0f,
                        sweepAngle = if (state == VpnConnectionState.CONNECTING) 140f else 260f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(arcSize, arcSize),
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }

        // Clickable core button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(152.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            surfaceColor.copy(alpha = 0.96f),
                            accentColor.copy(alpha = 0.14f)
                        )
                    )
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onToggle
                )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (state == VpnConnectionState.CONNECTED) Icons.Rounded.Lock else Icons.Rounded.PlayArrow,
                    contentDescription = "Toggle VPN",
                    tint = accentColor,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (state) {
                        VpnConnectionState.CONNECTED -> "ACTIVE"
                        VpnConnectionState.CONNECTING -> "TUNNELING"
                        VpnConnectionState.ERROR -> "RETRY"
                        else -> "CONNECT"
                    },
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 1.6.sp
                )
                if (state == VpnConnectionState.CONNECTED && durationLabel.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
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
