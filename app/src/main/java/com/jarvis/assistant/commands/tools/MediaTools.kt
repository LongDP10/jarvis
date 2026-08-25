package com.jarvis.assistant.commands.tools

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.jarvis.assistant.commands.ParamType
import com.jarvis.assistant.commands.Tool
import com.jarvis.assistant.commands.ToolGroup
import com.jarvis.assistant.commands.ToolParam
import com.jarvis.assistant.commands.ToolResult
import com.jarvis.assistant.commands.tool
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Volume and playback.
 *
 * Playback is driven with media key events rather than MediaSession. Both work,
 * but MediaSessionManager.getActiveSessions requires notification-listener
 * access, and demanding that just to press pause would be a poor trade. Key
 * events reach whichever app currently holds audio focus, which is what the user
 * means by "pause" anyway.
 */
@Singleton
class MediaTools @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolGroup {

    private val audioManager: AudioManager? =
        context.getSystemService(AudioManager::class.java)

    override val tools: List<Tool> = listOf(
        playMedia(),
        pauseMedia(),
        nextTrack(),
        previousTrack(),
        setVolume(),
        increaseVolume(),
        decreaseVolume(),
        mute(),
        getVolume(),
    )

    private fun playMedia() = mediaKeyTool(
        name = "play_media",
        description = "Resume or start media playback in whichever app was last playing.",
        keyCode = KeyEvent.KEYCODE_MEDIA_PLAY,
        successMessage = "Playing.",
    )

    private fun pauseMedia() = mediaKeyTool(
        name = "pause_media",
        description = "Pause media playback.",
        keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE,
        successMessage = "Paused.",
    )

    private fun nextTrack() = mediaKeyTool(
        name = "next_track",
        description = "Skip to the next track.",
        keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
        successMessage = "Skipped to the next track.",
    )

    private fun previousTrack() = mediaKeyTool(
        name = "previous_track",
        description = "Go back to the previous track.",
        keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        successMessage = "Went back to the previous track.",
    )

    private fun setVolume() = tool(
        name = "set_volume",
        description = "Set the media volume to a percentage from 0 to 100.",
        params = listOf(
            ToolParam("percent", ParamType.INTEGER, "Volume as a percentage, 0 to 100."),
        ),
    ) { call ->
        val manager = audioManager ?: return@tool noAudioManager()
        val percent = call.int("percent")
            ?: return@tool ToolResult.Failure("No volume percentage was given.")

        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (percent.coerceIn(0, 100) * max / 100f).toInt().coerceIn(0, max)
        runCatching {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        }.fold(
            onSuccess = { ToolResult.Success("Volume set to ${percent.coerceIn(0, 100)}%.") },
            onFailure = { volumeRefused(it) },
        )
    }

    private fun increaseVolume() = adjustVolumeTool(
        name = "increase_volume",
        description = "Turn the media volume up.",
        direction = AudioManager.ADJUST_RAISE,
        verb = "up",
    )

    private fun decreaseVolume() = adjustVolumeTool(
        name = "decrease_volume",
        description = "Turn the media volume down.",
        direction = AudioManager.ADJUST_LOWER,
        verb = "down",
    )

    private fun mute() = tool(
        name = "mute",
        description = "Mute or unmute media audio.",
        params = listOf(
            ToolParam(
                name = "muted",
                type = ParamType.BOOLEAN,
                description = "true to mute, false to unmute.",
                required = false,
            ),
        ),
    ) { call ->
        val manager = audioManager ?: return@tool noAudioManager()
        val shouldMute = call.boolean("muted") ?: true
        val direction = if (shouldMute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        runCatching {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }.fold(
            onSuccess = { ToolResult.Success(if (shouldMute) "Muted." else "Unmuted.") },
            onFailure = { volumeRefused(it) },
        )
    }

    private fun getVolume() = tool(
        name = "get_volume",
        description = "Report the current media volume as a percentage.",
    ) { _ ->
        val manager = audioManager ?: return@tool noAudioManager()
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = if (max == 0) 0 else current * 100 / max
        ToolResult.Success("Media volume is at $percent%.")
    }

    private fun adjustVolumeTool(
        name: String,
        description: String,
        direction: Int,
        verb: String,
    ): Tool = tool(
        name = name,
        description = description,
        params = listOf(
            ToolParam(
                name = "steps",
                type = ParamType.INTEGER,
                description = "How many volume steps to move. Defaults to 2.",
                required = false,
            ),
        ),
    ) { call ->
        val manager = audioManager ?: return@tool noAudioManager()
        val steps = (call.int("steps") ?: DEFAULT_STEPS).coerceIn(1, MAX_STEPS)
        runCatching {
            repeat(steps) {
                manager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    direction,
                    AudioManager.FLAG_SHOW_UI,
                )
            }
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val percent = if (max == 0) 0 else manager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
            ToolResult.Success("Turned the volume $verb. It is now at $percent%.")
        }.getOrElse { volumeRefused(it) }
    }

    private fun mediaKeyTool(
        name: String,
        description: String,
        keyCode: Int,
        successMessage: String,
    ): Tool = tool(name = name, description = description) { _ ->
        val manager = audioManager ?: return@tool noAudioManager()
        val now = System.currentTimeMillis()
        // Down and up both have to be sent; a lone ACTION_DOWN is treated as a
        // long-press by some players and does nothing on others.
        manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        ToolResult.Success(successMessage)
    }

    private fun noAudioManager() =
        ToolResult.Failure("The audio service is unavailable on this device.")

    /**
     * Do Not Disturb blocks volume changes outright, and the exception is the
     * only way to find out.
     */
    private fun volumeRefused(error: Throwable) = ToolResult.NotSupported(
        "Android refused the volume change. This normally means Do Not Disturb is on, which blocks volume adjustment until it is turned off. (${error.message})",
    )

    private companion object {
        const val DEFAULT_STEPS = 2
        const val MAX_STEPS = 15
    }
}
