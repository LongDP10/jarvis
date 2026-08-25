package com.jarvis.assistant.ai

import com.jarvis.assistant.commands.ParamType
import com.jarvis.assistant.commands.ToolParam
import com.jarvis.assistant.commands.ToolSpec
import com.jarvis.assistant.core.ChatMessage
import com.jarvis.assistant.core.MessageRole
import com.jarvis.assistant.core.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(payload: String) =
        AnthropicMapper.parseResponse(json.parseToJsonElement(payload).jsonObject)

    private val openAppCall = ToolCall(
        name = "open_app",
        arguments = buildJsonObject { put("app", JsonPrimitive("YouTube")) },
        id = "toolu_abc123",
    )

    private val request = AIRequest(
        systemPrompt = "You are JARVIS.",
        messages = listOf(
            ChatMessage(MessageRole.USER, "mở YouTube"),
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "Opening it.",
                toolCalls = listOf(openAppCall),
            ),
            ChatMessage(
                role = MessageRole.TOOL,
                content = "Opened YouTube.",
                toolName = "open_app",
                toolCallId = "toolu_abc123",
            ),
        ),
        toolSpecs = listOf(
            ToolSpec(
                name = "open_app",
                description = "Launch an app.",
                params = listOf(ToolParam("app", ParamType.STRING, "App name.")),
            ),
        ),
        model = "claude-opus-5",
    )

    // ------------------------------------------------------------- responses

    @Test
    fun `a tool_use block becomes a tool call and keeps its id`() {
        // The id has to survive: it is what the next tool_result references, and
        // a mismatch makes the following request fail outright.
        val response = parse(
            """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5",
             "content":[{"type":"tool_use","id":"toolu_abc123","name":"open_app",
                         "input":{"app":"YouTube"}}],
             "stop_reason":"tool_use"}
            """.trimIndent(),
        )
        val call = (response as AIResponse.ToolCalls).calls.single()
        assertEquals("open_app", call.name)
        assertEquals("YouTube", call.string("app"))
        assertEquals("toolu_abc123", call.id)
    }

    @Test
    fun `text alongside a tool_use still runs the tool`() {
        val response = parse(
            """
            {"content":[{"type":"text","text":"Let me open that."},
                        {"type":"tool_use","id":"toolu_1","name":"open_app","input":{"app":"Zalo"}}],
             "stop_reason":"tool_use"}
            """.trimIndent(),
        )
        val calls = response as AIResponse.ToolCalls
        assertEquals("Zalo", calls.calls.single().string("app"))
        assertEquals("Let me open that.", calls.assistantText)
    }

    @Test
    fun `plain text becomes a text response`() {
        val response = parse(
            """{"content":[{"type":"text","text":"Đã mở YouTube."}],"stop_reason":"end_turn"}""",
        )
        assertEquals("Đã mở YouTube.", (response as AIResponse.Text).content)
    }

    @Test
    fun `a refusal is reported as a refusal, not as an empty answer`() {
        // A decline is HTTP 200 with an empty content array. Reading content
        // first would make it look like JARVIS simply ignored the user.
        val response = parse(
            """
            {"content":[],"stop_reason":"refusal",
             "stop_details":{"type":"refusal","category":"cyber"}}
            """.trimIndent(),
        )
        val error = response as AIResponse.Error
        assertTrue(error.message.contains("declined"))
        assertTrue(error.message.contains("cyber"))
        assertEquals(false, error.retryable)
    }

    @Test
    fun `a refusal without a category still reports cleanly`() {
        val response = parse("""{"content":[],"stop_reason":"refusal"}""")
        assertTrue((response as AIResponse.Error).message.contains("declined"))
    }

    @Test
    fun `an overloaded error is retryable`() {
        val response = parse(
            """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
        )
        assertTrue((response as AIResponse.Error).retryable)
    }

    @Test
    fun `an authentication error is not retryable`() {
        val response = parse(
            """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
        )
        val error = response as AIResponse.Error
        assertEquals("invalid x-api-key", error.message)
        assertEquals(false, error.retryable)
    }

    @Test
    fun `a truncated reply says so instead of returning nothing`() {
        val response = parse("""{"content":[],"stop_reason":"max_tokens"}""")
        assertTrue((response as AIResponse.Error).message.contains("cut off"))
    }

    @Test
    fun `an http error body is unwrapped to its message`() {
        val body = """{"type":"error","error":{"type":"not_found_error","message":"model: nope"}}"""
        val error = AnthropicMapper.httpErrorFor(
            404,
            body,
            json.parseToJsonElement(body).jsonObject,
        )
        assertEquals("model: nope", error.message)
        assertEquals(false, error.retryable)
    }

    @Test
    fun `an unparseable http error body falls back to the status code`() {
        val error = AnthropicMapper.httpErrorFor(529, "<html>overloaded</html>", null)
        assertEquals("HTTP 529", error.message)
        assertTrue(error.retryable)
    }

    // -------------------------------------------------------------- requests

    @Test
    fun `the system prompt is top level, not a message`() {
        // Anthropic has no system role inside messages; sending one is rejected.
        val body = AnthropicMapper.buildRequestBody(request)
        assertEquals("You are JARVIS.", body["system"]!!.jsonPrimitive.content)
        val roles = body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertTrue("system" !in roles)
    }

    @Test
    fun `an assistant turn replays its tool_use blocks`() {
        // Without this the following tool_result has nothing to attach to and
        // the API rejects the whole request.
        val messages = AnthropicMapper.buildRequestBody(request)["messages"]!!.jsonArray
        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)

        val blocks = assistant["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("text", blocks[0]["type"]!!.jsonPrimitive.content)
        assertEquals("tool_use", blocks[1]["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_abc123", blocks[1]["id"]!!.jsonPrimitive.content)
        assertEquals("open_app", blocks[1]["name"]!!.jsonPrimitive.content)
        assertEquals(
            "YouTube",
            blocks[1]["input"]!!.jsonObject["app"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a tool-only assistant turn omits the empty text block`() {
        // An empty text block is rejected by the API.
        val silent = request.copy(
            messages = listOf(
                ChatMessage(MessageRole.USER, "mở YouTube"),
                ChatMessage(MessageRole.ASSISTANT, "", toolCalls = listOf(openAppCall)),
            ),
        )
        val blocks = AnthropicMapper.buildRequestBody(silent)["messages"]!!.jsonArray[1]
            .jsonObject["content"]!!.jsonArray
        assertEquals(1, blocks.size)
        assertEquals("tool_use", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tool result is a user turn carrying a tool_result block`() {
        val messages = AnthropicMapper.buildRequestBody(request)["messages"]!!.jsonArray
        val toolTurn = messages[2].jsonObject
        assertEquals("user", toolTurn["role"]!!.jsonPrimitive.content)

        val block = toolTurn["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", block["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_abc123", block["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("Opened YouTube.", block["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools use input_schema rather than parameters`() {
        val tool = AnthropicMapper.buildRequestBody(request)["tools"]!!.jsonArray[0].jsonObject
        assertEquals("open_app", tool["name"]!!.jsonPrimitive.content)
        assertNull(tool["parameters"])
        val schema = tool["input_schema"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertTrue(schema["properties"]!!.jsonObject.containsKey("app"))
    }

    @Test
    fun `server-side fallbacks are opted into by default`() {
        assertEquals(
            "default",
            AnthropicMapper.buildRequestBody(request)["fallbacks"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `screen context is appended as a final user turn`() {
        val body = AnthropicMapper.buildRequestBody(request.copy(screenContext = "1. Search"))
        val last = body["messages"]!!.jsonArray.last().jsonObject
        assertEquals("user", last["role"]!!.jsonPrimitive.content)
        assertTrue(last["content"]!!.jsonPrimitive.content.contains("1. Search"))
    }

    @Test
    fun `no tools block is sent when none are available`() {
        val body = AnthropicMapper.buildRequestBody(request.copy(toolSpecs = emptyList()))
        assertNull(body["tools"])
    }
}
