package com.jarvis.assistant.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.R
import com.jarvis.assistant.core.ChatMessage
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.core.MessageRole
import com.jarvis.assistant.core.isBusy
import com.jarvis.assistant.overlay.OrbCanvas
import com.jarvis.assistant.ui.common.JarvisTopBar
import com.jarvis.assistant.ui.theme.JarvisColors

/**
 * Text entry for the times speaking out loud is not an option.
 *
 * Deliberately secondary to the orb, and deliberately the same pipeline: typed
 * input goes through the identical agent loop, so anything JARVIS can do by
 * voice it can do here, including tool calls and confirmations.
 */
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val state by viewModel.jarvisState.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisColors.Background)
            .systemBarsPadding()
            .imePadding(),
    ) {
        JarvisTopBar(title = stringResource(R.string.chat_title), onBack = onBack)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            OrbCanvas(state = state, size = 84.dp)
        }

        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.chat_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = JarvisColors.OnSurfaceMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages) { message -> MessageBubble(message) }
            }
        }

        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            busy = state.isBusy,
            onSend = {
                viewModel.send(draft)
                draft = ""
            },
            onVoice = { viewModel.startVoice() },
            onStop = { viewModel.cancel() },
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val fromUser = message.role == MessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            text = message.content,
            style = MaterialTheme.typography.bodyMedium,
            color = if (fromUser) JarvisColors.OnPrimary else JarvisColors.OnBackground,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (fromUser) 16.dp else 4.dp,
                        bottomEnd = if (fromUser) 4.dp else 16.dp,
                    ),
                )
                .background(
                    if (fromUser) JarvisColors.Primary else JarvisColors.SurfaceElevated,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(text = stringResource(R.string.chat_hint)) },
            shape = RoundedCornerShape(22.dp),
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = JarvisColors.Primary.copy(alpha = 0.6f),
                unfocusedBorderColor = JarvisColors.Outline,
                focusedContainerColor = JarvisColors.Surface,
                unfocusedContainerColor = JarvisColors.Surface,
                cursorColor = JarvisColors.Primary,
            ),
        )

        // While JARVIS is working the same button becomes Stop, so there is
        // always a way to interrupt without hunting for one.
        if (busy) {
            IconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.action_close),
                    tint = JarvisColors.Error,
                )
            }
        } else if (draft.isBlank()) {
            IconButton(onClick = onVoice) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = stringResource(R.string.chat_mic),
                    tint = JarvisColors.Primary,
                )
            }
        } else {
            IconButton(onClick = onSend) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send),
                    tint = JarvisColors.Primary,
                )
            }
        }
    }
}
