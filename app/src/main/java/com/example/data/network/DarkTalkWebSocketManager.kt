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
import okhttp3.*

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

    private var chatsWebSocket: WebSocket? = null
    private var chatRoomWebSocket: WebSocket? = null
    private var activeChatRoomId: Long? = null

    private val _connectionState = MutableStateFlow(WsConnectionState.DISCONNECTED)
    val connectionState: StateFlow<WsConnectionState> = _connectionState

    private val _roomConnectionState = MutableStateFlow(WsConnectionState.DISCONNECTED)
    val roomConnectionState: StateFlow<WsConnectionState> = _roomConnectionState

    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WsEvent> = _events

    private val rawEventAdapter = moshi.adapter(WsRawEvent::class.java)
    private val sendMessageAdapter = moshi.adapter(WsSendMessagePayload::class.java)
    private val typingAdapter = moshi.adapter(WsTypingPayload::class.java)
    private val readMessageAdapter = moshi.adapter(WsReadMessagePayload::class.java)
    private val editMessageAdapter = moshi.adapter(WsEditMessagePayload::class.java)
    private val deleteMessageAdapter = moshi.adapter(WsDeleteMessagePayload::class.java)

    /**
     * Connect to global user chats stream (/ws/chats/)
     */
    fun connectChatsStream() {
        val token = preferencesManager.authToken ?: return
        if (chatsWebSocket != null) return

        val wsUrl = "${preferencesManager.wsBaseUrl}/ws/chats/"
        val origin = preferencesManager.httpBaseUrl.trimEnd('/')

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Token $token")
            .addHeader("Dark-Talk-Secret-Key", preferencesManager.wsSecretKeyHeader)
            .addHeader("Origin", origin)
            .build()

        _connectionState.value = WsConnectionState.CONNECTING

        chatsWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "Chats WebSocket connected to $wsUrl")
                _connectionState.value = WsConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text, isRoom = false)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                _connectionState.value = WsConnectionState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "Chats WebSocket error: ${t.message} (code: ${response?.code})")
                _connectionState.value = WsConnectionState.ERROR
                chatsWebSocket = null
                // Attempt reconnect after delay if still logged in
                scope.launch {
                    delay(5000)
                    if (preferencesManager.isLoggedIn && chatsWebSocket == null) {
                        connectChatsStream()
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "Chats WebSocket closed: $code $reason")
                _connectionState.value = WsConnectionState.DISCONNECTED
                chatsWebSocket = null
            }
        })
    }

    /**
     * Connect to a specific chat room (/ws/chat/{chat_id}/)
     */
    fun enterChatRoom(chatId: Long) {
        val token = preferencesManager.authToken ?: return
        leaveChatRoom()

        activeChatRoomId = chatId
        val wsUrl = "${preferencesManager.wsBaseUrl}/ws/chat/$chatId/"
        val origin = preferencesManager.httpBaseUrl.trimEnd('/')

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Token $token")
            .addHeader("Dark-Talk-Secret-Key", preferencesManager.wsSecretKeyHeader)
            .addHeader("Origin", origin)
            .build()

        _roomConnectionState.value = WsConnectionState.CONNECTING

        chatRoomWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "Chat room #$chatId WebSocket connected")
                _roomConnectionState.value = WsConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text, isRoom = true)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                _roomConnectionState.value = WsConnectionState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "Chat room WebSocket error: ${t.message} (code: ${response?.code})")
                _roomConnectionState.value = WsConnectionState.ERROR
                chatRoomWebSocket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "Chat room WebSocket closed: $code $reason")
                _roomConnectionState.value = WsConnectionState.DISCONNECTED
                chatRoomWebSocket = null
            }
        })
    }

    fun leaveChatRoom() {
        chatRoomWebSocket?.close(1000, "Leaving chat room")
        chatRoomWebSocket = null
        activeChatRoomId = null
        _roomConnectionState.value = WsConnectionState.DISCONNECTED
    }

    fun disconnectAll() {
        chatsWebSocket?.close(1000, "User logged out or stopped")
        chatsWebSocket = null
        leaveChatRoom()
        _connectionState.value = WsConnectionState.DISCONNECTED
    }

    fun sendRoomTextMessage(text: String, replyTo: Long? = null, clientMessageId: String? = null): Boolean {
        val ws = chatRoomWebSocket ?: return false
        val payload = WsSendMessagePayload(
            text = text,
            replyTo = replyTo,
            clientMessageId = clientMessageId
        )
        val json = sendMessageAdapter.toJson(payload)
        return ws.send(json)
    }

    fun sendRoomTyping(isTyping: Boolean): Boolean {
        val ws = chatRoomWebSocket ?: return false
        val payload = WsTypingPayload(isTyping = isTyping)
        val json = typingAdapter.toJson(payload)
        return ws.send(json)
    }

    fun sendRoomReadMessage(messageId: Long): Boolean {
        val ws = chatRoomWebSocket ?: return false
        val payload = WsReadMessagePayload(messageId = messageId)
        val json = readMessageAdapter.toJson(payload)
        return ws.send(json)
    }

    fun sendRoomEditMessage(messageId: Long, newText: String): Boolean {
        val ws = chatRoomWebSocket ?: return false
        val payload = WsEditMessagePayload(messageId = messageId, text = newText)
        val json = editMessageAdapter.toJson(payload)
        return ws.send(json)
    }

    fun sendRoomDeleteMessage(messageId: Long): Boolean {
        val ws = chatRoomWebSocket ?: return false
        val payload = WsDeleteMessagePayload(messageId = messageId)
        val json = deleteMessageAdapter.toJson(payload)
        return ws.send(json)
    }

    private fun handleIncomingMessage(jsonText: String, isRoom: Boolean) {
        try {
            val raw = rawEventAdapter.fromJson(jsonText) ?: return
            val event: WsEvent? = when (raw.type) {
                "connection_ready" -> WsEvent.ConnectionReady(raw.scope, raw.chatId)
                "chat_created" -> {
                    val chatObj = raw.chat
                    val cId = raw.chatId ?: chatObj?.id
                    if (cId != null && chatObj != null) {
                        WsEvent.ChatCreated(cId, chatObj)
                    } else null
                }
                "message_created" -> {
                    val msg = raw.message
                    val cId = raw.chatId ?: msg?.chatId
                    if (cId != null && msg != null) {
                        WsEvent.MessageCreated(cId, msg, raw.clientMessageId)
                    } else null
                }
                "message_updated" -> {
                    val msg = raw.message
                    val cId = raw.chatId ?: msg?.chatId
                    if (cId != null && msg != null) {
                        WsEvent.MessageUpdated(cId, msg)
                    } else null
                }
                "message_deleted" -> {
                    val cId = raw.chatId
                    val mId = raw.messageId
                    if (cId != null && mId != null) {
                        WsEvent.MessageDeleted(cId, mId)
                    } else null
                }
                "message_read" -> {
                    val cId = raw.chatId
                    val mId = raw.messageId
                    val uId = raw.userId
                    if (cId != null && mId != null && uId != null) {
                        WsEvent.MessageRead(cId, mId, uId, raw.readAt)
                    } else null
                }
                "typing" -> {
                    val cId = raw.chatId
                    val uId = raw.userId
                    val typing = raw.isTyping ?: false
                    if (cId != null && uId != null) {
                        WsEvent.Typing(cId, uId, typing)
                    } else null
                }
                "error" -> {
                    WsEvent.Error(raw.code ?: "unknown_error", raw.errorDescription)
                }
                else -> null
            }

            if (event != null) {
                scope.launch {
                    _events.emit(event)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse incoming WebSocket message: $jsonText", e)
        }
    }
}
