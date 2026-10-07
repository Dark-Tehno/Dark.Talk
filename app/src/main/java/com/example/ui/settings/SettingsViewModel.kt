package com.example.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Device
import com.example.data.model.LoginHistoryItem
import com.example.data.model.User
import com.example.data.repository.DarkTalkRepository
import com.example.data.repository.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class MediaItem(
    val messageId: Long,
    val chatId: Long,
    val type: String, // "image", "video", "voice_message", "file"
    val name: String,
    val sizeBytes: Long,
    val url: String?
)

data class ChatMediaGroup(
    val chatId: Long,
    val chatName: String,
    val chatAvatar: String?,
    val photos: List<MediaItem> = emptyList(),
    val videos: List<MediaItem> = emptyList(),
    val voices: List<MediaItem> = emptyList(),
    val files: List<MediaItem> = emptyList(),
    val totalSizeBytes: Long = 0L
)

data class SettingsUiState(
    val selectedTab: Int = 0, // 0 = Profile, 1 = Security, 2 = Network, 3 = Storage, 4 = Personalization
    // Profile tab
    val user: User? = null,
    val editUsername: String = "",
    val editInfo: String = "",
    val editLanguage: String = "Russian",
    val editDateOfBirth: String = "",
    val editAvatarAccess: String = "all",
    val avatarBytes: ByteArray? = null,
    val avatarFileName: String? = null,
    val avatarMimeType: String? = null,
    val isProfileSaving: Boolean = false,
    val profileMessage: String? = null,
    val isProfileError: Boolean = false,
    // Security tab
    val twoFactorEnabled: Boolean = false,
    val devices: List<Device> = emptyList(),
    val loginHistory: List<LoginHistoryItem> = emptyList(),
    val isLoggedOut: Boolean = false,
    // Network tab
    val serverDomain: String = "",
    val useSsl: Boolean = true,
    val appSecretKey: String = "",
    val deviceId: String = "",
    val clientName: String = "",
    val clientVersion: String = "",
    val isNetworkSaved: Boolean = false,
    val pingResult: String? = null,
    val isPinging: Boolean = false,
    // Storage & Data tab
    val cacheSizeBytes: Long = 0L,
    val cacheFileCount: Int = 0,
    val maxCacheMb: Long = 4096L,
    val customCacheGbInput: String = "4",
    val chatMediaGroups: List<ChatMediaGroup> = emptyList(),
    val cacheClearedMessage: String? = null,
    // Personalization tab
    val appTheme: String = "cyber",
    val customReactionEmojisInput: String = "❤️,🔥,👍,😂,😮,👏",
    val personalizationSavedNotice: String? = null,
    // Common
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class SettingsViewModel(private val repository: DarkTalkRepository) : ViewModel() {

    private val prefs = repository.preferencesManager

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            serverDomain = prefs.serverDomain,
            useSsl = prefs.useSsl,
            appSecretKey = prefs.appSecretKey,
            deviceId = prefs.deviceId,
            clientName = prefs.clientName,
            clientVersion = prefs.clientVersion,
            twoFactorEnabled = prefs.twoFactorEnabled,
            maxCacheMb = prefs.maxCacheMb,
            customCacheGbInput = prefs.customCacheGb,
            appTheme = prefs.appTheme,
            customReactionEmojisInput = prefs.customReactionEmojis
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.cachedUser.collect { user ->
                if (user != null) {
                    _uiState.update { state ->
                        state.copy(
                            user = user,
                            editUsername = if (state.editUsername.isBlank()) user.username else state.editUsername,
                            editInfo = if (state.editInfo.isBlank()) user.info.orEmpty() else state.editInfo,
                            editLanguage = user.language ?: state.editLanguage,
                            editDateOfBirth = user.dateOfBirth.orEmpty(),
                            twoFactorEnabled = user.twoFactorEnabled ?: state.twoFactorEnabled
                        )
                    }
                }
            }
        }
        loadAllData()
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
    }

    fun loadAllData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val profileRes = repository.getProfile()
            val twoFaRes = repository.get2FaState()
            val devicesRes = repository.getDevices()
            val historyRes = repository.getLoginHistory()

            _uiState.update { state ->
                val user = if (profileRes is Resource.Success) profileRes.data else state.user
                state.copy(
                    user = user,
                    editUsername = user?.username ?: state.editUsername,
                    editInfo = user?.info.orEmpty(),
                    editLanguage = user?.language ?: state.editLanguage,
                    editDateOfBirth = user?.dateOfBirth.orEmpty(),
                    twoFactorEnabled = if (twoFaRes is Resource.Success) twoFaRes.data else prefs.twoFactorEnabled,
                    devices = if (devicesRes is Resource.Success) devicesRes.data else emptyList(),
                    loginHistory = if (historyRes is Resource.Success) historyRes.data else emptyList(),
                    isLoading = false
                )
            }
        }
    }

    // Personalization tab
    fun onAppThemeChanged(theme: String) {
        _uiState.update { it.copy(appTheme = theme, personalizationSavedNotice = null) }
    }

    fun onCustomReactionEmojisChanged(emojis: String) {
        _uiState.update { it.copy(customReactionEmojisInput = emojis, personalizationSavedNotice = null) }
    }

    fun savePersonalization() {
        val theme = _uiState.value.appTheme
        val emojis = _uiState.value.customReactionEmojisInput.trim()

        prefs.appTheme = theme
        prefs.customReactionEmojis = if (emojis.isNotBlank()) emojis else "❤️,🔥,👍,😂,😮,👏"

        _uiState.update {
            it.copy(
                appTheme = theme,
                customReactionEmojisInput = prefs.customReactionEmojis,
                personalizationSavedNotice = "Personalization settings saved successfully!"
            )
        }
    }

    fun clearPersonalizationNotice() {
        _uiState.update { it.copy(personalizationSavedNotice = null) }
    }

    // Storage & Data tab
    fun loadStorageInfo(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val mediaDir = context.cacheDir.resolve("media_cache")
            var size = 0L
            var count = 0
            if (mediaDir.exists()) {
                mediaDir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        size += file.length()
                        count++
                    }
                }
            }

            val allChats = repository.database.chatDao().getAllChatsSync()
            val chatMap = allChats.associateBy { it.id }
            val allMediaMsgs = repository.database.messageDao().getAllMessagesWithAttachments()

            val chatGroupsMap = mutableMapOf<Long, MutableList<MediaItem>>()

            for (msg in allMediaMsgs) {
                val itemSize = msg.attachmentSize ?: 1024L
                val itemName = msg.attachmentName ?: msg.attachment?.substringAfterLast("/") ?: "File"
                val itemUrl = msg.attachment

                val item = MediaItem(
                    messageId = msg.id,
                    chatId = msg.chatId,
                    type = msg.messageType,
                    name = itemName,
                    sizeBytes = itemSize,
                    url = itemUrl
                )
                chatGroupsMap.getOrPut(msg.chatId) { mutableListOf() }.add(item)
            }

            val resultGroups = chatGroupsMap.map { (cId, items) ->
                val chatEntity = chatMap[cId]
                val cName = chatEntity?.title ?: chatEntity?.otherUserName ?: "Chat #$cId"
                val cAvatar = chatEntity?.avatar ?: chatEntity?.otherUserAvatar

                val photos = items.filter { it.type == "image" }
                val videos = items.filter { it.type == "video" }
                val voices = items.filter { it.type == "voice_message" }
                val files = items.filter { it.type !in listOf("image", "video", "voice_message") }
                val grpTotal = items.sumOf { it.sizeBytes }

                ChatMediaGroup(
                    chatId = cId,
                    chatName = cName,
                    chatAvatar = cAvatar,
                    photos = photos,
                    videos = videos,
                    voices = voices,
                    files = files,
                    totalSizeBytes = grpTotal
                )
            }.sortedByDescending { it.totalSizeBytes }

            _uiState.update {
                it.copy(
                    cacheSizeBytes = size,
                    cacheFileCount = count,
                    maxCacheMb = prefs.maxCacheMb,
                    customCacheGbInput = prefs.customCacheGb,
                    chatMediaGroups = resultGroups
                )
            }
        }
    }

    fun clearMediaCache(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val mediaDir = context.cacheDir.resolve("media_cache")
            if (mediaDir.exists()) {
                mediaDir.deleteRecursively()
                mediaDir.mkdirs()
            }
            _uiState.update {
                it.copy(
                    cacheSizeBytes = 0L,
                    cacheFileCount = 0,
                    chatMediaGroups = emptyList(),
                    cacheClearedMessage = "Media cache cleared successfully!"
                )
            }
        }
    }

    fun deleteChatMedia(context: Context, chatId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { state ->
                val updatedList = state.chatMediaGroups.filterNot { it.chatId == chatId }
                state.copy(
                    chatMediaGroups = updatedList,
                    cacheClearedMessage = "Cleared media for selected chat."
                )
            }
        }
    }

    fun deleteSingleMedia(context: Context, item: MediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { state ->
                val updatedGroups = state.chatMediaGroups.map { grp ->
                    if (grp.chatId == item.chatId) {
                        grp.copy(
                            photos = grp.photos.filterNot { it.messageId == item.messageId },
                            videos = grp.videos.filterNot { it.messageId == item.messageId },
                            voices = grp.voices.filterNot { it.messageId == item.messageId },
                            files = grp.files.filterNot { it.messageId == item.messageId },
                            totalSizeBytes = (grp.totalSizeBytes - item.sizeBytes).coerceAtLeast(0L)
                        )
                    } else grp
                }.filter { it.photos.isNotEmpty() || it.videos.isNotEmpty() || it.voices.isNotEmpty() || it.files.isNotEmpty() }

                state.copy(
                    chatMediaGroups = updatedGroups,
                    cacheClearedMessage = "Deleted ${item.name}"
                )
            }
        }
    }

    fun updateCustomCacheGb(context: Context, gbInput: String) {
        val trimmed = gbInput.trim()
        val parsedGb = trimmed.toDoubleOrNull()
        val mb = if (parsedGb != null && parsedGb > 0.0) {
            (parsedGb * 1024.0).toLong()
        } else if (trimmed.equals("unlimited", ignoreCase = true) || trimmed.equals("бесконечный", ignoreCase = true) || trimmed == "-1" || trimmed == "0") {
            -1L
        } else {
            4096L
        }

        prefs.customCacheGb = trimmed
        prefs.maxCacheMb = mb

        _uiState.update {
            it.copy(
                customCacheGbInput = trimmed,
                maxCacheMb = mb,
                cacheClearedMessage = if (mb <= 0L) "Max Cache Limit set to Unlimited." else "Max Cache Limit updated to ${formatFileSize(mb * 1024 * 1024)}."
            )
        }
        loadStorageInfo(context)
    }

    fun updateMaxCacheMbPreset(maxMb: Long, context: Context) {
        val gbStr = if (maxMb <= 0L) "Unlimited" else String.format(Locale.US, "%.2f", maxMb / 1024.0)
        prefs.maxCacheMb = maxMb
        prefs.customCacheGb = gbStr
        _uiState.update {
            it.copy(
                maxCacheMb = maxMb,
                customCacheGbInput = gbStr,
                cacheClearedMessage = if (maxMb <= 0L) "Max Cache Limit set to Unlimited." else "Max Cache Limit updated to ${formatFileSize(maxMb * 1024 * 1024)}."
            )
        }
        loadStorageInfo(context)
    }

    fun clearCacheNotice() {
        _uiState.update { it.copy(cacheClearedMessage = null) }
    }

    // Profile Edits
    fun onUsernameChanged(value: String) {
        _uiState.update { it.copy(editUsername = value, profileMessage = null) }
    }

    fun onInfoChanged(value: String) {
        _uiState.update { it.copy(editInfo = value, profileMessage = null) }
    }

    fun onLanguageChanged(value: String) {
        _uiState.update { it.copy(editLanguage = value, profileMessage = null) }
    }

    fun onDateOfBirthChanged(value: String) {
        _uiState.update { it.copy(editDateOfBirth = value, profileMessage = null) }
    }

    fun onAvatarAccessChanged(value: String) {
        _uiState.update { it.copy(editAvatarAccess = value, profileMessage = null) }
    }

    fun onAvatarPicked(bytes: ByteArray, fileName: String, mimeType: String) {
        _uiState.update {
            it.copy(
                avatarBytes = bytes,
                avatarFileName = fileName,
                avatarMimeType = mimeType,
                profileMessage = null
            )
        }
    }

    fun saveProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(isProfileSaving = true, profileMessage = null) }
            val state = _uiState.value
            val res = repository.updateProfile(
                username = state.editUsername.trim(),
                info = state.editInfo,
                language = state.editLanguage,
                dateOfBirth = state.editDateOfBirth.takeIf { it.isNotBlank() },
                avatarAccess = state.editAvatarAccess,
                avatarBytes = state.avatarBytes,
                avatarFileName = state.avatarFileName,
                avatarMimeType = state.avatarMimeType
            )

            when (res) {
                is Resource.Success -> {
                    val updatedUser = res.data
                    _uiState.update {
                        it.copy(
                            user = updatedUser,
                            editUsername = updatedUser.username,
                            editInfo = updatedUser.info.orEmpty(),
                            editLanguage = updatedUser.language ?: "Russian",
                            editDateOfBirth = updatedUser.dateOfBirth.orEmpty(),
                            avatarBytes = null,
                            avatarFileName = null,
                            avatarMimeType = null,
                            isProfileSaving = false,
                            profileMessage = "Profile updated successfully!",
                            isProfileError = false
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isProfileSaving = false,
                            profileMessage = res.message,
                            isProfileError = true
                        )
                    }
                }
                is Resource.Loading -> {}
            }
        }
    }

    // Security
    fun toggle2Fa(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = repository.toggle2Fa(enabled)) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            twoFactorEnabled = res.data,
                            isLoading = false,
                            profileMessage = if (res.data) "Two-factor authentication enabled" else "Two-factor authentication disabled",
                            isProfileError = false
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            profileMessage = res.message,
                            isProfileError = true
                        )
                    }
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.logout()
            _uiState.update { it.copy(isLoading = false, isLoggedOut = true) }
        }
    }

    // Network Settings
    fun updateServerDomain(domain: String) {
        val trimmed = domain.trim()
        val detectedUseSsl = when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("ws://", ignoreCase = true) -> false
            trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("wss://", ignoreCase = true) -> true
            else -> _uiState.value.useSsl
        }
        _uiState.update { it.copy(serverDomain = domain, useSsl = detectedUseSsl, isNetworkSaved = false) }
    }

    fun updateUseSsl(useSsl: Boolean) {
        _uiState.update { it.copy(useSsl = useSsl, isNetworkSaved = false) }
    }

    fun updateAppSecretKey(key: String) {
        _uiState.update { it.copy(appSecretKey = key, isNetworkSaved = false) }
    }

    fun updateDeviceId(id: String) {
        _uiState.update { it.copy(deviceId = id, isNetworkSaved = false) }
    }

    fun saveNetworkSettings() {
        val domain = _uiState.value.serverDomain.trim()
        if (domain.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Server domain cannot be empty") }
            return
        }

        prefs.serverDomain = domain
        prefs.useSsl = _uiState.value.useSsl
        prefs.appSecretKey = _uiState.value.appSecretKey
        prefs.deviceId = _uiState.value.deviceId

        repository.updateApiConfig()
        if (prefs.isLoggedIn) {
            repository.webSocketManager.disconnectAll()
            repository.webSocketManager.connectChatsStream()
        }

        _uiState.update { it.copy(isNetworkSaved = true, errorMessage = null) }
    }

    fun resetNetworkDefaults() {
        prefs.serverDomain = "vsp210.ru"
        prefs.useSsl = true
        _uiState.update {
            it.copy(
                serverDomain = "vsp210.ru",
                useSsl = true,
                isNetworkSaved = true,
                errorMessage = null
            )
        }
        repository.updateApiConfig()
    }

    fun testPingServer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPinging = true, pingResult = null) }
            when (val res = repository.pingServer()) {
                is Resource.Success -> {
                    val p = res.data
                    val text = "Status: ${p.status ?: "OK"} • Time: ${p.serverTime?.take(19) ?: ""} (UTC ${p.serverUtc ?: "+0"})"
                    _uiState.update { it.copy(isPinging = false, pingResult = text) }
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(isPinging = false, pingResult = "Error: ${res.message}") }
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun clearProfileMessage() {
        _uiState.update { it.copy(profileMessage = null) }
    }

    fun clearNetworkNotice() {
        _uiState.update { it.copy(isNetworkSaved = false, errorMessage = null) }
    }
}
