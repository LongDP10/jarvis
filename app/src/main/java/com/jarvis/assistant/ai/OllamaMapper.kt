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
 * Translation for Ollama's /api/chat endpoint.
 *
 * Ollama borrows OpenAI's tool *vocabulary* but not its wire format, and the
 * difference is easy to get wrong in exactly one place: OpenAI sends tool call
 * arguments as a JSON string that has to be parsed, Ollama sends a real object.
 * Split out from the provider so that difference is covered by a test rather
 * than by hope.
 */
object OllamaMapper {

    fun buildRequestBody(request: AIRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        // Streaming would give nicer first-token latency, but the whole reply is
        // needed before any tool can run, so it buys nothing here.
        put("stream", false)

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
        }
    }

    private fun messageFor(message: ChatMessage): JsonObject = buildJsonObject {
        put(
            "role",
            when (message.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.TOOL -> "tool"
            },
        )
        put("content", message.content)
        // Object arguments, matching the shape Ollama sends them in.
        if (message.toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                message.toolCalls.forEach { call ->
                    add(
                        buildJsonObject {
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.arguments)
                            }
                        },
                    )
                }
            }
        }
    }

    fun parseResponse(root: JsonObject): AIResponse {
        root["error"]?.jsonPrimitive?.contentOrNull?.let { error ->
            return AIResponse.Error(error)
        }

        val message = root["message"]?.jsonObject
            ?: return AIResponse.Error("Ollama returned no message.")

        val calls = message["tool_calls"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val function = entry.jsonObject["function"]?.jsonObject ?: return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            // A real object here, unlike OpenAI's escaped string.
            val arguments = function["arguments"]?.jsonObject ?: JsonObject(emptyMap())
            ToolCall(name = name, arguments = arguments)
        }

        val text = message["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        return when {
            calls.isNotEmpty() -> AIResponse.ToolCalls(calls, text.takeIf { it.isNotEmpty() })
            text.isNotEmpty() -> AIResponse.Text(text)
            // A model without tool support that was given tools sometimes returns
            // an empty message rather than prose. Saying so beats a silent stall.
            else -> AIResponse.Error(
                "The model returned an empty message. If it does not support tool calling, " +
                    "switch to one that does, such as qwen2.5 or llama3.1.",
            )
        }
    }
}
