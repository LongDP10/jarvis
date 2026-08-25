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

class GeminiMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(payload: String) =
        GeminiMapper.parseResponse(json.parseToJsonElement(payload).jsonObject)

    private val request = AIRequest(
        systemPrompt = "You are JARVIS.",
        messages = listOf(
            ChatMessage(MessageRole.USER, "open youtube"),
            ChatMessage(MessageRole.ASSISTANT, "Opening it."),
            ChatMessage(MessageRole.TOOL, "Opened YouTube.", toolName = "open_app"),
        ),
        toolSpecs = listOf(
            ToolSpec(
                name = "open_app",
                description = "Launch an app.",
                params = listOf(ToolParam("app", ParamType.STRING, "App name.")),
            ),
        ),
        model = "gemini-2.0-flash",
    )

    @Test
    fun `a function call becomes a tool call`() {
        val response = parse(
            """
            {"candidates":[{"content":{"role":"model","parts":[
              {"functionCall":{"name":"open_app","args":{"app":"YouTube"}}}
            ]},"finishReason":"STOP"}]}
            """.trimIndent(),
        )

        assertTrue(response is AIResponse.ToolCalls)
        val call = (response as AIResponse.ToolCalls).calls.single()
        assertEquals("open_app", call.name)
        assertEquals("YouTube", call.string("app"))
    }

    @Test
    fun `plain text becomes a text response`() {
        val response = parse(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Xin chào"}]}}]}""",
        )
        assertEquals("Xin chào", (response as AIResponse.Text).content)
    }

    @Test
    fun `a tool call alongside text still runs the tool`() {
        val response = parse(
            """
            {"candidates":[{"content":{"role":"model","parts":[
              {"text":"Opening it now."},
              {"functionCall":{"name":"open_app","args":{"app":"Zalo"}}}
            ]}}]}
            """.trimIndent(),
        )
        val toolCalls = response as AIResponse.ToolCalls
        assertEquals("Zalo", toolCalls.calls.single().string("app"))
        assertEquals("Opening it now.", toolCalls.assistantText)
    }

    @Test
    fun `an api error is surfaced with its message`() {
        val response = parse(
            """{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}""",
        )
        val error = response as AIResponse.Error
        assertEquals("API key not valid", error.message)
        assertEquals(false, error.retryable)
    }

    @Test
    fun `a rate limit is marked retryable`() {
        val response = parse("""{"error":{"code":429,"message":"Quota exceeded"}}""")
        assertTrue((response as AIResponse.Error).retryable)
    }

    @Test
    fun `an empty candidate reports the finish reason instead of an empty answer`() {
        // A SAFETY stop returns no parts at all. Reporting that as an empty
        // string would look to the user like JARVIS simply ignored them.
        val response = parse("""{"candidates":[{"content":{"role":"model"},"finishReason":"SAFETY"}]}""")
        assertTrue((response as AIResponse.Error).message.contains("SAFETY"))
    }

    @Test
    fun `the system prompt is sent as systemInstruction`() {
        val body = GeminiMapper.buildRequestBody(request)
        val text = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0]
            .jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("You are JARVIS.", text)
    }

    @Test
    fun `an assistant turn is mapped to the model role`() {
        val contents = GeminiMapper.buildRequestBody(request)["contents"]!!.jsonArray
        assertEquals("model", contents[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tool result is sent as a user functionResponse`() {
        // Gemini has no tool role; sending one is rejected outright.
        val contents = GeminiMapper.buildRequestBody(request)["contents"]!!.jsonArray
        val toolTurn = contents[2].jsonObject
        assertEquals("user", toolTurn["role"]!!.jsonPrimitive.content)
        val functionResponse = toolTurn["parts"]!!.jsonArray[0]
            .jsonObject["functionResponse"]!!.jsonObject
        assertEquals("open_app", functionResponse["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `screen context is appended as a final user turn`() {
        val withScreen = GeminiMapper.buildRequestBody(
            request.copy(screenContext = "1. Search"),
        )
        val contents = withScreen["contents"]!!.jsonArray
        val last = contents.last().jsonObject
        assertEquals("user", last["role"]!!.jsonPrimitive.content)
        assertTrue(
            last["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                .contains("1. Search"),
        )
    }

    @Test
    fun `tools are declared when specs are present`() {
        val body = GeminiMapper.buildRequestBody(request)
        val declarations = body["tools"]!!.jsonArray[0]
            .jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals("open_app", declarations[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a model turn replays its functionCall parts`() {
        // Regression: a functionResponse with no preceding functionCall leaves
        // the model unable to tell which call the result belongs to.
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
        val parts = GeminiMapper.buildRequestBody(replay)["contents"]!!.jsonArray[1]
            .jsonObject["parts"]!!.jsonArray
        // No empty text part when the turn was tools only.
        assertEquals(1, parts.size)
        val functionCall = parts[0].jsonObject["functionCall"]!!.jsonObject
        assertEquals("open_app", functionCall["name"]!!.jsonPrimitive.content)
        assertEquals(
            "YouTube",
            functionCall["args"]!!.jsonObject["app"]!!.jsonPrimitive.content,
        )
    }

}
