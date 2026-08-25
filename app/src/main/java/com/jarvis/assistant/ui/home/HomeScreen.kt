package com.jarvis.assistant.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.R
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.overlay.OrbCanvas
import com.jarvis.assistant.ui.common.OrbAction
import com.jarvis.assistant.ui.theme.JarvisColors

/**
 * The home screen is the orb.
 *
 * There is no message list here and no chat affordance in the centre, because
 * the product is voice-first: the orb is the control, and everything else is a
 * shortcut around the edge. Reading state off the same
 * [com.jarvis.assistant.core.JarvisStateMachine] the overlay uses means walking
 * between the two never shows a discontinuity.
 */
@Composable
fun HomeScreen(
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDebug: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.jarvisState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var micGranted by remember { mutableStateOf(viewModel.permissionSnapshot().microphoneGranted) }
    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        // Only start listening if this request was the thing standing in the way.
        if (granted) viewModel.startListening()
    }

    LaunchedEffect(Unit) {
        micGranted = viewModel.permissionSnapshot().microphoneGranted
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        JarvisColors.Surface,
                        JarvisColors.Background,
                    ),
                    radius = 1400f,
                ),
            )
            .systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayLarge,
                color = JarvisColors.OnBackground,
            )

            Spacer(modifier = Modifier.weight(1f))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.clickable {
                    if (micGranted) {
                        viewModel.onOrbTapped()
                    } else {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            ) {
                OrbCanvas(state = state, size = 260.dp)
            }

            Spacer(modifier = Modifier.height(26.dp))

            StatusCaption(state = state)

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                OrbAction(
                    icon = Icons.Filled.Mic,
                    label = stringResource(R.string.home_voice),
                    onClick = {
                        if (micGranted) {
                            viewModel.startListening()
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
                OrbAction(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    label = stringResource(R.string.chat_title),
                    onClick = onOpenChat,
                )
                OrbAction(
                    icon = Icons.Filled.History,
                    label = stringResource(R.string.home_history),
                    onClick = onOpenHistory,
                )
                OrbAction(
                    icon = Icons.Filled.Settings,
                    label = stringResource(R.string.home_settings),
                    onClick = onOpenSettings,
                )
                AnimatedVisibility(
                    visible = settings.debugLogEnabled,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    OrbAction(
                        icon = Icons.Filled.BugReport,
                        label = stringResource(R.string.debug_title),
                        onClick = onOpenDebug,
                    )
                }
            }
        }
    }
}

/**
 * One line under the orb saying what is happening. Shows the live transcript
 * while listening, which is the clearest possible signal that the microphone is
 * genuinely open and hearing the user.
 */
@Composable
private fun StatusCaption(state: JarvisState) {
    val text = when (state) {
        is JarvisState.Idle -> stringResource(R.string.home_greeting)
        is JarvisState.Wake -> stringResource(R.string.state_listening)
        is JarvisState.Listening ->
            state.partial.ifBlank { stringResource(R.string.state_listening) }
        is JarvisState.Processing -> state.label ?: stringResource(R.string.state_thinking)
        is JarvisState.Executing -> state.label
        is JarvisState.Speaking -> state.text
        is JarvisState.ConfirmationRequired -> state.request.body
        is JarvisState.WaitingForUser -> state.question
        is JarvisState.Error -> state.message
        is JarvisState.Cancelled -> stringResource(R.string.state_cancelled)
    }

    val tint = when (state) {
        is JarvisState.Error -> JarvisColors.Error
        is JarvisState.Idle -> JarvisColors.OnSurfaceMuted
        else -> JarvisColors.OnBackground
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = tint,
            textAlign = TextAlign.Center,
        )
        if (state is JarvisState.Idle) {
            Text(
                text = stringResource(R.string.home_tap_to_speak),
                style = MaterialTheme.typography.bodySmall,
                color = JarvisColors.OnSurfaceMuted.copy(alpha = 0.7f),
            )
        }
    }
}
