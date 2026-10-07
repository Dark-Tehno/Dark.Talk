package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class User(
    @Json(name = "id") val id: Long,
    @Json(name = "username") val username: String,
    @Json(name = "email") val email: String? = null,
    @Json(name = "avatar") val avatar: String? = null,
    @Json(name = "info") val info: String? = null,
    @Json(name = "date_of_birth") val dateOfBirth: String? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "is_online") val isOnline: Boolean? = null,
    @Json(name = "last_online") val lastOnline: String? = null,
    @Json(name = "email_confirmed") val emailConfirmed: Boolean? = null,
    @Json(name = "two_factor_enabled") val twoFactorEnabled: Boolean? = null,
    @Json(name = "date_joined") val dateJoined: String? = null
)

@JsonClass(generateAdapter = true)
data class Device(
    @Json(name = "id") val id: String? = null,
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "device_type") val deviceType: String? = null,
    @Json(name = "trusted") val trusted: Boolean? = null,
    @Json(name = "blocked") val blocked: Boolean? = null,
    @Json(name = "last_seen") val lastSeen: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class AuthResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "token") val token: String? = null,
    @Json(name = "user") val user: User? = null,
    @Json(name = "device") val device: Device? = null,
    @Json(name = "challenge_id") val challengeId: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "detail") val detail: String? = null
)

@JsonClass(generateAdapter = true)
data class RegisterRequest(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String,
    @Json(name = "username") val username: String? = null,
    @Json(name = "language") val language: String = "Russian",
    @Json(name = "date_of_birth") val dateOfBirth: String? = null,
    @Json(name = "device_id") val deviceId: String? = null
)

@JsonClass(generateAdapter = true)
data class LoginRequest(
    @Json(name = "username") val username: String,
    @Json(name = "password") val password: String,
    @Json(name = "device_id") val deviceId: String? = null
)

@JsonClass(generateAdapter = true)
data class TwoFactorVerifyRequest(
    @Json(name = "challenge_id") val challengeId: String,
    @Json(name = "code") val code: String,
    @Json(name = "device_id") val deviceId: String? = null
)

@JsonClass(generateAdapter = true)
data class TwoFactorResendRequest(
    @Json(name = "challenge_id") val challengeId: String
)

@JsonClass(generateAdapter = true)
data class TwoFactorStateResponse(
    @Json(name = "two_factor_enabled") val twoFactorEnabled: Boolean
)

@JsonClass(generateAdapter = true)
data class TwoFactorToggleRequest(
    @Json(name = "enabled") val enabled: Boolean
)

@JsonClass(generateAdapter = true)
data class LogoutRequest(
    @Json(name = "device_id") val deviceId: String
)

@JsonClass(generateAdapter = true)
data class ProfileResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "user") val user: User
)

@JsonClass(generateAdapter = true)
data class DevicesListResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "devices") val devices: List<Device> = emptyList()
)

@JsonClass(generateAdapter = true)
data class LoginHistoryItem(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "device") val device: String? = null,
    @Json(name = "ip") val ip: String? = null,
    @Json(name = "country") val country: String? = null,
    @Json(name = "city") val city: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class LoginHistoryResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "login_history") val loginHistory: List<LoginHistoryItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ChatParticipant(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "user") val user: User? = null,
    @Json(name = "role") val role: String? = null, // "admin" or "member"
    @Json(name = "is_muted") val isMuted: Boolean? = null,
    @Json(name = "left_at") val leftAt: String? = null
)

@JsonClass(generateAdapter = true)
data class Chat(
    @Json(name = "id") val id: Long,
    @Json(name = "chat_type") val chatType: String, // "direct" or "group"
    @Json(name = "title") val title: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "avatar") val avatar: String? = null,
    @Json(name = "created_by") val createdBy: User? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "unread_count") val unreadCount: Int = 0,
    @Json(name = "participants") val participants: List<ChatParticipant> = emptyList(),
    @Json(name = "last_message") val lastMessage: Message? = null
)

@JsonClass(generateAdapter = true)
data class ChatsListResponse(
    @Json(name = "chats") val chats: List<Chat> = emptyList()
)

