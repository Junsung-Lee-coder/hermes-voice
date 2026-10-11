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
        // Asked for, not armed yet (the service is still entering the foreground).
        status.microphone && loop == WakeLoop.WAITING_FOR_SCREEN -> "Wake phrase waiting for the screen (screen-off recognition is off). Replies play here"
        status.microphone && loop == WakeLoop.NOT_SELECTED -> "Wake phrase off for this watch (Listen on does not include it). Replies play here"
        status.microphone && loop == WakeLoop.OFF -> "Starting the wake phrase. Replies play here"
        status.microphone -> "Listening for the wake phrase. Replies play here"
        else -> "Replies play here. " + when (status.notice) {
            BackgroundNotice.NEEDS_PERMISSION -> "Listening needs the microphone permission"
            BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN -> "Open the app to listen for the wake phrase"
            BackgroundNotice.NEEDS_NOTIFICATIONS -> "Not listening: notifications are off"
            BackgroundNotice.NO_RECOGNIZER -> "Wake phrase unavailable on this watch"
            BackgroundNotice.NEEDS_SETTINGS -> "Not listening: Phone settings check not complete"
            else -> "Background wake standby is off for the watch (Phone settings)"
        }
    }

    /** The Watch's own label for the control. */
    fun watchLabel(status: BackgroundStatus, loop: WakeLoop): String = when (status.notice) {
        BackgroundNotice.OFF -> "Background: off"
        BackgroundNotice.LISTENING -> when (loop) {
            WakeLoop.RETRYING -> "Background: retrying"
            WakeLoop.WAITING_FOR_SCREEN -> "Background: waiting for screen"
            WakeLoop.NOT_SELECTED -> "Background: not selected in Listen on"
            else -> "Background: listening"
        }
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

    /** The Phone's background-listening notification line; null when nothing runs (no notification). */
    fun phoneWakeNotification(status: PhoneWakeStatus): String? = when {
        !status.session.running -> null
        !status.session.microphone -> "Not listening: " + phoneWakeBlocked(status.session.notice)
        status.loop == WakeLoop.RETRYING -> "Wake phrase paused, retrying (recognizer not ready)"
        status.loop == WakeLoop.NO_RECOGNIZER -> "Not listening: no on-device recognizer for the wake phrase"
        status.loop == WakeLoop.NOT_SELECTED -> "Not listening: Listen on does not include this phone"
        status.loop == WakeLoop.WAITING_FOR_SCREEN -> "Waiting for the screen: screen-off recognition is off for this phone"
        status.listeningNow -> "Listening for the wake phrase"
        else -> "Listens for the wake phrase when this app is closed"
    }

    /** The Phone's status line under its background-listening switch. */
    fun phoneWakeStatus(status: PhoneWakeStatus, notifications: Boolean): String = when (status.session.notice) {
        BackgroundNotice.OFF -> "Off. The wake phrase works only while this app is open on screen"
        BackgroundNotice.PAUSED -> "Paused: Android stopped it. Open this app and switch it on again"
        BackgroundNotice.REFUSED, BackgroundNotice.NEEDS_VISIBLE -> "Couldn't start. Open this app and switch it on again"
        BackgroundNotice.LISTENING -> when {
            status.loop == WakeLoop.RETRYING -> "On, retrying: the recognizer isn't ready"
            status.loop == WakeLoop.NOT_SELECTED -> "On, not listening: Listen on does not include this phone"
            status.loop == WakeLoop.WAITING_FOR_SCREEN -> "On, waiting for the screen: recognition with the screen off is off for this phone"
            status.listeningNow -> "Listening in the background now"
            else -> "On: listens when this app is closed"
        } + if (notifications) " (Stop is in the notification)" else ""
        else -> "On, but not listening: " + phoneWakeBlocked(status.session.notice)
    }

    private fun phoneWakeBlocked(notice: BackgroundNotice): String = when (notice) {
        BackgroundNotice.NEEDS_PERMISSION -> "it needs the microphone permission (tap Talk once to grant it), then open this app"
        BackgroundNotice.NEEDS_NOTIFICATIONS -> "notifications are off, so its Stop couldn't be seen"
        BackgroundNotice.NO_RECOGNIZER -> "this phone has no on-device speech recognizer for the wake phrase (it never streams the room to a server)"
        BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN -> "open this app to let it listen again"
        else -> "background wake standby is off for this phone (Phone settings)"
    }

    /** The Phone's status line under its switch. [notifications]: the relay's notification can be seen now. */
    fun phoneStatus(status: BackgroundStatus, notifications: Boolean): String = when (status.notice) {
        BackgroundNotice.OFF -> "Off. Watch requests are relayed while this app is open, and otherwise only as long as Android leaves it running"
        BackgroundNotice.PAUSED -> "Paused: Android stopped it. It starts again when you open this app"
        BackgroundNotice.REFUSED, BackgroundNotice.NEEDS_VISIBLE -> "Couldn't start. Open this app and switch it on again"
        else -> "Relaying in the background" + if (notifications) " (Stop is in the notification)" else
            ". The notification is hidden: allow notifications to see it; this switch stops it too"
    }
}
