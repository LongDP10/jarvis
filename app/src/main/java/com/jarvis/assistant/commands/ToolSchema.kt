package com.jarvis.assistant.commands

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Turns a [ToolSpec] into the JSON Schema fragment that both OpenAI and Gemini
 * expect for a function declaration.
 *
 * Kept separate from the providers because the schema itself is identical for
 * both; only the envelope around it differs. Kept free of Android so it can be
 * tested directly.
 */
object ToolSchema {

    /** The `parameters` object of a function declaration. */
    fun parameters(spec: ToolSpec): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            spec.params.forEach { param ->
                putJsonObject(param.name) {
                    put("type", param.type.jsonType)
                    put("description", param.description)
                    if (param.allowedValues.isNotEmpty()) {
                        putJsonArray("enum") {
                            param.allowedValues.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                }
            }
        }
        putJsonArray("required") {
            spec.params.filter { it.required }.forEach { add(JsonPrimitive(it.name)) }
        }
    }

    /** OpenAI's chat-completions tool shape. */
    fun openAiTool(spec: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", spec.name)
            put("description", describe(spec))
            put("parameters", parameters(spec))
        }
    }

    /** Gemini's functionDeclarations entry. */
    fun geminiDeclaration(spec: ToolSpec): JsonObject = buildJsonObject {
        put("name", spec.name)
        put("description", describe(spec))
        // Gemini rejects an empty properties object, so a tool with no
        // parameters omits the block entirely rather than sending "{}".
        if (spec.params.isNotEmpty()) {
            put("parameters", parameters(spec))
        }
    }

    fun geminiTools(specs: List<ToolSpec>): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                putJsonArray("functionDeclarations") {
                    specs.forEach { add(geminiDeclaration(it)) }
                }
            },
        )
    }

    /**
     * The description the model sees. Dangerous tools say so in the text as well
     * as in the spec flag, so the model knows a confirmation step is coming and
     * does not treat a cancellation as a failure worth retrying.
     */
    fun describe(spec: ToolSpec): String = buildString {
        append(spec.description)
        if (spec.isDangerous) {
            append(" The user will be asked to confirm before this runs; if they decline, do not retry it.")
        }
    }

    /**
     * Two tools answering to the same name would make dispatch ambiguous and the
     * model's behaviour unpredictable, so this is checked once at startup.
     */
    fun duplicateNames(specs: List<ToolSpec>): List<String> =
        specs.groupBy { it.name }.filterValues { it.size > 1 }.keys.sorted()

    private val ParamType.jsonType: String
        get() = when (this) {
            ParamType.STRING -> "string"
            ParamType.INTEGER -> "integer"
            ParamType.NUMBER -> "number"
            ParamType.BOOLEAN -> "boolean"
        }
}
