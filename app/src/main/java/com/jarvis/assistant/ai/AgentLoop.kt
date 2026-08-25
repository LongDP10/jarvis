package com.jarvis.assistant.ai

import android.util.Log
import com.jarvis.assistant.R
import com.jarvis.assistant.accessibility.AccessibilityController
import com.jarvis.assistant.commands.CommandExecutor
import com.jarvis.assistant.commands.ToolRegistry
import com.jarvis.assistant.commands.ToolResult
import com.jarvis.assistant.core.ChatMessage
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.core.JarvisStateMachine
import com.jarvis.assistant.core.Language
import com.jarvis.assistant.core.MessageRole
import com.jarvis.assistant.core.ToolCall
import com.jarvis.assistant.data.repo.CommandLogRepository
import com.jarvis.assistant.data.repo.ConversationRepository
import com.jarvis.assistant.data.repo.LogStage
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.utils.LocalizedStrings
import com.jarvis.assistant.utils.NetworkMonitor
import com.jarvis.assistant.utils.TextNormalizer
import com.jarvis.assistant.voice.LanguageDetector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What JARVIS says back, and in which language, so the caller knows how to speak it.
 */
data class AgentOutcome(
    val reply: String,
    val language: Language,
    val handledOffline: Boolean,
    val toolsRun: Int,
)

/**
 * The brain: takes what the user said and produces what JARVIS says back, having
 * done whatever needed doing along the way.
 *
 * Two paths. The offline matcher gets first refusal, because most commands are
 * simple and paying a network round trip for "turn the volume up" is absurd.
 * Everything else goes to the model in a bounded tool-calling loop.
 *
 * The loop is what makes multi-step work. The model calls a tool, the result is
 * fed back, and it decides what to do next with the screen contents in hand. It
 * is capped at [MAX_ITERATIONS] and then forced to answer, because a model that
 * has misunderstood will otherwise keep calling tools until the bill or the
 * battery runs out.
 */
