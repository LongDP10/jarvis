package com.jarvis.assistant.ai

import com.jarvis.assistant.core.ProviderId
import com.jarvis.assistant.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AIProviderFactory @Inject constructor(
    private val gemini: GeminiProvider,
    private val openAi: OpenAIProvider,
    private val anthropic: AnthropicProvider,
    private val ollama: OllamaProvider,
    private val settings: SettingsRepository,
) {

    fun byId(id: ProviderId): AIProvider = when (id) {
        ProviderId.GEMINI -> gemini
        ProviderId.OPENAI -> openAi
        ProviderId.ANTHROPIC -> anthropic
        ProviderId.OLLAMA -> ollama
    }

    suspend fun current(): AIProvider = byId(settings.current().provider)

    fun all(): List<AIProvider> = listOf(gemini, openAi, anthropic, ollama)
}
