package com.example.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.Message
import com.example.data.model.MessageReaction
import com.example.data.model.User

@Entity(
    tableName = "messages",
    indices = [Index(value = ["chatId"]), Index(value = ["chatId", "id"])]
)
data class MessageEntity(
    @PrimaryKey val id: Long,
    val chatId: Long,
    val senderId: Long?,
    val senderUsername: String?,
    val senderAvatar: String?,
    val replyTo: Long?,
    val messageType: String,
    val text: String?,
    val attachment: String?,
    val attachmentName: String?,
    val attachmentSize: Long?,
    val isEdited: Boolean,
    val isDeleted: Boolean,
    val createdAt: String?,
    val clientMessageId: String?,
    val isRead: Boolean,
    val reactionsRaw: String? = null
) {
    fun toDomain(): Message {
        val parsedReactions = reactionsRaw?.split(";")?.filter { it.isNotBlank() }?.mapNotNull { item ->
            val parts = item.split(":")
            if (parts.size >= 2) {
                val emoji = parts[0]
                val uId = parts[1].toLongOrNull() ?: 0L
                MessageReaction(emoji = emoji, userId = uId)
            } else null
        } ?: emptyList()

        return Message(
            id = id,
            chatId = chatId,
            sender = senderId?.let {
                User(id = it, username = senderUsername ?: "user", avatar = senderAvatar)
            },
            replyTo = replyTo,
            messageType = messageType,
            text = text,
            attachment = attachment,
            attachmentName = attachmentName,
            attachmentSize = attachmentSize,
            isEdited = isEdited,
            isDeleted = isDeleted,
            createdAt = createdAt,
            clientMessageId = clientMessageId,
            readBy = if (isRead) listOf(1L) else emptyList(),
            reactions = parsedReactions
        )
    }

    companion object {
        fun fromDomain(message: Message): MessageEntity {
            val reactionsString = message.reactions.joinToString(";") {
                "${it.emoji}:${it.userId ?: 0L}"
            }.takeIf { it.isNotBlank() }

            return MessageEntity(
                id = message.id,
                chatId = message.chatId,
                senderId = message.sender?.id,
                senderUsername = message.sender?.username,
                senderAvatar = message.sender?.avatar,
                replyTo = message.replyTo,
                messageType = message.messageType,
                text = message.text,
                attachment = message.attachment,
                attachmentName = message.attachmentName,
                attachmentSize = message.attachmentSize,
                isEdited = message.isEdited,
                isDeleted = message.isDeleted,
                createdAt = message.createdAt,
                clientMessageId = message.clientMessageId,
                isRead = message.readBy.isNotEmpty(),
                reactionsRaw = reactionsString
            )
        }
    }
}
