package com.example.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY sortTimestamp DESC, id DESC")
    fun getAllChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats ORDER BY sortTimestamp DESC, id DESC")
    suspend fun getAllChatsSync(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :chatId LIMIT 1")
    suspend fun getChatByIdSync(chatId: Long): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChats(chats: List<ChatEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChatById(chatId: Long)

    @Query("DELETE FROM chats WHERE id NOT IN (:validChatIds)")
    suspend fun deleteChatsNotIn(validChatIds: List<Long>)

    @Query("DELETE FROM chats")
    suspend fun clearChats()
}
