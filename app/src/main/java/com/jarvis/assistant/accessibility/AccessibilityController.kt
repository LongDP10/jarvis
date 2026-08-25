package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.utils.TextNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/**
 * The bridge between the tool layer and the accessibility service.
 *
 * The service itself is created by the system and cannot be injected into, so it
 * registers itself here when it connects and clears itself when it does not.
 * That is what lets a tool distinguish "the user has not enabled the service"
 * from "the action was attempted and failed" -- a distinction the whole
 * honest-capability promise depends on.
 */
@Singleton
class AccessibilityController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val screenReader = ScreenReader()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    @Volatile
    private var service: AccessibilityService? = null

    internal fun attach(service: AccessibilityService) {
        this.service = service
        _isConnected.value = true
    }

    internal fun detach() {
        service = null
        _isConnected.value = false
    }

    /**
     * Whether the user has switched the service on in system settings. Checked
     * against the setting rather than [isConnected] so the UI can prompt
     * correctly even before the service has had a chance to bind.
     */
    fun isEnabledInSystemSettings(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.startsWith(context.packageName) }
    }

    // ---------------------------------------------------------------- actions

    suspend fun globalAction(action: Int): Boolean = withContext(Dispatchers.Main) {
        service?.performGlobalAction(action) ?: false
    }

    suspend fun goHome() = globalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    suspend fun goBack() = globalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    suspend fun openRecents() = globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

    suspend fun openNotifications() = globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

    suspend fun openQuickSettings() = globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)

    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                .build(),
        )
    }

    suspend fun longPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, LONG_PRESS_DURATION_MS))
                .build(),
        )
    }

    suspend fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = SWIPE_DURATION_MS,
    ): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(),
        )
    }

    /**
     * Scrolls by asking the scrollable node first and falling back to a swipe.
     *
     * The node action is preferred because it respects the app's own fling
     * behaviour; the swipe fallback exists because plenty of custom views are
     * scrollable in practice without ever reporting ACTION_SCROLL_FORWARD.
     */
    suspend fun scroll(direction: ScrollDirection, times: Int = 1): Boolean {
        var succeeded = false
        repeat(times.coerceIn(1, MAX_REPEATS)) {
            val viaNode = scrollViaNode(direction)
            succeeded = if (viaNode) true else scrollViaGesture(direction) || succeeded
            kotlinx.coroutines.delay(SCROLL_GAP_MS)
        }
        return succeeded
    }

    private suspend fun scrollViaNode(direction: ScrollDirection): Boolean =
        withContext(Dispatchers.Main) {
            val root = service?.rootInActiveWindow ?: return@withContext false
            val scrollable = findScrollable(root) ?: return@withContext false
            val action = when (direction) {
                ScrollDirection.DOWN, ScrollDirection.RIGHT ->
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                ScrollDirection.UP, ScrollDirection.LEFT ->
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            scrollable.performAction(action)
        }

    private suspend fun scrollViaGesture(direction: ScrollDirection): Boolean {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val dx = width * 0.35f
        val dy = height * 0.30f

        return when (direction) {
            // Content moves opposite to the finger: to see what is further down
            // the page the finger travels up.
            ScrollDirection.DOWN -> swipe(cx, cy + dy, cx, cy - dy)
            ScrollDirection.UP -> swipe(cx, cy - dy, cx, cy + dy)
            ScrollDirection.RIGHT -> swipe(cx + dx, cy, cx - dx, cy)
            ScrollDirection.LEFT -> swipe(cx - dx, cy, cx + dx, cy)
        }
    }

    /**
     * Finds a node whose label matches [text] and clicks it, climbing to the
     * nearest clickable ancestor because the visible label is very often a
     * TextView inside the button rather than the button itself.
     *
     * Matching ignores case and Vietnamese diacritics, so "tim kiem" finds
     * "Tìm kiếm".
     */
    suspend fun clickByText(text: String): Boolean = withContext(Dispatchers.Main) {
        val root = service?.rootInActiveWindow ?: return@withContext false
        val needle = normalise(text)
        if (needle.isEmpty()) return@withContext false

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { node ->
            val label = normalise((node.text ?: node.contentDescription)?.toString().orEmpty())
            if (label.isNotEmpty() && (label == needle || label.contains(needle))) {
                candidates += node
            }
        }

        // Exact matches first, then the shortest containing label: "Search" is a
        // better target than "Search results for cats".
        val best = candidates
            .sortedWith(
                compareBy(
                    { normalise((it.text ?: it.contentDescription)?.toString().orEmpty()) != needle },
                    { (it.text ?: it.contentDescription)?.length ?: Int.MAX_VALUE },
                ),
            )
            .firstOrNull() ?: return@withContext false

        clickableAncestor(best)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    /** Types into whichever field currently has focus. */
    suspend fun inputText(text: String): Boolean = withContext(Dispatchers.Main) {
        val root = service?.rootInActiveWindow ?: return@withContext false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirst(root) { it.isEditable }
            ?: return@withContext false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    suspend fun readScreen(): ScreenSnapshot = withContext(Dispatchers.Main) {
        screenReader.read(service?.rootInActiveWindow)
    }

    /**
     * Screenshots without MediaProjection, which is the whole reason the service
     * declares canTakeScreenshot. Returns null when the service is off or the
     * system refuses (some secure screens are never capturable).
     */
    suspend fun takeScreenshotToFile(): File? {
        val svc = service ?: return null
        val result = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Bitmap?> { continuation ->
                svc.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    context.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace,
                            )
                            screenshot.hardwareBuffer.close()
                            continuation.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "Screenshot refused by system, code $errorCode")
                            continuation.resume(null)
                        }
                    },
                )
            }
        } ?: return null

        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                val file = File(dir, "jarvis_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    result.copy(Bitmap.Config.ARGB_8888, false)
                        .compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                file
            }.getOrNull()
        }
    }

    // ------------------------------------------------------------- internals

    private suspend fun dispatch(gesture: GestureDescription): Boolean {
        val svc = service ?: return false
        return withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val dispatched = svc.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(description: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onCancelled(description: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                    null,
                )
                if (!dispatched && continuation.isActive) continuation.resume(false)
            }
        } ?: false
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            if (current.isClickable) return current
            current = current.parent
            hops++
        }
        return null
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findFirst(root) { it.isScrollable }

    private fun findFirst(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (found == null && predicate(node)) found = node
        }
        return found
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        action: (AccessibilityNodeInfo) -> Unit,
    ) {
        if (node == null || depth > MAX_DEPTH) return
        action(node)
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), depth + 1, action)
        }
    }

    private fun normalise(text: String): String = TextNormalizer.normalise(text)

    private companion object {
        const val TAG = "A11yController"
        const val TAP_DURATION_MS = 60L
        const val LONG_PRESS_DURATION_MS = 600L
        const val SWIPE_DURATION_MS = 300L
        const val SCROLL_GAP_MS = 350L
        const val GESTURE_TIMEOUT_MS = 5_000L
        const val SCREENSHOT_TIMEOUT_MS = 8_000L
        const val MAX_ANCESTOR_HOPS = 6
        const val MAX_DEPTH = 40
        const val MAX_REPEATS = 20
    }
}
