package com.example.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    fun getMessagesForChat(chatId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    suspend fun getMessagesForChatSync(chatId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestMessageForChatSync(chatId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE attachment IS NOT NULL AND attachment != ''")
    suspend fun getAllMessagesWithAttachments(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND id NOT IN (SELECT id FROM messages WHERE chatId = :chatId ORDER BY id DESC LIMIT :keepCount)")
    suspend fun trimOldMessages(chatId: Long, keepCount: Int = 10)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Long)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesForChat(chatId: Long)

    @Query("DELETE FROM messages WHERE chatId NOT IN (:validChatIds)")
    suspend fun deleteMessagesNotInChats(validChatIds: List<Long>)

    @Query("DELETE FROM messages")
    suspend fun clearAllMessages()
}
