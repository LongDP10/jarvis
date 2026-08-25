package com.jarvis.assistant.ai

import com.jarvis.assistant.core.ProviderId
import com.jarvis.assistant.data.secure.SecureKeyStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anthropic's Messages API.
 *
 * Raw HTTP rather than the official Java SDK, matching the other providers.
 * The SDK would be the usual choice, but it brings Jackson into an APK that
 * already ships kotlinx.serialization, and it would make this the only provider
 * whose mapping could not be unit tested the way the other three are. The wire
 * format here is taken verbatim from Anthropic's documented request shape.
 */
@Singleton
class AnthropicProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val keyStore: SecureKeyStore,
) : AIProvider {

    override val id: ProviderId = ProviderId.ANTHROPIC

    override suspend fun isConfigured(): Boolean = keyStore.getApiKey(id) != null

    override suspend fun chat(request: AIRequest): AIResponse {
        val apiKey = keyStore.getApiKey(id)
            ?: return AIResponse.Error("No Anthropic API key is set. Add one in Settings.")

        val outcome = client.postJson(
            url = AnthropicMapper.ENDPOINT,
            body = AnthropicMapper.buildRequestBody(request),
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to AnthropicMapper.API_VERSION,
                "anthropic-beta" to AnthropicMapper.FALLBACK_BETA,
            ),
            json = json,
        )

        return when (outcome) {
            is HttpOutcome.Ok -> AnthropicMapper.parseResponse(outcome.body)

            is HttpOutcome.HttpError -> AnthropicMapper.httpErrorFor(
                code = outcome.code,
                body = outcome.body,
                parsed = runCatching {
                    json.parseToJsonElement(outcome.body).jsonObject
                }.getOrNull(),
            )

            is HttpOutcome.Transport -> AIResponse.Error(outcome.message, retryable = true)
        }
    }
}
