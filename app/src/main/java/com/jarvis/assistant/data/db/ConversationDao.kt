package com.jarvis.assistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: Long): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    /**
     * The tail of the conversation, used to build the model's context window.
     * Returned newest first so the caller can take what fits and reverse.
     */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit",
    )
    suspend fun recentMessages(conversationId: Long, limit: Int): List<MessageEntity>

    @Query("UPDATE conversations SET updatedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}
