package com.example.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Chat
import com.example.data.model.User
import com.example.data.model.WsEvent
import com.example.data.network.WsConnectionState
import com.example.data.repository.DarkTalkRepository
import com.example.data.repository.Resource
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
    val createdChatId: Long? = null
)

class ChatsViewModel(private val repository: DarkTalkRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatsUiState())
    val uiState: StateFlow<ChatsUiState> = _uiState.asStateFlow()

    init {
        // Collect cached chats from Room persistence
        viewModelScope.launch {
            repository.cachedChats.collect { cached ->
                if (cached.isNotEmpty()) {
                    _uiState.update { it.copy(chats = cached) }
                }
            }
        }

        // Collect WebSocket connection state: auto-refresh when reconnected
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

        // Periodic background refresh every 1 minute
        viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                loadChats(isBackground = true)
            }
        }

        // Collect incoming WebSocket events across all chats
        viewModelScope.launch {
            repository.wsEvents.collect { event ->
                handleWsEvent(event)
            }
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
                    _uiState.update {
                        it.copy(
                            chats = result.data,
                            isLoading = false
                        )
                    }
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
                val currentList = _uiState.value.chats.toMutableList()
                val index = currentList.indexOfFirst { it.id == event.chatId }
                if (index != -1) {
                    currentList[index] = event.chat
                } else {
                    currentList.add(0, event.chat)
                }
                _uiState.update { it.copy(chats = currentList) }
            }
            is WsEvent.MessageCreated -> {
                val currentList = _uiState.value.chats.toMutableList()
                val index = currentList.indexOfFirst { it.id == event.chatId }
                if (index != -1) {
                    val existing = currentList.removeAt(index)
                    val isOwnMessage = event.message.sender?.id == repository.preferencesManager.userId
                    val newUnread = if (isOwnMessage) existing.unreadCount else existing.unreadCount + 1
                    val updated = existing.copy(
                        lastMessage = event.message,
                        unreadCount = newUnread
                    )
                    currentList.add(0, updated)
                    _uiState.update { it.copy(chats = currentList) }
                } else {
                    // Chat is missing from current list (e.g. new chat created by someone else) -> refresh!
                    loadChats(isBackground = true)
                }
            }
            is WsEvent.MessageUpdated -> {
                _uiState.update { state ->
                    state.copy(
                        chats = state.chats.map { chat ->
                            if (chat.id == event.chatId && chat.lastMessage?.id == event.message.id) {
                                chat.copy(lastMessage = event.message)
                            } else chat
                        }
                    )
                }
            }
            is WsEvent.MessageDeleted -> {
                _uiState.update { state ->
                    state.copy(
                        chats = state.chats.map { chat ->
                            if (chat.id == event.chatId && chat.lastMessage?.id == event.messageId) {
                                chat.copy(lastMessage = chat.lastMessage.copy(isDeleted = true, text = ""))
                            } else chat
                        }
                    )
                }
            }
            is WsEvent.MessageRead -> {
                if (event.userId == repository.preferencesManager.userId) {
                    _uiState.update { state ->
                        state.copy(
                            chats = state.chats.map { chat ->
                                if (chat.id == event.chatId) chat.copy(unreadCount = 0) else chat
                            }
                        )
                    }
                }
            }
            else -> {}
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun searchUsers(query: String) {
        if (query.isBlank()) {
            _uiState.update { it.copy(userSearchResults = emptyList(), isSearchingUsers = false) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchingUsers = true) }
            when (val res = repository.searchUsers(query)) {
                is Resource.Success -> {
                    _uiState.update { it.copy(userSearchResults = res.data, isSearchingUsers = false) }
                }
                else -> {
                    _uiState.update { it.copy(userSearchResults = emptyList(), isSearchingUsers = false) }
                }
            }
        }
    }

    fun toggleSelectGroupMember(user: User) {
        _uiState.update { state ->
            val list = state.selectedGroupMembers.toMutableList()
            if (list.any { it.id == user.id }) {
                list.removeAll { it.id == user.id }
            } else {
                list.add(user)
            }
            state.copy(selectedGroupMembers = list)
        }
    }

    fun removeGroupMember(user: User) {
        _uiState.update { state ->
            state.copy(selectedGroupMembers = state.selectedGroupMembers.filterNot { it.id == user.id })
        }
    }

    fun onFilterChanged(filter: ChatFilter) {
        _uiState.update { it.copy(activeFilter = filter) }
    }

    fun openDirectDialog() {
        _uiState.update {
            it.copy(
                isDirectDialogOpen = true,
                userSearchResults = emptyList()
            )
        }
    }

    fun closeDirectDialog() {
        _uiState.update {
            it.copy(
                isDirectDialogOpen = false,
                userSearchResults = emptyList()
            )
        }
    }

    fun openGroupDialog() {
        _uiState.update {
            it.copy(
                isGroupDialogOpen = true,
                userSearchResults = emptyList(),
                selectedGroupMembers = emptyList()
            )
        }
    }

    fun closeGroupDialog() {
        _uiState.update {
            it.copy(
                isGroupDialogOpen = false,
                userSearchResults = emptyList(),
                selectedGroupMembers = emptyList()
            )
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
                            createdChatId = result.data
                        )
                    }
                    loadChats()
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message
                        )
                    }
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun createGroupChat(participantNames: String, title: String?, desc: String?) {
        if (participantNames.isBlank()) return
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
                            createdChatId = result.data
                        )
                    }
                    loadChats()
                }
                is Resource.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message
                        )
                    }
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun deleteChat(chatId: Long) {
        viewModelScope.launch {
            when (val result = repository.deleteChat(chatId)) {
                is Resource.Success -> {
                    _uiState.update { state ->
                        state.copy(chats = state.chats.filter { it.id != chatId })
                    }
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun clearCreatedChatId() {
        _uiState.update { it.copy(createdChatId = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
