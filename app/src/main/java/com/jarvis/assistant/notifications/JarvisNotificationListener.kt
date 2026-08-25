package com.jarvis.assistant.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Feeds [NotificationStore] so the read_notifications tool has something real to
 * report. Ongoing and group-summary notifications are skipped: they are the
 * media players and sync indicators nobody means when they ask "what are my
 * notifications".
 */
@AndroidEntryPoint
class JarvisNotificationListener : NotificationListenerService() {

    @Inject
    lateinit var store: NotificationStore

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras
        store.record(
            NotificationSummary(
                packageName = sbn.packageName,
                appLabel = labelFor(sbn.packageName),
                title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                postedAt = sbn.postTime,
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        store.remove(sbn.packageName, sbn.postTime)
    }

    private fun labelFor(packageName: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    companion object {
        /**
         * Notification access is granted on a system screen, not through a
         * runtime permission dialog, so this reads the setting directly.
         */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val expected = ComponentName(context, JarvisNotificationListener::class.java)
            return flat.split(':').any {
                ComponentName.unflattenFromString(it) == expected
            }
        }
    }
}
