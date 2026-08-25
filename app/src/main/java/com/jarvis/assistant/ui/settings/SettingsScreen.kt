package com.jarvis.assistant.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.R
import com.jarvis.assistant.core.Language
import com.jarvis.assistant.core.ProviderId
import com.jarvis.assistant.data.settings.JarvisSettings
import com.jarvis.assistant.data.settings.OrbCorner
import com.jarvis.assistant.data.settings.VoiceProcessing
import com.jarvis.assistant.update.UpdateStatus
import com.jarvis.assistant.ui.common.GlassCard
import com.jarvis.assistant.ui.common.JarvisTopBar
import com.jarvis.assistant.ui.common.SectionHeader
import com.jarvis.assistant.ui.common.SettingRow
import com.jarvis.assistant.ui.theme.JarvisColors

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDebug: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val permissions by viewModel.permissionStatus.collectAsStateWithLifecycle()
    val maskedKeys by viewModel.maskedKeys.collectAsStateWithLifecycle()
    val ollamaCheck by viewModel.ollamaCheck.collectAsStateWithLifecycle()
    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun open(intent: Intent) = context.startActivity(intent)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisColors.Background)
            .systemBarsPadding(),
    ) {
        JarvisTopBar(title = stringResource(R.string.settings_title), onBack = onBack)

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                SectionHeader(stringResource(R.string.settings_section_language))
                GlassCard {
                    ChipRow(
                        options = listOf(
                            Language.VIETNAMESE to stringResource(R.string.settings_language_vi),
                            Language.ENGLISH to stringResource(R.string.settings_language_en),
                            Language.AUTO to stringResource(R.string.settings_language_auto),
                        ),
                        selected = settings.language,
                        onSelect = viewModel::setLanguage,
                    )
                }
            }

            item {
                SectionHeader(stringResource(R.string.settings_section_voice))
                GlassCard {
                    Text(
                        text = stringResource(R.string.settings_voice),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (voices.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_voice_default),
                            style = MaterialTheme.typography.bodySmall,
                            color = JarvisColors.OnSurfaceMuted,
                        )
                    } else {
                        val selectedName = if (settings.language.resolve() == Language.VIETNAMESE) {
                            settings.voiceNameVi
                        } else {
                            settings.voiceNameEn
                        }
                        ChipRow(
                            options = listOf(
                                null to stringResource(R.string.settings_voice_default),
                            ) + voices.take(MAX_VOICE_CHIPS).map { it to it.label },
                            selected = voices.firstOrNull { it.name == selectedName },
                            onSelect = viewModel::setVoice,
                        )
                    }

                    LabelledSlider(
                        label = stringResource(R.string.settings_speech_rate),
                        value = settings.speechRate,
                        range = JarvisSettings.SPEECH_RATE_RANGE,
                        onChange = viewModel::setSpeechRate,
                    )
                    LabelledSlider(
                        label = stringResource(R.string.settings_pitch),
                        value = settings.pitch,
                        range = JarvisSettings.PITCH_RANGE,
                        onChange = viewModel::setPitch,
                    )

                    Text(
                        text = stringResource(R.string.settings_voice_processing),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    ChipRow(
                        options = listOf(
                            VoiceProcessing.CLOUD to stringResource(R.string.settings_voice_processing_cloud),
                            VoiceProcessing.LOCAL to stringResource(R.string.settings_voice_processing_local),
                            VoiceProcessing.HYBRID to stringResource(R.string.settings_voice_processing_hybrid),
                        ),
                        selected = settings.voiceProcessing,
                        onSelect = viewModel::setVoiceProcessing,
                    )
                    Text(
                        text = stringResource(R.string.settings_voice_processing_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = JarvisColors.OnSurfaceMuted,
                    )
                }
            }

            item {
                SectionHeader(stringResource(R.string.settings_section_ai))
                GlassCard {
                    ChipRow(
                        options = ProviderId.entries.map { it to it.displayName },
                        selected = settings.provider,
                        onSelect = viewModel::setProvider,
                    )
                    ProviderConfiguration(
                        settings = settings,
                        maskedKey = maskedKeys[settings.provider],
                        ollamaCheck = ollamaCheck,
                        onSaveKey = { viewModel.saveApiKey(settings.provider, it) },
                        onClearKey = { viewModel.clearApiKey(settings.provider) },
                        onSaveModel = { viewModel.setModel(settings.provider, it) },
                        onSaveOllamaUrl = viewModel::setOllamaUrl,
                        onTestConnection = viewModel::testOllamaConnection,
                    )
                }
            }

            item {
                SectionHeader(stringResource(R.string.settings_section_assistant))
                GlassCard {
                    SettingRow(
                        title = stringResource(R.string.settings_floating_orb),
                        subtitle = if (permissions.overlay) {
                            null
                        } else {
                            stringResource(R.string.permission_overlay_reason)
                        },
                        trailing = {
                            JarvisSwitch(
                                checked = settings.overlayEnabled && permissions.overlay,
                                onCheckedChange = { wanted ->
                                    if (wanted && !permissions.overlay) {
                                        open(viewModel.overlayIntent())
                                    } else {
                                        viewModel.setOverlay(wanted)
                                    }
                                },
                            )
                        },
                    )

                    SettingRow(
                        title = stringResource(R.string.settings_wake_word),
                        subtitle = stringResource(R.string.settings_wake_word_warning),
                        trailing = {
                            JarvisSwitch(
                                checked = settings.wakeWordEnabled,
                                onCheckedChange = viewModel::setWakeWord,
                            )
                        },
                    )

                    SettingRow(
                        title = stringResource(R.string.settings_always_listening),
                        trailing = {
                            JarvisSwitch(
                                checked = settings.alwaysListening,
                                onCheckedChange = viewModel::setAlwaysListening,
                            )
                        },
                    )

                    LabelledSlider(
                        label = stringResource(R.string.settings_orb_size),
                        value = settings.orbScale,
                        range = JarvisSettings.ORB_SCALE_RANGE,
                        onChange = viewModel::setOrbScale,
                    )

                    Text(
                        text = stringResource(R.string.settings_orb_position),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    ChipRow(
                        options = listOf(
                            OrbCorner.TOP_LEFT to stringResource(R.string.settings_position_top_left),
                            OrbCorner.TOP_RIGHT to stringResource(R.string.settings_position_top_right),
                            OrbCorner.BOTTOM_LEFT to stringResource(R.string.settings_position_bottom_left),
                            OrbCorner.BOTTOM_RIGHT to stringResource(R.string.settings_position_bottom_right),
                        ),
                        selected = settings.orbCorner,
                        onSelect = viewModel::setOrbCorner,
                    )

                    SettingRow(
                        title = stringResource(R.string.settings_confirm_dangerous),
                        subtitle = stringResource(R.string.settings_confirm_dangerous_note),
                        trailing = {
                            // Not a toggle. Confirmation for calls and messages is
                            // enforced in the executor and cannot be disabled from
                            // the UI, so showing a switch here would be a lie.
                            Text(
                                text = stringResource(R.string.permission_granted),
                                style = MaterialTheme.typography.labelLarge,
                                color = JarvisColors.Success,
                            )
                        },
                    )
                }
            }

            item {
                SectionHeader(stringResource(R.string.onboarding_title))
                GlassCard {
                    PermissionRow(
                        title = stringResource(R.string.permission_accessibility),
                        reason = stringResource(R.string.permission_accessibility_reason),
                        granted = permissions.accessibility,
                        onOpen = { open(viewModel.accessibilityIntent()) },
                    )
                    PermissionRow(
                        title = stringResource(R.string.permission_overlay),
                        reason = stringResource(R.string.permission_overlay_reason),
                        granted = permissions.overlay,
                        onOpen = { open(viewModel.overlayIntent()) },
                    )
                    PermissionRow(
                        title = stringResource(R.string.permission_notification_listener),
                        reason = stringResource(R.string.permission_notification_listener_reason),
                        granted = permissions.notificationAccess,
                        onOpen = { open(viewModel.notificationAccessIntent()) },
                    )
                    PermissionRow(
                        title = stringResource(R.string.permission_write_settings),
                        reason = stringResource(R.string.permission_write_settings_reason),
                        granted = permissions.writeSettings,
                        onOpen = { open(viewModel.writeSettingsIntent()) },
                    )
                }
            }

            item {
                SectionHeader(stringResource(R.string.settings_section_updates))
                GlassCard {
                    SettingRow(
                        title = stringResource(R.string.settings_current_version),
                        subtitle = "${viewModel.currentVersionName} (${viewModel.currentVersionCode})",
                        trailing = {
                            TextButton(
                                onClick = viewModel::checkForUpdate,
                                enabled = updateStatus !is UpdateStatus.Checking &&
                                    updateStatus !is UpdateStatus.Downloading,
                            ) {
                                Text(
                                    text = stringResource(R.string.update_check),
                                    color = JarvisColors.Primary,
                                )
                            }
                        },
                    )

                    UpdateStatusRow(
                        status = updateStatus,
                        onInstall = { viewModel.installUpdate(it) },
                        onGrantPermission = { open(viewModel.installPermissionIntent()) },
                        onOpenReleases = { open(viewModel.releasesPageIntent()) },
                    )
                }
            }

            item {
                SectionHeader(stringResource(R.string.settings_section_developer))
                GlassCard {
                    SettingRow(
                        title = stringResource(R.string.settings_debug_log),
                        trailing = {
                            JarvisSwitch(
                                checked = settings.debugLogEnabled,
                                onCheckedChange = viewModel::setDebugLog,
                            )
                        },
                    )
                    TextButton(onClick = onOpenDebug) {
                        Text(
                            text = stringResource(R.string.settings_open_debug),
                            color = JarvisColors.Primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderConfiguration(
    settings: JarvisSettings,
    maskedKey: String?,
    ollamaCheck: OllamaCheck,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onSaveModel: (String) -> Unit,
    onSaveOllamaUrl: (String) -> Unit,
    onTestConnection: () -> Unit,
) {
    var keyDraft by remember(settings.provider) { mutableStateOf("") }
    var modelDraft by remember(settings.provider) {
        mutableStateOf(settings.modelForCurrentProvider)
    }
    var urlDraft by remember(settings.provider) { mutableStateOf(settings.ollamaBaseUrl) }

    if (settings.provider.requiresApiKey) {
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_api_key)) },
            placeholder = { Text(maskedKey ?: stringResource(R.string.settings_api_key_hint)) },
            // The key is never echoed back in clear text, not even to the person
            // who typed it, so a shoulder-surf or a screenshot cannot lift it.
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = jarvisFieldColors(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    onSaveKey(keyDraft)
                    keyDraft = ""
                },
                enabled = keyDraft.isNotBlank(),
            ) {
                Text(text = stringResource(R.string.chat_send), color = JarvisColors.Primary)
            }
            if (maskedKey != null) {
                TextButton(onClick = onClearKey) {
                    Text(text = stringResource(R.string.debug_clear), color = JarvisColors.Error)
                }
            }
        }
        Text(
            text = maskedKey?.let { stringResource(R.string.settings_api_key_saved) }
                ?: stringResource(R.string.settings_api_key_missing),
            style = MaterialTheme.typography.bodySmall,
            color = if (maskedKey != null) JarvisColors.Success else JarvisColors.Warning,
        )
        Text(
            text = stringResource(R.string.settings_api_key_note),
            style = MaterialTheme.typography.bodySmall,
            color = JarvisColors.OnSurfaceMuted,
        )
    } else {
        OutlinedTextField(
            value = urlDraft,
            onValueChange = {
                urlDraft = it
                onSaveOllamaUrl(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_ollama_url)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = jarvisFieldColors(),
        )

        TextButton(
            onClick = onTestConnection,
            enabled = ollamaCheck != OllamaCheck.Checking,
        ) {
            Text(
                text = if (ollamaCheck == OllamaCheck.Checking) {
                    stringResource(R.string.settings_testing)
                } else {
                    stringResource(R.string.settings_test_connection)
                },
                color = JarvisColors.Primary,
            )
        }

        OllamaCheckResult(check = ollamaCheck, configuredModel = modelDraft)

        Text(
            text = stringResource(R.string.settings_ollama_note),
            style = MaterialTheme.typography.bodySmall,
            color = JarvisColors.OnSurfaceMuted,
        )
    }

    OutlinedTextField(
        value = modelDraft,
        onValueChange = {
            modelDraft = it
            onSaveModel(it)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.settings_model)) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = jarvisFieldColors(),
    )
}

/**
 * Turns the check into the specific next action. "Connected" alone is not enough
 * when the model named in settings is not one the server actually has -- that
 * would still fail at command time, so it is called out here instead.
 */
@Composable
private fun OllamaCheckResult(check: OllamaCheck, configuredModel: String) {
    when (check) {
        is OllamaCheck.Idle -> Unit

        is OllamaCheck.Checking -> Text(
            text = stringResource(R.string.settings_testing),
            style = MaterialTheme.typography.bodySmall,
            color = JarvisColors.OnSurfaceMuted,
        )

        is OllamaCheck.Unreachable -> Text(
            text = check.message,
            style = MaterialTheme.typography.bodySmall,
            color = JarvisColors.Error,
        )

        is OllamaCheck.Reachable -> Column {
            if (check.models.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_connection_no_models),
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisColors.Warning,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.settings_connection_ok,
                        check.models.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisColors.Success,
                )
                // Ollama tags carry a :tag suffix ("qwen2.5:latest"), so a bare
                // model name in settings still counts as present.
                val present = check.models.any { it.substringBefore(':') == configuredModel.substringBefore(':') }
                if (!present && configuredModel.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.settings_model_not_pulled, configuredModel),
                        style = MaterialTheme.typography.bodySmall,
                        color = JarvisColors.Warning,
                    )
                }
            }
        }
    }
}

