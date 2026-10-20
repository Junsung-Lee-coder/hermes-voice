package com.rumi.hermesvoice.core.notify

/**
 * When a conversation is really in front of the user: it is the one shown on the conversation screen AND the app is in the
 * foreground. Selecting it in the background, or having another screen up, is not being visible. Each transition into being
 * visible is an "open" ([onOpened]); a reply that arrives for the visible conversation is reported to [onArrival].
 * Device-local and in memory; the stacked alerts themselves are what survive a process restart.
 */
class ConversationPresence {
    @Volatile var onOpened: (String) -> Unit = {}
    @Volatile var onArrival: (String) -> Unit = {}

    private val lock = Any()
    private var foreground = false
    private var shown: String? = null
    private var epoch = 0L

    /** Changes on leaving as well as entering a visible opening, even when the selection stays. */
    val generation: Long get() = synchronized(lock) { epoch }

    /** Short visible-state commit only; never hold this monitor over a read/network operation. */
    fun ifVisible(storedSessionId: String, generation: Long, commit: () -> Unit) = synchronized(lock) {
        if (foreground && shown == storedSessionId && epoch == generation) commit()
    }

    val visibleSession: String? get() = synchronized(lock) { if (foreground) shown else null }

    fun setForeground(value: Boolean) = change { foreground = value }

    fun setShown(storedSessionId: String?) = change { shown = storedSessionId }

    fun isVisible(storedSessionId: String): Boolean = visibleSession == storedSessionId

    private fun change(update: () -> Unit) {
        val opened = synchronized(lock) {
            val before = if (foreground) shown else null
            update()
            val after = if (foreground) shown else null
            if (after != before) epoch++
            after?.takeIf { it != before }
        }
        if (opened != null) {
            try {
                onOpened(opened)
            } catch (_: Exception) {
            }
        }
    }
}
