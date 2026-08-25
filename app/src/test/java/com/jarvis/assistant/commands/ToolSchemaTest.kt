package com.jarvis.assistant.commands

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaTest {

    private val scrollSpec = ToolSpec(
        name = "scroll",
        description = "Scroll the current screen.",
        params = listOf(
            ToolParam(
                name = "direction",
                type = ParamType.STRING,
                description = "Which way to scroll.",
                allowedValues = listOf("up", "down"),
            ),
            ToolParam(
                name = "times",
                type = ParamType.INTEGER,
                description = "How many times.",
                required = false,
            ),
        ),
    )

    private val noArgSpec = ToolSpec(name = "go_home", description = "Go to the home screen.")

    private val dangerousSpec = ToolSpec(
        name = "send_sms",
        description = "Send a text message.",
        params = listOf(ToolParam("message", ParamType.STRING, "Body.")),
        isDangerous = true,
    )

    @Test
    fun `parameters carry type description and enum`() {
        val direction = ToolSchema.parameters(scrollSpec)
            .jsonObject["properties"]!!.jsonObject["direction"]!!.jsonObject

        assertEquals("string", direction["type"]!!.jsonPrimitive.content)
        assertEquals("Which way to scroll.", direction["description"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("up", "down"),
            direction["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `only required parameters are listed as required`() {
        val required = ToolSchema.parameters(scrollSpec)["required"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("direction"), required)
    }

    @Test
    fun `integer parameters map to the integer json type`() {
        val times = ToolSchema.parameters(scrollSpec)
            .jsonObject["properties"]!!.jsonObject["times"]!!.jsonObject
        assertEquals("integer", times["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai envelope wraps the schema in a function object`() {
        val tool = ToolSchema.openAiTool(scrollSpec)
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("scroll", tool["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(tool["function"]!!.jsonObject.containsKey("parameters"))
    }

    @Test
    fun `gemini omits parameters entirely for a tool that takes none`() {
        // Gemini rejects a declaration whose parameters object has no
        // properties, so the block has to be absent rather than empty.
        val declaration = ToolSchema.geminiDeclaration(noArgSpec)
        assertFalse(declaration.containsKey("parameters"))
        assertEquals("go_home", declaration["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini tools are wrapped in a single functionDeclarations array`() {
        val tools = ToolSchema.geminiTools(listOf(scrollSpec, noArgSpec))
        assertEquals(1, tools.size)
        val declarations = tools[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(2, declarations.size)
    }

    @Test
    fun `a dangerous tool tells the model a confirmation step is coming`() {
        val described = ToolSchema.describe(dangerousSpec)
        assertTrue(described.startsWith("Send a text message."))
        assertTrue(described.contains("confirm"))
    }

    @Test
    fun `a safe tool description is left alone`() {
        assertEquals("Go to the home screen.", ToolSchema.describe(noArgSpec))
    }

    @Test
    fun `duplicate tool names are reported`() {
        val duplicated = listOf(noArgSpec, scrollSpec, noArgSpec.copy(description = "Other"))
        assertEquals(listOf("go_home"), ToolSchema.duplicateNames(duplicated))
    }

    @Test
    fun `distinct tool names report no duplicates`() {
        assertTrue(ToolSchema.duplicateNames(listOf(noArgSpec, scrollSpec)).isEmpty())
    }
}
