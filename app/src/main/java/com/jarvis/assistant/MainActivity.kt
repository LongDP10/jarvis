package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.ui.AppViewModel
import com.jarvis.assistant.ui.common.ConfirmationDialog
import com.jarvis.assistant.ui.nav.JarvisNavHost
import com.jarvis.assistant.ui.nav.Routes
import com.jarvis.assistant.ui.theme.JarvisColors
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.overlay.OverlayService
import com.jarvis.assistant.utils.PermissionManager
import com.jarvis.assistant.voice.TextToSpeechManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var permissions: PermissionManager
    @Inject lateinit var tts: TextToSpeechManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        warmUpSpeech()
        restoreOverlayIfEnabled()

        setContent {
            JarvisTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = JarvisColors.Background,
                ) {
                    JarvisApp(viewModel)
                }
            }
        }

        handleAssistIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAssistIntent(intent)
    }

    override fun onDestroy() {
        // The engine is process-wide, but nothing else owns it, and leaving it
        // bound keeps an audio service alive for no reason.
        if (isFinishing) tts.shutdown()
        super.onDestroy()
    }

    /**
     * Text-to-speech takes a second or two to bind the first time. Doing it at
     * launch means the first spoken reply is not preceded by an awkward pause.
     */
    private fun warmUpSpeech() {
        lifecycleScope.launch { tts.preload() }
    }

    /**
     * The overlay is a foreground service and does not survive a reboot or a
     * process death on its own, so the setting is the source of truth and the
     * service is brought back to match it.
     */
    private fun restoreOverlayIfEnabled() {
        lifecycleScope.launch {
            val configured = settings.settings.first()
            if (configured.overlayEnabled && permissions.hasOverlay) {
                OverlayService.start(this@MainActivity)
            }
        }
    }

    /** Long-press home, or the assist gesture, should start listening at once. */
    private fun handleAssistIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_ASSIST) {
            viewModel.startVoiceCommand()
        }
    }
}

@Composable
private fun JarvisApp(viewModel: AppViewModel) {
    val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    val confirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(JarvisColors.Background)) {
        // Held blank for the one frame it takes to read the stored flag; showing
        // home first and then jumping to onboarding would be worse.
        when (onboardingComplete) {
            null -> Unit
            true -> JarvisNavHost(startDestination = Routes.HOME)
            false -> JarvisNavHost(startDestination = Routes.ONBOARDING)
        }

        confirmation?.let { request ->
            ConfirmationDialog(
                request = request,
                onConfirm = { viewModel.confirm(request) },
                onCancel = { viewModel.decline(request) },
            )
        }
    }
}
