package com.example.data.network

import android.util.Log
import com.example.data.local.PreferencesManager
import com.example.data.model.*
import com.squareup.moshi.Moshi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

enum class WsConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

class DarkTalkWebSocketManager(
    private val preferencesManager: PreferencesManager,
    private val moshi: Moshi,
    private val okHttpClient: OkHttpClient
) {
    private val tag = "DarkTalkWS"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()

    private var chatsWebSocket: WebSocket? = null
    private var chatRoomWebSocket: WebSocket? = null

    // «Поколения» сокетов: колбэки устаревшего сокета не должны затирать состояние нового.
    private var chatsGen = 0
    private var roomGen = 0

    @Volatile
    var activeChatRoomId: Long? = null
        private set

    private var chatsReconnectJob: Job? = null
    private var roomReconnectJob: Job? = null

    private val _connectionState = MutableStateFlow(WsConnectionState.DISCONNECTED)
    val connectionState: StateFlow<WsConnectionState> = _connectionState

    private val _roomConnectionState = MutableStateFlow(WsConnectionState.DISCONNECTED)
    val roomConnectionState: StateFlow<WsConnectionState> = _roomConnectionState

    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<WsEvent> = _events

    private val rawEventAdapter = moshi.adapter(WsRawEvent::class.java)
    private val sendMessageAdapter = moshi.adapter(WsSendMessagePayload::class.java)
    private val typingAdapter = moshi.adapter(WsTypingPayload::class.java)
    private val readMessageAdapter = moshi.adapter(WsReadMessagePayload::class.java)
    private val editMessageAdapter = moshi.adapter(WsEditMessagePayload::class.java)
    private val deleteMessageAdapter = moshi.adapter(WsDeleteMessagePayload::class.java)

    // Одно и то же событие приходит и из /ws/chats/, и из /ws/chat/{id}/ – отсекаем дубли.
    private val seen = LinkedHashMap<String, Long>()

    private fun isDuplicate(key: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(seen) {
            val iterator = seen.entries.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next().value > 4000) iterator.remove() else break
            }
            if (seen.containsKey(key)) return true
            seen[key] = now
            return false
        }
    }

    private fun buildRequest(path: String): Request? {
        val token = preferencesManager.authToken?.takeIf { it.isNotBlank() } ?: return null
        return Request.Builder()
            .url("${preferencesManager.wsBaseUrl}$path")
            .addHeader("Authorization", "Token $token")
            .addHeader("Dark-Talk-Secret-Key", preferencesManager.wsSecretKeyHeader)
            .addHeader("Origin", preferencesManager.httpBaseUrl.trimEnd('/'))
            .build()
    }

    private fun shouldRetry(httpCode: Int?, closeCode: Int? = null): Boolean {
        if (httpCode == 401 || httpCode == 403) return false
        if (closeCode == 1000 || closeCode == 4001 || closeCode == 4003) return false
        return preferencesManager.isLoggedIn
    }

    // ---------------- /ws/chats/ ----------------

    fun connectChatsStream() {
        synchronized(lock) {
            if (chatsWebSocket != null) return
            val request = buildRequest("/ws/chats/") ?: return
            val gen = ++chatsGen
            _connectionState.value = WsConnectionState.CONNECTING

            chatsWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (gen != chatsGen) return
                    Log.d(tag, "Chats WebSocket connected")
                    _connectionState.value = WsConnectionState.CONNECTED
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (gen != chatsGen) return
                    handleIncomingMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    synchronized(lock) {
                        if (gen != chatsGen) return
                        Log.e(tag, "Chats WebSocket error: ${t.message} (http ${response?.code})")
                        _connectionState.value = WsConnectionState.ERROR
                        chatsWebSocket = null
                        if (shouldRetry(response?.code)) scheduleChatsReconnect()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    synchronized(lock) {
                        if (gen != chatsGen) return
                        Log.d(tag, "Chats WebSocket closed: $code $reason")
                        _connectionState.value = WsConnectionState.DISCONNECTED
                        chatsWebSocket = null
                        if (shouldRetry(null, code)) scheduleChatsReconnect()
                    }
                }
            })
        }
    }

    private fun scheduleChatsReconnect() {
        chatsReconnectJob?.cancel()
        chatsReconnectJob = scope.launch {
            delay(5000)
            if (preferencesManager.isLoggedIn && chatsWebSocket == null) connectChatsStream()
        }
    }

    // ---------------- /ws/chat/{id}/ ----------------

    fun enterChatRoom(chatId: Long) {
        synchronized(lock) {
            if (activeChatRoomId == chatId && chatRoomWebSocket != null) return
            leaveChatRoom()
            connectRoom(chatId)
        }
    }

    private fun connectRoom(chatId: Long) {
        synchronized(lock) {
            val request = buildRequest("/ws/chat/$chatId/") ?: return
            activeChatRoomId = chatId
            val gen = ++roomGen
            _roomConnectionState.value = WsConnectionState.CONNECTING

            chatRoomWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (gen != roomGen) return
                    Log.d(tag, "Chat room #$chatId connected")
                    _roomConnectionState.value = WsConnectionState.CONNECTED
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (gen != roomGen) return
                    handleIncomingMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    synchronized(lock) {
                        if (gen != roomGen) return
                        Log.e(tag, "Chat room WebSocket error: ${t.message} (http ${response?.code})")
                        _roomConnectionState.value = WsConnectionState.ERROR
                        chatRoomWebSocket = null
                        if (shouldRetry(response?.code)) scheduleRoomReconnect(chatId)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    synchronized(lock) {
                        if (gen != roomGen) return
                        _roomConnectionState.value = WsConnectionState.DISCONNECTED
                        chatRoomWebSocket = null
                        // 4003 – мы больше не участник чата, переподключаться не нужно
                        if (shouldRetry(null, code)) scheduleRoomReconnect(chatId)
                    }
                }
            })
        }
    }

    private fun scheduleRoomReconnect(chatId: Long) {
        roomReconnectJob?.cancel()
        roomReconnectJob = scope.launch {
            delay(3000)
            if (activeChatRoomId == chatId && chatRoomWebSocket == null && preferencesManager.isLoggedIn) {
                connectRoom(chatId)
            }
        }
    }

    fun leaveChatRoom() {
        synchronized(lock) {
            roomGen++ // инвалидируем колбэки старого сокета
            roomReconnectJob?.cancel()
            chatRoomWebSocket?.close(1000, "Leaving chat room")
            chatRoomWebSocket = null
            activeChatRoomId = null
            _roomConnectionState.value = WsConnectionState.DISCONNECTED
        }
    }

    fun disconnectAll() {
        synchronized(lock) {
            chatsGen++
            chatsReconnectJob?.cancel()
            chatsWebSocket?.close(1000, "User logged out or stopped")
            chatsWebSocket = null
            _connectionState.value = WsConnectionState.DISCONNECTED
            leaveChatRoom()
        }
    }

    // ---------------- Отправка ----------------

    private fun sendInRoom(json: String): Boolean {
        if (_roomConnectionState.value != WsConnectionState.CONNECTED) return false
        return chatRoomWebSocket?.send(json) ?: false
    }

    fun sendRoomTextMessage(text: String, replyTo: Long? = null, clientMessageId: String? = null): Boolean =
        sendInRoom(sendMessageAdapter.toJson(WsSendMessagePayload(text = text, replyTo = replyTo, clientMessageId = clientMessageId)))

    fun sendRoomTyping(isTyping: Boolean): Boolean =
        sendInRoom(typingAdapter.toJson(WsTypingPayload(isTyping = isTyping)))

    fun sendRoomReadMessage(messageId: Long): Boolean =
        sendInRoom(readMessageAdapter.toJson(WsReadMessagePayload(messageId = messageId)))

    fun sendRoomEditMessage(messageId: Long, newText: String): Boolean =
        sendInRoom(editMessageAdapter.toJson(WsEditMessagePayload(messageId = messageId, text = newText)))

    fun sendRoomDeleteMessage(messageId: Long): Boolean =
        sendInRoom(deleteMessageAdapter.toJson(WsDeleteMessagePayload(messageId = messageId)))

    // ---------------- Приём ----------------

    private fun handleIncomingMessage(jsonText: String) {
        try {
            // В ошибках поле "message" – строка (в остальных событиях – объект Message),
            // поэтому ошибки разбираем отдельно, иначе Moshi падает и ошибка теряется.
            val head = JSONObject(jsonText)
            if (head.optString("type") == "error") {
                val msg = head.optString("message").takeIf { it.isNotBlank() }
                emit(WsEvent.Error(head.optString("code", "unknown_error"), msg))
                return
            }

            val raw = rawEventAdapter.fromJson(jsonText) ?: return
            val event: WsEvent? = when (raw.type) {
                "connection_ready" -> WsEvent.ConnectionReady(raw.scope, raw.chatId)
                "chat_created" -> {
                    val chatObj = raw.chat
                    val cId = raw.chatId ?: chatObj?.id
                    if (cId != null && chatObj != null && !isDuplicate("n:$cId")) WsEvent.ChatCreated(cId, chatObj) else null
                }
                "message_created" -> {
                    val msg = raw.message
                    val cId = raw.chatId ?: msg?.chatId
                    if (cId != null && msg != null && !isDuplicate("c:${msg.id}")) {
                        WsEvent.MessageCreated(cId, msg, raw.clientMessageId)
                    } else null
                }
                "message_updated" -> {
                    val msg = raw.message
                    val cId = raw.chatId ?: msg?.chatId
                    if (cId != null && msg != null && !isDuplicate("u:${msg.id}:${msg.updatedAt}:${msg.text.hashCode()}")) {
                        WsEvent.MessageUpdated(cId, msg)
                    } else null
                }
                "message_deleted" -> {
                    val cId = raw.chatId
                    val mId = raw.messageId
                    if (cId != null && mId != null && !isDuplicate("d:$mId")) WsEvent.MessageDeleted(cId, mId) else null
                }
                "message_read" -> {
                    val cId = raw.chatId
                    val mId = raw.messageId
                    val uId = raw.userId
                    if (cId != null && mId != null && uId != null && !isDuplicate("r:$cId:$mId:$uId")) {
                        WsEvent.MessageRead(cId, mId, uId, raw.readAt)
                    } else null
                }
                "typing" -> {
                    val cId = raw.chatId
                    val uId = raw.userId
                    if (cId != null && uId != null) WsEvent.Typing(cId, uId, raw.isTyping ?: false) else null
                }
                else -> null
            }
            event?.let { emit(it) }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse incoming WebSocket message: $jsonText", e)
        }
    }

    // tryEmit сохраняет порядок событий (раньше scope.launch { emit } мог их переставлять).
    private fun emit(event: WsEvent) {
        _events.tryEmit(event)
    }
}
