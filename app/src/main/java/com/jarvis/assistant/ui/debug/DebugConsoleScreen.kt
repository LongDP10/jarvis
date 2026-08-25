package com.jarvis.assistant.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.R
import com.jarvis.assistant.data.repo.CommandLogEntry
import com.jarvis.assistant.data.repo.CommandLogRepository
import com.jarvis.assistant.data.repo.LogStage
import com.jarvis.assistant.ui.common.JarvisTopBar
import com.jarvis.assistant.ui.theme.JarvisColors
import com.jarvis.assistant.ui.theme.MonoTextStyle
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
class DebugViewModel @Inject constructor(
    private val log: CommandLogRepository,
) : ViewModel() {

    val entries: StateFlow<List<CommandLogEntry>> = log.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() = viewModelScope.launch { log.clear() }
}

/**
 * The VOICE / AI / TOOL / RESULT / TTS trace, in order, for working out why a
 * command did something unexpected.
 *
 * Only populated while the debug log is switched on in settings; the repository
 * drops writes otherwise, so leaving this screen open costs nothing on a normal
 * install.
 */
@Composable
fun DebugConsoleScreen(
    onBack: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisColors.Background)
            .systemBarsPadding(),
    ) {
        JarvisTopBar(
            title = stringResource(R.string.debug_title),
            onBack = onBack,
            actions = {
                if (entries.isNotEmpty()) {
                    TextButton(onClick = viewModel::clear) {
                        Text(
                            text = stringResource(R.string.debug_clear),
                            color = JarvisColors.Error,
                        )
                    }
                }
            },
        )

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.debug_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = JarvisColors.OnSurfaceMuted,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries, key = { it.id }) { entry -> LogRow(entry) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: CommandLogEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(JarvisColors.Surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "[${entry.stage.name}]",
                style = MonoTextStyle,
                color = colourFor(entry.stage, entry.success),
            )
            Text(
                text = entry.label,
                style = MonoTextStyle,
                color = JarvisColors.OnBackground,
            )
            Text(
                text = timestamp(entry.createdAt),
                style = MonoTextStyle,
                color = JarvisColors.OnSurfaceMuted,
            )
        }
        if (entry.detail.isNotBlank()) {
            // Horizontally scrollable rather than wrapped: a JSON argument blob
            // is far easier to scan on one line than reflowed across six.
            Text(
                text = entry.detail,
                style = MonoTextStyle,
                color = JarvisColors.OnSurfaceMuted,
                maxLines = 6,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

private fun colourFor(stage: LogStage, success: Boolean?): Color = when {
    success == false -> JarvisColors.Error
    stage == LogStage.VOICE -> JarvisColors.StateListening
    stage == LogStage.AI -> JarvisColors.StateThinking
    stage == LogStage.TOOL -> JarvisColors.StateExecuting
    stage == LogStage.RESULT -> JarvisColors.Success
    stage == LogStage.TTS -> JarvisColors.StateSpeaking
    else -> JarvisColors.Error
}

private fun timestamp(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))
