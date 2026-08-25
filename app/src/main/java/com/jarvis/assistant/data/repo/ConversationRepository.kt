package com.jarvis.assistant.data.repo

import com.jarvis.assistant.core.ChatMessage
import com.jarvis.assistant.core.Language
import com.jarvis.assistant.core.MessageRole
import com.jarvis.assistant.core.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.jarvis.assistant.data.db.ConversationDao
import com.jarvis.assistant.data.db.ConversationEntity
import com.jarvis.assistant.data.db.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val dao: ConversationDao,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val conversationLock = Mutex()
    private var activeConversationId: Long? = null

    fun observeConversations(): Flow<List<Conversation>> =
        dao.observeConversations().map { list -> list.map { it.toDomain() } }

    fun observeMessages(conversationId: Long): Flow<List<ChatMessage>> =
        dao.observeMessages(conversationId).map { list -> list.map { it.toDomain() } }

    /**
     * Returns the id of the conversation currently being spoken into, creating
     * one on first use. Guarded by a mutex because the overlay service and the
     * chat screen can both land here at the same moment and two conversations
     * for one session would split the model's context in half.
     */
    suspend fun activeConversation(language: Language): Long = conversationLock.withLock {
        activeConversationId?.let { return it }
        val now = System.currentTimeMillis()
        val id = dao.insertConversation(
            ConversationEntity(
                title = "",
                languageTag = language.tag,
                startedAt = now,
                updatedAt = now,
            ),
        )
        activeConversationId = id
        id
    }

    /** Ends the session so the next command starts a fresh context. */
    suspend fun endActiveConversation() = conversationLock.withLock {
        activeConversationId = null
    }

    suspend fun append(conversationId: Long, message: ChatMessage) {
        dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = message.role.wire,
                content = message.content,
                toolName = message.toolName,
                toolCallId = message.toolCallId,
                toolCallsJson = encodeToolCalls(message.toolCalls),
                createdAt = message.createdAt,
            ),
        )
        dao.touch(conversationId, System.currentTimeMillis())
        maybeTitle(conversationId, message)
    }

    /**
     * The tail of the conversation, oldest first, for the model's context window.
     * Tool messages are included: without them the model cannot tell what it has
     * already tried this turn and will happily repeat a failed action.
     */
    suspend fun contextWindow(conversationId: Long, limit: Int = CONTEXT_LIMIT): List<ChatMessage> =
        dao.recentMessages(conversationId, limit).map { it.toDomain() }.reversed()

    suspend fun delete(conversationId: Long) = conversationLock.withLock {
        dao.deleteConversation(conversationId)
        if (activeConversationId == conversationId) activeConversationId = null
    }

    suspend fun deleteAll() = conversationLock.withLock {
        dao.deleteAll()
        activeConversationId = null
    }

    /** The first thing the user said becomes the conversation's name. */
    private suspend fun maybeTitle(conversationId: Long, message: ChatMessage) {
        if (message.role != MessageRole.USER) return
        val existing = dao.getConversation(conversationId) ?: return
        if (existing.title.isNotBlank()) return
        dao.rename(conversationId, message.content.take(TITLE_LENGTH).trim())
    }

    private fun ConversationEntity.toDomain() = Conversation(
        id = id,
        title = title,
        language = Language.fromTag(languageTag),
        startedAt = startedAt,
        updatedAt = updatedAt,
    )

    private fun MessageEntity.toDomain() = ChatMessage(
        role = MessageRole.fromWire(role),
        content = content,
        toolName = toolName,
        toolCallId = toolCallId,
        toolCalls = decodeToolCalls(toolCallsJson),
        createdAt = createdAt,
    )

    /**
     * Tool calls are stored as JSON rather than as extra columns: the argument
     * object is provider-shaped and arbitrary, so a column per field is not
     * possible, and a separate table would buy nothing for data that is only
     * ever read back with its own message.
     */
    private fun encodeToolCalls(calls: List<ToolCall>): String? {
        if (calls.isEmpty()) return null
        val array = buildJsonArray {
            calls.forEach { call ->
                add(
                    buildJsonObject {
                        put("id", call.id)
                        put("name", call.name)
                        put("arguments", call.arguments)
                    },
                )
            }
        }
        return array.toString()
    }

    private fun decodeToolCalls(raw: String?): List<ToolCall> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.map { element ->
                val obj = element.jsonObject
                ToolCall(
                    name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    arguments = obj["arguments"]?.jsonObject ?: JsonObject(emptyMap()),
                    id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val CONTEXT_LIMIT = 24
        const val TITLE_LENGTH = 60
    }
}

data class Conversation(
    val id: Long,
    val title: String,
    val language: Language,
    val startedAt: Long,
    val updatedAt: Long,
)