@Singleton
class AgentLoop @Inject constructor(
    private val matcher: LocalIntentMatcher,
    private val providers: AIProviderFactory,
    private val registry: ToolRegistry,
    private val executor: CommandExecutor,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
    private val stateMachine: JarvisStateMachine,
    private val commandLog: CommandLogRepository,
    private val languageDetector: LanguageDetector,
    private val accessibility: AccessibilityController,
    private val spoken: SpokenResponses,
    private val strings: LocalizedStrings,
) {

    suspend fun run(userText: String): AgentOutcome {
        val current = settings.current()
        val language = current.language.resolve(languageDetector.detect(userText))
        val turnId = commandLog.newTurnId()
        commandLog.log(turnId, LogStage.VOICE, "user", userText)

        val conversationId = conversations.activeConversation(language)
        conversations.append(conversationId, ChatMessage(MessageRole.USER, userText))

        matcher.match(userText, language)?.let { call ->
            commandLog.log(turnId, LogStage.AI, "local_intent", call.name)
            val result = executor.execute(call, language, turnId)
            val reply = spoken.forResult(call, result, language)
            conversations.append(conversationId, ChatMessage(MessageRole.ASSISTANT, reply))
            return AgentOutcome(reply, language, handledOffline = true, toolsRun = 1)
        }

        return runModel(userText, conversationId, language, turnId)
    }

    private suspend fun runModel(
        userText: String,
        conversationId: Long,
        language: Language,
        turnId: String,
    ): AgentOutcome {
        val online = network.currentlyOnline()
        val provider = providers.current()

        // Reachability is asked of the provider, not of the internet. A local
        // model server sits on the LAN, so a Wi-Fi network with no upstream
        // connectivity -- which Android reports as offline -- is perfectly fine
        // for it, and refusing here would disable the fully-local setup entirely.
        if (provider.requiresInternet && !online) {
            // The local matcher already had its chance, so reaching here offline
            // means the request genuinely needs a model.
            return fail(conversationId, language, strings.get(language, R.string.error_no_network))
        }
        if (!provider.isConfigured()) {
            // "No API key" is the wrong sentence for a local server; what is
            // missing there is an address.
            val message = if (provider.id.requiresApiKey) {
                strings.get(language, R.string.error_no_api_key)
            } else {
                strings.get(language, R.string.error_no_ollama_server)
            }
            return fail(conversationId, language, message)
        }

        stateMachine.transition(JarvisState.Processing(strings.get(language, R.string.state_thinking)))

        val model = settings.current().modelForCurrentProvider
        // Still keyed off actual connectivity rather than the provider: a local
        // model planning the command does not make search_web work on a network
        // with no route out.
        val toolSpecs = registry.specsFor(online)
        var screenContext = if (needsScreenContext(userText)) readScreen() else null
        var toolsRun = 0

        repeat(MAX_ITERATIONS) { iteration ->
            val request = AIRequest(
                systemPrompt = SystemPrompts.forLanguage(language, online),
                messages = conversations.contextWindow(conversationId),
                // On the final permitted iteration the tools are withheld, which
                // forces a spoken answer instead of yet another call.
                toolSpecs = if (iteration == MAX_ITERATIONS - 1) emptyList() else toolSpecs,
                model = model,
                screenContext = screenContext,
            )
            screenContext = null

            when (val response = provider.chat(request)) {
                is AIResponse.Text -> {
                    commandLog.log(turnId, LogStage.AI, provider.id.storageKey, response.content)
                    conversations.append(
                        conversationId,
                        ChatMessage(MessageRole.ASSISTANT, response.content),
                    )
                    return AgentOutcome(response.content, language, false, toolsRun)
                }

                is AIResponse.ToolCalls -> {
                    commandLog.log(
                        turnId,
                        LogStage.AI,
                        provider.id.storageKey,
                        response.calls.joinToString { it.name },
                    )
                    // The assistant turn is recorded WITH its tool calls, before
                    // any of them runs. Every provider requires the calls to be
                    // present in history for the results to attach to: Anthropic
                    // rejects a tool_result whose tool_use_id it never issued,
                    // and OpenAI rejects a tool message that does not follow an
                    // assistant message carrying tool_calls.
                    conversations.append(
                        conversationId,
                        ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = response.assistantText.orEmpty(),
                            toolCalls = response.calls,
                        ),
                    )

                    var changedScreen = false
                    response.calls.forEachIndexed { index, call ->
                        val result = executor.execute(
                            call = call,
                            language = language,
                            turnId = turnId,
                            step = index + 1,
                            total = response.calls.size,
                        )
                        toolsRun++
                        conversations.append(
                            conversationId,
                            ChatMessage(
                                role = MessageRole.TOOL,
                                content = result.summary,
                                toolName = call.name,
                                toolCallId = call.id,
                            ),
                        )
                        if (call.name in SCREEN_CHANGING_TOOLS) changedScreen = true

                        // The user said no. Stop the whole chain rather than
                        // letting the model work around the refusal.
                        if (result is ToolResult.Cancelled) {
                            val reply = strings.get(language, R.string.error_cancelled)
                            conversations.append(
                                conversationId,
                                ChatMessage(MessageRole.ASSISTANT, reply),
                            )
                            return AgentOutcome(reply, language, false, toolsRun)
                        }
                    }

                    if (changedScreen) {
                        // Give the app a moment to draw before reading it, or the
                        // dump describes the screen we just left.
                        kotlinx.coroutines.delay(SCREEN_SETTLE_MS)
                        screenContext = readScreen()
                    }
                    stateMachine.transition(
                        JarvisState.Processing(strings.get(language, R.string.state_thinking)),
                    )
                }

                is AIResponse.Error -> {
                    Log.w(TAG, "Provider error: ${response.message}")
                    commandLog.log(
                        turnId,
                        LogStage.ERROR,
                        provider.id.storageKey,
                        response.message,
                        success = false,
                    )
                    if (response.retryable && iteration < MAX_ITERATIONS - 2) {
                        kotlinx.coroutines.delay(RETRY_DELAY_MS)
                        return@repeat
                    }
                    return fail(
                        conversationId,
                        language,
                        strings.get(language, R.string.error_provider, response.message),
                    )
                }
            }
        }

        return fail(conversationId, language, strings.get(language, R.string.error_not_understood))
    }

    /**
     * Whether the user's phrasing points at something they can see. Sending the
     * node dump on every turn would cost tokens on the large majority of
     * commands that do not need it.
     */
    private fun needsScreenContext(text: String): Boolean {
        val normalised = TextNormalizer.normalise(text)
        return SystemPrompts.DEICTIC_MARKERS.any { normalised.contains(it) }
    }

    private suspend fun readScreen(): String? {
        if (!accessibility.isConnected.value) return null
        val snapshot = accessibility.readScreen()
        return if (snapshot.isEmpty) null else snapshot.toPromptText()
    }

    private suspend fun fail(
        conversationId: Long,
        language: Language,
        message: String,
    ): AgentOutcome {
        conversations.append(conversationId, ChatMessage(MessageRole.ASSISTANT, message))
        return AgentOutcome(message, language, handledOffline = false, toolsRun = 0)
    }

    private companion object {
        const val TAG = "AgentLoop"
        const val MAX_ITERATIONS = 6
        const val SCREEN_SETTLE_MS = 900L
        const val RETRY_DELAY_MS = 1200L

        /** After these, what is on screen is no longer what the model last saw. */
        val SCREEN_CHANGING_TOOLS = setOf(
            "open_app", "search_web", "open_url", "open_settings", "open_camera",
            "open_gallery", "go_home", "go_back", "open_recents",
            "open_notifications", "open_quick_settings", "click_text", "tap",
            "scroll", "swipe", "input_text", "long_press",
        )
    }
}
