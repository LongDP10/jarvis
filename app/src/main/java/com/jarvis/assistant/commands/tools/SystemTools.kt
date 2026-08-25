package com.jarvis.assistant.commands.tools

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.provider.Settings
import com.jarvis.assistant.commands.ParamType
import com.jarvis.assistant.commands.Tool
import com.jarvis.assistant.commands.ToolGroup
import com.jarvis.assistant.commands.ToolParam
import com.jarvis.assistant.commands.ToolResult
import com.jarvis.assistant.commands.tool
import com.jarvis.assistant.utils.PermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device hardware and system toggles.
 *
 * This is where Android's restrictions bite hardest, and where the app's honesty
 * rule earns its keep. The torch and brightness are genuinely controllable. Wi-Fi
 * and Bluetooth are not, and no amount of wanting them to be changes that:
 *
 *  - `WifiManager.setWifiEnabled` has been a no-op for third-party apps since
 *    Android 10. The sanctioned replacement is a settings panel the user taps.
 *  - `BluetoothAdapter.enable`/`disable` stopped working for apps in Android 13.
 *    The replacement is a system dialog the user confirms.
 *
 * Both tools therefore return NotSupported with the right screen attached, and
 * say plainly that JARVIS cannot flip the switch itself.
 */
@Singleton
class SystemTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionManager,
) : ToolGroup {

    private val cameraManager: CameraManager? =
        context.getSystemService(CameraManager::class.java)

    private var torchOn = false

    override val tools: List<Tool> = listOf(
        toggleFlashlight(),
        setBrightness(),
        toggleWifi(),
        toggleBluetooth(),
        getBatteryLevel(),
    )

    private fun toggleFlashlight() = tool(
        name = "toggle_flashlight",
        description = "Turn the torch on or off.",
        params = listOf(
            ToolParam(
                name = "on",
                type = ParamType.BOOLEAN,
                description = "true to turn the torch on, false to turn it off. Omit to toggle.",
                required = false,
            ),
        ),
    ) { call ->
        val manager = cameraManager
            ?: return@tool ToolResult.Failure("This device has no camera service.")

        val cameraId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return@tool ToolResult.NotSupported(
            "This device does not report a camera with a flash, so there is no torch to control.",
        )

        val target = call.boolean("on") ?: !torchOn
        runCatching { manager.setTorchMode(cameraId, target) }.fold(
            onSuccess = {
                torchOn = target
                ToolResult.Success(if (target) "Torch on." else "Torch off.")
            },
            onFailure = {
                // Thrown when the camera is in use by another app.
                ToolResult.Failure(
                    "The torch could not be switched: another app is using the camera. (${it.message})",
                )
            },
        )
    }

    private fun setBrightness() = tool(
        name = "set_brightness",
        description = "Set screen brightness to a percentage from 0 to 100.",
        params = listOf(
            ToolParam("percent", ParamType.INTEGER, "Brightness percentage, 0 to 100."),
        ),
    ) { call ->
        if (!permissions.canWriteSystemSettings) {
            return@tool ToolResult.RequiresPermission(
                permission = Settings.ACTION_MANAGE_WRITE_SETTINGS,
                rationale = "Changing brightness needs the Modify system settings permission, which is granted on a separate settings screen.",
            )
        }
        val percent = call.int("percent")
            ?: return@tool ToolResult.Failure("No brightness percentage was given.")

        val value = (percent.coerceIn(0, 100) * 255 / 100).coerceIn(1, 255)
        runCatching {
            // Automatic brightness overrides any manual value, so it has to go
            // off first or the change silently reverts a moment later.
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value,
            )
        }.fold(
            onSuccess = { ToolResult.Success("Brightness set to ${percent.coerceIn(0, 100)}%.") },
            onFailure = { ToolResult.Failure("Android refused the brightness change: ${it.message}") },
        )
    }

    private fun toggleWifi() = tool(
        name = "toggle_wifi",
        description = "Turn Wi-Fi on or off. Android blocks apps from doing this directly, so this opens the internet settings panel instead.",
        params = listOf(
            ToolParam(
                name = "on",
                type = ParamType.BOOLEAN,
                description = "Whether the user asked to turn it on or off.",
                required = false,
            ),
        ),
    ) { call ->
        val wanted = call.boolean("on")
        val verb = when (wanted) {
            true -> "on"
            false -> "off"
            null -> "on or off"
        }
        ToolResult.NotSupported(
            summary = "Android has not allowed apps to switch Wi-Fi $verb since Android 10. The internet panel is open now, so it is one tap.",
            fallbackIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun toggleBluetooth() = tool(
        name = "toggle_bluetooth",
        description = "Turn Bluetooth on or off. Android blocks apps from doing this directly, so this asks the system to show its own prompt.",
        params = listOf(
            ToolParam(
                name = "on",
                type = ParamType.BOOLEAN,
                description = "Whether the user asked to turn it on or off.",
                required = false,
            ),
        ),
    ) { call ->
        val wanted = call.boolean("on") ?: true
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return@tool ToolResult.NotSupported("This device has no Bluetooth adapter.")

        val alreadyCorrect = adapter.isEnabled == wanted
        if (alreadyCorrect) {
            return@tool ToolResult.Success(
                if (wanted) "Bluetooth is already on." else "Bluetooth is already off.",
            )
        }

        if (wanted) {
            ToolResult.NotSupported(
                summary = "Android 13 removed the ability for apps to switch Bluetooth on. The system's own prompt is showing now.",
                fallbackIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            // There is no ACTION_REQUEST_DISABLE equivalent, so settings is all
            // that is left.
            ToolResult.NotSupported(
                summary = "Android does not let apps switch Bluetooth off at all. Bluetooth settings is open so it can be done there.",
                fallbackIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun getBatteryLevel() = tool(
        name = "get_battery_level",
        description = "Report the current battery percentage and whether the phone is charging.",
    ) { _ ->
        val manager = context.getSystemService(android.os.BatteryManager::class.java)
            ?: return@tool ToolResult.Failure("The battery service is unavailable.")
        val level = manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = manager.isCharging
        ToolResult.Success(
            "Battery is at $level%${if (charging) " and charging" else ""}.",
        )
    }
}
