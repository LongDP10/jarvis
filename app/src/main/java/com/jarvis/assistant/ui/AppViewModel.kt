package com.jarvis.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.commands.ConfirmationGate
import com.jarvis.assistant.core.ConfirmationRequest
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.core.JarvisStateMachine
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.session.JarvisController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Activity-wide concerns that do not belong to any one screen: which screen to
 * start on, and the confirmation dialog, which can be raised by a tool call
 * started from anywhere including the floating orb.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val gate: ConfirmationGate,
    private val controller: JarvisController,
    stateMachine: JarvisStateMachine,
    settings: SettingsRepository,
) : ViewModel() {

    /** Null until the stored value has been read; the UI waits rather than flashing. */
    val onboardingComplete: StateFlow<Boolean?> = settings.settings
        .map { it.onboardingComplete }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val pendingConfirmation: StateFlow<ConfirmationRequest?> = stateMachine.state
        .map { (it as? JarvisState.ConfirmationRequired)?.request }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun confirm(request: ConfirmationRequest) = gate.resolve(request.id, approved = true)

    fun decline(request: ConfirmationRequest) = gate.resolve(request.id, approved = false)

    /** Entry point for the system assist gesture. */
    fun startVoiceCommand() = controller.startVoiceCommand()
}
