package com.example.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.Chat
import com.example.data.model.ChatParticipant
import com.example.data.model.Message
import com.example.data.model.User
import com.example.util.MediaUrlUtils

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: Long,
    val chatType: String,
    val title: String?,
    val description: String?,
    val avatar: String?,
    val createdBy: Long?,
    val unreadCount: Int,
    val lastMessageId: Long? = null,
    val lastMessageType: String? = null,
    val lastMessageText: String? = null,
    val lastMessageAttachment: String? = null,
    val lastMessageAttachmentName: String? = null,
    val lastMessageTime: String? = null,
    val lastMessageSenderName: String? = null,
    val otherUserId: Long?,
    val otherUserName: String?,
    val otherUserAvatar: String?,
    val otherUserOnline: Boolean?,
    val sortTimestamp: Long = 0L,
    val cachedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Chat {
        val participantsList = mutableListOf<ChatParticipant>()
        if (otherUserId != null && otherUserName != null) {
            participantsList.add(
                ChatParticipant(
                    user = User(
                        id = otherUserId,
                        username = otherUserName,
                        avatar = otherUserAvatar,
                        isOnline = otherUserOnline
                    )
                )
            )
        }

        val lastMsg = if (lastMessageId != null || !lastMessageText.isNullOrBlank() || !lastMessageAttachment.isNullOrBlank() || !lastMessageType.isNullOrBlank()) {
            Message(
                id = lastMessageId ?: 0L,
                chatId = id,
                messageType = lastMessageType ?: "text",
                text = lastMessageText,
                attachment = lastMessageAttachment,
                attachmentName = lastMessageAttachmentName,
                createdAt = lastMessageTime,
                sender = lastMessageSenderName?.let { User(id = 0L, username = it) }
            )
        } else null

        return Chat(
            id = id,
            chatType = chatType,
            title = title,
            description = description,
            avatar = avatar,
            createdBy = createdBy?.let { User(id = it, username = "") },
            unreadCount = unreadCount,
            participants = participantsList,
            lastMessage = lastMsg
        )
    }

    companion object {
        fun fromDomain(chat: Chat, currentUserId: Long): ChatEntity {
            val otherPart = chat.participants.firstOrNull { it.user?.id != currentUserId }?.user
            val lastMsg = chat.lastMessage
            val msgTime = lastMsg?.createdAt ?: chat.updatedAt ?: chat.createdAt
            val sortTs = MediaUrlUtils.parseIsoMillis(msgTime) ?: 0L

            return ChatEntity(
                id = chat.id,
                chatType = chat.chatType,
                title = chat.title,
                description = chat.description,
                avatar = chat.avatar,
                createdBy = chat.createdBy?.id,
                unreadCount = chat.unreadCount,
                lastMessageId = lastMsg?.id,
                lastMessageType = lastMsg?.messageType,
                lastMessageText = lastMsg?.text ?: chat.description,
                lastMessageAttachment = lastMsg?.attachment,
                lastMessageAttachmentName = lastMsg?.attachmentName,
                lastMessageTime = lastMsg?.createdAt,
                lastMessageSenderName = lastMsg?.sender?.username,
                otherUserId = otherPart?.id,
                otherUserName = otherPart?.username,
                otherUserAvatar = otherPart?.avatar,
                otherUserOnline = otherPart?.isOnline,
                sortTimestamp = sortTs
            )
        }
    }
}
