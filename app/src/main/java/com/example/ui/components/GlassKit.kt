package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.BackdropBase
import com.example.ui.theme.LocalAppThemeColors
import com.example.ui.theme.VioletAccent

/**
 * "Жидкое стекло": полупрозрачная тёмная основа, вертикальный блик и градиентная
 * кромка (яркая сверху-слева, затухающая к центру) – имитация преломления света.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    tint: Color = Color.White,
    strength: Float = 1f,
    baseAlpha: Float = 0.5f,
    borderWidth: Dp = 1.dp
): Modifier = this
    .clip(shape)
    .background(Color(0xFF0B1224).copy(alpha = baseAlpha), shape)
    .background(
        Brush.verticalGradient(
            listOf(tint.copy(alpha = 0.16f * strength), tint.copy(alpha = 0.04f * strength))
        ),
        shape
    )
    .border(
        borderWidth,
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.50f),
                Color.White.copy(alpha = 0.05f),
                Color.White.copy(alpha = 0.20f)
            )
        ),
        shape
    )

/** Общий фон приложения: тёмная база + медленно «плавающие» цветные пятна света. */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val accent = LocalAppThemeColors.current.primary
    val transition = rememberInfiniteTransition(label = "backdrop")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse),
        label = "backdropT"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackdropBase)
            .drawBehind {
                val w = size.width
                val h = size.height
                fun blob(color: Color, cx: Float, cy: Float, r: Float) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(color, Color.Transparent),
                            center = Offset(cx, cy),
                            radius = r
                        ),
                        radius = r,
                        center = Offset(cx, cy)
                    )
                }
                blob(accent.copy(alpha = 0.38f), w * (0.88f - 0.18f * t), h * (0.08f + 0.06f * t), w * 0.85f)
                blob(VioletAccent.copy(alpha = 0.28f), w * (0.08f + 0.14f * t), h * (0.55f - 0.08f * t), w * 0.9f)
                blob(Color(0xFF00BFA5).copy(alpha = 0.16f), w * 0.75f, h * (0.95f - 0.10f * t), w * 0.8f)
            },
        content = content
    )
}

@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    accent: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(8.dp)
                .glass(RoundedCornerShape(28.dp), tint = accent, strength = 0.6f, baseAlpha = 0.92f)
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            content = content
        )
    }
}
