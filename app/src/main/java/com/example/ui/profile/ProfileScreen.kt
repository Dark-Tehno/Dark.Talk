package com.example.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AvatarView
import com.example.ui.components.CyberButton
import com.example.ui.components.DarkTalkTopBar
import com.example.ui.theme.*

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onBackClick: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isLoggedOut) {
        if (uiState.isLoggedOut) {
            onLoggedOut()
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            DarkTalkTopBar(
                title = "Profile & Security",
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Notice banner
            AnimatedVisibility(visible = !uiState.message.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CyanAccentContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = CyanAccent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.message.orEmpty(),
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearMessage() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                        }
                    }
                }
            }

            // User Info Card
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = DarkSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, BubbleBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val user = uiState.user
                    val displayName = user?.username ?: "User"

                    AvatarView(
                        avatarUrl = user?.avatar,
                        displayName = displayName,
                        size = 80.dp,
                        isOnline = user?.isOnline ?: true
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )

                    if (!user?.email.isNullOrBlank()) {
                        Text(
                            text = user?.email.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        InfoPill(label = "User ID", value = "#${user?.id ?: "-"}")
                        InfoPill(label = "Language", value = user?.language ?: "Russian")
                        InfoPill(label = "Status", value = if (user?.isOnline == true) "Online" else "Active")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Security & 2FA Section
            Text(
                text = "Security Settings",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DarkSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, BubbleBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (uiState.twoFactorEnabled) CyanAccentContainer else DarkSurfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "2FA",
                            tint = if (uiState.twoFactorEnabled) CyanAccent else TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Two-Factor Authentication (2FA)",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = if (uiState.twoFactorEnabled) "Enabled — code is sent on login" else "Disabled — login with password only",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                    }

                    Switch(
                        checked = uiState.twoFactorEnabled,
                        onCheckedChange = { viewModel.toggle2Fa(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF001F28),
                            checkedTrackColor = CyanAccent,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = DarkSurfaceElevated
                        ),
                        modifier = Modifier.testTag("2fa_toggle_switch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Devices Section
            Text(
                text = "Registered Devices (${uiState.devices.size})",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (uiState.devices.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DarkSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No device records available.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.devices.forEach { dev ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BubbleBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Devices,
                                    contentDescription = null,
                                    tint = CyanAccent,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dev.name ?: dev.deviceId,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                    )
                                    Text(
                                        text = "ID: ${dev.deviceId} • ${dev.deviceType ?: "client"}",
                                        style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                                    )
                                }
                                if (dev.trusted == true) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = EmeraldSuccess.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = "Trusted",
                                            color = EmeraldSuccess,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Login History Section
            if (uiState.loginHistory.isNotEmpty()) {
                Text(
                    text = "Recent Login History",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.loginHistory.take(5).forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BubbleBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${item.city ?: "Location"}, ${item.country ?: "Unknown"}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                    )
                                    Text(
                                        text = "IP: ${item.ip ?: "-"} • ${item.createdAt?.take(16) ?: ""}",
                                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                                    )
                                }
                                Text(
                                    text = item.status ?: "success",
                                    color = if (item.status == "success") EmeraldSuccess else CrimsonError,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Logout Button
            CyberButton(
                text = "Sign Out",
                onClick = { viewModel.logout() },
                isLoading = uiState.isLoading,
                isSecondary = true,
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = "Sign out",
                        tint = CrimsonError
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("logout_button")
            )

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
fun InfoPill(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = DarkSurfaceElevated,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 10.sp))
            Text(text = value, style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary, fontWeight = FontWeight.Bold))
        }
    }
}
