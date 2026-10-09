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
import androidx.compose.ui.text.style.TextOverflow
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
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    val (color, text) = when (state) {
        WsConnectionState.CONNECTED -> EmeraldSuccess to "Live"
        WsConnectionState.CONNECTING -> AmberWarning to "Connecting…"
        WsConnectionState.DISCONNECTED -> TextMuted to "Offline"
        WsConnectionState.ERROR -> CrimsonError to "Reconnecting…"
    }

    if (state == WsConnectionState.CONNECTED) {
        Box(
            modifier = modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
                .testTag("ws_connection_badge")
        )
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .glass(RoundedCornerShape(16.dp), strength = 0.8f)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("ws_connection_badge")
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (state == WsConnectionState.CONNECTING) color.copy(alpha = alpha) else color)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DarkTalkTopBar(
    title: String,
    subtitle: String? = null,
    onBackClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    wsState: WsConnectionState? = null,
    titleLeading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glass(
                RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp),
                strength = 1.1f,
                baseAlpha = 0.6f
            )
    ) {
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (onTitleClick != null) {
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onTitleClick() }
                    } else Modifier
                ) {
                    if (titleLeading != null) {
                        titleLeading()
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Column {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = if (subtitle.startsWith("typing")) CyanAccent else TextSecondary,
                                    fontSize = 12.sp
                                )
                            )
                        }
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
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
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
        val palettes = listOf(
            listOf(Color(0xFFFF8A8A), Color(0xFFE5486B)),
            listOf(Color(0xFFFFB454), Color(0xFFE67E22)),
            listOf(Color(0xFFB388FF), Color(0xFF7C4DFF)),
            listOf(Color(0xFF5EE6A8), Color(0xFF1FAF7A)),
            listOf(Color(0xFF4FD8FF), Color(0xFF1E9BD7)),
            listOf(Color(0xFF6FB7FF), Color(0xFF3A74E0)),
            listOf(Color(0xFFFF8FD0), Color(0xFFD6459A))
        )
        palettes[abs(displayName.hashCode()) % palettes.size]
    }

    val resolvedAvatar = remember(avatarUrl) { MediaUrlUtils.resolveUrl(avatarUrl) }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
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
                    .background(BackdropBase)
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
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(alpha = 0.10f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                focusedBorderColor = CyanAccent,
                unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
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
    val onAccent = LocalAppThemeColors.current.buttonContent
    val buttonColors = if (isSecondary) {
        ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = TextPrimary,
            disabledContainerColor = Color.White.copy(alpha = 0.05f),
            disabledContentColor = TextMuted
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = CyanAccent,
            contentColor = onAccent,
            disabledContainerColor = CyanAccent.copy(alpha = 0.3f),
            disabledContentColor = Color.White.copy(alpha = 0.4f)
        )
    }

    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        colors = buttonColors,
        shape = RoundedCornerShape(20.dp),
        border = if (isSecondary) BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)) else null,
        modifier = modifier
            .heightIn(min = 50.dp)
            .testTag(testTag)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = if (isSecondary) CyanAccent else onAccent,
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
