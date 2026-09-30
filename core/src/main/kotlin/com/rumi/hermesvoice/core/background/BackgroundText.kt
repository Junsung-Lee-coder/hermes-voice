package com.rumi.hermesvoice.core.background

/**
 * What the ongoing notification and the app say about a background session. The words follow what
 * really runs: a session that cannot listen never says it listens.
 */
object BackgroundText {
    /** The Watch's notification line; null when nothing runs (no notification). [recognizer]: the Watch has a speech recognizer. */
    fun watchNotification(status: BackgroundStatus, recognizer: Boolean): String? = when {
        !status.running -> null
        status.microphone && recognizer -> "Listening for the wake phrase. Replies play here"
        status.microphone -> "Wake phrase unavailable on this watch. Replies play here"
        else -> when (status.notice) {
            BackgroundNotice.NEEDS_PERMISSION -> "Replies play here. Listening needs the microphone permission"
            BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN -> "Replies play here. Open the app to listen for the wake phrase"
            else -> "Replies play here. The wake phrase is off for the watch"
        }
    }

    /** The Watch's own label for the control. */
    fun watchLabel(status: BackgroundStatus, recognizer: Boolean): String = when (status.notice) {
        BackgroundNotice.OFF -> "Background: off"
        BackgroundNotice.LISTENING -> if (recognizer) "Background: listening" else "Background: replies only (no recognizer)"
        BackgroundNotice.RUNNING -> "Background: replies only"
        BackgroundNotice.NEEDS_PERMISSION -> "Background: replies only (no mic permission)"
        BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN -> "Background: replies only until opened"
        BackgroundNotice.PAUSED -> "Background: paused"
        BackgroundNotice.NEEDS_VISIBLE -> "Background: open the app to start"
        BackgroundNotice.REFUSED -> "Background: couldn't start"
    }

    /** What a tap on the Watch's control does. */
    fun watchAction(status: BackgroundStatus): String = if (status.running) "Tap to stop" else "Tap to start"

    fun phoneNotification(status: BackgroundStatus): String? = if (status.running) "Relaying voice requests and replies for your Watch" else null

    /** The Phone's status line under its switch. [notifications]: the app may show its notification. */
    fun phoneStatus(status: BackgroundStatus, notifications: Boolean): String = when (status.notice) {
        BackgroundNotice.OFF -> "Off. Watch requests are relayed while this app is open, and otherwise only as long as Android leaves it running"
        BackgroundNotice.PAUSED -> "Paused: Android stopped it. It starts again when you open this app"
        BackgroundNotice.REFUSED, BackgroundNotice.NEEDS_VISIBLE -> "Couldn't start. Open this app and switch it on again"
        else -> "Relaying in the background" + if (notifications) " (Stop is in the notification)" else
            ". The notification is hidden: allow notifications to see it; this switch stops it too"
    }
}
