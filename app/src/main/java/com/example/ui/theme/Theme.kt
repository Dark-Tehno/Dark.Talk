package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalAppThemeColors = staticCompositionLocalOf { CyberThemeColors }

@Composable
fun DarkTalkTheme(
    themeName: String = "cyber",
    content: @Composable () -> Unit
) {
    val themeColors = remember(themeName) { getThemeColors(themeName) }

    val darkColorScheme = darkColorScheme(
        primary = themeColors.primary,
        onPrimary = themeColors.buttonContent,
        primaryContainer = themeColors.primaryContainer,
        onPrimaryContainer = themeColors.onPrimaryContainer,
        secondary = VioletAccent,
        onSecondary = Color(0xFF280056),
        secondaryContainer = VioletContainer,
        onSecondaryContainer = Color(0xFFEADBFF),
        tertiary = EmeraldSuccess,
        background = DarkBackground,
        onBackground = TextPrimary,
        surface = DarkSurface,
        onSurface = TextPrimary,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = TextSecondary,
        outline = Color(0xFF2D3748),
        outlineVariant = Color(0xFF1E2638),
        error = CrimsonError,
        onError = Color.White
    )

    CompositionLocalProvider(
        LocalAppThemeColors provides themeColors
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme,
            typography = Typography,
            content = content
        )
    }
}

// Backward compatibility alias
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    DarkTalkTheme(content = content)
}
