package com.jarvis.assistant.ai

import com.jarvis.assistant.core.ProviderId
import com.jarvis.assistant.data.secure.SecureKeyStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val keyStore: SecureKeyStore,
) : AIProvider {

    override val id: ProviderId = ProviderId.GEMINI

    override suspend fun isConfigured(): Boolean = keyStore.getApiKey(id) != null

    override suspend fun chat(request: AIRequest): AIResponse {
        val apiKey = keyStore.getApiKey(id)
            ?: return AIResponse.Error("No Gemini API key is set. Add one in Settings.")

        val outcome = client.postJson(
            url = GeminiMapper.endpoint(request.model),
            body = GeminiMapper.buildRequestBody(request),
            headers = mapOf("x-goog-api-key" to apiKey),
            json = json,
        )

        return when (outcome) {
            is HttpOutcome.Ok -> GeminiMapper.parseResponse(outcome.body)

            // The error body is still JSON on a failed status, and it carries a
            // far more useful message than the status code alone.
            is HttpOutcome.HttpError -> runCatching {
                GeminiMapper.parseResponse(json.parseToJsonElement(outcome.body).jsonObject)
            }.getOrElse {
                AIResponse.Error(
                    "Gemini returned HTTP ${outcome.code}.",
                    retryable = outcome.code == 429 || outcome.code >= 500,
                )
            }

            is HttpOutcome.Transport -> AIResponse.Error(outcome.message, retryable = true)
        }
    }
}
