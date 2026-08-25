package com.jarvis.assistant.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * One action to perform, whether it came from the offline intent matcher or from
 * a model's tool call. Both paths produce this same shape so the executor has
 * exactly one thing to run.
 */
data class ToolCall(
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val id: String = UUID.randomUUID().toString(),
) {
    fun string(key: String): String? =
        arguments[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    fun int(key: String): Int? = string(key)?.toIntOrNull()

    fun float(key: String): Float? = string(key)?.toFloatOrNull()

    fun boolean(key: String): Boolean? = when (string(key)?.lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> null
    }

    companion object {
        /** Convenience for the offline matcher, which builds flat string args. */
        fun of(name: String, vararg args: Pair<String, String>): ToolCall = ToolCall(
            name = name,
            arguments = buildJsonObject {
                args.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            },
        )
    }
}
