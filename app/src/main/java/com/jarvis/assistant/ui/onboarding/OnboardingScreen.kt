package com.jarvis.assistant.ui.onboarding

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.R
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.ui.common.GlassCard
import com.jarvis.assistant.ui.common.SectionHeader
import com.jarvis.assistant.ui.common.SettingRow
import com.jarvis.assistant.ui.theme.JarvisColors
import com.jarvis.assistant.utils.PermissionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingState(
    val microphone: Boolean = false,
    val notifications: Boolean = false,
    val overlay: Boolean = false,
    val accessibility: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val permissions: PermissionManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun refresh() {
        _state.value = OnboardingState(
            microphone = permissions.hasMicrophone,
            notifications = permissions.hasNotifications,
            overlay = permissions.hasOverlay,
            accessibility = permissions.hasAccessibility,
        )
    }

    fun finish() = viewModelScope.launch { settings.setOnboardingComplete(true) }

    fun overlayIntent(): Intent = permissions.overlaySettingsIntent()
    fun accessibilityIntent(): Intent = permissions.accessibilitySettingsIntent()
    fun batteryIntent(): Intent = permissions.batteryOptimisationIntent()
}

/**
 * Shown once, and it grants nothing by itself.
 *
 * Each permission is presented with the reason it exists and a button that goes
 * to the right place. Every one of them is skippable, because JARVIS degrades
 * honestly: without accessibility it can still launch apps, without location it
 * can still do everything else, and telling the user that up front is more
 * respectful than a wall of dialogs on first launch.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.refresh() }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisColors.Background)
            .systemBarsPadding(),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = stringResource(R.string.onboarding_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = JarvisColors.OnSurfaceMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            item {
                SectionHeader(stringResource(R.string.settings_section_assistant))
                GlassCard {
                    PermissionCard(
                        title = stringResource(R.string.permission_microphone),
                        reason = stringResource(R.string.permission_microphone_reason),
                        granted = state.microphone,
                        onGrant = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    )
                    PermissionCard(
                        title = stringResource(R.string.permission_notification),
                        reason = stringResource(R.string.permission_notification_reason),
                        granted = state.notifications,
                        onGrant = {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                    )
                    PermissionCard(
                        title = stringResource(R.string.permission_overlay),
                        reason = stringResource(R.string.permission_overlay_reason),
                        granted = state.overlay,
                        onGrant = { context.startActivity(viewModel.overlayIntent()) },
                    )
                    PermissionCard(
                        title = stringResource(R.string.permission_accessibility),
                        reason = stringResource(R.string.permission_accessibility_reason),
                        granted = state.accessibility,
                        onGrant = { context.startActivity(viewModel.accessibilityIntent()) },
                    )
                }
            }

            item {
                GlassCard {
                    // Samsung's battery management will stop a background
                    // assistant within minutes otherwise, and the user would
                    // rightly blame the app rather than One UI.
                    SettingRow(
                        title = "Battery optimisation",
                        subtitle = "One UI stops background assistants aggressively. Exempting JARVIS keeps the wake word and the orb alive.",
                        trailing = {
                            TextButton(onClick = { context.startActivity(viewModel.batteryIntent()) }) {
                                Text(
                                    text = stringResource(R.string.permission_open_settings),
                                    color = JarvisColors.Primary,
                                )
                            }
                        },
                    )
                }
            }
        }

        Button(
            onClick = {
                viewModel.finish()
                onDone()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = JarvisColors.Primary,
                contentColor = JarvisColors.OnPrimary,
            ),
        ) {
            Text(text = stringResource(R.string.home_greeting))
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    reason: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = reason,
        trailing = {
            if (granted) {
                Text(
                    text = stringResource(R.string.permission_granted),
                    style = MaterialTheme.typography.labelLarge,
                    color = JarvisColors.Success,
                )
            } else {
                TextButton(onClick = onGrant) {
                    Text(
                        text = stringResource(R.string.permission_grant),
                        color = JarvisColors.Primary,
                    )
                }
            }
        },
    )
}
