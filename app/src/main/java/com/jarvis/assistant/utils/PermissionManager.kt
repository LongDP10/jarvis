package com.jarvis.assistant.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.assistant.accessibility.AccessibilityController
import com.jarvis.assistant.notifications.JarvisNotificationListener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which permissions a feature has, and where to send the user for the ones
 * Android will not grant through a runtime dialog.
 *
 * Nothing is requested up front. A permission is asked for at the moment the
 * feature that needs it is first used, which is why every tool declares its own
 * requirements rather than the app declaring one big list at launch.
 */
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accessibility: AccessibilityController,
) {

    fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(permissions: List<String>): List<String> = permissions.filterNot { has(it) }

    val hasMicrophone: Boolean get() = has(Manifest.permission.RECORD_AUDIO)

    val hasLocation: Boolean
        get() = has(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            has(Manifest.permission.ACCESS_FINE_LOCATION)

    val hasPhone: Boolean get() = has(Manifest.permission.CALL_PHONE)

    val hasSms: Boolean get() = has(Manifest.permission.SEND_SMS)

    val hasContacts: Boolean get() = has(Manifest.permission.READ_CONTACTS)

    val hasNotifications: Boolean get() = has(Manifest.permission.POST_NOTIFICATIONS)

    /** Special grants: no runtime dialog exists, only a settings screen. */
    val hasOverlay: Boolean get() = Settings.canDrawOverlays(context)

    val canWriteSystemSettings: Boolean get() = Settings.System.canWrite(context)

    val hasAccessibility: Boolean get() = accessibility.isEnabledInSystemSettings()

    val hasNotificationAccess: Boolean get() = JarvisNotificationListener.isEnabled(context)

    // ------------------------------------------------------------- settings

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).withNewTask()

    fun overlaySettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).withNewTask()

    fun writeSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_WRITE_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).withNewTask()

    fun notificationListenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).withNewTask()

    fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).withNewTask()

    /**
     * Samsung's battery management is aggressive enough that a background
     * assistant will be killed without this, so the onboarding screen offers it.
     */
    fun batteryOptimisationIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).withNewTask()

    private fun Intent.withNewTask(): Intent = apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
