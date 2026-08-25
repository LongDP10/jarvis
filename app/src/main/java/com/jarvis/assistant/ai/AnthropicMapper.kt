package com.jarvis.assistant.ai

import com.jarvis.assistant.commands.ToolSchema
import com.jarvis.assistant.core.ChatMessage
import com.jarvis.assistant.core.MessageRole
import com.jarvis.assistant.core.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Translation for Anthropic's Messages API.
 *
 * Three things differ from the other providers and each one is a hard error if
 * got wrong:
 *
 *  - The system prompt is a **top-level `system` field**, not a message with a
 *    system role. Putting it in `messages` is rejected.
 *  - Tool results are **user-turn content blocks** (`tool_result`), and every one
 *    must reference a `tool_use` block that appears in the immediately preceding
 *    assistant turn. This is why [ChatMessage.toolCalls] exists.
 *  - A declined request is a **successful HTTP 200** with
 *    `stop_reason: "refusal"`, not an error status. Code that reads `content[0]`
 *    without checking `stop_reason` breaks on it.
 */
object AnthropicMapper {

    const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    const val API_VERSION = "2023-06-01"

    /**
     * Opts into server-side fallbacks. When a safety classifier declines a
     * request, Anthropic re-runs it on a suitable model and returns that answer
     * instead of handing back a refusal.
     */
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

    fun buildRequestBody(request: AIRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_tokens", MAX_TOKENS)
        put("system", request.systemPrompt)

        // Routed by refusal category server-side, so there is no fallback model
        // list here to go stale.
        put("fallbacks", "default")

        // Thinking is on by default on Opus 5, and disabling it is a documented
        // trap: the model starts writing tool calls into visible text instead of
        // emitting tool_use blocks, which in an agentic loop fails silently.
        // Effort is lowered instead -- these are short spoken commands with an
        // explicit tool list, and lower effort also means terser confirmations,
        // which is exactly what a voice assistant wants.
        putJsonObject("output_config") {
            put("effort", "low")
        }

        putJsonArray("messages") {
            request.messages.forEach { message -> add(messageFor(message)) }
            request.screenContext?.let { context ->
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", "Current screen:\n$context")
                    },
                )
            }
        }

        if (request.toolSpecs.isNotEmpty()) {
            putJsonArray("tools") {
                request.toolSpecs.forEach { spec ->
                    add(
                        buildJsonObject {
                            put("name", spec.name)
                            put("description", ToolSchema.describe(spec))
                            put("input_schema", ToolSchema.parameters(spec))
                        },
                    )
                }
            }
        }
    }

    private fun messageFor(message: ChatMessage): JsonObject = when (message.role) {
        // A tool result is a user turn whose content is a tool_result block.
        MessageRole.TOOL -> buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", message.toolCallId ?: message.toolName.orEmpty())
                        put("content", message.content)
                    },
                )
            }
        }

        MessageRole.ASSISTANT -> buildJsonObject {
            put("role", "assistant")
            if (message.toolCalls.isEmpty()) {
                put("content", message.content)
            } else {
                putJsonArray("content") {
                    // An empty text block is rejected, and an assistant turn that
                    // only called tools has no prose to send.
                    if (message.content.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", message.content)
                            },
                        )
                    }
                    message.toolCalls.forEach { call ->
                        add(
                            buildJsonObject {
                                put("type", "tool_use")
                                put("id", call.id)
                                put("name", call.name)
                                put("input", call.arguments)
                            },
                        )
                    }
                }
            }
        }

        MessageRole.USER -> buildJsonObject {
            put("role", "user")
            put("content", message.content)
        }
    }

    fun parseResponse(root: JsonObject): AIResponse {
        root["error"]?.jsonObject?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull
                ?: "Unknown Anthropic error"
            val type = error["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return AIResponse.Error(
                message,
                retryable = type == "overloaded_error" || type == "rate_limit_error" ||
                    type == "api_error",
            )
        }

        val stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull

        // Checked before content is read. A refusal arrives as HTTP 200 with an
        // empty content array, so indexing content first would look like an
        // empty answer rather than a decline.
        if (stopReason == "refusal") {
            val category = root["stop_details"]?.jsonObject
                ?.get("category")?.jsonPrimitive?.contentOrNull
            return AIResponse.Error(
                "Claude declined this request" +
                    (if (category != null) " ($category)" else "") +
                    ". Rephrasing it usually helps.",
                retryable = false,
            )
        }

        val content = root["content"]?.jsonArray
            ?: return AIResponse.Error("Anthropic returned no content.")

        val calls = content.mapNotNull { block ->
            val obj = block.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ToolCall(
                name = name,
                arguments = obj["input"]?.jsonObject ?: JsonObject(emptyMap()),
                // The id is not cosmetic: it has to come back on the tool_result
                // or the next request is rejected.
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: name,
            )
        }

        val text = content
            .filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n")
            .trim()

        return when {
            calls.isNotEmpty() -> AIResponse.ToolCalls(calls, text.takeIf { it.isNotEmpty() })
            text.isNotEmpty() -> AIResponse.Text(text)
            stopReason == "max_tokens" -> AIResponse.Error(
                "The reply was cut off before any text was produced.",
            )
            else -> AIResponse.Error("Anthropic returned an empty response.")
        }
    }

    fun httpErrorFor(code: Int, body: String, parsed: JsonObject?): AIResponse.Error {
        val message = parsed?.get("error")?.jsonObject
            ?.get("message")?.jsonPrimitive?.contentOrNull
        return AIResponse.Error(
            message ?: "HTTP $code",
            // 529 is Anthropic's "overloaded"; 401/400 are the caller's problem.
            retryable = code == 429 || code >= 500,
        )
    }

    /** Non-streaming, so this stays under the SDK-recommended ceiling. */
    private const val MAX_TOKENS = 16_000
}
