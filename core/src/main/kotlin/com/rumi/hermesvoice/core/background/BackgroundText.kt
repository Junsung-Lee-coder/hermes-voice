package com.rumi.hermesvoice.core.background

import com.rumi.hermesvoice.core.wake.WakeLoop

/**
 * What the ongoing notification and the app say about a background session. The words follow what
 * really runs: a session that cannot listen never says it listens.
 */
object BackgroundText {
    /** The Watch's notification line; null when nothing runs (no notification). */
    fun watchNotification(status: BackgroundStatus, loop: WakeLoop): String? = when {
        !status.running -> null
        status.microphone && loop == WakeLoop.RETRYING -> "Wake phrase paused, retrying (phone or recognizer not ready). Replies play here"
        status.microphone -> "Listening for the wake phrase. Replies play here"
        else -> "Replies play here. " + when (status.notice) {
            BackgroundNotice.NEEDS_PERMISSION -> "Listening needs the microphone permission"
            BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN -> "Open the app to listen for the wake phrase"
            BackgroundNotice.NEEDS_NOTIFICATIONS -> "Not listening: notifications are off"
            BackgroundNotice.NO_RECOGNIZER -> "Wake phrase unavailable on this watch"
            BackgroundNotice.NEEDS_SETTINGS -> "Not listening: Phone settings check not complete"
            else -> "The wake phrase is off for the watch"
        }
    }

    /** The Watch's own label for the control. */
    fun watchLabel(status: BackgroundStatus, loop: WakeLoop): String = when (status.notice) {
        BackgroundNotice.OFF -> "Background: off"
        BackgroundNotice.LISTENING -> if (loop == WakeLoop.RETRYING) "Background: retrying" else "Background: listening"
        BackgroundNotice.RUNNING -> "Background: replies only"
        BackgroundNotice.NEEDS_PERMISSION -> "Background: replies only (no mic permission)"
        BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN -> "Background: replies only until opened"
        BackgroundNotice.NEEDS_NOTIFICATIONS -> "Background: replies only. Allow notifications, then open this app to listen"
        BackgroundNotice.NO_RECOGNIZER -> "Background: replies only (no recognizer)"
        BackgroundNotice.NEEDS_SETTINGS -> "Background: checking Phone settings (replies only)"
        BackgroundNotice.PAUSED -> "Background: paused"
        BackgroundNotice.NEEDS_VISIBLE -> "Background: open the app to start"
        BackgroundNotice.REFUSED -> "Background: couldn't start"
    }

    /**
     * What a tap on the Watch's control does. A running session whose notification can't be seen
     * says so: this control is then the only Stop.
     */
    fun watchAction(status: BackgroundStatus, notification: NotificationCapability): String = when {
        !status.running -> "Tap to start"
        !notification.shown -> "Notification hidden. Tap here to stop"
        else -> "Tap to stop"
    }

    fun phoneNotification(status: BackgroundStatus): String? = if (status.running) "Relaying voice requests and replies for your Watch" else null

    /** The Phone's status line under its switch. [notifications]: the relay's notification can be seen now. */
    fun phoneStatus(status: BackgroundStatus, notifications: Boolean): String = when (status.notice) {
        BackgroundNotice.OFF -> "Off. Watch requests are relayed while this app is open, and otherwise only as long as Android leaves it running"
        BackgroundNotice.PAUSED -> "Paused: Android stopped it. It starts again when you open this app"
        BackgroundNotice.REFUSED, BackgroundNotice.NEEDS_VISIBLE -> "Couldn't start. Open this app and switch it on again"
        else -> "Relaying in the background" + if (notifications) " (Stop is in the notification)" else
            ". The notification is hidden: allow notifications to see it; this switch stops it too"
    }
}
