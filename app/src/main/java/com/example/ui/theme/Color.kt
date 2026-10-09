package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Реальный фон рисует GlassBackdrop (MainActivity), поэтому экраны/Scaffold остаются прозрачными. */
val BackdropBase = Color(0xFF070B14)
val DarkBackground = Color.Transparent

val DarkSurface = Color(0xB3101828)
val DarkSurfaceVariant = Color(0xB31A2338)
val DarkSurfaceElevated = Color(0xCC1F2A44)

// Liquid Glass
val GlassSurface = Color(0x99101828)
val GlassSurfaceElevated = Color(0xB31C2740)
val GlassBorder = Color(0x33FFFFFF)
val GlassHighlight = Color(0x26FFFFFF)

data class AppThemeColors(
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val buttonContent: Color,
    val glassBorder: Color
)

val TelegramThemeColors = AppThemeColors(
    primary = Color(0xFF4EA8FF),
    primaryContainer = Color(0xFF16406F),
    onPrimaryContainer = Color(0xFFD3E8FF),
    buttonContent = Color(0xFFFFFFFF),
    glassBorder = Color(0x664EA8FF)
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
        "cyber" -> CyberThemeColors
        "neon" -> NeonThemeColors
        "emerald" -> EmeraldThemeColors
        "crimson" -> CrimsonThemeColors
        "amber" -> AmberThemeColors
        else -> TelegramThemeColors
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
val TextSecondary = Color(0xFFA3B1C6)
val TextMuted = Color(0xFF6E7C93)

val BubbleSelf = Color(0xE62A7FDB)
val BubbleOther = Color(0x26FFFFFF)
val BubbleBorder = Color(0x2EFFFFFF)

val GlassGlossBrush = Brush.verticalGradient(
    listOf(Color(0x26FFFFFF), Color(0x05FFFFFF))
)
