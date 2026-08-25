package com.jarvis.assistant.ai

import com.jarvis.assistant.commands.ToolSpec
import com.jarvis.assistant.core.ChatMessage
import com.jarvis.assistant.core.ProviderId
import com.jarvis.assistant.core.ToolCall

/**
 * One turn's worth of input to a model.
 *
 * @param screenContext a rendering of what is currently on screen, attached only
 *   when the user's phrasing needs it. It is not sent on every turn: the node
 *   dump is large, it changes constantly, and sending it unconditionally would
 *   both cost tokens and drown the actual instruction.
 */
data class AIRequest(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val toolSpecs: List<ToolSpec>,
    val model: String,
    val screenContext: String? = null,
)

sealed interface AIResponse {

    /** The model answered in words; the turn is over. */
    data class Text(val content: String) : AIResponse

    /**
     * The model wants tools run. [assistantText] is anything it said alongside
     * the calls, which some models use to narrate what they are about to do.
     */
    data class ToolCalls(
        val calls: List<ToolCall>,
        val assistantText: String? = null,
    ) : AIResponse

    /**
     * @param retryable true for transport and rate-limit failures, false for
     *   anything the user has to fix such as a bad key. The agent loop only
     *   retries the former.
     */
    data class Error(
        val message: String,
        val retryable: Boolean = false,
    ) : AIResponse
}

/**
 * A model backend.
 *
 * The whole AI layer talks to this and nothing else, so adding a provider means
 * writing one class and one binding rather than touching the command pipeline.
 */
interface AIProvider {

    val id: ProviderId

    /**
     * Whether this backend needs the public internet.
     *
     * False for a model server on the user's own network. The distinction
     * matters: Android reports a Wi-Fi network with no upstream connectivity as
     * offline, and refusing to run on that basis would break the one setup where
     * JARVIS is genuinely fully local.
     */
    val requiresInternet: Boolean get() = true

    /** False when there is no API key, or no reachable server for a local one. */
    suspend fun isConfigured(): Boolean

    suspend fun chat(request: AIRequest): AIResponse
}
