package com.jarvis.assistant.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.core.JarvisStateMachine
import com.jarvis.assistant.core.Language
import com.jarvis.assistant.data.settings.OrbCorner
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.session.JarvisController
import com.jarvis.assistant.utils.LocalizedStrings
import com.jarvis.assistant.utils.PermissionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Keeps the orb on screen above other apps, and keeps JARVIS alive while it is.
 *
 * Two foreground service types are declared in the manifest and the correct one
 * is chosen here at runtime. Android 14 requires a microphone-typed service to
 * actually hold RECORD_AUDIO, so starting as microphone with the permission
 * denied would crash immediately; when the wake word is off, or the permission
 * has not been granted, this runs as specialUse instead and simply cannot
 * listen in the background -- which is the truthful state of affairs.
 */
@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var stateMachine: JarvisStateMachine
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var controller: JarvisController
    @Inject lateinit var permissions: PermissionManager
    @Inject lateinit var strings: LocalizedStrings

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var host: ComposeOverlayHost? = null
    private var orbView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var confirmationWatcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely()
        showOrb()
        watchWakeWord()
        watchConfirmations()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Restarted by the system after being killed: the orb should come back,
        // but without a redelivered intent that might re-run a stale command.
        return START_STICKY
    }

    override fun onDestroy() {
        confirmationWatcher?.cancel()
        controller.stopWakeWord()
        removeOrb()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ foreground

    private fun startForegroundSafely() {
        val useMicrophoneType = permissions.hasMicrophone
        val type = if (useMicrophoneType) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        }.onFailure { error ->
            Log.e(TAG, "Could not enter the foreground", error)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, JarvisApplication.ASSISTANT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ------------------------------------------------------------------ orb

    private fun showOrb() {
        if (!Settings.canDrawOverlays(this)) {
            // The permission can be revoked while the service is running, and
            // addView would throw. Stopping is honest: without the permission
            // there is no overlay to provide.
            Log.w(TAG, "Overlay permission is not granted; stopping")
            stopSelf()
            return
        }

        val manager = getSystemService(WindowManager::class.java) ?: return
        windowManager = manager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        layoutParams = params

        val overlayHost = ComposeOverlayHost(this)
        host = overlayHost

        val view = overlayHost.createView {
            val state by stateMachine.state.collectAsState()
            val configured by settings.settings.collectAsState(initial = null)
            val scale = configured?.orbScale ?: 1f

            com.jarvis.assistant.ui.theme.JarvisTheme {
                FloatingOrb(
                    state = state,
                    caption = captionFor(state, configured?.language ?: Language.AUTO),
                    orbSize = (BASE_ORB_DP * scale).dp(),
                    onTap = { controller.startVoiceCommand() },
                    onDoubleTap = { controller.startVoiceCommand() },
                    onLongPress = { openChat() },
                    onDrag = ::moveBy,
                    onDragEnd = ::snapToNearestCorner,
                )
            }
        }
        orbView = view

        runCatching {
            manager.addView(view, params)
            overlayHost.onResumed()
        }.onFailure { error ->
            Log.e(TAG, "Could not add the overlay window", error)
            stopSelf()
            return
        }

        scope.launch {
            settings.settings
                .map { it.orbCorner }
                .distinctUntilChanged()
                .collect { corner -> positionAt(corner) }
        }
    }

    private fun removeOrb() {
        val view = orbView ?: return
        runCatching { windowManager?.removeView(view) }
        host?.onDestroyed()
        orbView = null
        host = null
    }

    private fun moveBy(dx: Float, dy: Float) {
        val params = layoutParams ?: return
        val view = orbView ?: return
        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    /**
     * Snapping rather than free placement: an orb left mid-screen covers content
     * and is easy to lose track of, and the corner it lands in is remembered so
     * it reappears where the user put it.
     */
    private fun snapToNearestCorner() {
        val params = layoutParams ?: return
        val metrics = resources.displayMetrics
        val corner = when {
            params.x + params.width / 2 < metrics.widthPixels / 2 &&
                params.y + params.height / 2 < metrics.heightPixels / 2 -> OrbCorner.TOP_LEFT

            params.x + params.width / 2 >= metrics.widthPixels / 2 &&
                params.y + params.height / 2 < metrics.heightPixels / 2 -> OrbCorner.TOP_RIGHT

            params.x + params.width / 2 < metrics.widthPixels / 2 -> OrbCorner.BOTTOM_LEFT

            else -> OrbCorner.BOTTOM_RIGHT
        }
        positionAt(corner)
        scope.launch { settings.setOrbCorner(corner) }
    }

    private fun positionAt(corner: OrbCorner) {
        val params = layoutParams ?: return
        val view = orbView ?: return
        val metrics = resources.displayMetrics
        val margin = (MARGIN_DP * metrics.density).roundToInt()
        val estimated = ((BASE_ORB_DP + CAPTION_ALLOWANCE_DP) * metrics.density).roundToInt()

        params.x = when (corner) {
            OrbCorner.TOP_LEFT, OrbCorner.BOTTOM_LEFT -> margin
            else -> metrics.widthPixels - estimated - margin
        }
        params.y = when (corner) {
            OrbCorner.TOP_LEFT, OrbCorner.TOP_RIGHT -> margin * 4
            else -> metrics.heightPixels - estimated - margin * 4
        }
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    // --------------------------------------------------------------- wiring

    private fun watchWakeWord() {
        scope.launch {
            settings.settings
                .map { it.wakeWordEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled && permissions.hasMicrophone) {
                        controller.startWakeWord()
                    } else {
                        controller.stopWakeWord()
                    }
                }
        }
    }

    /**
     * A dangerous action needs a real dialog, and a dialog needs an Activity. So
     * when the gate opens while the user is in another app, JARVIS brings itself
     * to the front rather than silently waiting for a confirmation nobody can see.
     */
    private fun watchConfirmations() {
        confirmationWatcher = scope.launch {
            stateMachine.state.collect { state ->
                if (state is JarvisState.ConfirmationRequired) openChat()
            }
        }
    }

    private fun openChat() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    /**
     * The caption follows the assistant language the user chose, not the phone's
     * locale: someone running an English phone with JARVIS set to Vietnamese
     * should see Vietnamese here, same as they hear it.
     */
    private fun captionFor(state: JarvisState, language: Language): String? {
        return when (state) {
            is JarvisState.Idle -> null
            is JarvisState.Wake -> strings.get(language, R.string.state_listening)
            is JarvisState.Listening ->
                state.partial.ifBlank { strings.get(language, R.string.state_listening) }
            is JarvisState.Processing ->
                state.label ?: strings.get(language, R.string.state_thinking)
            is JarvisState.Executing -> state.label
            is JarvisState.Speaking -> state.text
            is JarvisState.ConfirmationRequired -> state.request.body
            is JarvisState.WaitingForUser -> state.question
            is JarvisState.Error -> state.message
            is JarvisState.Cancelled -> strings.get(language, R.string.state_cancelled)
        }
    }

    private fun Float.dp() = androidx.compose.ui.unit.Dp(this)

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.jarvis.assistant.STOP_OVERLAY"
        private const val BASE_ORB_DP = 96f
        private const val CAPTION_ALLOWANCE_DP = 24f
        private const val MARGIN_DP = 12f

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
