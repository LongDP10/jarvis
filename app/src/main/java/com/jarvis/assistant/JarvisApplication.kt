package com.jarvis.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class JarvisApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Created up front rather than lazily in the service: [OverlayService] has
     * five seconds to call startForeground() once it is started, and channel
     * creation is not something to be doing inside that window.
     */
    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            ASSISTANT_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ASSISTANT_CHANNEL_ID = "jarvis_assistant"
    }
}
