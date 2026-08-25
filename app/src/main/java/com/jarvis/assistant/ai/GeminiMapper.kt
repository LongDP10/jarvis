package com.jarvis.assistant.ai

import com.jarvis.assistant.commands.ToolSchema
import com.jarvis.assistant.core.ChatMessage
import com.jarvis.assistant.core.MessageRole
import com.jarvis.assistant.core.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
 * Translation between the app's provider-neutral types and Gemini's
 * generateContent wire format.
 *
 * Split out from the provider so the mapping can be tested against real recorded
 * payloads without any network or Android involvement -- the mapping is where
 * the bugs live, not the HTTP call.
 */
object GeminiMapper {

    fun buildRequestBody(request: AIRequest): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") {
                add(buildJsonObject { put("text", request.systemPrompt) })
            }
        }

        putJsonArray("contents") {
            request.messages.forEach { message ->
                add(contentFor(message))
            }
            request.screenContext?.let { context ->
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", "Current screen:\n$context") })
                        }
                    },
                )
            }
        }

        if (request.toolSpecs.isNotEmpty()) {
            put("tools", ToolSchema.geminiTools(request.toolSpecs))
        }

        putJsonObject("generationConfig") {
            put("temperature", 0.2)
            put("maxOutputTokens", 1024)
        }
    }

    private fun contentFor(message: ChatMessage): JsonObject = when (message.role) {
        // Gemini has no tool role. A tool's output is sent back as a user turn
        // carrying a functionResponse part, which is what the API expects.
        MessageRole.TOOL -> buildJsonObject {
            put("role", "user")
            putJsonArray("parts") {
                add(
                    buildJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", message.toolName ?: "unknown")
                            putJsonObject("response") {
                                put("result", message.content)
                            }
                        }
                    },
                )
            }
        }

        MessageRole.ASSISTANT -> buildJsonObject {
            put("role", "model")
            putJsonArray("parts") {
                // An empty text part is not valid, and a model turn that only
                // called tools legitimately has no prose.
                if (message.content.isNotBlank()) {
                    add(buildJsonObject { put("text", message.content) })
                }
                message.toolCalls.forEach { call ->
                    add(
                        buildJsonObject {
                            putJsonObject("functionCall") {
                                put("name", call.name)
                                put("args", call.arguments)
                            }
                        },
                    )
                }
            }
        }

        MessageRole.USER -> buildJsonObject {
            put("role", "user")
            putJsonArray("parts") {
                add(buildJsonObject { put("text", message.content) })
            }
        }
    }

    /**
     * A response can carry text parts, functionCall parts, or both. Tool calls
     * win: if the model asked for work to be done, doing it matters more than
     * relaying whatever it said while asking.
     */
    fun parseResponse(root: JsonObject): AIResponse {
        root["error"]?.jsonObject?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown Gemini error"
            val code = error["code"]?.jsonPrimitive?.runCatching { int }?.getOrNull()
            return AIResponse.Error(message, retryable = code == 429 || (code ?: 0) >= 500)
        }

        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return AIResponse.Error("Gemini returned no candidates.")

        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()

        val calls = parts.mapNotNull { part ->
            val functionCall = part.jsonObject["functionCall"]?.jsonObject ?: return@mapNotNull null
            val name = functionCall["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ToolCall(
                name = name,
                arguments = functionCall["args"]?.jsonObject ?: JsonObject(emptyMap()),
            )
        }

        val text = parts
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n")
            .trim()

        return when {
            calls.isNotEmpty() -> AIResponse.ToolCalls(calls, text.takeIf { it.isNotEmpty() })
            text.isNotEmpty() -> AIResponse.Text(text)
            // A finishReason of SAFETY or MAX_TOKENS produces an empty part list,
            // and reporting that as an empty answer would look like a hang.
            else -> AIResponse.Error(
                "Gemini returned an empty response (finish reason: " +
                    "${candidate["finishReason"]?.jsonPrimitive?.contentOrNull ?: "unknown"}).",
            )
        }
    }

    /**
     * The key goes in the x-goog-api-key header rather than the documented
     * ?key= query parameter. Both are accepted; a header keeps the secret out of
     * URLs, which are the thing most likely to end up in a log or a crash trace.
     */
    fun endpoint(model: String): String =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
}
