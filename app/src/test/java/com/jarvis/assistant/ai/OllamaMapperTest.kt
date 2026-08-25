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

class OllamaMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(payload: String) =
        OllamaMapper.parseResponse(json.parseToJsonElement(payload).jsonObject)

    private val request = AIRequest(
        systemPrompt = "You are JARVIS.",
        messages = listOf(
            ChatMessage(MessageRole.USER, "mở YouTube"),
            ChatMessage(MessageRole.TOOL, "Opened YouTube.", toolName = "open_app"),
        ),
        toolSpecs = listOf(
            ToolSpec(
                name = "open_app",
                description = "Launch an app.",
                params = listOf(ToolParam("app", ParamType.STRING, "App name.")),
            ),
        ),
        model = "qwen2.5",
    )

    @Test
    fun `tool call arguments arrive as an object, not a string`() {
        // This is the one place Ollama diverges from OpenAI's format. Parsing it
        // as a string would silently produce a call with no arguments.
        val response = parse(
            """
            {"model":"qwen2.5","message":{"role":"assistant","content":"","tool_calls":[
              {"function":{"name":"open_app","arguments":{"app":"YouTube"}}}
            ]},"done":true}
            """.trimIndent(),
        )
        val call = (response as AIResponse.ToolCalls).calls.single()
        assertEquals("open_app", call.name)
        assertEquals("YouTube", call.string("app"))
    }

    @Test
    fun `several tool calls in one message are all returned`() {
        val response = parse(
            """
            {"message":{"role":"assistant","tool_calls":[
              {"function":{"name":"go_home","arguments":{}}},
              {"function":{"name":"open_app","arguments":{"app":"Zalo"}}}
            ]}}
            """.trimIndent(),
        )
        assertEquals(2, (response as AIResponse.ToolCalls).calls.size)
    }

    @Test
    fun `plain content becomes a text response`() {
        val response = parse(
            """{"message":{"role":"assistant","content":"Đã mở YouTube."},"done":true}""",
        )
        assertEquals("Đã mở YouTube.", (response as AIResponse.Text).content)
    }

    @Test
    fun `a missing message is an error, not an empty answer`() {
        val response = parse("""{"model":"qwen2.5","done":true}""")
        assertTrue(response is AIResponse.Error)
    }

    @Test
    fun `an empty message suggests switching to a tool-capable model`() {
        // The usual cause is a model with no tool support being handed tools.
        val response = parse("""{"message":{"role":"assistant","content":""}}""")
        val error = response as AIResponse.Error
        assertTrue(error.message.contains("tool calling"))
        assertTrue(error.message.contains("qwen2.5"))
    }

    @Test
    fun `a top level error string is surfaced`() {
        val response = parse("""{"error":"model 'llama9' not found, try pulling it first"}""")
        assertEquals(
            "model 'llama9' not found, try pulling it first",
            (response as AIResponse.Error).message,
        )
    }

    @Test
    fun `the system prompt is the first message`() {
        val messages = OllamaMapper.buildRequestBody(request)["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("You are JARVIS.", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tool result keeps the tool role`() {
        // Unlike Gemini, Ollama accepts a "tool" role directly.
        val messages = OllamaMapper.buildRequestBody(request)["messages"]!!.jsonArray
        assertEquals("tool", messages[2].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streaming is disabled so the whole reply is available before tools run`() {
        val body = OllamaMapper.buildRequestBody(request)
        assertEquals(false, body["stream"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `tools are declared in the OpenAI function shape`() {
        val tools = OllamaMapper.buildRequestBody(request)["tools"]!!.jsonArray
        val name = tools[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        assertEquals("open_app", name)
    }

    @Test
    fun `screen context is appended as a final user turn`() {
        val body = OllamaMapper.buildRequestBody(request.copy(screenContext = "1. Search"))
        val last = body["messages"]!!.jsonArray.last().jsonObject
        assertEquals("user", last["role"]!!.jsonPrimitive.content)
        assertTrue(last["content"]!!.jsonPrimitive.content.contains("1. Search"))
    }

    @Test
    fun `no tools are sent when none are available`() {
        val body = OllamaMapper.buildRequestBody(request.copy(toolSpecs = emptyList()))
        assertTrue(body["tools"] == null)
    }

    @Test
    fun `an assistant turn replays its tool_calls with object arguments`() {
        val replay = request.copy(
            messages = listOf(
                ChatMessage(MessageRole.USER, "mở YouTube"),
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(
                        ToolCall(
                            name = "open_app",
                            arguments = buildJsonObject { put("app", JsonPrimitive("YouTube")) },
                        ),
                    ),
                ),
            ),
        )
        val assistant = OllamaMapper.buildRequestBody(replay)["messages"]!!.jsonArray[2].jsonObject
        val function = assistant["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
        assertEquals("open_app", function["name"]!!.jsonPrimitive.content)
        assertEquals(
            "YouTube",
            function["arguments"]!!.jsonObject["app"]!!.jsonPrimitive.content,
        )
    }

}
