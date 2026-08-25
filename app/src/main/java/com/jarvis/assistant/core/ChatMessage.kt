package com.jarvis.assistant.core

enum class MessageRole(val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
    /** The outcome of a tool call, fed back to the model on the next turn. */
    TOOL("tool"),
    ;

    companion object {
        fun fromWire(value: String): MessageRole =
            entries.firstOrNull { it.wire == value } ?: USER
    }
}

/**
 * One turn of conversation, in the form both the database and the AI providers
 * can work from. Deliberately provider-neutral: each provider maps this into its
 * own wire format rather than the app bending to any one vendor's shape.
 */
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val toolName: String? = null,
    val toolCallId: String? = null,
    /**
     * The tool calls an assistant turn asked for, empty for every other role.
     *
     * These have to be stored, not just executed. Anthropic rejects a
     * `tool_result` whose `tool_use_id` has no matching `tool_use` in the
     * preceding assistant turn, and OpenAI rejects a `tool` message that does
     * not follow an assistant message carrying `tool_calls`. Replaying a
     * conversation therefore needs the calls themselves, not only their results.
     */
    val toolCalls: List<ToolCall> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)
