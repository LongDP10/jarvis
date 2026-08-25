package com.jarvis.assistant.commands

import com.jarvis.assistant.core.ToolCall

enum class ParamType { STRING, INTEGER, NUMBER, BOOLEAN }

data class ToolParam(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = true,
    /** Non-empty turns this into a closed set the model must choose from. */
    val allowedValues: List<String> = emptyList(),
)

/**
 * Everything a provider needs in order to describe a tool to a model, and
 * everything the executor needs in order to decide whether it may run.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList(),
    /** Runtime permissions that must be granted first. */
    val permissions: List<String> = emptyList(),
    /**
     * Requires the user's explicit confirmation every single time. The executor
     * enforces this; a tool cannot opt itself out.
     */
    val isDangerous: Boolean = false,
    val requiresAccessibility: Boolean = false,
    val requiresNetwork: Boolean = false,
    /** Works with no connection, so the offline path may still offer it. */
    val worksOffline: Boolean = true,
)

interface Tool {
    val spec: ToolSpec
    suspend fun execute(call: ToolCall): ToolResult
}

/** A related set of tools, registered together. */
interface ToolGroup {
    val tools: List<Tool>
}

/**
 * Small helper so individual tools stay a few lines each instead of repeating
 * the spec boilerplate.
 */
class SimpleTool(
    override val spec: ToolSpec,
    private val action: suspend (ToolCall) -> ToolResult,
) : Tool {
    override suspend fun execute(call: ToolCall): ToolResult = action(call)
}

fun tool(
    name: String,
    description: String,
    params: List<ToolParam> = emptyList(),
    permissions: List<String> = emptyList(),
    isDangerous: Boolean = false,
    requiresAccessibility: Boolean = false,
    requiresNetwork: Boolean = false,
    worksOffline: Boolean = true,
    action: suspend (ToolCall) -> ToolResult,
): Tool = SimpleTool(
    ToolSpec(
        name = name,
        description = description,
        params = params,
        permissions = permissions,
        isDangerous = isDangerous,
        requiresAccessibility = requiresAccessibility,
        requiresNetwork = requiresNetwork,
        worksOffline = worksOffline,
    ),
    action,
)
