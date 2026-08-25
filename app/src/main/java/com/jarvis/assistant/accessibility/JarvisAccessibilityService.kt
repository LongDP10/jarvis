package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The service the user enables in Android settings. Deliberately thin: it exists
 * to hand itself to [AccessibilityController] and to keep note of which app is
 * in the foreground. All the actual work lives in the controller so that tools
 * never hold a reference to a system-owned object with its own lifecycle.
 *
 * Android does not allow an app to enable this itself, by design. JARVIS can
 * only send the user to the settings screen and explain why.
 */
@AndroidEntryPoint
class JarvisAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var controller: AccessibilityController

    override fun onServiceConnected() {
        super.onServiceConnected()
        controller.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val event = event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (packageName != null) {
                foregroundPackage = packageName
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        controller.detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        controller.detach()
        super.onDestroy()
    }

    companion object {
        /**
         * Which app is on screen right now. Read by the app launcher so it can
         * tell the model "YouTube is already open" instead of relaunching it, and
         * by close_app so it knows what the user meant by "this".
         */
        @Volatile
        var foregroundPackage: String? = null
            private set
    }
}
