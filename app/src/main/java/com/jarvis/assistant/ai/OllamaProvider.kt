package com.jarvis.assistant.ai

import com.jarvis.assistant.core.ProviderId
import com.jarvis.assistant.data.settings.SettingsRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of a reachability check against the configured server. */
sealed interface OllamaStatus {
    data class Ok(val models: List<String>) : OllamaStatus
    data class Failed(val message: String) : OllamaStatus
}

/**
 * Talks to an Ollama server on the user's own network.
 *
 * The one configuration in which JARVIS can plan a multi-step command with no
 * data leaving the house. It shares OpenAI's tool vocabulary but not its wire
 * format, and tool support depends on the model being run -- a model without it
 * will simply answer in prose, which the agent loop handles as a normal text
 * reply.
 */
@Singleton
class OllamaProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) : AIProvider {

    override val id: ProviderId = ProviderId.OLLAMA

    /** Runs on the LAN, so upstream internet is irrelevant to reaching it. */
    override val requiresInternet: Boolean = false

    /**
     * A local model on CPU can take well over a minute to answer its first
     * request while the weights are paged in. The shared client's 60s read
     * timeout is right for a cloud API and far too short here, so this one
     * derives a more patient copy -- sharing the same connection pool and
     * dispatcher, so it costs nothing.
     */
    private val localClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(LOCAL_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(LOCAL_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun isConfigured(): Boolean =
        settings.current().ollamaBaseUrl.isNotBlank()

    /**
     * Asks the server which models it has pulled.
     *
     * Exists because the commonest Ollama failure is not a bug in the app: it is
     * a wrong IP address, a server bound to 127.0.0.1 instead of 0.0.0.0, or a
     * model name that was never pulled. Settings uses this to tell those three
     * apart instead of leaving the user guessing at a timeout.
     */
    suspend fun listModels(): OllamaStatus {
        val baseUrl = settings.current().ollamaBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) return OllamaStatus.Failed("No server address is set.")

        return when (val outcome = localClient.getJson("$baseUrl/api/tags", json)) {
            is HttpOutcome.Ok -> {
                val models = outcome.body["models"]?.jsonArray.orEmpty().mapNotNull {
                    it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                }
                OllamaStatus.Ok(models)
            }

            is HttpOutcome.HttpError -> OllamaStatus.Failed(
                "Server answered with HTTP ${outcome.code}.",
            )

            is HttpOutcome.Transport -> OllamaStatus.Failed(
                "Could not reach $baseUrl. Check the address, and that Ollama was started with " +
                    "OLLAMA_HOST=0.0.0.0 so it accepts connections from the network rather than " +
                    "only from the machine it runs on.",
            )
        }
    }

    override suspend fun chat(request: AIRequest): AIResponse {
        val baseUrl = settings.current().ollamaBaseUrl.trimEnd('/')
        if (baseUrl.isBlank()) {
            return AIResponse.Error("No Ollama server address is set. Add one in Settings.")
        }

        val outcome = localClient.postJson(
            url = "$baseUrl/api/chat",
            body = OllamaMapper.buildRequestBody(request),
            json = json,
        )

        return when (outcome) {
            is HttpOutcome.Ok -> OllamaMapper.parseResponse(outcome.body)
            is HttpOutcome.HttpError -> AIResponse.Error(
                "Ollama returned HTTP ${outcome.code}. Check the model name is pulled on that server.",
                retryable = outcome.code >= 500,
            )
            is HttpOutcome.Transport -> AIResponse.Error(
                "Could not reach the Ollama server at $baseUrl (${outcome.message}). " +
                    "Check the phone and the server are on the same network.",
                retryable = true,
            )
        }
    }

    private companion object {
        const val LOCAL_READ_TIMEOUT_SECONDS = 180L
    }
}
