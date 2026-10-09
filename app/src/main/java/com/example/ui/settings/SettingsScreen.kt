package com.example.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ui.components.AvatarView
import com.example.ui.components.CyberButton
import com.example.ui.components.CyberTextField
import com.example.ui.components.DarkTalkTopBar
import com.example.ui.theme.*
import com.example.util.LocalAppStrings
import com.example.util.MediaUrlUtils
import com.example.util.isEnglishLanguage
import kotlinx.coroutines.launch
import java.util.Locale

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val clampedGroup = digitGroups.coerceIn(0, units.lastIndex)
    val value = bytes / Math.pow(1024.0, clampedGroup.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[clampedGroup])
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    initialTab: Int = 0,
    onBackClick: () -> Unit,
    onLoggedOut: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(initialTab) {
        if (initialTab in 0..4 && uiState.selectedTab != initialTab) {
            viewModel.selectTab(initialTab)
        }
    }

    LaunchedEffect(uiState.isLoggedOut) {
        if (uiState.isLoggedOut) {
            onLoggedOut?.invoke()
        }
    }

    // Avatar image picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val fileName = "avatar_${System.currentTimeMillis()}.jpg"
                    if (bytes != null) {
                        viewModel.onAvatarPicked(bytes, fileName, mimeType)
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            DarkTalkTopBar(
                title = strings.settingsTitle,
                subtitle = when (uiState.selectedTab) {
                    0 -> strings.profileTab
                    1 -> strings.securityTab
                    2 -> strings.networkTab
                    3 -> strings.storageTab
                    else -> strings.personalizationTab
                },
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Telegram-style Scrollable Tab Row
            ScrollableTabRow(
                selectedTabIndex = uiState.selectedTab,
                containerColor = DarkSurface,
                contentColor = CyanAccent,
                edgePadding = 12.dp,
                indicator = { tabPositions ->
                    if (uiState.selectedTab in tabPositions.indices) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[uiState.selectedTab]),
                            color = CyanAccent
                        )
                    }
                },
                divider = {
                    HorizontalDivider(color = BubbleBorder, thickness = 0.5.dp)
                }
            ) {
                Tab(
                    selected = uiState.selectedTab == 0,
                    onClick = { viewModel.selectTab(0) },
                    text = {
                        Text(
                            text = strings.profileTab,
                            fontWeight = if (uiState.selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    icon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    selectedContentColor = CyanAccent,
                    unselectedContentColor = TextSecondary,
                    modifier = Modifier.testTag("settings_tab_profile")
                )

                Tab(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.selectTab(1) },
                    text = {
                        Text(
                            text = strings.securityTab,
                            fontWeight = if (uiState.selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    icon = { Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    selectedContentColor = CyanAccent,
                    unselectedContentColor = TextSecondary,
                    modifier = Modifier.testTag("settings_tab_security")
                )

                Tab(
                    selected = uiState.selectedTab == 2,
                    onClick = { viewModel.selectTab(2) },
                    text = {
                        Text(
                            text = strings.networkTab,
                            fontWeight = if (uiState.selectedTab == 2) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    selectedContentColor = CyanAccent,
                    unselectedContentColor = TextSecondary,
                    modifier = Modifier.testTag("settings_tab_network")
                )

                Tab(
                    selected = uiState.selectedTab == 3,
                    onClick = { viewModel.selectTab(3) },
                    text = {
                        Text(
                            text = strings.storageTab,
                            fontWeight = if (uiState.selectedTab == 3) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    selectedContentColor = CyanAccent,
                    unselectedContentColor = TextSecondary,
                    modifier = Modifier.testTag("settings_tab_storage")
                )

                Tab(
                    selected = uiState.selectedTab == 4,
                    onClick = { viewModel.selectTab(4) },
                    text = {
                        Text(
                            text = strings.personalizationTab,
                            fontWeight = if (uiState.selectedTab == 4) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    icon = { Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    selectedContentColor = CyanAccent,
                    unselectedContentColor = TextSecondary,
                    modifier = Modifier.testTag("settings_tab_personalization")
                )
            }

            // Tab Content Body
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                when (uiState.selectedTab) {
                    0 -> ProfileTabContent(
                        uiState = uiState,
                        viewModel = viewModel,
                        onPickAvatar = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                    1 -> SecurityTabContent(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                    2 -> NetworkTabContent(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                    3 -> StorageTabContent(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                    4 -> PersonalizationTabContent(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

// TAB 0: PROFILE & ACCOUNT
@Composable
fun ProfileTabContent(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onPickAvatar: () -> Unit
) {
    val strings = LocalAppStrings.current
    Column {
        // Notice banner
        AnimatedVisibility(visible = !uiState.profileMessage.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (uiState.isProfileError) CrimsonError.copy(alpha = 0.15f) else EmeraldSuccess.copy(alpha = 0.15f),
                border = BorderStroke(
                    1.dp,
                    if (uiState.isProfileError) CrimsonError else EmeraldSuccess
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (uiState.isProfileError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (uiState.isProfileError) CrimsonError else EmeraldSuccess
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.profileMessage.orEmpty(),
                        color = if (uiState.isProfileError) CrimsonError else EmeraldSuccess,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearProfileMessage() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }
            }
        }

        // Profile Preview Header Card
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, BubbleBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    AvatarView(
                        avatarUrl = uiState.user?.avatar,
                        displayName = uiState.editUsername.ifBlank { uiState.user?.username ?: "User" },
                        size = 84.dp,
                        isOnline = uiState.user?.isOnline ?: true
                    )

                    SmallFloatingActionButton(
                        onClick = onPickAvatar,
                        containerColor = CyanAccent,
                        contentColor = Color(0xFF001F28),
                        shape = CircleShape,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("change_avatar_button")
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "Change avatar", modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = uiState.user?.username ?: "User",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )

                if (!uiState.user?.email.isNullOrBlank()) {
                    Text(
                        text = uiState.user?.email.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                    )
                }

                if (uiState.avatarBytes != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "New avatar selected: ${uiState.avatarFileName}",
                        color = CyanAccent,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Edit Profile Details",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Username
        CyberTextField(
            value = uiState.editUsername,
            onValueChange = { viewModel.onUsernameChanged(it) },
            label = "Username",
            placeholder = "e.g. john_doe",
            leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null, tint = CyanAccent) },
            testTag = "edit_username_input"
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Info / Bio
        CyberTextField(
            value = uiState.editInfo,
            onValueChange = { viewModel.onInfoChanged(it) },
            label = strings.bioLabel,
            placeholder = "Say something about yourself",
            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = CyanAccent) },
            singleLine = false,
            testTag = "edit_info_input"
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Date of Birth
        CyberTextField(
            value = uiState.editDateOfBirth,
            onValueChange = { viewModel.onDateOfBirthChanged(it) },
            label = "${strings.dobLabel} (YYYY-MM-DD)",
            placeholder = "2000-01-31",
            leadingIcon = { Icon(Icons.Default.Cake, contentDescription = null, tint = CyanAccent) },
            testTag = "edit_dob_input"
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Language Selector
        Text(
            text = strings.languageLabel,
            style = MaterialTheme.typography.labelLarge.copy(color = TextSecondary)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf("Russian", "English").forEach { lang ->
                val isSelected = isEnglishLanguage(uiState.editLanguage) == lang.equals("English", ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { viewModel.onLanguageChanged(lang) },
                    label = { Text(lang) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanAccentContainer,
                        selectedLabelColor = CyanAccent,
                        containerColor = DarkSurface,
                        labelColor = TextSecondary
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Avatar Access Selector
        Text(
            text = "Avatar Visibility Privacy",
            style = MaterialTheme.typography.labelLarge.copy(color = TextSecondary)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            mapOf("all" to "Everyone", "authenticated" to "Logged In", "nobody" to "Nobody").forEach { (key, label) ->
                val isSelected = uiState.editAvatarAccess.equals(key, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { viewModel.onAvatarAccessChanged(key) },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanAccentContainer,
                        selectedLabelColor = CyanAccent,
                        containerColor = DarkSurface,
                        labelColor = TextSecondary
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        CyberButton(
            text = strings.saveProfileButton,
            onClick = { viewModel.saveProfile() },
            isLoading = uiState.isProfileSaving,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("save_profile_button")
        )

        Spacer(modifier = Modifier.height(30.dp))
    }
}

// TAB 1: SECURITY & SESSIONS
@Composable
fun SecurityTabContent(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val strings = LocalAppStrings.current
    Column {
        Text(
            text = "Two-Factor Authentication",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(10.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, BubbleBorder),
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
                        text = "Two-Factor Protection",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = if (uiState.twoFactorEnabled) "Enabled — email confirmation code required on sign in" else "Disabled — sign in with password only",
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

        Text(
            text = "Active Devices (${uiState.devices.size})",
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
                        border = BorderStroke(1.dp, BubbleBorder),
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

        if (uiState.loginHistory.isNotEmpty()) {
            Text(
                text = "Recent Login Sessions",
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
                        border = BorderStroke(1.dp, BubbleBorder),
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

        CyberButton(
            text = strings.signOutButton,
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

// TAB 2: NETWORK & APP
@Composable
fun NetworkTabContent(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    var domainInput by remember(uiState.serverDomain) { mutableStateOf(uiState.serverDomain) }
    var secretKeyInput by remember(uiState.appSecretKey) { mutableStateOf(uiState.appSecretKey) }
    var deviceIdInput by remember(uiState.deviceId) { mutableStateOf(uiState.deviceId) }
    var useSslState by remember(uiState.useSsl) { mutableStateOf(uiState.useSsl) }

    Column {
        AnimatedVisibility(visible = uiState.isNetworkSaved) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = EmeraldSuccess.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldSuccess)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Configuration saved successfully. Connections re-established.",
                        color = EmeraldSuccess,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearNetworkNotice() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }
            }
        }

        AnimatedVisibility(visible = !uiState.errorMessage.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CrimsonError.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, CrimsonError.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CrimsonError)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.errorMessage.orEmpty(),
                        color = CrimsonError,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Text(
            text = "Server Endpoint",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Default domain is configured in .env (SERVER_DOMAIN=vsp210.ru). You can change it below.",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )

        Spacer(modifier = Modifier.height(14.dp))

        CyberTextField(
            value = domainInput,
            onValueChange = {
                domainInput = it
                viewModel.updateServerDomain(it)
            },
            label = "Server Domain",
            placeholder = "vsp210.ru",
            leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null, tint = CyanAccent) },
            testTag = "settings_domain_input"
        )

        Spacer(modifier = Modifier.height(16.dp))

        // SSL Switch
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, BubbleBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (useSslState) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (useSslState) CyanAccent else AmberWarning,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (useSslState) "Secure TLS (HTTPS / WSS)" else "Plain (HTTP / WS)",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = if (useSslState) "Encrypted TLS connections (recommended)" else "Unencrypted development only",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                    )
                }
                Switch(
                    checked = useSslState,
                    onCheckedChange = {
                        useSslState = it
                        viewModel.updateUseSsl(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF001F28),
                        checkedTrackColor = CyanAccent,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = DarkSurfaceElevated
                    ),
                    modifier = Modifier.testTag("ssl_toggle_switch")
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Active Endpoints Preview
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurfaceElevated,
            border = BorderStroke(1.dp, BubbleBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Active Endpoints Preview",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                val schemeHttp = if (useSslState) "https" else "http"
                val schemeWs = if (useSslState) "wss" else "ws"
                val cleanDomain = domainInput.removePrefix("http://").removePrefix("https://").trimEnd('/')

                Text(
                    text = "REST: $schemeHttp://$cleanDomain/chat/api/",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "WS: $schemeWs://$cleanDomain/ws/chats/",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Secret Key & Device ID
        Text(
            text = "Application Headers",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(14.dp))

        CyberTextField(
            value = secretKeyInput,
            onValueChange = {
                secretKeyInput = it
                viewModel.updateAppSecretKey(it)
            },
            label = "Application Secret Key",
            placeholder = "darktalk_secret_key_prod",
            leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null, tint = CyanAccent) },
            testTag = "settings_secret_key_input"
        )

        Spacer(modifier = Modifier.height(12.dp))

        CyberTextField(
            value = deviceIdInput,
            onValueChange = {
                deviceIdInput = it
                viewModel.updateDeviceId(it)
            },
            label = "Device ID",
            placeholder = "android-client-id",
            leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = CyanAccent) },
            testTag = "settings_device_id_input"
        )

        Spacer(modifier = Modifier.height(24.dp))

        AnimatedVisibility(visible = !uiState.pingResult.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = GlassSurfaceElevated,
                border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Dns, contentDescription = null, tint = CyanAccent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.pingResult.orEmpty(),
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CyberButton(
                text = "Ping Server",
                onClick = { viewModel.testPingServer() },
                isLoading = uiState.isPinging,
                isSecondary = true,
                modifier = Modifier.weight(1f),
                testTag = "settings_ping_button"
            )

            CyberButton(
                text = "Save Config",
                onClick = { viewModel.saveNetworkSettings() },
                modifier = Modifier.weight(1f),
                testTag = "settings_save_button"
            )
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

// TAB 3: STORAGE & DATA (Данные и память)
@Composable
fun StorageTabContent(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    var customGbInput by remember(uiState.customCacheGbInput) { mutableStateOf(uiState.customCacheGbInput) }

    LaunchedEffect(Unit) {
        viewModel.loadStorageInfo(context)
    }

    Column {
        AnimatedVisibility(visible = !uiState.cacheClearedMessage.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = EmeraldSuccess.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldSuccess)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.cacheClearedMessage.orEmpty(),
                        color = EmeraldSuccess,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearCacheNotice() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }
            }
        }

        Text(
            text = "Storage Usage",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Local media cache stores downloaded images, videos, voice messages, and file attachments.",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Cache Status Card
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, BubbleBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(CyanAccentContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = "Cache",
                            tint = CyanAccent,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = formatFileSize(uiState.cacheSizeBytes),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = "${uiState.cacheFileCount} cached media items",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                CyberButton(
                    text = strings.clearCacheButton,
                    onClick = { viewModel.clearMediaCache(context) },
                    isSecondary = true,
                    icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = CrimsonError) },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "clear_cache_button"
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Max Cache Limit & Custom GB Input
        Text(
            text = "Max Cache Limit",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Set a custom limit in GB (e.g. 5, 8, 16, 9.53) or choose Unlimited.",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 5 Prominent Limit Preset Buttons
        val presets = listOf(
            1024L to "1 ГБ",
            4096L to "4 ГБ (По умолчанию)",
            8192L to "8 ГБ",
            16384L to "16 ГБ",
            -1L to "Бесконечность ∞"
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (mb, label) ->
                val isSelected = uiState.maxCacheMb == mb
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) CyanAccentContainer else DarkSurface,
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) CyanAccent else BubbleBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            viewModel.updateMaxCacheMbPreset(mb, context)
                            customGbInput = if (mb <= 0L) "Бесконечность" else String.format(Locale.US, "%.2f", mb / 1024.0)
                        }
                        .testTag("max_cache_preset_${mb}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                viewModel.updateMaxCacheMbPreset(mb, context)
                                customGbInput = if (mb <= 0L) "Бесконечность" else String.format(Locale.US, "%.2f", mb / 1024.0)
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = CyanAccent,
                                unselectedColor = TextMuted
                            )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (isSelected) CyanAccent else TextPrimary,
                                fontSize = 15.sp
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Custom GB Input Field
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CyberTextField(
                value = customGbInput,
                onValueChange = { customGbInput = it },
                label = "Своё значение (ГБ)",
                placeholder = "например: 5, 9.53 или Бесконечность",
                modifier = Modifier.weight(1f),
                testTag = "custom_cache_gb_input"
            )

            CyberButton(
                text = strings.applyLimitButton,
                onClick = { viewModel.updateCustomCacheGb(context, customGbInput) },
                modifier = Modifier.align(Alignment.CenterVertically),
                testTag = "apply_custom_cache_button"
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Media Breakdown by Chat and Media Type
        Text(
            text = "Media Breakdown by Chat",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "View and clean up media grouped by chat and category (Photos, Videos, Voices, Files).",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (uiState.chatMediaGroups.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DarkSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No chat media records found.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                uiState.chatMediaGroups.forEach { group ->
                    ChatMediaGroupCard(
                        group = group,
                        onDeleteChatMedia = { viewModel.deleteChatMedia(context, group.chatId) },
                        onDeleteSingleMedia = { item -> viewModel.deleteSingleMedia(context, item) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

// TAB 4: PERSONALIZATION (Персонализация / Оформление)
@Composable
fun PersonalizationTabContent(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    var reactionInput by remember(uiState.customReactionEmojisInput) {
        mutableStateOf(uiState.customReactionEmojisInput)
    }

    Column {
        AnimatedVisibility(visible = !uiState.personalizationSavedNotice.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = EmeraldSuccess.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldSuccess)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.personalizationSavedNotice.orEmpty(),
                        color = EmeraldSuccess,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearPersonalizationNotice() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }
            }
        }

        Text(
            text = "App Theme & Color Palette",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Select your preferred color theme for buttons, accents, and high-tech highlights.",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )

        Spacer(modifier = Modifier.height(14.dp))

        val themes = listOf(
            "cyber" to ("Cyber Cyan" to Color(0xFF00E5FF)),
            "neon" to ("Neon Violet" to Color(0xFFA855F7)),
            "emerald" to ("Emerald Green" to Color(0xFF10B981)),
            "crimson" to ("Crimson Red" to Color(0xFFF43F5E)),
            "amber" to ("Amber Gold" to Color(0xFFF59E0B))
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            themes.forEach { (key, pair) ->
                val (label, accentColor) = pair
                val isSelected = uiState.appTheme == key

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) DarkSurfaceElevated else DarkSurface,
                    border = BorderStroke(1.dp, if (isSelected) accentColor else BubbleBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.onAppThemeChanged(key) }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        RadioButton(
                            selected = isSelected,
                            onClick = { viewModel.onAppThemeChanged(key) },
                            colors = RadioButtonDefaults.colors(selectedColor = accentColor)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Custom Quick Reaction Emojis",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Customize the quick emoji bar shown when reacting to messages. Separate emojis with commas.",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )

        Spacer(modifier = Modifier.height(14.dp))

        CyberTextField(
            value = reactionInput,
            onValueChange = {
                reactionInput = it
                viewModel.onCustomReactionEmojisChanged(it)
            },
            label = "Quick Reaction Emojis",
            placeholder = "❤️, 🔥, 👍, 😂, 😮, 👏",
            leadingIcon = { Icon(Icons.Default.AddReaction, contentDescription = null, tint = CyanAccent) },
            testTag = "custom_emojis_input"
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Live preview of custom reaction emojis
        Text(
            text = "Reaction Bar Preview:",
            style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary)
        )
        Spacer(modifier = Modifier.height(8.dp))

        val parsedEmojis = remember(reactionInput) {
            reactionInput.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            parsedEmojis.forEach { emoji ->
                Surface(
                    shape = CircleShape,
                    color = DarkSurfaceElevated,
                    border = BorderStroke(1.dp, BubbleBorder),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = emoji, fontSize = 20.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        CyberButton(
            text = "Save Personalization",
            onClick = { viewModel.savePersonalization() },
            modifier = Modifier.fillMaxWidth(),
            testTag = "save_personalization_button"
        )

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
fun ChatMediaGroupCard(
    group: ChatMediaGroup,
    onDeleteChatMedia: () -> Unit,
    onDeleteSingleMedia: (MediaItem) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DarkSurface,
        border = BorderStroke(1.dp, BubbleBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { isExpanded = !isExpanded }
            ) {
                AvatarView(
                    avatarUrl = group.chatAvatar,
                    displayName = group.chatName,
                    size = 40.dp
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.chatName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = "Total: ${formatFileSize(group.totalSizeBytes)}",
                        style = MaterialTheme.typography.labelSmall.copy(color = CyanAccent)
                    )
                }

                IconButton(onClick = onDeleteChatMedia) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Clear Chat Media",
                        tint = CrimsonError,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Expand",
                        tint = TextSecondary
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(color = BubbleBorder, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (group.photos.isNotEmpty()) {
                        MediaCategorySection(title = "🖼 Photos", items = group.photos, onDeleteItem = onDeleteSingleMedia)
                    }
                    if (group.videos.isNotEmpty()) {
                        MediaCategorySection(title = "📹 Videos", items = group.videos, onDeleteItem = onDeleteSingleMedia)
                    }
                    if (group.voices.isNotEmpty()) {
                        MediaCategorySection(title = "🎙 Voice Messages", items = group.voices, onDeleteItem = onDeleteSingleMedia)
                    }
                    if (group.files.isNotEmpty()) {
                        MediaCategorySection(title = "📁 Files & Documents", items = group.files, onDeleteItem = onDeleteSingleMedia)
                    }
                }
            }
        }
    }
}

@Composable
fun MediaCategorySection(
    title: String,
    items: List<MediaItem>,
    onDeleteItem: (MediaItem) -> Unit
) {
    var previewMediaItem by remember { mutableStateOf<MediaItem?>(null) }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "$title (${items.size})",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                color = TextSecondary
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { item ->
                val resolvedUrl = remember(item.url) { MediaUrlUtils.resolveUrl(item.url) }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DarkSurfaceElevated,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.type == "image" || item.type == "video") {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(DarkSurface)
                                    .clickable { previewMediaItem = item }
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(resolvedUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = item.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                        } else {
                            Icon(
                                imageVector = when (item.type) {
                                    "voice_message" -> Icons.Default.Mic
                                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                                },
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (item.type == "image" || item.type == "video") {
                                        previewMediaItem = item
                                    }
                                }
                        ) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1
                            )
                            Text(
                                text = formatFileSize(item.sizeBytes),
                                style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                            )
                        }

                        IconButton(
                            onClick = { onDeleteItem(item) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Delete",
                                tint = CrimsonError,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Media Preview Dialog
    if (previewMediaItem != null) {
        val item = previewMediaItem!!
        val previewUrl = remember(item.url) { MediaUrlUtils.resolveUrl(item.url) }

        Dialog(onDismissRequest = { previewMediaItem = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = DarkSurface,
                border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Size: ${formatFileSize(item.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkSurfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(previewUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { previewMediaItem = null }) {
                            Text("Close", color = TextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        CyberButton(
                            text = "Delete File",
                            onClick = {
                                onDeleteItem(item)
                                previewMediaItem = null
                            },
                            isSecondary = true,
                            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = CrimsonError) }
                        )
                    }
                }
            }
        }
    }
}
