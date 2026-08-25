package com.jarvis.assistant.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.data.settings.JarvisSettings
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.session.JarvisController
import com.jarvis.assistant.utils.PermissionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val settings: JarvisSettings = JarvisSettings(),
    val microphoneGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val overlayGranted: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val controller: JarvisController,
    private val permissions: PermissionManager,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val jarvisState: StateFlow<JarvisState> = controller.state

    val settings: StateFlow<JarvisSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = JarvisSettings(),
    )

    /**
     * Read on demand rather than held in a flow: these are all granted on system
     * screens the user leaves the app to visit, so the only reliable moment to
     * check is when the screen resumes.
     */
    fun permissionSnapshot(): HomeUiState = HomeUiState(
        settings = settings.value,
        microphoneGranted = permissions.hasMicrophone,
        accessibilityEnabled = permissions.hasAccessibility,
        overlayGranted = permissions.hasOverlay,
    )

    fun onOrbTapped() {
        val state = controller.state.value
        // Tapping while JARVIS is talking or working means "stop", not "start
        // again" -- the same gesture the user would expect from any assistant.
        if (state is JarvisState.Speaking || state is JarvisState.Executing ||
            state is JarvisState.Processing || state is JarvisState.Listening
        ) {
            controller.cancel()
        } else {
            controller.startVoiceCommand()
        }
    }

    fun startListening() = controller.startVoiceCommand()

    fun cancel() = controller.cancel()
}
