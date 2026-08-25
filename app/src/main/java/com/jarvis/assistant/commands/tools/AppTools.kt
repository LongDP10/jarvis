package com.jarvis.assistant.commands.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import com.jarvis.assistant.accessibility.AccessibilityController
import com.jarvis.assistant.commands.ParamType
import com.jarvis.assistant.commands.Tool
import com.jarvis.assistant.commands.ToolGroup
import com.jarvis.assistant.commands.ToolParam
import com.jarvis.assistant.commands.ToolResult
import com.jarvis.assistant.commands.tool
import com.jarvis.assistant.utils.AppRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appRegistry: AppRegistry,
    private val accessibility: AccessibilityController,
) : ToolGroup {

    override val tools: List<Tool> = listOf(
        openApp(),
        closeApp(),
        listApps(),
        searchWeb(),
        openUrl(),
        openSettings(),
        openCamera(),
        openGallery(),
    )

    private fun openApp() = tool(
        name = "open_app",
        description = "Launch an installed app by its name, for example YouTube, Chrome, Zalo or Messenger.",
        params = listOf(
            ToolParam("app", ParamType.STRING, "The app name as the user said it."),
        ),
    ) { call ->
        val query = call.string("app")
            ?: return@tool ToolResult.Failure("No app name was given.")

        val matches = appRegistry.resolve(query)
        val installed = appRegistry.installedApps()
        when {
            // These two used to share one message, which made a broken app index
            // indistinguishable from a genuinely missing app -- and the index
            // failing looks exactly like every app on the phone being missing.
            installed.isEmpty() -> ToolResult.Failure(
                appRegistry.lastFailure
                    ?: "JARVIS cannot see any installed apps on this phone, so it cannot open one.",
            )

            matches.isEmpty() -> ToolResult.Failure(
                "None of the ${installed.size} apps on this phone match \"$query\". " +
                    "Tell the user it does not appear to be installed, and that JARVIS can " +
                    "see apps such as ${installed.take(3).joinToString(", ") { it.label }}.",
            )

            matches.size > 1 -> ToolResult.Failure(
                "Several installed apps match \"$query\": " +
                    matches.take(5).joinToString(", ") { it.label } +
                    ". Ask the user which one they meant, then call open_app again with that exact name.",
            )

            else -> {
                val app = matches.first()
                val intent = appRegistry.launchIntentFor(app.packageName)
                    ?: return@tool ToolResult.Failure(
                        "${app.label} is installed but does not expose a launcher screen, so it cannot be opened.",
                    )
                launch(intent, "Opened ${app.label}.")
            }
        }
    }

    /**
     * Android has no public API for killing another app, and there is no
     * accessibility action for it either. Going home is the closest honest
     * equivalent, and the summary says exactly that rather than claiming the app
     * was closed.
     */
    private fun closeApp() = tool(
        name = "close_app",
        description = "Leave the current app. Android does not allow force-stopping another app, so this returns to the home screen.",
        requiresAccessibility = true,
    ) { _ ->
        if (!accessibility.isConnected.value) {
            return@tool ToolResult.NotSupported(
                "Android provides no way to close another app. The Accessibility service is also off, so JARVIS cannot even go to the home screen.",
                fallbackIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        val wentHome = accessibility.goHome()
        if (wentHome) {
            ToolResult.Success(
                "Android does not let an app force-stop another app, so JARVIS went to the home screen instead. The app is still running in the background.",
            )
        } else {
            ToolResult.Failure("Could not return to the home screen.")
        }
    }

    private fun listApps() = tool(
        name = "list_installed_apps",
        description = "List the apps installed on this phone. Use this when the user's app name did not match anything.",
    ) { _ ->
        val apps = appRegistry.installedApps()
        if (apps.isEmpty()) {
            ToolResult.Failure("No launchable apps were found.")
        } else {
            ToolResult.Success(
                "Installed apps: " + apps.joinToString(", ") { it.label },
            )
        }
    }

    private fun searchWeb() = tool(
        name = "search_web",
        description = "Search the web for a query in the user's browser.",
        params = listOf(
            ToolParam("query", ParamType.STRING, "What to search for."),
        ),
        requiresNetwork = true,
        worksOffline = false,
    ) { call ->
        val query = call.string("query")
            ?: return@tool ToolResult.Failure("No search query was given.")

        val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallback = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // ACTION_WEB_SEARCH is not handled on every device, so a plain browser
        // URL is the backstop rather than reporting failure.
        launch(searchIntent, "Searching the web for $query.")
            .takeIf { it.isSuccess }
            ?: launch(fallback, "Searching the web for $query.")
    }

    private fun openUrl() = tool(
        name = "open_url",
        description = "Open a web address in the browser.",
        params = listOf(
            ToolParam("url", ParamType.STRING, "A full http or https URL."),
        ),
        requiresNetwork = true,
        worksOffline = false,
    ) { call ->
        val raw = call.string("url")
            ?: return@tool ToolResult.Failure("No URL was given.")
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        launch(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "Opened $url.",
        )
    }

    private fun openSettings() = tool(
        name = "open_settings",
        description = "Open an Android settings screen.",
        params = listOf(
            ToolParam(
                name = "section",
                type = ParamType.STRING,
                description = "Which settings screen to open.",
                required = false,
                allowedValues = SETTINGS_SECTIONS.keys.toList(),
            ),
        ),
    ) { call ->
        val section = call.string("section")?.lowercase()
        val action = SETTINGS_SECTIONS[section] ?: Settings.ACTION_SETTINGS
        launch(
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            if (section == null) "Opened Settings." else "Opened $section settings.",
        )
    }

    private fun openCamera() = tool(
        name = "open_camera",
        description = "Open the camera app.",
    ) { _ ->
        launch(
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "Opened the camera.",
        )
    }

    private fun openGallery() = tool(
        name = "open_gallery",
        description = "Open the photo gallery.",
    ) { _ ->
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "image/*"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launch(intent, "Opened the gallery.")
    }

    private fun launch(intent: Intent, successMessage: String): ToolResult = try {
        context.startActivity(intent)
        ToolResult.Success(successMessage)
    } catch (e: ActivityNotFoundException) {
        ToolResult.Failure("No app on this phone can handle that: ${e.message}")
    } catch (e: SecurityException) {
        ToolResult.Failure("Android refused to open that screen: ${e.message}")
    }

    private companion object {
        val SETTINGS_SECTIONS: Map<String, String> = mapOf(
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
            "apps" to Settings.ACTION_APPLICATION_SETTINGS,
            "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "security" to Settings.ACTION_SECURITY_SETTINGS,
            "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "date" to Settings.ACTION_DATE_SETTINGS,
            "language" to Settings.ACTION_LOCALE_SETTINGS,
            "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            "nfc" to Settings.ACTION_NFC_SETTINGS,
            "developer" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        )
    }
}
