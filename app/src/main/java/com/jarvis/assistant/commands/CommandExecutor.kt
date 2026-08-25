package com.jarvis.assistant.commands

import android.content.Context
import android.util.Log
import com.jarvis.assistant.R
import com.jarvis.assistant.accessibility.AccessibilityController
import com.jarvis.assistant.core.ConfirmationRequest
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.core.JarvisStateMachine
import com.jarvis.assistant.core.Language
import com.jarvis.assistant.core.ToolCall
import com.jarvis.assistant.data.repo.CommandLogRepository
import com.jarvis.assistant.data.repo.LogStage
import com.jarvis.assistant.utils.LocalizedStrings
import com.jarvis.assistant.utils.NetworkMonitor
import com.jarvis.assistant.utils.PermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one tool call, with every gate applied in order.
 *
 * The order is the point. Availability is checked before permissions,
 * permissions before confirmation, and confirmation before the tool ever runs,
 * so a dangerous action cannot slip through because an earlier check happened to
 * pass. The dangerous flag is read from the registered [ToolSpec], never from
 * anything the model sent, which is what makes it unbypassable.
 */
@Singleton
class CommandExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ToolRegistry,
    private val gate: ConfirmationGate,
    private val permissions: PermissionManager,
    private val accessibility: AccessibilityController,
    private val network: NetworkMonitor,
    private val stateMachine: JarvisStateMachine,
    private val commandLog: CommandLogRepository,
    private val strings: LocalizedStrings,
) {

    suspend fun execute(
        call: ToolCall,
        language: Language,
        turnId: String,
        step: Int = 1,
        total: Int = 1,
    ): ToolResult {
        val tool = registry.get(call.name) ?: return logged(
            turnId,
            call,
            ToolResult.Failure("There is no tool called \"${call.name}\"."),
        )

        stateMachine.transition(
            JarvisState.Executing(label = describe(tool.spec, call, language), step = step, total = total),
        )
        commandLog.log(turnId, LogStage.TOOL, call.name, call.arguments.toString())

        preflight(tool.spec, language)?.let { return logged(turnId, call, it) }

        if (tool.spec.isDangerous) {
            val approved = gate.request(confirmationFor(tool.spec, call, language))
            if (!approved) {
                return logged(
                    turnId,
                    call,
                    ToolResult.Cancelled("The user declined, so nothing was done."),
                )
            }
            // The state machine was moved to Processing by the gate; put the
            // execution label back so the orb does not sit blank while the
            // action actually happens.
            stateMachine.transition(
                JarvisState.Executing(describe(tool.spec, call, language), step, total),
            )
        }

        val result = runCatching { tool.execute(call) }.getOrElse { error ->
            Log.e(TAG, "Tool ${call.name} threw", error)
            ToolResult.Failure("${call.name} failed unexpectedly: ${error.message ?: error::class.simpleName}")
        }

        if (result is ToolResult.NotSupported) {
            result.fallbackIntent?.let { intent ->
                // Opening the fallback screen before speaking means the right
                // panel is already up by the time JARVIS explains the limit.
                runCatching { context.startActivity(intent) }
                    .onFailure { Log.w(TAG, "Fallback intent for ${call.name} could not be started", it) }
            }
        }

        return logged(turnId, call, result)
    }

    /**
     * Everything that would make the call fail for a reason the user can act on.
     * Returned as a result rather than thrown so the model gets a usable
     * explanation and can adapt its plan.
     */
    private fun preflight(spec: ToolSpec, language: Language): ToolResult? {
        if (spec.requiresAccessibility && !accessibility.isConnected.value) {
            return ToolResult.RequiresPermission(
                permission = com.jarvis.assistant.commands.tools.NavigationTools.ACCESSIBILITY,
                rationale = strings.get(language, R.string.error_needs_accessibility),
            )
        }

        if (spec.requiresNetwork && !network.currentlyOnline()) {
            return ToolResult.NotSupported(strings.get(language, R.string.error_no_network))
        }

        val missing = permissions.missing(spec.permissions)
        if (missing.isNotEmpty()) {
            return ToolResult.RequiresPermission(
                permission = missing.first(),
                rationale = strings.get(
                    language,
                    R.string.error_needs_permission,
                    friendlyPermissionName(missing.first()),
                ),
            )
        }
        return null
    }

    private fun confirmationFor(
        spec: ToolSpec,
        call: ToolCall,
        language: Language,
    ): ConfirmationRequest {
        val target = call.string("name") ?: call.string("number") ?: "?"
        return when (spec.name) {
            "make_call" -> ConfirmationRequest(
                toolName = spec.name,
                title = strings.get(language, R.string.confirm_title),
                body = strings.get(language, R.string.confirm_call_message, target),
                confirmLabel = strings.get(language, R.string.confirm_call),
            )

            "send_sms" -> ConfirmationRequest(
                toolName = spec.name,
                title = strings.get(language, R.string.confirm_title),
                // The message body is shown in full: confirming a message you
                // cannot read is not consent.
                body = strings.get(language, R.string.confirm_sms_message, target) +
                    "\n\n" + (call.string("message") ?: ""),
                confirmLabel = strings.get(language, R.string.confirm_send),
            )

            else -> ConfirmationRequest(
                toolName = spec.name,
                title = strings.get(language, R.string.confirm_title),
                body = "${spec.name} ${call.arguments}",
                confirmLabel = strings.get(language, R.string.confirm_ok),
            )
        }
    }

    /** The line shown under the orb while this runs. */
    private fun describe(spec: ToolSpec, call: ToolCall, language: Language): String =
        when (spec.name) {
            "open_app" -> executingLabel(language, call.string("app"))
            "search_web" -> executingLabel(language, call.string("query"))
            "click_text" -> executingLabel(language, call.string("text"))
            else -> spec.name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }

    private fun executingLabel(language: Language, subject: String?): String {
        val base = strings.get(language, R.string.state_executing)
        return if (subject.isNullOrBlank()) base else "$base $subject"
    }

    private suspend fun logged(turnId: String, call: ToolCall, result: ToolResult): ToolResult {
        commandLog.log(
            turnId = turnId,
            stage = LogStage.RESULT,
            label = call.name,
            detail = result.summary,
            success = result.isSuccess,
        )
        return result
    }

    private fun friendlyPermissionName(permission: String): String =
        permission.substringAfterLast('.').replace('_', ' ').lowercase()

    private companion object {
        const val TAG = "CommandExecutor"
    }
}
