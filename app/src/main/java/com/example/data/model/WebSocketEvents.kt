package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

sealed class WsEvent {
    data class ConnectionReady(val scope: String?, val chatId: Long?) : WsEvent()
    data class ChatCreated(val chatId: Long, val chat: Chat) : WsEvent()
    data class MessageCreated(val chatId: Long, val message: Message, val clientMessageId: String?) : WsEvent()
    data class MessageUpdated(val chatId: Long, val message: Message) : WsEvent()
    data class MessageDeleted(val chatId: Long, val messageId: Long) : WsEvent()
    data class MessageRead(val chatId: Long, val messageId: Long, val userId: Long, val readAt: String?) : WsEvent()
    data class Typing(val chatId: Long, val userId: Long, val isTyping: Boolean) : WsEvent()
    data class Error(val code: String, val message: String?) : WsEvent()
}

@JsonClass(generateAdapter = true)
data class WsRawEvent(
    @Json(name = "type") val type: String,
    @Json(name = "scope") val scope: String? = null,
    @Json(name = "chat_id") val chatId: Long? = null,
    @Json(name = "chat") val chat: Chat? = null,
    @Json(name = "user_id") val userId: Long? = null,
    @Json(name = "message_id") val messageId: Long? = null,
    @Json(name = "message") val message: Message? = null,
    @Json(name = "client_message_id") val clientMessageId: String? = null,
    @Json(name = "is_typing") val isTyping: Boolean? = null,
    @Json(name = "read_at") val readAt: String? = null,
    @Json(name = "code") val code: String? = null,
    @Json(name = "message_text") val errorDescription: String? = null
)

@JsonClass(generateAdapter = true)
data class WsSendMessagePayload(
    @Json(name = "type") val type: String = "send_message",
    @Json(name = "text") val text: String,
    @Json(name = "reply_to") val replyTo: Long? = null,
    @Json(name = "client_message_id") val clientMessageId: String? = null
)

@JsonClass(generateAdapter = true)
data class WsTypingPayload(
    @Json(name = "type") val type: String = "typing",
    @Json(name = "is_typing") val isTyping: Boolean
)

@JsonClass(generateAdapter = true)
data class WsReadMessagePayload(
    @Json(name = "type") val type: String = "read_message",
    @Json(name = "message_id") val messageId: Long
)

@JsonClass(generateAdapter = true)
data class WsEditMessagePayload(
    @Json(name = "type") val type: String = "edit_message",
    @Json(name = "message_id") val messageId: Long,
    @Json(name = "text") val text: String
)

@JsonClass(generateAdapter = true)
data class WsDeleteMessagePayload(
    @Json(name = "type") val type: String = "delete_message",
    @Json(name = "message_id") val messageId: Long
)