@JsonClass(generateAdapter = true)
data class CreateChatResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "chat_id") val chatId: Long
)

@JsonClass(generateAdapter = true)
data class CreateDirectChatRequest(
    @Json(name = "chat_type") val chatType: String = "direct",
    @Json(name = "participant_name") val participantName: String
)

@JsonClass(generateAdapter = true)
data class CreateGroupChatRequest(
    @Json(name = "chat_type") val chatType: String = "group",
    @Json(name = "participant_names") val participantNames: String,
    @Json(name = "title") val title: String? = null,
    @Json(name = "description") val description: String? = null
)

@JsonClass(generateAdapter = true)
data class MessageReaction(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "user_id") val userId: Long? = null,
    @Json(name = "emoji") val emoji: String,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class Message(
    @Json(name = "id") val id: Long,
    @Json(name = "chat_id") val chatId: Long,
    @Json(name = "sender") val sender: User? = null,
    @Json(name = "reply_to") val replyTo: Long? = null,
    @Json(name = "message_type") val messageType: String = "text", // "text", "image", "file", "voice_message", "system"
    @Json(name = "text") val text: String? = null,
    @Json(name = "attachment") val attachment: String? = null,
    @Json(name = "attachment_name") val attachmentName: String? = null,
    @Json(name = "attachment_size") val attachmentSize: Long? = null,
    @Json(name = "metadata") val metadata: Map<String, Any?>? = null,
    @Json(name = "is_edited") val isEdited: Boolean = false,
    @Json(name = "is_deleted") val isDeleted: Boolean = false,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "reactions") val reactions: List<MessageReaction> = emptyList(),
    @Json(name = "read_by") val readBy: List<Long> = emptyList(),
    @Json(name = "client_message_id") val clientMessageId: String? = null
)

@JsonClass(generateAdapter = true)
data class MessagesPageResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "chat_id") val chatId: Long? = null,
    @Json(name = "messages") val messages: List<Message> = emptyList(),
    @Json(name = "has_more") val hasMore: Boolean = false,
    @Json(name = "next_before_id") val nextBeforeId: Long? = null
)

@JsonClass(generateAdapter = true)
data class CreateMessageRequest(
    @Json(name = "chat_id") val chatId: Long,
    @Json(name = "text") val text: String,
    @Json(name = "message_type") val messageType: String = "text",
    @Json(name = "reply_to") val replyTo: Long? = null,
    @Json(name = "client_message_id") val clientMessageId: String? = null
)

@JsonClass(generateAdapter = true)
data class CreateMessageResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "chat_id") val chatId: Long,
    @Json(name = "message_id") val messageId: Long
)

@JsonClass(generateAdapter = true)
data class EditMessageRequest(
    @Json(name = "text") val text: String
)

@JsonClass(generateAdapter = true)
data class ReadChatRequest(
    @Json(name = "last_message_id") val lastMessageId: Long? = null
)

@JsonClass(generateAdapter = true)
data class ReactionRequest(
    @Json(name = "emoji") val emoji: String
)

@JsonClass(generateAdapter = true)
data class ReactionResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "message_reaction") val messageReaction: MessageReaction? = null
)

@JsonClass(generateAdapter = true)
data class UserSearchResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "users") val users: List<User> = emptyList()
)

@JsonClass(generateAdapter = true)
data class UpdateProfileRequest(
    @Json(name = "username") val username: String? = null,
    @Json(name = "info") val info: String? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "date_of_birth") val dateOfBirth: String? = null,
    @Json(name = "avatar_access") val avatarAccess: String? = null
)

@JsonClass(generateAdapter = true)
data class AddParticipantsRequest(
    @Json(name = "usernames") val usernames: List<String>
)

@JsonClass(generateAdapter = true)
data class GenericStatusResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "detail") val detail: String? = null,
    @Json(name = "code") val code: String? = null
)

@JsonClass(generateAdapter = true)
data class PingResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "server_time") val serverTime: String? = null,
    @Json(name = "server_utc") val serverUtc: String? = null
)
