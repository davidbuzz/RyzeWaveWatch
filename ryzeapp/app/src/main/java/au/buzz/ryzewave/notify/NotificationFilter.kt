package au.buzz.ryzewave.notify

import au.buzz.ryzewave.protocol.NotificationType

/** What the listener extracted from one posted notification. Android-free so the filter is unit-testable. */
data class PostedNotification(
    val packageName: String,
    /** `StatusBarNotification.key` (unique per posting app + id + tag + user). */
    val key: String,
    val title: String?,
    val text: String?,
    /** Launcher label of the posting app, or null when unknown. */
    val appLabel: String?,
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
)

/** A message to push to the watch: `C5` type per docs/PROTOCOL.md section 6 and "<app or sender>: <text>". */
data class OutgoingNotification(val type: Int, val text: String, val packageName: String)

/**
 * Decides which posted notifications go to the watch and shapes the text. Pure Kotlin.
 *
 *  - dropped: ongoing (foreground services, media, downloads), group summaries (the children carry the text),
 *    our own package, a package outside the allow-list (unless forwardAll), nothing to say;
 *  - de-duplicated by key + text within [DEDUP_WINDOW_MS]: apps re-post the same notification on every update
 *    and the watch would buzz each time;
 *  - text: "<title or app label>: <text>"; a notification with only a title gets "<app label>: <title>".
 */
class NotificationFilter(
    private val ownPackage: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val recent = LinkedHashMap<String, Long>()

    /** Why the last [decide] returned null (for logs / tests). */
    @Volatile var lastDropReason: String? = null
        private set

    fun decide(n: PostedNotification, allowed: Set<String>, forwardAll: Boolean): OutgoingNotification? {
        lastDropReason = null
        val pkg = n.packageName
        if (n.ongoing) return drop("ongoing")
        if (n.groupSummary) return drop("group summary")
        if (pkg == ownPackage) return drop("own notification")
        if (!forwardAll && pkg !in allowed) return drop("package not allowed")
        val text = messageText(n) ?: return drop("no text")
        val dedupKey = n.key + " " + text
        val now = clock()
        synchronized(recent) {
            val it = recent.entries.iterator()
            while (it.hasNext()) if (now - it.next().value > DEDUP_WINDOW_MS) it.remove()
            val seen = recent[dedupKey]
            if (seen != null && now - seen <= DEDUP_WINDOW_MS) return drop("duplicate within ${DEDUP_WINDOW_MS / 1000} s")
            recent[dedupKey] = now
            while (recent.size > MAX_RECENT) recent.remove(recent.keys.first())
        }
        return OutgoingNotification(NotificationType.forPackage(pkg), text, pkg)
    }

    private fun drop(reason: String): OutgoingNotification? {
        lastDropReason = reason
        return null
    }

    companion object {
        const val DEDUP_WINDOW_MS = 10_000L
        private const val MAX_RECENT = 64

        /** "<title or app label>: <text>", null when there is nothing to show. Whitespace-only fields count as absent. */
        fun messageText(n: PostedNotification): String? {
            val title = n.title?.trim()?.takeIf { it.isNotEmpty() }
            val text = n.text?.trim()?.takeIf { it.isNotEmpty() }
            val label = n.appLabel?.trim()?.takeIf { it.isNotEmpty() } ?: n.packageName.substringAfterLast('.')
            return when {
                title != null && text != null -> "$title: $text"
                text != null -> "$label: $text"
                title != null -> "$label: $title"
                else -> null
            }
        }
    }
}