/**
 * Shows exactly one next action for whatever state the updater is in. An update
 * that is available but needs the install permission is a different problem from
 * one that failed to download, and conflating them would leave the user with
 * nothing to press.
 */
@Composable
private fun UpdateStatusRow(
    status: UpdateStatus,
    onInstall: (UpdateStatus.Available) -> Unit,
    onGrantPermission: () -> Unit,
    onOpenReleases: () -> Unit,
) {
    when (status) {
        is UpdateStatus.Idle -> Unit

        is UpdateStatus.Checking -> Text(
            text = stringResource(R.string.update_checking),
            style = MaterialTheme.typography.bodySmall,
            color = JarvisColors.OnSurfaceMuted,
        )

        is UpdateStatus.UpToDate -> Text(
            text = stringResource(R.string.update_up_to_date),
            style = MaterialTheme.typography.bodySmall,
            color = JarvisColors.Success,
        )

        is UpdateStatus.Available -> Column {
            Text(
                text = stringResource(R.string.update_available, status.versionName),
                style = MaterialTheme.typography.bodySmall,
                color = JarvisColors.Primary,
            )
            if (status.notes.isNotBlank()) {
                Text(
                    text = status.notes.lineSequence().take(4).joinToString(separator = "\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisColors.OnSurfaceMuted,
                )
            }
            TextButton(onClick = { onInstall(status) }) {
                Text(
                    text = stringResource(R.string.update_download),
                    color = JarvisColors.Primary,
                )
            }
        }

        is UpdateStatus.Downloading -> Text(
            text = status.progress?.let {
                stringResource(R.string.update_downloading_percent, (it * 100).toInt())
            } ?: stringResource(R.string.update_downloading),
            style = MaterialTheme.typography.bodySmall,
            color = JarvisColors.OnSurfaceMuted,
        )

        is UpdateStatus.ReadyToInstall -> Text(
            text = stringResource(R.string.update_ready),
            style = MaterialTheme.typography.bodySmall,
            color = JarvisColors.Success,
        )

        is UpdateStatus.Failed -> Column {
            Text(
                text = status.message,
                style = MaterialTheme.typography.bodySmall,
                color = JarvisColors.Error,
            )
            Row {
                TextButton(onClick = onGrantPermission) {
                    Text(
                        text = stringResource(R.string.update_grant_install),
                        color = JarvisColors.Primary,
                    )
                }
                TextButton(onClick = onOpenReleases) {
                    Text(
                        text = stringResource(R.string.update_open_releases),
                        color = JarvisColors.OnSurfaceMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    reason: String,
    granted: Boolean,
    onOpen: () -> Unit,
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
                TextButton(onClick = onOpen) {
                    Text(
                        text = stringResource(R.string.permission_open_settings),
                        color = JarvisColors.Primary,
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            AssistChip(
                onClick = { onSelect(value) },
                label = { Text(text = label, style = MaterialTheme.typography.bodySmall) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (isSelected) {
                        JarvisColors.Primary.copy(alpha = 0.18f)
                    } else {
                        JarvisColors.SurfaceElevated
                    },
                    labelColor = if (isSelected) JarvisColors.Primary else JarvisColors.OnSurfaceMuted,
                ),
                border = AssistChipDefaults.assistChipBorder(
                    enabled = true,
                    borderColor = if (isSelected) {
                        JarvisColors.Primary.copy(alpha = 0.55f)
                    } else {
                        JarvisColors.Outline
                    },
                ),
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(
            text = "$label  ${String.format(java.util.Locale.US, "%.1f", value)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = JarvisColors.Primary,
                activeTrackColor = JarvisColors.Primary.copy(alpha = 0.7f),
                inactiveTrackColor = JarvisColors.Outline,
            ),
        )
    }
}

@Composable
private fun JarvisSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = JarvisColors.OnPrimary,
            checkedTrackColor = JarvisColors.Primary,
            uncheckedThumbColor = JarvisColors.OnSurfaceMuted,
            uncheckedTrackColor = JarvisColors.SurfaceElevated,
        ),
    )
}

@Composable
private fun jarvisFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = JarvisColors.Primary.copy(alpha = 0.6f),
    unfocusedBorderColor = JarvisColors.Outline,
    focusedContainerColor = JarvisColors.Surface,
    unfocusedContainerColor = JarvisColors.Surface,
    cursorColor = JarvisColors.Primary,
    focusedLabelColor = JarvisColors.Primary,
    unfocusedLabelColor = JarvisColors.OnSurfaceMuted,
)

private const val MAX_VOICE_CHIPS = 6
