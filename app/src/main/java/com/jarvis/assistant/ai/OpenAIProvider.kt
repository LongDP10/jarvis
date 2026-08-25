package com.jarvis.assistant.ai

import com.jarvis.assistant.core.ProviderId
import com.jarvis.assistant.data.secure.SecureKeyStore
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenAIProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val keyStore: SecureKeyStore,
) : AIProvider {

    override val id: ProviderId = ProviderId.OPENAI

    override suspend fun isConfigured(): Boolean = keyStore.getApiKey(id) != null

    override suspend fun chat(request: AIRequest): AIResponse {
        val apiKey = keyStore.getApiKey(id)
            ?: return AIResponse.Error("No OpenAI API key is set. Add one in Settings.")

        val outcome = client.postJson(
            url = OpenAiMapper.ENDPOINT,
            body = OpenAiMapper.buildRequestBody(request),
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            json = json,
        )

        return when (outcome) {
            is HttpOutcome.Ok -> OpenAiMapper.parseResponse(outcome.body)
            is HttpOutcome.HttpError -> OpenAiMapper.httpErrorFor(outcome.code, outcome.body)
            is HttpOutcome.Transport -> AIResponse.Error(outcome.message, retryable = true)
        }
    }
}
