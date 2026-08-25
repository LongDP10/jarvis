package com.jarvis.assistant.ai

import com.jarvis.assistant.commands.ToolSchema
import com.jarvis.assistant.core.ChatMessage
import com.jarvis.assistant.core.MessageRole
import com.jarvis.assistant.core.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Translation for OpenAI's chat-completions API, and for the many services that
 * copy its shape.
 *
 * Kept separate from the provider for the same reason as [GeminiMapper]: the
 * mapping is the part worth testing.
 */
object OpenAiMapper {

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun buildRequestBody(request: AIRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("temperature", 0.2)

        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", request.systemPrompt)
                },
            )
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
                request.toolSpecs.forEach { add(ToolSchema.openAiTool(it)) }
            }
            put("tool_choice", "auto")
        }
    }

    private fun messageFor(message: ChatMessage): JsonObject = when (message.role) {
        MessageRole.TOOL -> buildJsonObject {
            put("role", "tool")
            // The API rejects a tool message whose id does not match a call it
            // issued, so the id recorded when the call arrived is echoed back.
            put("tool_call_id", message.toolCallId ?: message.toolName.orEmpty())
            put("content", message.content)
        }

        MessageRole.ASSISTANT -> buildJsonObject {
            put("role", "assistant")
            put("content", message.content)
            // Required whenever the next message is a tool result: the API
            // rejects a "tool" message that does not follow an assistant message
            // carrying tool_calls. Arguments go back as a JSON *string*, which is
            // the shape OpenAI issued them in.
            if (message.toolCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    message.toolCalls.forEach { call ->
                        add(
                            buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.arguments.toString())
                                }
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
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown OpenAI error"
            val type = error["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return AIResponse.Error(
                message,
                retryable = type.contains("rate_limit") || type.contains("server_error"),
            )
        }

        val message = root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject
            ?: return AIResponse.Error("OpenAI returned no choices.")

        val text = message["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        val calls = message["tool_calls"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val call = entry.jsonObject
            val function = call["function"]?.jsonObject ?: return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ToolCall(
                name = name,
                // Arguments arrive as a JSON string, not an object, and a model
                // can emit malformed JSON in it. A parse failure becomes an empty
                // argument set so the tool can report what is missing rather than
                // the whole turn blowing up.
                arguments = parseArguments(function["arguments"]?.jsonPrimitive?.contentOrNull),
                id = call["id"]?.jsonPrimitive?.contentOrNull ?: name,
            )
        }

        return when {
            calls.isNotEmpty() -> AIResponse.ToolCalls(calls, text.takeIf { it.isNotEmpty() })
            text.isNotEmpty() -> AIResponse.Text(text)
            else -> AIResponse.Error("OpenAI returned an empty message.")
        }
    }

    fun parseArguments(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return JsonObject(emptyMap())
        return runCatching { lenientJson.parseToJsonElement(raw).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
    }

    fun httpErrorFor(code: Int, body: String): AIResponse.Error {
        val parsed = runCatching {
            lenientJson.parseToJsonElement(body).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return AIResponse.Error(
            parsed ?: "HTTP $code",
            retryable = code == 429 || code >= 500,
        )
    }

    const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
}
