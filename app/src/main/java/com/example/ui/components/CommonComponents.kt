package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.network.WsConnectionState
import com.example.ui.theme.*
import com.example.util.MediaUrlUtils
import kotlin.math.abs

@Composable
fun ConnectionBadge(
    state: WsConnectionState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val (color, text) = when (state) {
        WsConnectionState.CONNECTED -> EmeraldSuccess to "Live WS"
        WsConnectionState.CONNECTING -> AmberWarning to "Connecting"
        WsConnectionState.DISCONNECTED -> TextMuted to "Offline"
        WsConnectionState.ERROR -> CrimsonError to "WS Error"
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = GlassSurfaceElevated,
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
        modifier = modifier.testTag("ws_connection_badge")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (state == WsConnectionState.CONNECTING) color.copy(alpha = alpha) else color
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DarkTalkTopBar(
    title: String,
    subtitle: String? = null,
    onBackClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    wsState: WsConnectionState? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp),
        color = GlassSurface,
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(CyanGlassBorder, Color(0x1AFFFFFF))
            )
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        TopAppBar(
            title = {
                Column(
                    modifier = if (onTitleClick != null) Modifier.clickable { onTitleClick() } else Modifier
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = CyanAccent,
                                fontSize = 12.sp
                            )
                        )
                    }
                }
            },
            navigationIcon = {
                if (onBackClick != null) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("top_bar_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                }
            },
            actions = {
                if (wsState != null) {
                    ConnectionBadge(state = wsState, modifier = Modifier.padding(end = 8.dp))
                }
                actions()
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )
    }
}

@Composable
fun AvatarView(
    avatarUrl: String?,
    displayName: String,
    size: Dp = 44.dp,
    isOnline: Boolean? = null,
    modifier: Modifier = Modifier
) {
    val initial = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val gradientColors = remember(displayName) {
        val hash = abs(displayName.hashCode())
        val palettes = listOf(
            listOf(Color(0xFF0284C7), Color(0xFF0369A1)),
            listOf(Color(0xFF7C3AED), Color(0xFF4C1D95)),
            listOf(Color(0xFF059669), Color(0xFF047857)),
            listOf(Color(0xFFDB2777), Color(0xFF9D174D)),
            listOf(Color(0xFFD97706), Color(0xFFB45309))
        )
        palettes[hash % palettes.size]
    }

    val resolvedAvatar = remember(avatarUrl) {
        MediaUrlUtils.resolveUrl(avatarUrl)
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (!resolvedAvatar.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(resolvedAvatar)
                    .crossfade(true)
                    .build(),
                contentDescription = "$displayName avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(1.5.dp, GlassBorder, CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Brush.linearGradient(gradientColors))
                    .border(1.5.dp, GlassBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.42f).sp
                )
            }
        }

        if (isOnline == true) {
            Box(
                modifier = Modifier
                    .size(size * 0.28f)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(DarkBackground)
                    .padding(1.5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(EmeraldSuccess)
                )
            }
        }
    }
}

@Composable
fun CyberTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    testTag: String = "cyber_text_field"
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it, color = TextMuted) } },
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            shape = RoundedCornerShape(22.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = GlassSurfaceElevated,
                unfocusedContainerColor = GlassSurface,
                focusedBorderColor = CyanAccent,
                unfocusedBorderColor = BubbleBorder,
                focusedLabelColor = CyanAccent,
                unfocusedLabelColor = TextSecondary,
                cursorColor = CyanAccent,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
        AnimatedVisibility(visible = isError && !errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage.orEmpty(),
                color = CrimsonError,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
            )
        }
    }
}

@Composable
fun CyberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    icon: @Composable (() -> Unit)? = null,
    isSecondary: Boolean = false,
    testTag: String = "cyber_button"
) {
    val buttonColors = if (isSecondary) {
        ButtonDefaults.buttonColors(
            containerColor = GlassSurfaceElevated,
            contentColor = TextPrimary,
            disabledContainerColor = GlassSurface,
            disabledContentColor = TextMuted
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = CyanAccent,
            contentColor = Color(0xFF001F28),
            disabledContainerColor = CyanAccent.copy(alpha = 0.3f),
            disabledContentColor = Color.White.copy(alpha = 0.4f)
        )
    }

    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        colors = buttonColors,
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .heightIn(min = 50.dp)
            .testTag(testTag)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = if (isSecondary) CyanAccent else Color(0xFF001F28),
                modifier = Modifier.size(20.dp)
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    icon()
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                )
            }
        }
    }
}
