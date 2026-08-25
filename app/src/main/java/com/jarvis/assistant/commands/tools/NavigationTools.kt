package com.jarvis.assistant.commands.tools

import com.jarvis.assistant.accessibility.AccessibilityController
import com.jarvis.assistant.commands.Tool
import com.jarvis.assistant.commands.ToolGroup
import com.jarvis.assistant.commands.ToolResult
import com.jarvis.assistant.commands.tool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System navigation and screen capture.
 *
 * Everything here goes through the accessibility service. That is not a
 * workaround: global actions and screenshots are exactly what an accessibility
 * service is for, and there is no other public API that can press Back or Home
 * on the user's behalf.
 */
@Singleton
class NavigationTools @Inject constructor(
    private val accessibility: AccessibilityController,
) : ToolGroup {

    override val tools: List<Tool> = listOf(
        goHome(),
        goBack(),
        openRecents(),
        openNotifications(),
        openQuickSettings(),
        takeScreenshot(),
    )

    private fun goHome() = globalTool(
        name = "go_home",
        description = "Go to the home screen.",
        successMessage = "Went to the home screen.",
    ) { accessibility.goHome() }

    private fun goBack() = globalTool(
        name = "go_back",
        description = "Press the back button.",
        successMessage = "Went back.",
    ) { accessibility.goBack() }

    private fun openRecents() = globalTool(
        name = "open_recents",
        description = "Open the recent apps switcher.",
        successMessage = "Opened recent apps.",
    ) { accessibility.openRecents() }

    private fun openNotifications() = globalTool(
        name = "open_notifications",
        description = "Pull down the notification shade.",
        successMessage = "Opened the notification shade.",
    ) { accessibility.openNotifications() }

    private fun openQuickSettings() = globalTool(
        name = "open_quick_settings",
        description = "Open the quick settings panel.",
        successMessage = "Opened quick settings.",
    ) { accessibility.openQuickSettings() }

    private fun takeScreenshot() = tool(
        name = "take_screenshot",
        description = "Capture the current screen to a PNG file.",
        requiresAccessibility = true,
    ) { _ ->
        if (!accessibility.isConnected.value) return@tool accessibilityOff()
        val file = accessibility.takeScreenshotToFile()
        if (file != null) {
            ToolResult.Success("Screenshot saved to ${file.name}.")
        } else {
            // Secure screens (banking apps, DRM video) are never capturable, and
            // the system does not say which reason applied.
            ToolResult.Failure(
                "The screenshot was refused. Some screens, such as banking apps and protected video, cannot be captured on Android.",
            )
        }
    }

    private fun globalTool(
        name: String,
        description: String,
        successMessage: String,
        action: suspend () -> Boolean,
    ): Tool = tool(
        name = name,
        description = description,
        requiresAccessibility = true,
    ) { _ ->
        if (!accessibility.isConnected.value) return@tool accessibilityOff()
        if (action()) ToolResult.Success(successMessage) else ToolResult.Failure("$name was rejected by the system.")
    }

    private fun accessibilityOff() = ToolResult.RequiresPermission(
        permission = ACCESSIBILITY,
        rationale = "This needs the JARVIS Accessibility service, which is currently off. Android does not allow an app to enable it, so the user has to switch it on in Settings.",
    )

    companion object {
        /**
         * Not a real Android permission string; the accessibility service is
         * granted on a settings screen instead. Used as a marker so the UI can
         * route the user to the right place.
         */
        const val ACCESSIBILITY = "jarvis.permission.ACCESSIBILITY"
    }
}
