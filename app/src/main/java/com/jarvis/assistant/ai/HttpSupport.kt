package com.jarvis.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The one HTTP call every provider makes, so that timeouts, error shapes and
 * cancellation behave identically no matter which backend is selected.
 */
sealed interface HttpOutcome {
    data class Ok(val body: JsonObject) : HttpOutcome
    data class HttpError(val code: Int, val body: String) : HttpOutcome
    data class Transport(val message: String) : HttpOutcome
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

suspend fun OkHttpClient.postJson(
    url: String,
    body: JsonObject,
    headers: Map<String, String> = emptyMap(),
    json: Json,
): HttpOutcome = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(url)
        .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
        .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
        .build()

    try {
        newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext HttpOutcome.HttpError(response.code, text)
            }
            val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return@withContext HttpOutcome.Transport(
                    "The provider returned something that is not JSON.",
                )
            HttpOutcome.Ok(parsed)
        }
    } catch (e: IOException) {
        // Covers no connection, DNS failure, timeouts and a wrong Ollama address.
        HttpOutcome.Transport(e.message ?: "Network error")
    }
}

/** Used only by the Ollama reachability check, which is a plain GET. */
suspend fun OkHttpClient.getJson(
    url: String,
    json: Json,
    headers: Map<String, String> = emptyMap(),
): HttpOutcome = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(url)
        .get()
        .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
        .build()

    try {
        newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext HttpOutcome.HttpError(response.code, text)
            }
            val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return@withContext HttpOutcome.Transport(
                    "The server returned something that is not JSON. Is that address really Ollama?",
                )
            HttpOutcome.Ok(parsed)
        }
    } catch (e: IOException) {
        HttpOutcome.Transport(e.message ?: "Network error")
    } catch (e: IllegalArgumentException) {
        // A malformed base URL reaches OkHttp as an argument error, not an IOException.
        HttpOutcome.Transport("\"$url\" is not a valid address.")
    }
}
