package com.jarvis.assistant.commands

import android.util.Log
import com.jarvis.assistant.commands.tools.AppTools
import com.jarvis.assistant.commands.tools.CommunicationTools
import com.jarvis.assistant.commands.tools.InfoTools
import com.jarvis.assistant.commands.tools.MediaTools
import com.jarvis.assistant.commands.tools.NavigationTools
import com.jarvis.assistant.commands.tools.SystemTools
import com.jarvis.assistant.commands.tools.UiTools
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every tool JARVIS can run, and the filtered view of them that a model is told
 * about.
 *
 * Filtering matters: offering a model a tool it cannot use produces a call that
 * fails, an apology, and a retry loop. So when the phone is offline the
 * network-only tools are simply not described, and the model plans around what
 * is genuinely available.
 */
@Singleton
class ToolRegistry @Inject constructor(
    appTools: AppTools,
    navigationTools: NavigationTools,
    mediaTools: MediaTools,
    systemTools: SystemTools,
    uiTools: UiTools,
    communicationTools: CommunicationTools,
    infoTools: InfoTools,
) {

    private val allTools: List<Tool> = listOf(
        appTools,
        navigationTools,
        mediaTools,
        systemTools,
        uiTools,
        communicationTools,
        infoTools,
    ).flatMap { it.tools }

    private val byName: Map<String, Tool> = allTools.associateBy { it.spec.name }

    init {
        val duplicates = ToolSchema.duplicateNames(allTools.map { it.spec })
        // A duplicate name makes dispatch ambiguous. Loud in the log rather than
        // a crash, because losing one tool is better than failing to launch.
        if (duplicates.isNotEmpty()) {
            Log.e(TAG, "Duplicate tool names registered, later ones shadowed: $duplicates")
        }
    }

    fun all(): List<Tool> = allTools

    fun get(name: String): Tool? = byName[name]

    /**
     * The specs to describe to a model.
     *
     * @param online when false, tools that cannot work without a connection are
     *   left out entirely.
     */
    fun specsFor(online: Boolean): List<ToolSpec> =
        allTools.map { it.spec }.filter { online || it.worksOffline }

    /** Names only, for the offline matcher's sanity checks and the debug UI. */
    fun names(): List<String> = allTools.map { it.spec.name }.sorted()

    private companion object {
        const val TAG = "ToolRegistry"
    }
}
