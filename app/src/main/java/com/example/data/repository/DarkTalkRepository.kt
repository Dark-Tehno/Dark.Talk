package com.example.data.repository

import com.example.data.local.PreferencesManager
import com.example.data.local.db.AppDatabase
import com.example.data.local.db.ChatEntity
import com.example.data.local.db.MessageEntity
import com.example.data.local.db.UserEntity
import com.example.data.model.*
import com.example.data.network.DarkTalkApi
import com.example.data.network.DarkTalkWebSocketManager
import com.example.data.network.NetworkClient
import com.example.data.network.WsConnectionState
import com.example.util.MediaUrlUtils
import com.example.util.parseServerError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

sealed class Resource<out T> {
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val message: String, val code: String? = null) : Resource<Nothing>()
    data object Loading : Resource<Nothing>()
}

class DarkTalkRepository(
    val preferencesManager: PreferencesManager,
    private val networkClient: NetworkClient,
    val webSocketManager: DarkTalkWebSocketManager,
    val database: AppDatabase
) {
    private var api: DarkTalkApi = networkClient.createApi()
    private val ioScope = CoroutineScope(Dispatchers.IO)

    init {
        // Observe WebSocket events to automatically persist incoming chats and messages to Room
        ioScope.launch {
            webSocketManager.events.collect { event ->
                when (event) {
                    is WsEvent.ChatCreated -> {
                        val curId = preferencesManager.userId
                        database.chatDao().insertChat(ChatEntity.fromDomain(event.chat, curId))
                    }
                    is WsEvent.MessageCreated -> {
                        persistMessage(event.message)
                    }
                    is WsEvent.MessageUpdated -> {
                        database.messageDao().insertMessage(MessageEntity.fromDomain(event.message))
                    }
                    is WsEvent.MessageDeleted -> {
                        database.messageDao().markMessageDeleted(event.messageId)
                    }
                    else -> {}
                }
            }
        }
    }

    fun updateApiConfig() {
        api = networkClient.createApi()
    }

    val wsEvents: SharedFlow<WsEvent> = webSocketManager.events
    val wsConnectionState: StateFlow<WsConnectionState> = webSocketManager.connectionState
    val roomConnectionState: StateFlow<WsConnectionState> = webSocketManager.roomConnectionState

    // Room persistent flows
    val cachedUser: Flow<User?> = database.userDao().getUser().map { it?.toDomain() }
    val cachedChats: Flow<List<Chat>> = database.chatDao().getAllChats().map { list -> list.map { it.toDomain() } }

    fun getCachedMessages(chatId: Long): Flow<List<Message>> {
        return database.messageDao().getMessagesForChat(chatId).map { list -> list.map { it.toDomain() } }
    }

    suspend fun register(
        email: String,
        pass: String,
        username: String?,
        language: String = "Russian",
        dob: String? = null
    ): Resource<AuthResponse> {
        return try {
            val response = api.register(
                RegisterRequest(
                    email = email,
                    password = pass,
                    username = username,
                    language = language,
                    dateOfBirth = dob,
                    deviceId = preferencesManager.deviceId
                )
            )
            if (response.isSuccessful && response.body() != null) {
                val auth = response.body()!!
                auth.token?.let { handleLoginSuccess(it, auth.user) }
                Resource.Success(auth)
            } else {
                val errorMsg = parseServerError(response.errorBody()?.string()) ?: "Registration failed: ${response.code()}"
                Resource.Error(errorMsg)
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Network error during registration")
        }
    }

    suspend fun login(username: String, pass: String): Resource<AuthResponse> {
        return try {
            val response = api.login(
                LoginRequest(
                    username = username,
                    password = pass,
                    deviceId = preferencesManager.deviceId
                )
            )
            if (response.isSuccessful && response.body() != null) {
                val auth = response.body()!!
                if (auth.status == "two_factor_required" || response.code() == 202) {
                    Resource.Success(auth)
                } else {
                    auth.token?.let { handleLoginSuccess(it, auth.user) }
                    Resource.Success(auth)
                }
            } else {
                val errorMsg = parseServerError(response.errorBody()?.string()) ?: "Login failed: ${response.code()}"
                Resource.Error(errorMsg)
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Network error during login")
        }
    }

    suspend fun verify2Fa(challengeId: String, code: String): Resource<AuthResponse> {
        return try {
            val response = api.verify2Fa(
                TwoFactorVerifyRequest(
                    challengeId = challengeId,
                    code = code,
                    deviceId = preferencesManager.deviceId
                )
            )
            if (response.isSuccessful && response.body() != null) {
                val auth = response.body()!!
                auth.token?.let { handleLoginSuccess(it, auth.user) }
                Resource.Success(auth)
            } else {
                val errorMsg = parseServerError(response.errorBody()?.string()) ?: "2FA Verification failed: ${response.code()}"
                Resource.Error(errorMsg)
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Network error during 2FA verify")
        }
    }

    suspend fun resend2Fa(challengeId: String): Resource<AuthResponse> {
        return try {
            val response = api.resend2Fa(TwoFactorResendRequest(challengeId = challengeId))
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!)
            } else {
                Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to resend 2FA: ${response.code()}")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Network error during 2FA resend")
        }
    }

    private suspend fun handleLoginSuccess(token: String, user: User?) {
        preferencesManager.authToken = token
        if (user != null) {
            preferencesManager.userId = user.id
            preferencesManager.username = user.username
            preferencesManager.userEmail = user.email
            preferencesManager.userAvatar = user.avatar
            preferencesManager.twoFactorEnabled = user.twoFactorEnabled ?: false
            user.language?.let { preferencesManager.userLanguage = it }
            // Persist user to Room Database
            database.userDao().insertUser(UserEntity.fromDomain(user))
        }
        webSocketManager.connectChatsStream()
    }

    suspend fun logout(): Resource<Unit> {
        return try {
            val devId = preferencesManager.deviceId
            api.logout(LogoutRequest(deviceId = devId))
            webSocketManager.disconnectAll()
            preferencesManager.clearAuth()
            // Clear Room database on logout
            database.userDao().clearUser()
            database.chatDao().clearChats()
            database.messageDao().clearAllMessages()
            Resource.Success(Unit)
        } catch (e: Exception) {
            webSocketManager.disconnectAll()
            preferencesManager.clearAuth()
            database.userDao().clearUser()
            database.chatDao().clearChats()
            database.messageDao().clearAllMessages()
            Resource.Success(Unit)
        }
    }

    suspend fun pingServer(): Resource<PingResponse> {
        return try {
            val response = api.ping()
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!)
            } else {
                Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Ping failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Ping failed")
        }
    }

    suspend fun getProfile(): Resource<User> {
        return try {
            val response = api.getProfile()
            if (response.isSuccessful && response.body() != null) {
                val user = response.body()!!.user
                preferencesManager.username = user.username
                preferencesManager.userEmail = user.email
                preferencesManager.twoFactorEnabled = user.twoFactorEnabled ?: false
                user.language?.let { preferencesManager.userLanguage = it }
                // Save to Room
                database.userDao().insertUser(UserEntity.fromDomain(user))
                Resource.Success(user)
            } else {
                val cached = database.userDao().getUserSync()
                if (cached != null) {
                    Resource.Success(cached.toDomain())
                } else {
                    Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to fetch profile: ${response.code()}")
                }
            }
        } catch (e: Exception) {
            val cached = database.userDao().getUserSync()
            if (cached != null) {
                Resource.Success(cached.toDomain())
            } else {
                Resource.Error(e.localizedMessage ?: "Error loading profile")
            }
        }
    }

    suspend fun updateProfile(
        username: String? = null,
        info: String? = null,
        language: String? = null,
        dateOfBirth: String? = null,
        avatarAccess: String? = null,
        avatarBytes: ByteArray? = null,
        avatarFileName: String? = null,
        avatarMimeType: String? = null
    ): Resource<User> {
        return try {
            val response = if (avatarBytes != null && avatarFileName != null) {
                val userMedia = "text/plain".toMediaTypeOrNull()
                val uBody = username?.takeIf { it.isNotBlank() }?.toRequestBody(userMedia)
                val iBody = info?.toRequestBody(userMedia)
                val lBody = language?.toRequestBody(userMedia)
                val dBody = dateOfBirth?.toRequestBody(userMedia)
                val aBody = avatarAccess?.toRequestBody(userMedia)

                val mime = (avatarMimeType ?: "image/jpeg").toMediaTypeOrNull()
                val fileReq = avatarBytes.toRequestBody(mime)
                val avatarPart = MultipartBody.Part.createFormData("avatar", avatarFileName, fileReq)

                api.updateProfileWithAvatar(
                    username = uBody,
                    info = iBody,
                    language = lBody,
                    dateOfBirth = dBody,
                    avatarAccess = aBody,
                    avatar = avatarPart
                )
            } else {
                api.updateProfile(
                    UpdateProfileRequest(
                        username = username?.takeIf { it.isNotBlank() },
                        info = info,
                        language = language,
                        dateOfBirth = dateOfBirth,
                        avatarAccess = avatarAccess
                    )
                )
            }

            if (response.isSuccessful && response.body() != null) {
                val user = response.body()!!.user
                preferencesManager.username = user.username
                preferencesManager.userEmail = user.email
                user.avatar?.let { preferencesManager.userAvatar = it }
                preferencesManager.userLanguage = user.language ?: language ?: preferencesManager.userLanguage
                database.userDao().insertUser(UserEntity.fromDomain(user))
                Resource.Success(user)
            } else {
                val errorMsg = parseServerError(response.errorBody()?.string()) ?: "Failed to update profile: ${response.code()}"
                Resource.Error(errorMsg)
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error updating profile")
        }
    }

    suspend fun searchUsers(query: String): Resource<List<User>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Resource.Success(emptyList())
        return try {
            val response = api.searchUsers(trimmed)
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!.users)
            } else {
                val msg = parseServerError(response.errorBody()?.string()) ?: "Failed to search users"
                Resource.Error(msg)
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error searching users")
        }
    }

    suspend fun addParticipants(chatId: Long, usernames: List<String>): Resource<Unit> {
        if (usernames.isEmpty()) return Resource.Success(Unit)
        return try {
            val response = api.addParticipants(chatId, AddParticipantsRequest(usernames))
            if (response.isSuccessful) {
                Resource.Success(Unit)
            } else {
                val msg = parseServerError(response.errorBody()?.string()) ?: "Failed to add participants"
                Resource.Error(msg)
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error adding participants")
        }
    }

    suspend fun get2FaState(): Resource<Boolean> {
        return try {
            val response = api.get2FaState()
            if (response.isSuccessful && response.body() != null) {
                val enabled = response.body()!!.twoFactorEnabled
                preferencesManager.twoFactorEnabled = enabled
                val cached = database.userDao().getUserSync()
                if (cached != null) {
                    database.userDao().insertUser(cached.copy(twoFactorEnabled = enabled))
                }
                Resource.Success(enabled)
            } else {
                Resource.Success(preferencesManager.twoFactorEnabled)
            }
        } catch (e: Exception) {
            Resource.Success(preferencesManager.twoFactorEnabled)
        }
    }

    suspend fun toggle2Fa(enabled: Boolean): Resource<Boolean> {
        return try {
            val response = api.toggle2Fa(TwoFactorToggleRequest(enabled = enabled))
            if (response.isSuccessful && response.body() != null) {
                val result = response.body()!!.twoFactorEnabled
                preferencesManager.twoFactorEnabled = result
                val cached = database.userDao().getUserSync()
                if (cached != null) {
                    database.userDao().insertUser(cached.copy(twoFactorEnabled = result))
                }
                Resource.Success(result)
            } else {
                Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to toggle 2FA: ${response.code()}")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error toggling 2FA")
        }
    }

    suspend fun getDevices(): Resource<List<Device>> {
        return try {
            val response = api.getDevices()
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!.devices)
            } else {
                Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to load devices: ${response.code()}")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error loading devices")
        }
    }

    suspend fun getLoginHistory(): Resource<List<LoginHistoryItem>> {
        return try {
            val response = api.getLoginHistory()
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!.loginHistory)
            } else {
                Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to load login history")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error loading login history")
        }
    }

    // Chats
    suspend fun getChats(): Resource<List<Chat>> {
        return try {
            val response = api.getChats()
            if (response.isSuccessful && response.body() != null) {
                val chats = response.body()!!.chats
                val curId = preferencesManager.userId

                val gate = Semaphore(6)
                val enrichedChats = coroutineScope {
                    chats.map { chat ->
                        async(Dispatchers.IO) {
                            var lastMsg = chat.lastMessage
                            if (lastMsg == null) {
                                val local = database.messageDao().getLatestMessageForChatSync(chat.id)?.toDomain()
                                val lastId = chat.lastMessageId
                                val needsFetch = lastId != null && (local == null || local.id < lastId)
                                lastMsg = if (needsFetch) {
                                    gate.withPermit {
                                        runCatching { api.getMessages(chat.id, 1, null).body()?.messages?.lastOrNull() }.getOrNull()
                                    }?.also { database.messageDao().insertMessage(MessageEntity.fromDomain(it)) } ?: local
                                } else local
                            }
                            chat.copy(lastMessage = lastMsg)
                        }
                    }.awaitAll()
                }

                val validChatIds = enrichedChats.map { it.id }

                // 1. Insert/update active chats from server
                database.chatDao().insertChats(enrichedChats.map { ChatEntity.fromDomain(it, curId) })

                // Persist all participant user profiles to UserDao for offline lookup
                val usersToSave = chats.flatMap { it.participants }.mapNotNull { it.user }.map { UserEntity.fromDomain(it) }
                if (usersToSave.isNotEmpty()) {
                    database.userDao().insertUsers(usersToSave)
                }

                // 2. Remove stale/deleted chats that no longer exist on server
                if (validChatIds.isNotEmpty()) {
                    database.chatDao().deleteChatsNotIn(validChatIds)
                    database.messageDao().deleteMessagesNotInChats(validChatIds)
                } else {
                    database.chatDao().clearChats()
                    database.messageDao().clearAllMessages()
                }

                Resource.Success(enrichedChats)
            } else {
                val cached = database.chatDao().getAllChatsSync()
                if (cached.isNotEmpty()) {
                    val enriched = cached.map { chatEntity ->
                        val domainChat = chatEntity.toDomain()
                        val lastMsg = domainChat.lastMessage ?: database.messageDao().getLatestMessageForChatSync(domainChat.id)?.toDomain()
                        domainChat.copy(lastMessage = lastMsg)
                    }
                    Resource.Success(enriched)
                } else {
                    Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to load chats: ${response.code()}")
                }
            }
        } catch (e: Exception) {
            val cached = database.chatDao().getAllChatsSync()
            if (cached.isNotEmpty()) {
                val enriched = cached.map { chatEntity ->
                    val domainChat = chatEntity.toDomain()
                    val lastMsg = domainChat.lastMessage ?: database.messageDao().getLatestMessageForChatSync(domainChat.id)?.toDomain()
                    domainChat.copy(lastMessage = lastMsg)
                }
                Resource.Success(enriched)
            } else {
                Resource.Error(e.localizedMessage ?: "Error loading chats")
            }
        }
    }

    suspend fun createDirectChat(participantName: String): Resource<Long> {
        return try {
            val response = api.createDirectChat(CreateDirectChatRequest(participantName = participantName.trim()))
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!.chatId)
            } else {
                val msg = parseServerError(response.errorBody()?.string()) ?: "Failed to create direct chat: ${response.code()}"
                Resource.Error(msg)
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error creating direct chat")
        }
    }

    suspend fun createGroupChat(participantNames: String, title: String?, desc: String?): Resource<Long> {
        return try {
            val response = api.createGroupChat(
                CreateGroupChatRequest(
                    participantNames = participantNames.trim(),
                    title = title?.takeIf { it.isNotBlank() },
                    description = desc?.takeIf { it.isNotBlank() }
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!.chatId)
            } else {
                val msg = parseServerError(response.errorBody()?.string()) ?: "Failed to create group: ${response.code()}"
                Resource.Error(msg)
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error creating group chat")
        }
    }

    suspend fun getUserById(userId: Long): User? {
        return database.userDao().getUserByIdSync(userId)?.toDomain()
    }

    suspend fun getChatDetails(chatId: Long): Resource<Chat> {
        return try {
            val response = api.getChatDetails(chatId)
            if (response.isSuccessful && response.body() != null) {
                val chat = response.body()!!
                database.chatDao().insertChat(ChatEntity.fromDomain(chat, preferencesManager.userId))

                val usersToSave = chat.participants.mapNotNull { it.user }.map { UserEntity.fromDomain(it) }
                if (usersToSave.isNotEmpty()) {
                    database.userDao().insertUsers(usersToSave)
                }

                Resource.Success(chat)
            } else {
                val cached = database.chatDao().getChatByIdSync(chatId)
                if (cached != null) {
                    Resource.Success(cached.toDomain())
                } else {
                    Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to fetch chat info")
                }
            }
        } catch (e: Exception) {
            val cached = database.chatDao().getChatByIdSync(chatId)
            if (cached != null) {
                Resource.Success(cached.toDomain())
            } else {
                Resource.Error(e.localizedMessage ?: "Error loading chat")
            }
        }
    }

    suspend fun leaveOrRemoveParticipant(chatId: Long, userId: Long): Resource<GenericStatusResponse> {
        return try {
            val response = api.leaveOrRemoveParticipant(chatId, userId)
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!)
            } else {
                val msg = parseServerError(response.errorBody()?.string()) ?: "Failed to remove participant: ${response.code()}"
                Resource.Error(msg)
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error removing participant")
        }
    }

    suspend fun deleteChat(chatId: Long): Resource<Unit> {
        return try {
            val response = api.deleteChat(chatId)
            if (response.isSuccessful) {
                database.chatDao().deleteChatById(chatId)
                database.messageDao().deleteMessagesForChat(chatId)
                Resource.Success(Unit)
            } else {
                Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to delete chat: ${response.code()}")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error deleting chat")
        }
    }

    suspend fun getMessages(chatId: Long, limit: Int = 50, beforeId: Long? = null): Resource<MessagesPageResponse> {
        suspend fun cachedPage(): MessagesPageResponse? {
            if (beforeId != null) return null
            val cached = database.messageDao().getMessagesForChatSync(chatId)
            return if (cached.isEmpty()) null
            else MessagesPageResponse(status = "success", chatId = chatId, messages = cached.map { it.toDomain() })
        }
        return try {
            val response = api.getMessages(chatId, limit, beforeId)
            if (response.isSuccessful && response.body() != null) {
                val page = response.body()!!
                if (page.messages.isNotEmpty()) {
                    database.messageDao().insertMessages(page.messages.map { MessageEntity.fromDomain(it) })
                    database.messageDao().trimOldMessages(chatId, keepCount = 50)
                }
                Resource.Success(page)
            } else {
                cachedPage()?.let { Resource.Success(it) }
                    ?: Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to fetch messages: ${response.code()}")
            }
        } catch (e: Exception) {
            cachedPage()?.let { Resource.Success(it) } ?: Resource.Error(e.localizedMessage ?: "Error loading messages")
        }
    }

    suspend fun persistMessage(message: Message) {
        database.messageDao().insertMessage(MessageEntity.fromDomain(message))
        // Maintain at least the last 50 messages per chat
        database.messageDao().trimOldMessages(message.chatId, keepCount = 50)

        // Also update lastMessage in ChatEntity in Room DB
        val chatEntity = database.chatDao().getChatByIdSync(message.chatId)
        if (chatEntity != null) {
            val updatedDomain = chatEntity.toDomain().copy(lastMessage = message)
            database.chatDao().insertChat(ChatEntity.fromDomain(updatedDomain, preferencesManager.userId))
        }
    }

    suspend fun markChatAsRead(chatId: Long, lastMessageId: Long? = null): Resource<Unit> {
        return try {
            api.markChatAsRead(chatId, ReadChatRequest(lastMessageId = lastMessageId))
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error marking chat read")
        }
    }

    suspend fun sendTextMessage(
        chatId: Long,
        text: String,
        replyTo: Long? = null,
        clientMessageId: String? = null
    ): Resource<Unit> {
        val sentViaWs = webSocketManager.sendRoomTextMessage(text, replyTo, clientMessageId)
        if (sentViaWs) {
            return Resource.Success(Unit)
        }

        return try {
            val response = api.createTextMessage(
                CreateMessageRequest(
                    chatId = chatId,
                    text = text,
                    messageType = "text",
                    replyTo = replyTo,
                    clientMessageId = clientMessageId
                )
            )
            if (response.isSuccessful) {
                Resource.Success(Unit)
            } else {
                Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to send message: ${response.code()}")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error sending message")
        }
    }

    suspend fun sendAttachment(
        chatId: Long,
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String,
        text: String? = null,
        replyTo: Long? = null,
        overrideMessageType: String? = null
    ): Resource<Unit> {
        return try {
            val chatIdBody = chatId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val textBody = text?.toRequestBody("text/plain".toMediaTypeOrNull())
            val mType = overrideMessageType?.takeIf { it.isNotBlank() }
                ?: if (mimeType.startsWith("image/")) "image" else "file"
            val messageTypeBody = mType.toRequestBody("text/plain".toMediaTypeOrNull())
            val replyToBody = replyTo?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())

            val fileRequestBody = fileBytes.toRequestBody(mimeType.toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("attachment", fileName, fileRequestBody)

            val response = api.createAttachmentMessage(
                chatId = chatIdBody,
                text = textBody,
                messageType = messageTypeBody,
                replyTo = replyToBody,
                attachment = part
            )
            if (response.isSuccessful) {
                Resource.Success(Unit)
            } else {
                Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to upload attachment: ${response.code()}")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Attachment upload failed")
        }
    }

    suspend fun downloadAuthenticatedFile(rawUrl: String, targetFile: File): Boolean {
        if (targetFile.exists() && targetFile.length() > 0) return true
        val resolvedUrl = MediaUrlUtils.resolveUrl(rawUrl) ?: return false
        return withContext(Dispatchers.IO) {
            val part = File(targetFile.path + ".part")
            try {
                targetFile.parentFile?.mkdirs()
                val request = Request.Builder().url(resolvedUrl).build()
                networkClient.okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) return@withContext false
                    part.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
                part.renameTo(targetFile)
            } catch (e: Exception) {
                part.delete()
                false
            }
        }
    }

    suspend fun editMessage(chatId: Long, messageId: Long, newText: String): Resource<Unit> {
        val sentViaWs = webSocketManager.sendRoomEditMessage(messageId, newText)
        if (sentViaWs) return Resource.Success(Unit)

        return try {
            val response = api.editMessage(messageId, EditMessageRequest(text = newText))
            if (response.isSuccessful) {
                Resource.Success(Unit)
            } else {
                Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to edit message: ${response.code()}")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error editing message")
        }
    }

    suspend fun deleteMessage(chatId: Long, messageId: Long, forceHttp: Boolean = false): Resource<Unit> {
        if (!forceHttp && webSocketManager.sendRoomDeleteMessage(messageId)) return Resource.Success(Unit)

        return try {
            val response = api.deleteMessage(messageId)
            if (response.isSuccessful) {
                database.messageDao().markMessageDeleted(messageId)
                Resource.Success(Unit)
            } else {
                Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to delete message: ${response.code()}")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error deleting message")
        }
    }

    fun sendTyping(isTyping: Boolean) {
        webSocketManager.sendRoomTyping(isTyping)
    }

    suspend fun markMessageRead(chatId: Long, messageId: Long): Resource<Unit> {
        val sentViaWs = webSocketManager.sendRoomReadMessage(messageId)
        if (sentViaWs) return Resource.Success(Unit)

        return try {
            api.markMessageAsRead(messageId)
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error marking read")
        }
    }

    suspend fun addReaction(messageId: Long, emoji: String): Resource<MessageReaction?> {
        return try {
            val response = api.addReaction(messageId, ReactionRequest(emoji = emoji))
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!.messageReaction)
            } else {
                Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to add reaction: ${response.code()}")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error adding reaction")
        }
    }

    suspend fun removeReaction(messageId: Long, emoji: String): Resource<Unit> {
        return try {
            val response = api.removeReaction(messageId, emoji)
            if (response.isSuccessful) {
                Resource.Success(Unit)
            } else {
                Resource.Error(parseServerError(response.errorBody()?.string()) ?: "Failed to remove reaction: ${response.code()}")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Error removing reaction")
        }
    }
}
