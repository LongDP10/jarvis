package com.jarvis.assistant.notifications

import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

data class NotificationSummary(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
)

/**
 * The last few notifications, held in memory only.
 *
 * Notification content is some of the most sensitive data on a phone, so none of
 * it is written to the database or the debug log; it lives here until the
 * process dies, and is read only when the user actually asks JARVIS what their
 * notifications say.
 */
@Singleton
class NotificationStore @Inject constructor() {

    private val entries = ConcurrentLinkedDeque<NotificationSummary>()

    fun record(summary: NotificationSummary) {
        if (summary.title.isBlank() && summary.text.isBlank()) return
        entries.addFirst(summary)
        while (entries.size > MAX_ENTRIES) entries.pollLast()
    }

    fun remove(packageName: String, postedAt: Long) {
        entries.removeIf { it.packageName == packageName && it.postedAt == postedAt }
    }

    fun snapshot(limit: Int = DEFAULT_LIMIT): List<NotificationSummary> =
        entries.take(limit)

    fun clear() = entries.clear()

    private companion object {
        const val MAX_ENTRIES = 60
        const val DEFAULT_LIMIT = 12
    }
}
