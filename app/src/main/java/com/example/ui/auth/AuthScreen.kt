package com.example.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.ui.components.CyberButton
import com.example.ui.components.CyberTextField
import com.example.ui.theme.*

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    serverDomain: String,
    onNavigateToSettings: () -> Unit,
    onAuthSuccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var isRegisterMode by remember { mutableStateOf(false) }

    // Login state
    var loginUsername by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var showLoginPassword by remember { mutableStateOf(false) }

    // Register state
    var regEmail by remember { mutableStateOf("") }
    var regPassword by remember { mutableStateOf("") }
    var regUsername by remember { mutableStateOf("") }
    var regLanguage by remember { mutableStateOf("Russian") }
    var regDob by remember { mutableStateOf("") }
    var showRegPassword by remember { mutableStateOf(false) }

    // 2FA state
    var twoFaCode by remember { mutableStateOf("") }

    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) {
            onAuthSuccess()
        }
    }

    Scaffold(
        containerColor = DarkBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            DarkBackground,
                            DarkSurface.copy(alpha = 0.8f),
                            DarkBackground
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                // App Logo and Brand
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(DarkSurfaceElevated)
                        .border(1.5.dp, CyanAccent.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_app_logo),
                        contentDescription = "Dark.Talk Logo",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Dark.Talk",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = TextPrimary
                    )
                )
                Text(
                    text = "Secure Real-time Messaging",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = CyanAccentMuted,
                        fontSize = 13.sp
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Server domain badge (clickable)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = DarkSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BubbleBorder),
                    modifier = Modifier
                        .clickable { onNavigateToSettings() }
                        .testTag("server_badge")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = "Server Domain",
                            tint = CyanAccent,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Server: $serverDomain",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Configure Server",
                            tint = TextMuted,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Segmented Tab Switcher
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DarkSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BubbleBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (!isRegisterMode) CyanAccent else Color.Transparent)
                                .clickable {
                                    isRegisterMode = false
                                    viewModel.clearError()
                                }
                                .testTag("tab_login"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Sign In",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (!isRegisterMode) Color(0xFF001F28) else TextSecondary
                                )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isRegisterMode) CyanAccent else Color.Transparent)
                                .clickable {
                                    isRegisterMode = true
                                    viewModel.clearError()
                                }
                                .testTag("tab_register"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Register",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRegisterMode) Color(0xFF001F28) else TextSecondary
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Error Notice
                AnimatedVisibility(visible = !uiState.error.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CrimsonError.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CrimsonError.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Error",
                                tint = CrimsonError,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.error.orEmpty(),
                                color = CrimsonError,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (!isRegisterMode) {
                    // LOGIN FORM
                    CyberTextField(
                        value = loginUsername,
                        onValueChange = { loginUsername = it },
                        label = "Username",
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null, tint = CyanAccent)
                        },
                        placeholder = "Enter your username",
                        testTag = "login_username_input"
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    CyberTextField(
                        value = loginPassword,
                        onValueChange = { loginPassword = it },
                        label = "Password",
                        visualTransformation = if (showLoginPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = CyanAccent)
                        },
                        trailingIcon = {
                            IconButton(onClick = { showLoginPassword = !showLoginPassword }) {
                                Icon(
                                    imageVector = if (showLoginPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle password visibility",
                                    tint = TextSecondary
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        placeholder = "Enter secret password",
                        testTag = "login_password_input"
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    CyberButton(
                        text = "Sign In",
                        onClick = { viewModel.login(loginUsername, loginPassword) },
                        isLoading = uiState.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "login_submit_button"
                    )
                } else {
                    // REGISTER FORM
                    CyberTextField(
                        value = regEmail,
                        onValueChange = { regEmail = it },
                        label = "Email Address *",
                        leadingIcon = {
                            Icon(Icons.Default.Email, contentDescription = null, tint = CyanAccent)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        placeholder = "user@example.com",
                        testTag = "register_email_input"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    CyberTextField(
                        value = regPassword,
                        onValueChange = { regPassword = it },
                        label = "Password *",
                        visualTransformation = if (showRegPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = CyanAccent)
                        },
                        trailingIcon = {
                            IconButton(onClick = { showRegPassword = !showRegPassword }) {
                                Icon(
                                    imageVector = if (showRegPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle password visibility",
                                    tint = TextSecondary
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        placeholder = "Create strong password",
                        testTag = "register_password_input"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    CyberTextField(
                        value = regUsername,
                        onValueChange = { regUsername = it },
                        label = "Username (Optional)",
                        leadingIcon = {
                            Icon(Icons.Default.AlternateEmail, contentDescription = null, tint = CyanAccent)
                        },
                        placeholder = "Leave empty to use email prefix",
                        testTag = "register_username_input"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Language Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Language:",
                            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        FilterChip(
                            selected = regLanguage == "Russian",
                            onClick = { regLanguage = "Russian" },
                            label = { Text("Russian") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanAccentContainer,
                                selectedLabelColor = CyanAccent
                            ),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        FilterChip(
                            selected = regLanguage == "English",
                            onClick = { regLanguage = "English" },
                            label = { Text("English") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanAccentContainer,
                                selectedLabelColor = CyanAccent
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    CyberTextField(
                        value = regDob,
                        onValueChange = { regDob = it },
                        label = "Date of Birth (Optional)",
                        leadingIcon = {
                            Icon(Icons.Default.CalendarToday, contentDescription = null, tint = CyanAccent)
                        },
                        placeholder = "YYYY-MM-DD (e.g. 2000-01-31)",
                        testTag = "register_dob_input"
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    CyberButton(
                        text = "Create Account",
                        onClick = {
                            viewModel.register(regEmail, regPassword, regUsername, regLanguage, regDob)
                        },
                        isLoading = uiState.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "register_submit_button"
                    )
                }

                Spacer(modifier = Modifier.height(30.dp))
            }

            // 2FA CHALLENGE DIALOG
            if (uiState.requires2Fa) {
                Dialog(onDismissRequest = { viewModel.cancel2Fa() }) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = DarkSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(CyanAccentContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = "2FA",
                                    tint = CyanAccent,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Two-Factor Auth",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "A 6-digit confirmation code was sent to your registered email address.",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = TextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            CyberTextField(
                                value = twoFaCode,
                                onValueChange = { if (it.length <= 6) twoFaCode = it },
                                label = "Verification Code",
                                placeholder = "123456",
                                leadingIcon = {
                                    Icon(Icons.Default.Key, contentDescription = null, tint = CyanAccent)
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                testTag = "2fa_code_input"
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            CyberButton(
                                text = "Verify & Sign In",
                                onClick = { viewModel.verify2Fa(twoFaCode) },
                                isLoading = uiState.isLoading,
                                modifier = Modifier.fillMaxWidth(),
                                testTag = "2fa_verify_button"
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                TextButton(
                                    onClick = { viewModel.resend2Fa() },
                                    modifier = Modifier.testTag("2fa_resend_button")
                                ) {
                                    Text("Resend Code", color = CyanAccent)
                                }

                                TextButton(
                                    onClick = { viewModel.cancel2Fa() },
                                    modifier = Modifier.testTag("2fa_cancel_button")
                                ) {
                                    Text("Cancel", color = TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
