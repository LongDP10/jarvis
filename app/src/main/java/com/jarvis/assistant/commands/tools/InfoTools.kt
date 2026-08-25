package com.jarvis.assistant.commands.tools

import android.Manifest
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.util.Log
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jarvis.assistant.commands.Tool
import com.jarvis.assistant.commands.ToolGroup
import com.jarvis.assistant.commands.ToolResult
import com.jarvis.assistant.commands.tool
import com.jarvis.assistant.notifications.NotificationStore
import com.jarvis.assistant.utils.PermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Read-only tools that answer questions rather than change anything.
 */
@Singleton
class InfoTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionManager,
    private val notificationStore: NotificationStore,
) : ToolGroup {

    override val tools: List<Tool> = listOf(
        getTime(),
        getLocation(),
        readNotifications(),
    )

    private fun getTime() = tool(
        name = "get_time",
        description = "Report the current date and time on this phone.",
    ) { _ ->
        val now = Date()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(now)
        ToolResult.Success("It is $time on $date.")
    }

    private fun getLocation() = tool(
        name = "get_location",
        description = "Report where the phone is now, as an address when one can be resolved.",
        permissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
        requiresNetwork = true,
        worksOffline = false,
    ) { _ ->
        if (!permissions.hasLocation) {
            return@tool ToolResult.RequiresPermission(
                permission = Manifest.permission.ACCESS_COARSE_LOCATION,
                rationale = "Finding where you are needs the Location permission.",
            )
        }

        val location = currentLocation()
            ?: return@tool ToolResult.Failure(
                "No location fix was available. Location services may be switched off, or the phone may be indoors with no signal.",
            )

        val address = describe(location)
        ToolResult.Success(
            if (address != null) {
                "You are near $address (${format(location.latitude)}, ${format(location.longitude)})."
            } else {
                "You are at ${format(location.latitude)}, ${format(location.longitude)}."
            },
        )
    }

    private fun readNotifications() = tool(
        name = "read_notifications",
        description = "Read out the recent notifications on this phone.",
    ) { _ ->
        if (!permissions.hasNotificationAccess) {
            return@tool ToolResult.RequiresPermission(
                permission = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
                rationale = "Reading notifications needs Notification access, which is granted on its own settings screen.",
            )
        }
        val recent = notificationStore.snapshot()
        if (recent.isEmpty()) {
            // The listener only sees notifications posted after it was enabled,
            // which is worth saying so an empty result does not look like a bug.
            ToolResult.Success(
                "There are no recent notifications. Note that JARVIS only sees notifications that arrived after Notification access was granted.",
            )
        } else {
            ToolResult.Success(
                recent.joinToString("\n") { entry ->
                    val body = listOf(entry.title, entry.text).filter { it.isNotBlank() }.joinToString(": ")
                    "${entry.appLabel} — $body"
                },
            )
        }
    }

    private suspend fun currentLocation(): Location? {
        val client = runCatching { LocationServices.getFusedLocationProviderClient(context) }
            .getOrNull() ?: return null

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(MAX_AGE_MS)
            .build()

        return withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { continuation ->
                try {
                    // getCurrentLocation rather than lastLocation: a stale fix
                    // from another city is worse than saying there is no fix.
                    //
                    // The permission is checked by the tool before this runs, but
                    // it can be revoked between the check and the call, so the
                    // SecurityException is caught explicitly rather than left to
                    // crash the turn.
                    client.getCurrentLocation(request, null)
                        .addOnSuccessListener { location ->
                            if (continuation.isActive) continuation.resume(location)
                        }
                        .addOnFailureListener {
                            if (continuation.isActive) continuation.resume(null)
                        }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Location permission was revoked mid-call", e)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private suspend fun describe(location: Location): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.let { address ->
                    listOfNotNull(
                        address.thoroughfare,
                        address.subAdminArea,
                        address.adminArea,
                        address.countryName,
                    ).distinct().joinToString(", ")
                }
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.4f", value)

    private companion object {
        const val TAG = "InfoTools"
        const val LOCATION_TIMEOUT_MS = 12_000L
        const val MAX_AGE_MS = 120_000L
    }
}
