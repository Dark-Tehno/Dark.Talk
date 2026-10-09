package com.example.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Chat
import com.example.data.model.User
import com.example.data.model.WsEvent
import com.example.data.network.WsConnectionState
import com.example.data.repository.DarkTalkRepository
import com.example.data.repository.Resource
import com.example.util.MediaUrlUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ChatFilter {
    ALL,
    DIRECT,
    GROUPS,
    UNREAD
}

data class ChatsUiState(
    val chats: List<Chat> = emptyList(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val activeFilter: ChatFilter = ChatFilter.ALL,
    val isDirectDialogOpen: Boolean = false,
    val isGroupDialogOpen: Boolean = false,
    val userSearchResults: List<User> = emptyList(),
    val isSearchingUsers: Boolean = false,
    val selectedGroupMembers: List<User> = emptyList(),
    val wsState: WsConnectionState = WsConnectionState.DISCONNECTED,
    val errorMessage: String? = null,
    val createdChatId: Long? = null,
    val createdChatTitle: String? = null
)

class ChatsViewModel(private val repository: DarkTalkRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatsUiState())
    val uiState: StateFlow<ChatsUiState> = _uiState.asStateFlow()

    private val myId: Long get() = repository.preferencesManager.userId

    // После первой успешной загрузки с сервера Room-кэш больше не перетирает состояние.
    private var networkLoaded = false
    private var searchJob: Job? = null

    private fun List<Chat>.sortedByActivity(): List<Chat> = sortedByDescending { chat ->
        MediaUrlUtils.parseIsoMillis(chat.lastMessage?.createdAt ?: chat.updatedAt ?: chat.createdAt) ?: 0L
    }

    init {
        viewModelScope.launch {
            repository.cachedChats.collect { cached ->
                if (!networkLoaded && cached.isNotEmpty()) {
                    _uiState.update { it.copy(chats = cached.sortedByActivity()) }
                }
            }
        }

        viewModelScope.launch {
            var previousState = WsConnectionState.DISCONNECTED
            repository.wsConnectionState.collect { state ->
                _uiState.update { it.copy(wsState = state) }
                if (previousState != WsConnectionState.CONNECTED && state == WsConnectionState.CONNECTED) {
                    loadChats(isBackground = true)
                }
                previousState = state
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                loadChats(isBackground = true)
            }
        }

        viewModelScope.launch {
            repository.wsEvents.collect { event -> handleWsEvent(event) }
        }

        loadChats()
    }

    fun loadChats(isBackground: Boolean = false) {
        viewModelScope.launch {
            if (!isBackground) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }
            when (val result = repository.getChats()) {
                is Resource.Success -> {
                    networkLoaded = true
                    _uiState.update { it.copy(chats = result.data.sortedByActivity(), isLoading = false) }
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = if (!isBackground) result.message else it.errorMessage
                        )
                    }
                }
                is Resource.Loading -> {}
            }
        }
    }

    private fun handleWsEvent(event: WsEvent) {
        when (event) {
            is WsEvent.ChatCreated -> {
                val list = _uiState.value.chats.toMutableList()
                val index = list.indexOfFirst { it.id == event.chatId }
                if (index != -1) list[index] = event.chat else list.add(0, event.chat)
                _uiState.update { it.copy(chats = list.sortedByActivity()) }
            }
            is WsEvent.MessageCreated -> {
                val list = _uiState.value.chats.toMutableList()
                val index = list.indexOfFirst { it.id == event.chatId }
                if (index != -1) {
                    val existing = list.removeAt(index)
                    val isOwn = event.message.sender?.id == myId
                    val isOpenRoom = repository.webSocketManager.activeChatRoomId == event.chatId
                    val unread = if (isOwn || isOpenRoom) existing.unreadCount else existing.unreadCount + 1
                    list.add(0, existing.copy(lastMessage = event.message, unreadCount = unread))
                    _uiState.update { it.copy(chats = list) }
                } else {
                    loadChats(isBackground = true)
                }
            }
            is WsEvent.MessageUpdated -> {
                _uiState.update { state ->
                    state.copy(chats = state.chats.map { chat ->
                        if (chat.id == event.chatId && chat.lastMessage?.id == event.message.id) {
                            chat.copy(lastMessage = event.message)
                        } else chat
                    })
                }
            }
            is WsEvent.MessageDeleted -> {
                _uiState.update { state ->
                    state.copy(chats = state.chats.map { chat ->
                        val last = chat.lastMessage
                        if (chat.id == event.chatId && last != null && last.id == event.messageId) {
                            chat.copy(lastMessage = last.copy(isDeleted = true, text = "", attachment = null))
                        } else chat
                    })
                }
            }
            is WsEvent.MessageRead -> {
                if (event.userId == myId) {
                    _uiState.update { state ->
                        state.copy(chats = state.chats.map { chat ->
                            if (chat.id == event.chatId) chat.copy(unreadCount = 0) else chat
                        })
                    }
                }
            }
            else -> {}
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    /** С задержкой 300 мс: раньше на каждую букву уходил запрос, ответы могли прийти не по порядку. */
    fun searchUsers(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(userSearchResults = emptyList(), isSearchingUsers = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _uiState.update { it.copy(isSearchingUsers = true) }
            when (val res = repository.searchUsers(query)) {
                is Resource.Success -> _uiState.update {
                    it.copy(userSearchResults = res.data.filter { u -> u.id != myId }, isSearchingUsers = false)
                }
                else -> _uiState.update { it.copy(userSearchResults = emptyList(), isSearchingUsers = false) }
            }
        }
    }

    fun toggleSelectGroupMember(user: User) {
        _uiState.update { state ->
            val list = state.selectedGroupMembers.toMutableList()
            if (list.any { it.id == user.id }) list.removeAll { it.id == user.id } else list.add(user)
            state.copy(selectedGroupMembers = list)
        }
    }

    fun removeGroupMember(user: User) {
        _uiState.update { it.copy(selectedGroupMembers = it.selectedGroupMembers.filterNot { m -> m.id == user.id }) }
    }

    fun onFilterChanged(filter: ChatFilter) {
        _uiState.update { it.copy(activeFilter = filter) }
    }

    fun openDirectDialog() {
        _uiState.update { it.copy(isDirectDialogOpen = true, userSearchResults = emptyList(), errorMessage = null) }
    }

    fun closeDirectDialog() {
        searchJob?.cancel()
        _uiState.update { it.copy(isDirectDialogOpen = false, userSearchResults = emptyList(), errorMessage = null) }
    }

    fun openGroupDialog() {
        _uiState.update {
            it.copy(isGroupDialogOpen = true, userSearchResults = emptyList(), selectedGroupMembers = emptyList(), errorMessage = null)
        }
    }

    fun closeGroupDialog() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(isGroupDialogOpen = false, userSearchResults = emptyList(), selectedGroupMembers = emptyList(), errorMessage = null)
        }
    }

    fun createDirectChat(participantName: String) {
        if (participantName.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = repository.createDirectChat(participantName)) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isDirectDialogOpen = false,
                            userSearchResults = emptyList(),
                            createdChatId = result.data,
                            createdChatTitle = participantName.trim()
                        )
                    }
                    loadChats(isBackground = true)
                }
                is Resource.Error -> _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
                is Resource.Loading -> {}
            }
        }
    }

    fun createGroupChat(participantNames: String, title: String?, desc: String?) {
        if (participantNames.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Add at least one participant") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = repository.createGroupChat(participantNames, title, desc)) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isGroupDialogOpen = false,
                            userSearchResults = emptyList(),
                            selectedGroupMembers = emptyList(),
                            createdChatId = result.data,
                            createdChatTitle = title?.takeIf { t -> t.isNotBlank() } ?: "Group #${result.data}"
                        )
                    }
                    loadChats(isBackground = true)
                }
                is Resource.Error -> _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
                is Resource.Loading -> {}
            }
        }
    }

    /**
     * README: удалять чат целиком может только admin/owner. Обычный участник группы
     * должен выйти (DELETE /participants/{свой id}/), иначе сервер вернёт ошибку.
     */
    fun deleteOrLeaveChat(chat: Chat) {
        val myRole = chat.participants.firstOrNull { it.user?.id == myId }?.role?.lowercase()
        val canDelete = chat.chatType != "group" || myRole == "owner" || myRole == "admin"
        viewModelScope.launch {
            val error: String? = if (canDelete) {
                (repository.deleteChat(chat.id) as? Resource.Error)?.message
            } else {
                (repository.leaveOrRemoveParticipant(chat.id, myId) as? Resource.Error)?.message
            }
            if (error == null) {
                if (!canDelete) {
                    repository.database.chatDao().deleteChatById(chat.id)
                    repository.database.messageDao().deleteMessagesForChat(chat.id)
                }
                _uiState.update { state -> state.copy(chats = state.chats.filter { it.id != chat.id }) }
            } else {
                _uiState.update { it.copy(errorMessage = error) }
            }
        }
    }

    fun clearCreatedChatId() {
        _uiState.update { it.copy(createdChatId = null, createdChatTitle = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
