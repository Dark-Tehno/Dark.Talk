package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.DarkTalkApplication
import com.example.data.model.Chat
import com.example.data.model.Message
import com.example.data.model.MessageReaction
import com.example.data.model.User
import com.example.data.model.WsEvent
import com.example.data.network.WsConnectionState
import com.example.data.repository.DarkTalkRepository
import com.example.data.repository.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class ChatRoomUiState(
    val chat: Chat? = null,
    val otherUserProfile: User? = null,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val nextBeforeId: Long? = null,
    val roomWsState: WsConnectionState = WsConnectionState.DISCONNECTED,
    val typingUserIds: Set<Long> = emptySet(),
    val replyingTo: Message? = null,
    val editingMessage: Message? = null,
    val errorMessage: String? = null
)

class ChatRoomViewModel(
    val chatId: Long,
    private val repository: DarkTalkRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatRoomUiState())
    val uiState: StateFlow<ChatRoomUiState> = _uiState.asStateFlow()

    val currentUserId: Long get() = repository.preferencesManager.userId

    private var typingJob: Job? = null
    private val typingTimeoutJobs = mutableMapOf<Long, Job>()

    init {
        // Pre-populate chat details from Room DB cache immediately
        viewModelScope.launch {
            val cachedChat = repository.database.chatDao().getChatByIdSync(chatId)
            if (cachedChat != null) {
                _uiState.update { it.copy(chat = cachedChat.toDomain()) }
            }
        }

        // Collect cached messages from Room persistence
        viewModelScope.launch {
            repository.getCachedMessages(chatId).collect { cached ->
                if (cached.isNotEmpty()) {
                    _uiState.update { it.copy(messages = cached) }
                }
            }
        }

        // Connect to WebSocket room /ws/chat/{chat_id}/
        repository.webSocketManager.enterChatRoom(chatId)

        // Observe room WebSocket status
        viewModelScope.launch {
            repository.roomConnectionState.collect { state ->
                _uiState.update { it.copy(roomWsState = state) }
            }
        }

        // Observe WebSocket events
        viewModelScope.launch {
            repository.wsEvents.collect { event ->
                handleWsEvent(event)
            }
        }

        loadChatDetails()
        loadMessages()
        markAsRead()
    }

    private fun loadChatDetails() {
        viewModelScope.launch {
            val cachedEntity = repository.database.chatDao().getChatByIdSync(chatId)
            if (cachedEntity != null) {
                val cachedDomain = cachedEntity.toDomain()
                _uiState.update { it.copy(chat = cachedDomain) }
                resolveOtherUserProfile(cachedDomain)
            }

            when (val result = repository.getChatDetails(chatId)) {
                is Resource.Success -> {
                    _uiState.update { it.copy(chat = result.data) }
                    resolveOtherUserProfile(result.data)
                }
                else -> {}
            }
        }
    }

    private fun resolveOtherUserProfile(chat: Chat) {
        val otherPart = chat.participants.firstOrNull { it.user?.id != currentUserId }?.user
        if (otherPart != null) {
            viewModelScope.launch {
                val fullUser = repository.getUserById(otherPart.id) ?: otherPart
                _uiState.update { it.copy(otherUserProfile = fullUser) }
            }
        }
    }

    fun loadMessages() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = repository.getMessages(chatId, limit = 50)) {
                is Resource.Success -> {
                    val msgs = result.data.messages
                    _uiState.update {
                        it.copy(
                            messages = msgs,
                            hasMore = result.data.hasMore,
                            nextBeforeId = result.data.nextBeforeId,
                            isLoading = false
                        )
                    }
                    val lastIncomingMsg = msgs.lastOrNull { it.sender?.id != currentUserId }
                    if (lastIncomingMsg != null) {
                        markAsRead(lastIncomingMsg.id, lastIncomingMsg.sender?.id)
                    }
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun loadMoreMessages() {
        val beforeId = _uiState.value.nextBeforeId ?: return
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMore) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            when (val result = repository.getMessages(chatId, limit = 50, beforeId = beforeId)) {
                is Resource.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            messages = result.data.messages + state.messages,
                            hasMore = result.data.hasMore,
                            nextBeforeId = result.data.nextBeforeId,
                            isLoadingMore = false
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
                is Resource.Loading -> {}
            }
        }
    }

    private fun handleWsEvent(event: WsEvent) {
        when (event) {
            is WsEvent.MessageCreated -> {
                if (event.chatId == chatId) {
                    val currentList = _uiState.value.messages.toMutableList()
                    val existingIndex = currentList.indexOfFirst {
                        it.id == event.message.id ||
                                (it.clientMessageId != null && it.clientMessageId == event.clientMessageId)
                    }
                    if (existingIndex != -1) {
                        currentList[existingIndex] = event.message
                    } else {
                        currentList.add(event.message)
                    }
                    _uiState.update { it.copy(messages = currentList) }

                    if (event.message.sender?.id != currentUserId) {
                        markAsRead(event.message.id, event.message.sender?.id)
                    }
                }
            }
            is WsEvent.MessageUpdated -> {
                if (event.chatId == chatId) {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { msg ->
                                if (msg.id == event.message.id) event.message else msg
                            }
                        )
                    }
                }
            }
            is WsEvent.MessageDeleted -> {
                if (event.chatId == chatId) {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { msg ->
                                if (msg.id == event.messageId) {
                                    msg.copy(isDeleted = true, text = "")
                                } else msg
                            }
                        )
                    }
                }
            }
            is WsEvent.MessageRead -> {
                if (event.chatId == chatId) {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { msg ->
                                if (msg.id <= event.messageId && !msg.readBy.contains(event.userId)) {
                                    msg.copy(readBy = msg.readBy + event.userId)
                                } else msg
                            }
                        )
                    }
                }
            }
            is WsEvent.Typing -> {
                if (event.chatId == chatId && event.userId != currentUserId) {
                    val currentTyping = _uiState.value.typingUserIds.toMutableSet()
                    if (event.isTyping) {
                        currentTyping.add(event.userId)
                        _uiState.update { it.copy(typingUserIds = currentTyping) }
                        // Expire typing state automatically after 3 seconds
                        typingTimeoutJobs[event.userId]?.cancel()
                        typingTimeoutJobs[event.userId] = viewModelScope.launch {
                            delay(3000)
                            _uiState.update { st ->
                                st.copy(typingUserIds = st.typingUserIds - event.userId)
                            }
                        }
                    } else {
                        currentTyping.remove(event.userId)
                        _uiState.update { it.copy(typingUserIds = currentTyping) }
                    }
                }
            }
            is WsEvent.Error -> {
                _uiState.update { it.copy(errorMessage = event.message ?: event.code) }
            }
            else -> {}
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val replyTo = _uiState.value.replyingTo?.id
        val editing = _uiState.value.editingMessage

        if (editing != null) {
            editMessage(editing.id, text.trim())
            _uiState.update { it.copy(editingMessage = null) }
            return
        }

        val clientMsgId = "local-${UUID.randomUUID().toString().take(8)}"

        viewModelScope.launch {
            _uiState.update { it.copy(replyingTo = null) }
            when (val result = repository.sendTextMessage(chatId, text.trim(), replyTo, clientMsgId)) {
                is Resource.Success -> {
                    // WebSocket event or loadMessages will handle update
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun sendAttachment(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        text: String? = null,
        overrideMessageType: String? = null
    ) {
        val replyTo = _uiState.value.replyingTo?.id
        viewModelScope.launch {
            _uiState.update { it.copy(replyingTo = null) }
            when (val result = repository.sendAttachment(chatId, bytes, fileName, mimeType, text, replyTo, overrideMessageType)) {
                is Resource.Success -> {
                    loadMessages()
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun editMessage(messageId: Long, newText: String) {
        viewModelScope.launch {
            when (val result = repository.editMessage(chatId, messageId, newText)) {
                is Resource.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map {
                                if (it.id == messageId) it.copy(text = newText, isEdited = true) else it
                            }
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            when (val result = repository.deleteMessage(chatId, messageId)) {
                is Resource.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map {
                                if (it.id == messageId) it.copy(isDeleted = true, text = "") else it
                            }
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun onTypingInput() {
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            repository.sendTyping(true)
            delay(2000)
            repository.sendTyping(false)
        }
    }

    fun setReplyingTo(message: Message?) {
        _uiState.update { it.copy(replyingTo = message, editingMessage = null) }
    }

    fun setEditingMessage(message: Message?) {
        _uiState.update { it.copy(editingMessage = message, replyingTo = null) }
    }

    fun toggleReaction(message: Message, emoji: String) {
        val existingReaction = message.reactions.firstOrNull { it.userId == currentUserId && it.emoji == emoji }
        viewModelScope.launch {
            if (existingReaction != null) {
                repository.removeReaction(message.id, emoji)
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { msg ->
                            if (msg.id == message.id) {
                                msg.copy(reactions = msg.reactions.filterNot { it.userId == currentUserId && it.emoji == emoji })
                            } else msg
                        }
                    )
                }
            } else {
                when (val result = repository.addReaction(message.id, emoji)) {
                    is Resource.Success -> {
                        val reaction = result.data ?: MessageReaction(
                            userId = currentUserId,
                            emoji = emoji
                        )
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages.map { msg ->
                                    if (msg.id == message.id) {
                                        msg.copy(reactions = msg.reactions + reaction)
                                    } else msg
                                }
                            )
                        }
                    }
                    is Resource.Error -> {
                        _uiState.update { it.copy(errorMessage = result.message) }
                    }
                    is Resource.Loading -> {}
                }
            }
        }
    }

    fun markAsRead(messageId: Long? = null, senderId: Long? = null) {
        if (senderId != null && senderId == currentUserId) return
        viewModelScope.launch {
            repository.markChatAsRead(chatId, messageId)
            if (messageId != null) {
                repository.markMessageRead(chatId, messageId)
            }
        }
    }

    fun addParticipants(usernames: List<String>) {
        if (usernames.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = repository.addParticipants(chatId, usernames)) {
                is Resource.Success -> {
                    loadChatDetails()
                    _uiState.update { it.copy(isLoading = false, errorMessage = null) }
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = res.message) }
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun removeParticipant(userId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = repository.leaveOrRemoveParticipant(chatId, userId)) {
                is Resource.Success -> {
                    loadChatDetails()
                    _uiState.update { it.copy(isLoading = false, errorMessage = null) }
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = res.message) }
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun searchUsers(query: String, onResult: (List<User>) -> Unit) {
        viewModelScope.launch {
            when (val res = repository.searchUsers(query)) {
                is Resource.Success -> onResult(res.data)
                else -> onResult(emptyList())
            }
        }
    }

    fun downloadVoiceFile(rawUrl: String, messageId: Long, onComplete: (File?) -> Unit) {
        val voiceDir = DarkTalkApplication.instance.cacheDir.resolve("media_cache")
        val targetFile = voiceDir.resolve("voice_${messageId}.m4a")
        viewModelScope.launch {
            val success = repository.downloadAuthenticatedFile(rawUrl, targetFile)
            if (success) {
                onComplete(targetFile)
            } else {
                onComplete(null)
            }
        }
    }

    fun getCustomReactionEmojis(): List<String> {
        val raw = repository.preferencesManager.customReactionEmojis
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty {
            listOf("❤️", "🔥", "👍", "😂", "😮", "👏")
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        repository.webSocketManager.leaveChatRoom()
    }
}
