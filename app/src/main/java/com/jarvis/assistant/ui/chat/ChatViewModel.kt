package com.jarvis.assistant.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.core.ChatMessage
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.data.repo.ConversationRepository
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.session.JarvisController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val controller: JarvisController,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val jarvisState: StateFlow<JarvisState> = controller.state

    private val conversationId = MutableStateFlow<Long?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<ChatMessage>> = conversationId
        .filterNotNull()
        .flatMapLatest { id -> conversations.observeMessages(id) }
        // Tool results are part of the model's context but not part of the
        // conversation a person is having, so they stay out of the transcript.
        .map { list -> list.filter { it.role != com.jarvis.assistant.core.MessageRole.TOOL } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val language = settings.current().language.resolve()
            conversationId.value = conversations.activeConversation(language)
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        controller.submitText(text)
    }

    fun startVoice() = controller.startVoiceCommand()

    fun cancel() = controller.cancel()
}
