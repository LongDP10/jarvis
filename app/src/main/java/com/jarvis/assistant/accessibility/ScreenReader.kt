package com.jarvis.assistant.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * One thing on screen that the model is allowed to know about and, if it is
 * interactive, ask JARVIS to touch.
 */
data class ScreenNode(
    val index: Int,
    val text: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val bounds: Rect,
    val className: String?,
) {
    val centreX: Float get() = bounds.exactCenterX()
    val centreY: Float get() = bounds.exactCenterY()
}

data class ScreenSnapshot(
    val packageName: String?,
    val nodes: List<ScreenNode>,
) {
    val isEmpty: Boolean get() = nodes.isEmpty()

    /**
     * Renders the screen for the model. Numbered so a plan can say "click 3"
     * rather than guessing at coordinates, and marked so it can tell a heading
     * apart from a button.
     */
    fun toPromptText(): String {
        if (nodes.isEmpty()) return "The screen has no readable content."
        val header = "Screen (${packageName ?: "unknown app"}):"
        val body = nodes.joinToString("\n") { node ->
            val marks = buildString {
                if (node.isClickable) append(" [clickable]")
                if (node.isEditable) append(" [text field]")
            }
            "${node.index}. ${node.text}$marks"
        }
        return "$header\n$body"
    }
}

/**
 * Flattens the accessibility node tree into something small enough to put in a
 * prompt.
 *
 * The tree on a real app screen is hundreds of nodes deep and mostly layout
 * containers. This keeps nodes that carry text or that the user could act on,
 * orders them the way they appear on screen, and caps the total, because the
 * point is to let the model answer "open the first video", not to reproduce the
 * whole view hierarchy.
 */
class ScreenReader {

    fun read(root: AccessibilityNodeInfo?, limit: Int = DEFAULT_LIMIT): ScreenSnapshot {
        if (root == null) return ScreenSnapshot(packageName = null, nodes = emptyList())

        val collected = mutableListOf<RawNode>()
        collect(root, collected, depth = 0)

        val nodes = collected
            .asSequence()
            .filter { it.text.isNotBlank() || it.isClickable }
            .distinctBy { it.text.lowercase() to it.bounds.top }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(limit)
            .mapIndexed { index, raw ->
                ScreenNode(
                    index = index + 1,
                    text = raw.text.ifBlank { raw.fallbackLabel() },
                    isClickable = raw.isClickable,
                    isEditable = raw.isEditable,
                    bounds = raw.bounds,
                    className = raw.className,
                )
            }
            .toList()

        return ScreenSnapshot(packageName = root.packageName?.toString(), nodes = nodes)
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<RawNode>, depth: Int) {
        if (node == null || depth > MAX_DEPTH || out.size > HARD_CAP) return

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        // Off-screen and zero-area nodes are noise: RecyclerViews keep recycled
        // rows in the tree, and reporting them would have the model clicking on
        // things the user cannot see.
        if (bounds.width() > 0 && bounds.height() > 0) {
            val text = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() || node.isClickable) {
                out += RawNode(
                    text = text,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    bounds = bounds,
                    className = node.className?.toString(),
                    viewId = node.viewIdResourceName,
                )
            }
        }

        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, depth + 1)
        }
    }

    private data class RawNode(
        val text: String,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val bounds: Rect,
        val className: String?,
        val viewId: String?,
    ) {
        /** A clickable icon with no label still needs a handle the model can use. */
        fun fallbackLabel(): String {
            val id = viewId?.substringAfterLast('/')?.replace('_', ' ')
            if (!id.isNullOrBlank()) return id
            return className?.substringAfterLast('.') ?: "control"
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 40
        const val HARD_CAP = 400
        const val MAX_DEPTH = 40
    }
}
