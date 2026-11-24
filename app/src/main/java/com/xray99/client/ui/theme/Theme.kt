package com.xray99.client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.xray99.client.model.ThemeMode

val XrayCyan = Color(0xFF00E5FF)
val XrayEmerald = Color(0xFF00E676)
val XrayAmber = Color(0xFFFFB300)
val XrayCoral = Color(0xFFFF5252)
val XrayViolet = Color(0xFF7C4DFF)

private val CyberDarkScheme = darkColorScheme(
    primary = XrayCyan,
    onPrimary = Color(0xFF001F24),
    primaryContainer = Color(0xFF0A2E38),
    onPrimaryContainer = Color(0xFFB4F8FF),
    secondary = XrayEmerald,
    onSecondary = Color(0xFF00210E),
    secondaryContainer = Color(0xFF0A2F1E),
    onSecondaryContainer = Color(0xFFB9F6CA),
    tertiary = XrayViolet,
    background = Color(0xFF090D16),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF0F1624),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF172133),
    onSurfaceVariant = Color(0xFF98A8C2),
    outline = Color(0xFF26334B),
    error = XrayCoral
)

private val AmoledBlackScheme = CyberDarkScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF0A0D12),
    surfaceVariant = Color(0xFF121721),
    outline = Color(0xFF1F2736)
)

private val CleanLightScheme = lightColorScheme(
    primary = Color(0xFF007A8C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0F4FA),
    onPrimaryContainer = Color(0xFF00363F),
    secondary = Color(0xFF008744),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F5E3),
    onSecondaryContainer = Color(0xFF00391B),
    tertiary = Color(0xFF5E35B1),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE7EEF6),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFD32F2F)
)

private val XRayTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp
    )
)

@Composable
fun XRay99Theme(
    themeMode: ThemeMode = ThemeMode.CYBER_DARK,
    content: @Composable () -> Unit
) {
    val scheme = when (themeMode) {
        ThemeMode.CYBER_DARK -> CyberDarkScheme
        ThemeMode.AMOLED_BLACK -> AmoledBlackScheme
        ThemeMode.LIGHT -> CleanLightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = XRayTypography,
        content = content
    )
}
