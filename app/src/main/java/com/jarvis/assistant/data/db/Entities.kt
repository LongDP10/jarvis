package com.jarvis.assistant.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val languageTag: String,
    val startedAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** "user", "assistant", or "tool". Stored as text so a new role is not a migration. */
    val role: String,
    val content: String,
    /** Tool name when [role] is "tool", null otherwise. */
    val toolName: String? = null,
    /** Echoed back to the provider so a tool result has a call to attach to. */
    val toolCallId: String? = null,
    /** JSON array of the tool calls an assistant turn requested, null if none. */
    val toolCallsJson: String? = null,
    val createdAt: Long,
)

/**
 * One line of the VOICE / AI / TOOL / RESULT / TTS trace. Rows sharing a
 * [turnId] belong to the same spoken command, which is what lets the debug
 * console group them.
 */
@Entity(
    tableName = "command_log",
    indices = [Index("turnId"), Index("createdAt")],
)
data class CommandLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val turnId: String,
    val stage: String,
    val label: String,
    val detail: String,
    val success: Boolean?,
    val createdAt: Long,
)
