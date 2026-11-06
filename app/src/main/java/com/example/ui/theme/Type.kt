package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.io.File

fun getAppFontFamily(fontType: String, customFontPath: String?): FontFamily {
    return when (fontType.lowercase()) {
        "serif" -> FontFamily.Serif
        "monospace" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        "sans_serif" -> FontFamily.SansSerif
        "custom" -> {
            if (!customFontPath.isNullOrBlank()) {
                val file = File(customFontPath)
                if (file.exists() && file.length() > 0) {
                    runCatching { FontFamily(Font(file)) }.getOrDefault(FontFamily.Default)
                } else FontFamily.Default
            } else FontFamily.Default
        }
        else -> FontFamily.Default
    }
}

fun createAppTypography(fontFamily: FontFamily): Typography {
    return Typography(
        displayLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 57.sp),
        displayMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 45.sp),
        displaySmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 36.sp),
        headlineLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 32.sp),
        headlineMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 28.sp),
        headlineSmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 24.sp),
        titleLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
        titleMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        bodyLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
        bodyMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        labelMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp),
        labelSmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp)
    )
}

val Typography = createAppTypography(FontFamily.Default)
