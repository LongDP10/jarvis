package com.jarvis.assistant.commands.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.jarvis.assistant.commands.ParamType
import com.jarvis.assistant.commands.Tool
import com.jarvis.assistant.commands.ToolGroup
import com.jarvis.assistant.commands.ToolParam
import com.jarvis.assistant.commands.ToolResult
import com.jarvis.assistant.commands.tool
import com.jarvis.assistant.utils.PermissionManager
import com.jarvis.assistant.utils.TextNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calling and texting: the two things JARVIS can do that the user cannot undo.
 *
 * Both are marked dangerous, which means [com.jarvis.assistant.commands.CommandExecutor]
 * routes them through the confirmation gate before this code is reached. The
 * flag lives on the tool spec rather than being decided per call, so no prompt
 * and no model output can talk its way past it.
 */
@Singleton
class CommunicationTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionManager,
) : ToolGroup {

    override val tools: List<Tool> = listOf(
        makeCall(),
        sendSms(),
        lookupContact(),
    )

    private fun makeCall() = tool(
        name = "make_call",
        description = "Call a phone number or a contact by name. The user is always asked to confirm first.",
        params = listOf(
            ToolParam(
                name = "number",
                type = ParamType.STRING,
                description = "The phone number to call. Provide this or name.",
                required = false,
            ),
            ToolParam(
                name = "name",
                type = ParamType.STRING,
                description = "The contact name to call. Provide this or number.",
                required = false,
            ),
        ),
        permissions = listOf(Manifest.permission.CALL_PHONE),
        isDangerous = true,
    ) { call ->
        val target = resolveTarget(call.string("number"), call.string("name"))
            ?: return@tool ToolResult.Failure(
                "No number or contact name was given, so there is nobody to call.",
            )
        if (target.error != null) return@tool target.error

        if (!permissions.hasPhone) {
            return@tool ToolResult.RequiresPermission(
                permission = Manifest.permission.CALL_PHONE,
                rationale = "Placing a call needs the Phone permission.",
            )
        }

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${target.number}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.fold(
            onSuccess = { ToolResult.Success("Calling ${target.display}.") },
            onFailure = {
                ToolResult.Failure("The call could not be started: ${it.message}")
            },
        )
    }

    private fun sendSms() = tool(
        name = "send_sms",
        description = "Send a text message to a number or contact. The user is always asked to confirm first.",
        params = listOf(
            ToolParam("message", ParamType.STRING, "The message body."),
            ToolParam(
                name = "number",
                type = ParamType.STRING,
                description = "The phone number to text. Provide this or name.",
                required = false,
            ),
            ToolParam(
                name = "name",
                type = ParamType.STRING,
                description = "The contact name to text. Provide this or number.",
                required = false,
            ),
        ),
        permissions = listOf(Manifest.permission.SEND_SMS),
        isDangerous = true,
    ) { call ->
        val message = call.string("message")
            ?: return@tool ToolResult.Failure("No message body was given.")
        val target = resolveTarget(call.string("number"), call.string("name"))
            ?: return@tool ToolResult.Failure("No number or contact name was given.")
        if (target.error != null) return@tool target.error

        if (!permissions.hasSms) {
            return@tool ToolResult.RequiresPermission(
                permission = Manifest.permission.SEND_SMS,
                rationale = "Sending a message needs the SMS permission.",
            )
        }

        runCatching {
            val manager = context.getSystemService(SmsManager::class.java)
                ?: SmsManager.getDefault()
            // Long messages have to be split; sendTextMessage silently truncates.
            val parts = manager.divideMessage(message)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(target.number, null, parts, null, null)
            } else {
                manager.sendTextMessage(target.number, null, message, null, null)
            }
        }.fold(
            onSuccess = { ToolResult.Success("Message sent to ${target.display}.") },
            onFailure = {
                ToolResult.Failure("The message could not be sent: ${it.message}")
            },
        )
    }

    private fun lookupContact() = tool(
        name = "lookup_contact",
        description = "Find a contact's phone number by name.",
        params = listOf(
            ToolParam("name", ParamType.STRING, "The contact name to look up."),
        ),
        permissions = listOf(Manifest.permission.READ_CONTACTS),
    ) { call ->
        val name = call.string("name")
            ?: return@tool ToolResult.Failure("No contact name was given.")
        if (!permissions.hasContacts) {
            return@tool ToolResult.RequiresPermission(
                permission = Manifest.permission.READ_CONTACTS,
                rationale = "Looking up a contact needs the Contacts permission.",
            )
        }
        val matches = findContacts(name)
        when {
            matches.isEmpty() -> ToolResult.Failure("No contact matches \"$name\".")
            matches.size > 1 -> ToolResult.Success(
                "Several contacts match \"$name\": " +
                    matches.take(5).joinToString(", ") { "${it.first} (${it.second})" } +
                    ". Ask the user which one they meant.",
            )
            else -> ToolResult.Success("${matches.first().first}: ${matches.first().second}")
        }
    }

    private data class Target(
        val number: String = "",
        val display: String = "",
        val error: ToolResult? = null,
    )

    /**
     * Turns whatever the model supplied into a dialable number, or an error that
     * explains why it could not. A name that matches several contacts is an
     * error on purpose: calling the wrong person is exactly the mistake the
     * confirmation gate exists to prevent, and guessing would undermine it.
     */
    private suspend fun resolveTarget(number: String?, name: String?): Target? {
        if (!number.isNullOrBlank()) {
            val cleaned = number.filter { it.isDigit() || it == '+' || it == '#' || it == '*' }
            if (cleaned.isEmpty()) {
                return Target(error = ToolResult.Failure("\"$number\" is not a usable phone number."))
            }
            return Target(number = cleaned, display = cleaned)
        }
        if (name.isNullOrBlank()) return null

        if (!permissions.hasContacts) {
            return Target(
                error = ToolResult.RequiresPermission(
                    permission = Manifest.permission.READ_CONTACTS,
                    rationale = "Finding \"$name\" in your contacts needs the Contacts permission.",
                ),
            )
        }

        val matches = findContacts(name)
        return when {
            matches.isEmpty() -> Target(
                error = ToolResult.Failure("No contact matches \"$name\"."),
            )
            matches.size > 1 -> Target(
                error = ToolResult.Failure(
                    "Several contacts match \"$name\": " +
                        matches.take(5).joinToString(", ") { it.first } +
                        ". Ask the user which one before doing anything.",
                ),
            )
            else -> Target(number = matches.first().second, display = matches.first().first)
        }
    }

    private suspend fun findContacts(name: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val needle = TextNormalizer.normalise(name)
            if (needle.isEmpty()) return@withContext emptyList()

            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val results = linkedMapOf<String, String>()
            runCatching {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
                )?.use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow(projection[0])
                    val numberIndex = cursor.getColumnIndexOrThrow(projection[1])
                    while (cursor.moveToNext()) {
                        val displayName = cursor.getString(nameIndex) ?: continue
                        val number = cursor.getString(numberIndex) ?: continue
                        // Diacritic-insensitive, because the recogniser rarely
                        // gets Vietnamese names fully accented.
                        if (TextNormalizer.normalise(displayName).contains(needle)) {
                            results.putIfAbsent(displayName, number)
                        }
                    }
                }
            }
            results.entries.map { it.key to it.value }
        }
}
