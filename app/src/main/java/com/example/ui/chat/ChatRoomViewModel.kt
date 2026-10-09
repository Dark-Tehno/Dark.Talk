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
import kotlinx.coroutines.isActive
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

    private var networkLoaded = false
    private var typingJob: Job? = null
    private var lastTypingSentAt = 0L
    private var searchJob: Job? = null
    private val typingTimeoutJobs = mutableMapOf<Long, Job>()

    /** Объединяет списки по id: серверная версия заменяет локальную, порядок – по id. */
    private fun merge(old: List<Message>, new: List<Message>): List<Message> {
        val map = LinkedHashMap<Long, Message>()
        old.forEach { map[it.id] = it }
        new.forEach { map[it.id] = it }
        return map.values.sortedBy { it.id }
    }

    init {
        viewModelScope.launch {
            repository.database.chatDao().getChatByIdSync(chatId)?.let { cached ->
                _uiState.update { it.copy(chat = cached.toDomain()) }
            }
        }

        // Room-кэш нужен только для мгновенного первого показа. Раньше он продолжал
        // перезаписывать список и после каждого сообщения «обрезал» историю до 10 штук.
        viewModelScope.launch {
            repository.getCachedMessages(chatId).collect { cached ->
                if (!networkLoaded && cached.isNotEmpty()) {
                    _uiState.update { it.copy(messages = merge(it.messages, cached)) }
                }
            }
        }

        repository.webSocketManager.enterChatRoom(chatId)

        viewModelScope.launch {
            var previous = WsConnectionState.DISCONNECTED
            repository.roomConnectionState.collect { state ->
                _uiState.update { it.copy(roomWsState = state) }
                // Переподключились -> догружаем то, что пропустили
                if (previous != WsConnectionState.CONNECTED && state == WsConnectionState.CONNECTED && networkLoaded) {
                    refreshLatest()
                }
                previous = state
            }
        }

        viewModelScope.launch {
            repository.wsEvents.collect { event -> handleWsEvent(event) }
        }

        // README: HTTP-реакции/правки/удаления не публикуют WS-события, поэтому
        // изменения других пользователей подтягиваем периодически.
        viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                if (networkLoaded) refreshLatest()
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
        val other = chat.participants.firstOrNull { it.user?.id != currentUserId }?.user ?: return
        viewModelScope.launch {
            val fullUser = repository.getUserById(other.id) ?: other
            // Данные из ответа сервера свежее кэша (онлайн / last_online)
            _uiState.update { it.copy(otherUserProfile = other.takeIf { o -> o.lastOnline != null || o.isOnline != null } ?: fullUser) }
        }
    }

    fun loadMessages() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = it.messages.isEmpty(), errorMessage = null) }
            when (val result = repository.getMessages(chatId, limit = 50)) {
                is Resource.Success -> {
                    networkLoaded = true
                    val msgs = result.data.messages
                    _uiState.update {
                        it.copy(
                            messages = merge(it.messages, msgs),
                            hasMore = result.data.hasMore,
                            nextBeforeId = result.data.nextBeforeId,
                            isLoading = false
                        )
                    }
                    msgs.lastOrNull { it.sender?.id != currentUserId }?.let { markAsRead(it.id, it.sender?.id) }
                }
                is Resource.Error -> _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
                is Resource.Loading -> {}
            }
        }
    }

    /** Тихо подтягивает последнюю страницу и сливает её со списком (не трогая пагинацию). */
    fun refreshLatest() {
        viewModelScope.launch {
            val result = repository.getMessages(chatId, limit = 50)
            if (result is Resource.Success) {
                _uiState.update { it.copy(messages = merge(it.messages, result.data.messages)) }
            }
        }
    }

    fun loadMoreMessages() {
        val beforeId = _uiState.value.nextBeforeId ?: return
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMore) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            when (val result = repository.getMessages(chatId, limit = 50, beforeId = beforeId)) {
                is Resource.Success -> _uiState.update { state ->
                    state.copy(
                        messages = merge(state.messages, result.data.messages),
                        hasMore = result.data.hasMore,
                        nextBeforeId = result.data.nextBeforeId,
                        isLoadingMore = false
                    )
                }
                is Resource.Error -> _uiState.update { it.copy(isLoadingMore = false) }
                is Resource.Loading -> {}
            }
        }
    }

    private fun handleWsEvent(event: WsEvent) {
        when (event) {
            is WsEvent.MessageCreated -> {
                if (event.chatId != chatId) return
                _uiState.update { state ->
                    val list = state.messages.toMutableList()
                    val idx = list.indexOfFirst {
                        it.id == event.message.id ||
                                (event.clientMessageId != null && it.clientMessageId == event.clientMessageId)
                    }
                    if (idx != -1) list[idx] = event.message else list.add(event.message)
                    state.copy(messages = list.sortedBy { it.id })
                }
                if (event.message.sender?.id != currentUserId) {
                    markAsRead(event.message.id, event.message.sender?.id)
                }
            }
            is WsEvent.MessageUpdated -> {
                if (event.chatId == chatId) {
                    _uiState.update { state ->
                        state.copy(messages = state.messages.map { if (it.id == event.message.id) event.message else it })
                    }
                }
            }
            is WsEvent.MessageDeleted -> {
                if (event.chatId == chatId) markDeletedLocally(event.messageId)
            }
            is WsEvent.MessageRead -> {
                if (event.chatId == chatId) {
                    _uiState.update { state ->
                        state.copy(messages = state.messages.map { msg ->
                            if (msg.id <= event.messageId && !msg.readBy.contains(event.userId)) {
                                msg.copy(readBy = msg.readBy + event.userId)
                            } else msg
                        })
                    }
                }
            }
            is WsEvent.Typing -> {
                if (event.chatId == chatId && event.userId != currentUserId) {
                    if (event.isTyping) {
                        _uiState.update { it.copy(typingUserIds = it.typingUserIds + event.userId) }
                        typingTimeoutJobs[event.userId]?.cancel()
                        typingTimeoutJobs[event.userId] = viewModelScope.launch {
                            delay(3500)
                            _uiState.update { st -> st.copy(typingUserIds = st.typingUserIds - event.userId) }
                        }
                    } else {
                        typingTimeoutJobs[event.userId]?.cancel()
                        _uiState.update { it.copy(typingUserIds = it.typingUserIds - event.userId) }
                    }
                }
            }
            is WsEvent.Error -> _uiState.update { it.copy(errorMessage = event.message ?: event.code) }
            else -> {}
        }
    }

    private fun markDeletedLocally(messageId: Long) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map {
                if (it.id == messageId) it.copy(isDeleted = true, text = "", attachment = null, attachmentName = null, attachmentSize = null) else it
            })
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val editing = _uiState.value.editingMessage
        if (editing != null) {
            if (editing.text != trimmed) editMessage(editing.id, trimmed)
            _uiState.update { it.copy(editingMessage = null) }
            return
        }

        val replyTo = _uiState.value.replyingTo?.id
        val clientMsgId = "local-${UUID.randomUUID().toString().take(8)}"
        stopTyping()

        viewModelScope.launch {
            _uiState.update { it.copy(replyingTo = null) }
            when (val result = repository.sendTextMessage(chatId, trimmed, replyTo, clientMsgId)) {
                is Resource.Success -> {
                    // Если WS комнаты недоступен (ушло по HTTP) – не ждём события, а перечитываем.
                    if (repository.roomConnectionState.value != WsConnectionState.CONNECTED) refreshLatest()
                }
                is Resource.Error -> _uiState.update { it.copy(errorMessage = result.message) }
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
                is Resource.Success -> refreshLatest() // раньше loadMessages() затирал подгруженную историю
                is Resource.Error -> _uiState.update { it.copy(errorMessage = result.message) }
                is Resource.Loading -> {}
            }
        }
    }

    fun editMessage(messageId: Long, newText: String) {
        viewModelScope.launch {
            when (val result = repository.editMessage(chatId, messageId, newText)) {
                is Resource.Success -> _uiState.update { state ->
                    state.copy(messages = state.messages.map {
                        if (it.id == messageId) it.copy(text = newText, isEdited = true) else it
                    })
                }
                is Resource.Error -> _uiState.update { it.copy(errorMessage = result.message) }
                is Resource.Loading -> {}
            }
        }
    }

    /**
     * Через WS удалять можно только своё сообщение; admin/owner удаляют чужие через HTTP (README).
     */
    fun deleteMessage(message: Message) {
        val own = message.sender?.id == currentUserId
        viewModelScope.launch {
            when (val result = repository.deleteMessage(chatId, message.id, forceHttp = !own)) {
                is Resource.Success -> markDeletedLocally(message.id)
                is Resource.Error -> _uiState.update { it.copy(errorMessage = result.message) }
                is Resource.Loading -> {}
            }
        }
    }

    /** Индикатор «печатает»: не чаще раза в 2 с, и «false» после паузы 2.5 с. */
    fun onTypingInput() {
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAt > 2000) {
            lastTypingSentAt = now
            repository.sendTyping(true)
        }
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(2500)
            lastTypingSentAt = 0L
            repository.sendTyping(false)
        }
    }

    fun stopTyping() {
        typingJob?.cancel()
        if (lastTypingSentAt != 0L) {
            lastTypingSentAt = 0L
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
        val mine = message.reactions.firstOrNull { it.userId == currentUserId && it.emoji == emoji }
        viewModelScope.launch {
            if (mine != null) {
                when (val r = repository.removeReaction(message.id, emoji)) {
                    is Resource.Success -> _uiState.update { state ->
                        state.copy(messages = state.messages.map { msg ->
                            if (msg.id == message.id) {
                                msg.copy(reactions = msg.reactions.filterNot { it.userId == currentUserId && it.emoji == emoji })
                            } else msg
                        })
                    }
                    is Resource.Error -> _uiState.update { it.copy(errorMessage = r.message) }
                    is Resource.Loading -> {}
                }
            } else {
                when (val result = repository.addReaction(message.id, emoji)) {
                    is Resource.Success -> {
                        val reaction = result.data ?: MessageReaction(userId = currentUserId, emoji = emoji)
                        _uiState.update { state ->
                            state.copy(messages = state.messages.map { msg ->
                                if (msg.id == message.id) msg.copy(reactions = msg.reactions + reaction) else msg
                            })
                        }
                    }
                    is Resource.Error -> _uiState.update { it.copy(errorMessage = result.message) }
                    is Resource.Loading -> {}
                }
            }
        }
    }

    fun markAsRead(messageId: Long? = null, senderId: Long? = null) {
        if (senderId != null && senderId == currentUserId) return
        viewModelScope.launch {
            repository.markChatAsRead(chatId, messageId)
            if (messageId != null) repository.markMessageRead(chatId, messageId)
        }
    }

    fun addParticipants(usernames: List<String>) {
        if (usernames.isEmpty()) return
        viewModelScope.launch {
            when (val res = repository.addParticipants(chatId, usernames)) {
                is Resource.Success -> loadChatDetails()
                is Resource.Error -> _uiState.update { it.copy(errorMessage = res.message) }
                is Resource.Loading -> {}
            }
        }
    }

    fun removeParticipant(userId: Long) {
        viewModelScope.launch {
            when (val res = repository.leaveOrRemoveParticipant(chatId, userId)) {
                is Resource.Success -> loadChatDetails()
                is Resource.Error -> _uiState.update { it.copy(errorMessage = res.message) }
                is Resource.Loading -> {}
            }
        }
    }

    fun leaveChat(onDone: () -> Unit) {
        viewModelScope.launch {
            when (val res = repository.leaveOrRemoveParticipant(chatId, currentUserId)) {
                is Resource.Success -> {
                    repository.database.chatDao().deleteChatById(chatId)
                    repository.database.messageDao().deleteMessagesForChat(chatId)
                    onDone()
                }
                is Resource.Error -> _uiState.update { it.copy(errorMessage = res.message) }
                is Resource.Loading -> {}
            }
        }
    }

    fun searchUsers(query: String, onResult: (List<User>) -> Unit) {
        searchJob?.cancel()
        if (query.isBlank()) {
            onResult(emptyList())
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            when (val res = repository.searchUsers(query)) {
                is Resource.Success -> onResult(res.data)
                else -> onResult(emptyList())
            }
        }
    }

    fun downloadVoiceFile(rawUrl: String, messageId: Long, onComplete: (File?) -> Unit) {
        // Отдельная папка: media_cache принадлежит Coil DiskCache и должна использоваться только им.
        val voiceDir = DarkTalkApplication.instance.cacheDir.resolve("voice_cache")
        val targetFile = voiceDir.resolve("voice_${messageId}.m4a")
        viewModelScope.launch {
            val success = repository.downloadAuthenticatedFile(rawUrl, targetFile)
            onComplete(if (success) targetFile else null)
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
        stopTyping()
        repository.webSocketManager.leaveChatRoom()
    }
}
