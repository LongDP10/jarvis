package com.jarvis.assistant.commands.tools

import com.jarvis.assistant.accessibility.AccessibilityController
import com.jarvis.assistant.accessibility.ScrollDirection
import com.jarvis.assistant.commands.ParamType
import com.jarvis.assistant.commands.Tool
import com.jarvis.assistant.commands.ToolGroup
import com.jarvis.assistant.commands.ToolParam
import com.jarvis.assistant.commands.ToolResult
import com.jarvis.assistant.commands.tool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct manipulation of whatever is on screen.
 *
 * This is the set that turns JARVIS from an app launcher into something that can
 * finish a task: read the screen, decide what to press, press it. Every tool
 * here needs the accessibility service and says so rather than failing vaguely.
 *
 * `read_screen` is the important one. It is what lets "open the first video"
 * work at all -- the model sees a numbered list of what is actually visible and
 * then calls `click_text` with the right label.
 */
@Singleton
class UiTools @Inject constructor(
    private val accessibility: AccessibilityController,
) : ToolGroup {

    override val tools: List<Tool> = listOf(
        readScreen(),
        clickText(),
        tap(),
        longPress(),
        swipe(),
        scroll(),
        inputText(),
    )

    private fun readScreen() = a11yTool(
        name = "read_screen",
        description = "Read what is currently on screen as a numbered list of visible text and controls. Call this before clicking anything you have not already seen.",
    ) { _ ->
        val snapshot = accessibility.readScreen()
        if (snapshot.isEmpty) {
            ToolResult.Failure("Nothing readable is on screen right now.")
        } else {
            ToolResult.Success(snapshot.toPromptText())
        }
    }

    private fun clickText() = a11yTool(
        name = "click_text",
        description = "Tap the on-screen element whose label matches the given text. Matching ignores case and Vietnamese accents.",
        params = listOf(
            ToolParam("text", ParamType.STRING, "The visible label of the thing to tap."),
        ),
    ) { call ->
        val text = call.string("text")
            ?: return@a11yTool ToolResult.Failure("No text was given to click.")
        if (accessibility.clickByText(text)) {
            ToolResult.Success("Tapped \"$text\".")
        } else {
            ToolResult.Failure(
                "Nothing on screen matches \"$text\". Call read_screen to see what is actually there.",
            )
        }
    }

    private fun tap() = a11yTool(
        name = "tap",
        description = "Tap an exact screen coordinate. Prefer click_text; only use this when there is no label to match.",
        params = listOf(
            ToolParam("x", ParamType.NUMBER, "X coordinate in pixels."),
            ToolParam("y", ParamType.NUMBER, "Y coordinate in pixels."),
        ),
    ) { call ->
        val x = call.float("x")
        val y = call.float("y")
        if (x == null || y == null) {
            return@a11yTool ToolResult.Failure("Both x and y coordinates are required.")
        }
        if (accessibility.tap(x, y)) {
            ToolResult.Success("Tapped at $x, $y.")
        } else {
            ToolResult.Failure("The tap gesture was not accepted.")
        }
    }

    private fun longPress() = a11yTool(
        name = "long_press",
        description = "Press and hold at a screen coordinate.",
        params = listOf(
            ToolParam("x", ParamType.NUMBER, "X coordinate in pixels."),
            ToolParam("y", ParamType.NUMBER, "Y coordinate in pixels."),
        ),
    ) { call ->
        val x = call.float("x")
        val y = call.float("y")
        if (x == null || y == null) {
            return@a11yTool ToolResult.Failure("Both x and y coordinates are required.")
        }
        if (accessibility.longPress(x, y)) {
            ToolResult.Success("Long-pressed at $x, $y.")
        } else {
            ToolResult.Failure("The long press gesture was not accepted.")
        }
    }

    private fun swipe() = a11yTool(
        name = "swipe",
        description = "Swipe from one coordinate to another.",
        params = listOf(
            ToolParam("from_x", ParamType.NUMBER, "Start X in pixels."),
            ToolParam("from_y", ParamType.NUMBER, "Start Y in pixels."),
            ToolParam("to_x", ParamType.NUMBER, "End X in pixels."),
            ToolParam("to_y", ParamType.NUMBER, "End Y in pixels."),
        ),
    ) { call ->
        val fromX = call.float("from_x")
        val fromY = call.float("from_y")
        val toX = call.float("to_x")
        val toY = call.float("to_y")
        if (fromX == null || fromY == null || toX == null || toY == null) {
            return@a11yTool ToolResult.Failure("All four swipe coordinates are required.")
        }
        if (accessibility.swipe(fromX, fromY, toX, toY)) {
            ToolResult.Success("Swiped.")
        } else {
            ToolResult.Failure("The swipe gesture was not accepted.")
        }
    }

    private fun scroll() = a11yTool(
        name = "scroll",
        description = "Scroll the current screen.",
        params = listOf(
            ToolParam(
                name = "direction",
                type = ParamType.STRING,
                description = "Which way to scroll.",
                allowedValues = listOf("up", "down", "left", "right"),
            ),
            ToolParam(
                name = "times",
                type = ParamType.INTEGER,
                description = "How many times to repeat. Defaults to 1.",
                required = false,
            ),
        ),
    ) { call ->
        val direction = when (call.string("direction")?.lowercase()) {
            "up" -> ScrollDirection.UP
            "down", null -> ScrollDirection.DOWN
            "left" -> ScrollDirection.LEFT
            "right" -> ScrollDirection.RIGHT
            else -> return@a11yTool ToolResult.Failure("Direction must be up, down, left or right.")
        }
        val times = (call.int("times") ?: 1).coerceIn(1, 20)
        if (accessibility.scroll(direction, times)) {
            ToolResult.Success(
                if (times == 1) "Scrolled ${direction.name.lowercase()}." else "Scrolled ${direction.name.lowercase()} $times times.",
            )
        } else {
            ToolResult.Failure("Nothing on this screen responded to scrolling.")
        }
    }

    private fun inputText() = a11yTool(
        name = "input_text",
        description = "Type text into the focused text field. Tap the field first if it is not already focused.",
        params = listOf(
            ToolParam("text", ParamType.STRING, "The text to type."),
        ),
    ) { call ->
        val text = call.string("text")
            ?: return@a11yTool ToolResult.Failure("No text was given to type.")
        if (accessibility.inputText(text)) {
            ToolResult.Success("Typed \"$text\".")
        } else {
            ToolResult.Failure(
                "No text field is focused. Tap the field first, then call input_text again.",
            )
        }
    }

    private fun a11yTool(
        name: String,
        description: String,
        params: List<ToolParam> = emptyList(),
        action: suspend (com.jarvis.assistant.core.ToolCall) -> ToolResult,
    ): Tool = tool(
        name = name,
        description = description,
        params = params,
        requiresAccessibility = true,
    ) { call ->
        if (!accessibility.isConnected.value) {
            ToolResult.RequiresPermission(
                permission = NavigationTools.ACCESSIBILITY,
                rationale = "This needs the JARVIS Accessibility service, which is currently off.",
            )
        } else {
            action(call)
        }
    }
}
