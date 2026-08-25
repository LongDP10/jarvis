package com.jarvis.assistant.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.ai.OllamaProvider
import com.jarvis.assistant.ai.OllamaStatus
import com.jarvis.assistant.core.Language
import com.jarvis.assistant.core.ProviderId
import com.jarvis.assistant.data.secure.SecureKeyStore
import com.jarvis.assistant.data.settings.JarvisSettings
import com.jarvis.assistant.data.settings.OrbCorner
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.data.settings.VoiceProcessing
import com.jarvis.assistant.overlay.OverlayService
import com.jarvis.assistant.update.UpdateManager
import com.jarvis.assistant.update.UpdateStatus
import com.jarvis.assistant.utils.PermissionManager
import com.jarvis.assistant.voice.TextToSpeechManager
import com.jarvis.assistant.voice.VoiceOption
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Result of the Ollama reachability check shown in Settings. */
sealed interface OllamaCheck {
    data object Idle : OllamaCheck
    data object Checking : OllamaCheck
    data class Reachable(val models: List<String>) : OllamaCheck
    data class Unreachable(val message: String) : OllamaCheck
}

data class PermissionStatus(
    val microphone: Boolean = false,
    val notifications: Boolean = false,
    val overlay: Boolean = false,
    val accessibility: Boolean = false,
    val notificationAccess: Boolean = false,
    val writeSettings: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SettingsRepository,
    private val keyStore: SecureKeyStore,
    private val tts: TextToSpeechManager,
    private val permissions: PermissionManager,
    private val ollama: OllamaProvider,
    private val updates: UpdateManager,
) : ViewModel() {

    val settings: StateFlow<JarvisSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = JarvisSettings(),
    )

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    private val _permissionStatus = MutableStateFlow(PermissionStatus())
    val permissionStatus: StateFlow<PermissionStatus> = _permissionStatus.asStateFlow()

    private val _maskedKeys = MutableStateFlow<Map<ProviderId, String?>>(emptyMap())
    val maskedKeys: StateFlow<Map<ProviderId, String?>> = _maskedKeys.asStateFlow()

    private val _ollamaCheck = MutableStateFlow<OllamaCheck>(OllamaCheck.Idle)
    val ollamaCheck: StateFlow<OllamaCheck> = _ollamaCheck.asStateFlow()

    val updateStatus: StateFlow<UpdateStatus> = updates.status
    val currentVersionName: String get() = updates.currentVersionName
    val currentVersionCode: Long get() = updates.currentVersionCode

    init {
        refreshPermissions()
        refreshKeys()
        viewModelScope.launch { loadVoices(repository.current().language.resolve()) }
    }

    /** Called on resume: every special permission is granted on a system screen. */
    fun refreshPermissions() {
        _permissionStatus.value = PermissionStatus(
            microphone = permissions.hasMicrophone,
            notifications = permissions.hasNotifications,
            overlay = permissions.hasOverlay,
            accessibility = permissions.hasAccessibility,
            notificationAccess = permissions.hasNotificationAccess,
            writeSettings = permissions.canWriteSystemSettings,
        )
    }

    private fun refreshKeys() {
        _maskedKeys.value = ProviderId.entries.associateWith { keyStore.maskedApiKey(it) }
    }

    fun setLanguage(language: Language) = viewModelScope.launch {
        repository.setLanguage(language)
        loadVoices(language.resolve())
    }

    fun setVoiceProcessing(mode: VoiceProcessing) = viewModelScope.launch {
        repository.setVoiceProcessing(mode)
    }

    fun setVoice(voice: VoiceOption?) = viewModelScope.launch {
        val language = repository.current().language.resolve()
        repository.setVoice(language, voice?.name)
    }

    fun setSpeechRate(rate: Float) = viewModelScope.launch { repository.setSpeechRate(rate) }

    fun setPitch(pitch: Float) = viewModelScope.launch { repository.setPitch(pitch) }

    fun setProvider(provider: ProviderId) = viewModelScope.launch {
        repository.setProvider(provider)
    }

    fun setModel(provider: ProviderId, model: String) = viewModelScope.launch {
        repository.setModel(provider, model)
    }

    fun setOllamaUrl(url: String) = viewModelScope.launch {
        repository.setOllamaBaseUrl(url)
        // Any previous verdict is about the old address and would be misleading.
        _ollamaCheck.value = OllamaCheck.Idle
    }

    /**
     * Verifies the configured server is actually reachable and reports which
     * models it has, so a wrong IP, a server bound only to localhost, and a
     * model that was never pulled are three distinguishable outcomes instead of
     * one silent timeout at command time.
     */
    fun testOllamaConnection() = viewModelScope.launch {
        _ollamaCheck.value = OllamaCheck.Checking
        _ollamaCheck.value = when (val status = ollama.listModels()) {
            is OllamaStatus.Ok -> OllamaCheck.Reachable(status.models)
            is OllamaStatus.Failed -> OllamaCheck.Unreachable(status.message)
        }
    }

    fun checkForUpdate() = viewModelScope.launch { updates.check() }

    fun installUpdate(update: UpdateStatus.Available) = viewModelScope.launch {
        updates.downloadAndInstall(update)
    }

    fun canInstallPackages(): Boolean = updates.canInstallPackages()

    fun installPermissionIntent(): Intent = updates.installPermissionIntent()

    fun releasesPageIntent(): Intent = updates.releasesPageIntent()

    fun saveApiKey(provider: ProviderId, key: String) {
        keyStore.setApiKey(provider, key)
        refreshKeys()
    }

    fun clearApiKey(provider: ProviderId) {
        keyStore.clear(provider)
        refreshKeys()
    }

    fun setWakeWord(enabled: Boolean) = viewModelScope.launch {
        repository.setWakeWordEnabled(enabled)
        // The wake word lives inside the overlay service, so switching it on
        // without the service running would do nothing at all.
        if (enabled && !repository.current().overlayEnabled) {
            repository.setOverlayEnabled(true)
            startOverlay()
        }
    }

    fun setOverlay(enabled: Boolean) = viewModelScope.launch {
        repository.setOverlayEnabled(enabled)
        if (enabled) startOverlay() else OverlayService.stop(context)
    }

    fun setAlwaysListening(enabled: Boolean) = viewModelScope.launch {
        repository.setAlwaysListening(enabled)
    }

    fun setOrbScale(scale: Float) = viewModelScope.launch { repository.setOrbScale(scale) }

    fun setOrbCorner(corner: OrbCorner) = viewModelScope.launch { repository.setOrbCorner(corner) }

    fun setDebugLog(enabled: Boolean) = viewModelScope.launch {
        repository.setDebugLogEnabled(enabled)
    }

    private fun startOverlay() {
        if (permissions.hasOverlay) OverlayService.start(context)
    }

    private suspend fun loadVoices(language: Language) {
        _voices.value = tts.availableVoices(language)
    }

    // System screens the user has to visit themselves.
    fun accessibilityIntent(): Intent = permissions.accessibilitySettingsIntent()
    fun overlayIntent(): Intent = permissions.overlaySettingsIntent()
    fun notificationAccessIntent(): Intent = permissions.notificationListenerSettingsIntent()
    fun writeSettingsIntent(): Intent = permissions.writeSettingsIntent()
    fun batteryIntent(): Intent = permissions.batteryOptimisationIntent()
}
