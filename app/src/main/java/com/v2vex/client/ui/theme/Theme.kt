package com.v2vex.client.ui.theme

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
import com.v2vex.client.model.ThemeMode

// Two-Tone Minimalist Tokens: Tone 1 (Monochrome Chalk/Ink) + Tone 2 (Surgical Signal Mint)
val XrayCyan = Color(0xFFF4F4F6)      // Tone 1: Crisp Chalk (Primary Interactive)
val XrayEmerald = Color(0xFF10B981)   // Tone 2: Surgical Signal Mint (Active Tunnel)
val XrayAmber = Color(0xFFD97706)     // Muted Amber (Warning only)
val XrayCoral = Color(0xFFEF4444)     // Clean Red (Error/Stop only)
val XrayViolet = Color(0xFFA1A1AA)    // Neutral Zinc Secondary

private val ObsidianTwoToneScheme = darkColorScheme(
    primary = Color(0xFFF4F4F6),
    onPrimary = Color(0xFF0A0A0C),
    primaryContainer = Color(0xFF1C1E24),
    onPrimaryContainer = Color(0xFFF4F4F6),
    secondary = Color(0xFF10B981),
    onSecondary = Color(0xFF032217),
    secondaryContainer = Color(0xFF0D2820),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFFA1A1AA),
    background = Color(0xFF0A0A0C),
    onBackground = Color(0xFFF4F4F6),
    surface = Color(0xFF121317),
    onSurface = Color(0xFFF4F4F6),
    surfaceVariant = Color(0xFF191B20),
    onSurfaceVariant = Color(0xFF8E929F),
    outline = Color(0xFF262932),
    error = XrayCoral
)

private val AmoledMonoScheme = ObsidianTwoToneScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF09090B),
    surfaceVariant = Color(0xFF121215),
    outline = Color(0xFF1F2026)
)

private val PaperInkLightScheme = lightColorScheme(
    primary = Color(0xFF111215),
    onPrimary = Color(0xFFF7F7F5),
    primaryContainer = Color(0xFFE8E8E4),
    onPrimaryContainer = Color(0xFF111215),
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFF52525B),
    background = Color(0xFFF6F6F3),
    onBackground = Color(0xFF111215),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111215),
    surfaceVariant = Color(0xFFEDEDEA),
    onSurfaceVariant = Color(0xFF52525B),
    outline = Color(0xFFD6D6D1),
    error = Color(0xFFDC2626)
)

private val V2VexTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        letterSpacing = (-0.4).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = (-0.1).sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.2.sp
    )
)

@Composable
fun V2VexTheme(
    themeMode: ThemeMode = ThemeMode.CYBER_DARK,
    content: @Composable () -> Unit
) {
    val scheme = when (themeMode) {
        ThemeMode.CYBER_DARK -> ObsidianTwoToneScheme
        ThemeMode.AMOLED_BLACK -> AmoledMonoScheme
        ThemeMode.LIGHT -> PaperInkLightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = V2VexTypography,
        content = content
    )
}
