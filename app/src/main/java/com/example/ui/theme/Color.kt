package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val DarkBackground = Color(0xFF0A0D14)
val DarkSurface = Color(0xEB131722)
val DarkSurfaceVariant = Color(0xD91B212F)
val DarkSurfaceElevated = Color(0xCC242C3D)

// Liquid Glass Surface colors
val GlassSurface = Color(0xCC141824)
val GlassSurfaceElevated = Color(0xE61E2638)
val GlassBorder = Color(0x2BFFFFFF)
val GlassHighlight = Color(0x1AFFFFFF)

data class AppThemeColors(
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val buttonContent: Color,
    val glassBorder: Color
)

val CyberThemeColors = AppThemeColors(
    primary = Color(0xFF00E5FF),
    primaryContainer = Color(0xFF003844),
    onPrimaryContainer = Color(0xFF97F0FF),
    buttonContent = Color(0xFF001F28),
    glassBorder = Color(0x4D00E5FF)
)

val NeonThemeColors = AppThemeColors(
    primary = Color(0xFFA855F7),
    primaryContainer = Color(0xFF371E5E),
    onPrimaryContainer = Color(0xFFEADBFF),
    buttonContent = Color(0xFF1E0038),
    glassBorder = Color(0x4DA855F7)
)

val EmeraldThemeColors = AppThemeColors(
    primary = Color(0xFF10B981),
    primaryContainer = Color(0xFF064E3B),
    onPrimaryContainer = Color(0xFFA7F3D0),
    buttonContent = Color(0xFF002818),
    glassBorder = Color(0x4D10B981)
)

val CrimsonThemeColors = AppThemeColors(
    primary = Color(0xFFF43F5E),
    primaryContainer = Color(0xFF881337),
    onPrimaryContainer = Color(0xFFFECDD3),
    buttonContent = Color(0xFF2E000A),
    glassBorder = Color(0x4DF43F5E)
)

val AmberThemeColors = AppThemeColors(
    primary = Color(0xFFF59E0B),
    primaryContainer = Color(0xFF78350F),
    onPrimaryContainer = Color(0xFFFDE68A),
    buttonContent = Color(0xFF291500),
    glassBorder = Color(0x4DF59E0B)
)

fun getThemeColors(themeName: String): AppThemeColors {
    return when (themeName.lowercase()) {
        "neon" -> NeonThemeColors
        "emerald" -> EmeraldThemeColors
        "crimson" -> CrimsonThemeColors
        "amber" -> AmberThemeColors
        else -> CyberThemeColors
    }
}

val CyanAccent: Color
    @Composable
    get() = LocalAppThemeColors.current.primary

val CyanAccentMuted: Color
    @Composable
    get() = LocalAppThemeColors.current.primary.copy(alpha = 0.7f)

val CyanAccentContainer: Color
    @Composable
    get() = LocalAppThemeColors.current.primaryContainer

val CyanGlassBorder: Color
    @Composable
    get() = LocalAppThemeColors.current.glassBorder

val VioletAccent = Color(0xFFB388FF)
val VioletContainer = Color(0xFF371E5E)

val EmeraldSuccess = Color(0xFF00E676)
val CrimsonError = Color(0xFFFF5252)
val AmberWarning = Color(0xFFFFB300)

val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)

val BubbleSelf = Color(0xE60284C7)
val BubbleOther = Color(0xD91E2638)
val BubbleBorder = Color(0x33FFFFFF)

// Glassmorphism Brushes
val GlassGlossBrush = Brush.verticalGradient(
    listOf(Color(0x26FFFFFF), Color(0x05FFFFFF))
)
