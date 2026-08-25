package com.jarvis.assistant.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.R
import com.jarvis.assistant.data.repo.Conversation
import com.jarvis.assistant.data.repo.ConversationRepository
import com.jarvis.assistant.ui.common.GlassCard
import com.jarvis.assistant.ui.common.JarvisTopBar
import com.jarvis.assistant.ui.theme.JarvisColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val conversations: ConversationRepository,
) : ViewModel() {

    val history: StateFlow<List<Conversation>> = conversations.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() = viewModelScope.launch { conversations.deleteAll() }

    fun delete(id: Long) = viewModelScope.launch { conversations.delete(id) }
}

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val history by viewModel.history.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisColors.Background)
            .systemBarsPadding(),
    ) {
        JarvisTopBar(
            title = stringResource(R.string.history_title),
            onBack = onBack,
            actions = {
                if (history.isNotEmpty()) {
                    TextButton(onClick = viewModel::clear) {
                        Text(
                            text = stringResource(R.string.history_clear),
                            color = JarvisColors.Error,
                        )
                    }
                }
            },
        )

        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = JarvisColors.OnSurfaceMuted,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(history, key = { it.id }) { conversation ->
                    GlassCard {
                        Text(
                            text = conversation.title.ifBlank {
                                stringResource(R.string.history_empty)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "${formatTimestamp(conversation.updatedAt)} · " +
                                conversation.language.tag,
                            style = MaterialTheme.typography.bodySmall,
                            color = JarvisColors.OnSurfaceMuted,
                        )
                        TextButton(
                            onClick = { viewModel.delete(conversation.id) },
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.debug_clear),
                                color = JarvisColors.Error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
