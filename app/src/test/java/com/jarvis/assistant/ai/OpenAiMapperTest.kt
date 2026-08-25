package com.jarvis.assistant.ai

import com.jarvis.assistant.commands.ParamType
import com.jarvis.assistant.commands.ToolParam
import com.jarvis.assistant.commands.ToolSpec
import com.jarvis.assistant.core.ChatMessage
import com.jarvis.assistant.core.MessageRole
import com.jarvis.assistant.core.ToolCall
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(payload: String) =
        OpenAiMapper.parseResponse(json.parseToJsonElement(payload).jsonObject)

    private val request = AIRequest(
        systemPrompt = "You are JARVIS.",
        messages = listOf(
            ChatMessage(MessageRole.USER, "open youtube"),
            ChatMessage(
                role = MessageRole.TOOL,
                content = "Opened YouTube.",
                toolName = "open_app",
                toolCallId = "call_abc",
            ),
        ),
        toolSpecs = listOf(
            ToolSpec(
                name = "open_app",
                description = "Launch an app.",
                params = listOf(ToolParam("app", ParamType.STRING, "App name.")),
            ),
        ),
        model = "gpt-4o-mini",
    )

    @Test
    fun `tool calls are parsed with their arguments`() {
        val response = parse(
            """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"open_app","arguments":"{\"app\":\"YouTube\"}"}}
            ]}}]}
            """.trimIndent(),
        )
        val call = (response as AIResponse.ToolCalls).calls.single()
        assertEquals("open_app", call.name)
        assertEquals("YouTube", call.string("app"))
        assertEquals("call_1", call.id)
    }

    @Test
    fun `several tool calls in one message are all returned`() {
        val response = parse(
            """
            {"choices":[{"message":{"role":"assistant","tool_calls":[
              {"id":"a","function":{"name":"go_home","arguments":"{}"}},
              {"id":"b","function":{"name":"open_app","arguments":"{\"app\":\"Zalo\"}"}}
            ]}}]}
            """.trimIndent(),
        )
        assertEquals(2, (response as AIResponse.ToolCalls).calls.size)
    }

    @Test
    fun `plain content becomes a text response`() {
        val response = parse(
            """{"choices":[{"message":{"role":"assistant","content":"All done."}}]}""",
        )
        assertEquals("All done.", (response as AIResponse.Text).content)
    }

    @Test
    fun `malformed argument json degrades to empty arguments`() {
        // Models do emit broken JSON here. Losing the arguments lets the tool
        // report what is missing; throwing would lose the whole turn.
        val response = parse(
            """
            {"choices":[{"message":{"tool_calls":[
              {"id":"c","function":{"name":"open_app","arguments":"{not json"}}
            ]}}]}
            """.trimIndent(),
        )
        val call = (response as AIResponse.ToolCalls).calls.single()
        assertEquals("open_app", call.name)
        assertEquals(null, call.string("app"))
    }

    @Test
    fun `an invalid key error is not retryable`() {
        val response = parse(
            """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}""",
        )
        val error = response as AIResponse.Error
        assertEquals("Incorrect API key provided", error.message)
        assertEquals(false, error.retryable)
    }

    @Test
    fun `a rate limit error is retryable`() {
        val response = parse(
            """{"error":{"message":"Rate limit reached","type":"rate_limit_error"}}""",
        )
        assertTrue((response as AIResponse.Error).retryable)
    }

    @Test
    fun `an http error body is unwrapped to its message`() {
        val error = OpenAiMapper.httpErrorFor(
            401,
            """{"error":{"message":"Invalid Authentication","type":"invalid_request_error"}}""",
        )
        assertEquals("Invalid Authentication", error.message)
        assertEquals(false, error.retryable)
    }

    @Test
    fun `an unparseable http error body falls back to the status code`() {
        val error = OpenAiMapper.httpErrorFor(502, "<html>Bad Gateway</html>")
        assertEquals("HTTP 502", error.message)
        assertTrue(error.retryable)
    }

    @Test
    fun `the system prompt is the first message`() {
        val messages = OpenAiMapper.buildRequestBody(request)["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("You are JARVIS.", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tool result echoes the tool call id back`() {
        val messages = OpenAiMapper.buildRequestBody(request)["messages"]!!.jsonArray
        val toolMessage = messages[2].jsonObject
        assertEquals("tool", toolMessage["role"]!!.jsonPrimitive.content)
        assertEquals("call_abc", toolMessage["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools are declared with automatic tool choice`() {
        val body = OpenAiMapper.buildRequestBody(request)
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
        val name = body["tools"]!!.jsonArray[0].jsonObject["function"]!!
            .jsonObject["name"]!!.jsonPrimitive.content
        assertEquals("open_app", name)
    }

    @Test
    fun `an assistant turn replays its tool_calls`() {
        // Regression: the API rejects a "tool" message that does not follow an
        // assistant message carrying tool_calls, so a multi-step command failed
        // on the second round trip when only the assistant's text was stored.
        val replay = request.copy(
            messages = listOf(
                ChatMessage(MessageRole.USER, "open youtube"),
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(
                        ToolCall(
                            name = "open_app",
                            arguments = buildJsonObject { put("app", JsonPrimitive("YouTube")) },
                            id = "call_abc",
                        ),
                    ),
                ),
                ChatMessage(
                    role = MessageRole.TOOL,
                    content = "Opened YouTube.",
                    toolName = "open_app",
                    toolCallId = "call_abc",
                ),
            ),
        )
        val assistant = OpenAiMapper.buildRequestBody(replay)["messages"]!!.jsonArray[2].jsonObject
        val call = assistant["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("call_abc", call["id"]!!.jsonPrimitive.content)
        assertEquals("function", call["type"]!!.jsonPrimitive.content)
        val function = call["function"]!!.jsonObject
        assertEquals("open_app", function["name"]!!.jsonPrimitive.content)
        // OpenAI takes arguments as a JSON *string*, not an object.
        assertTrue(function["arguments"]!!.jsonPrimitive.content.contains("YouTube"))
    }

}
