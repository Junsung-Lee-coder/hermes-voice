package com.rumi.hermesvoice.core.headset

import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundStatus

/**
 * What the user is told, and offered, about hearing headset answers while the Phone app is closed. Android 15+ gives the Phone audio focus
 * with the app closed only while an ADMITTED foreground service of this app runs; the existing background relay is that service. This is
 * only the wording and the state: it starts nothing, turns on no other option, and never claims more than the platform admitted.
 */
enum class HeadsetBackgroundState {
    /** Use headset is off, or this Android release does not gate focus on a foreground service. */
    NOT_NEEDED,

    /** Opt-in available: the relay is off. */
    NEEDS_SETUP,

    /** The relay was asked for; Android has not admitted its foreground service (yet). */
    STARTING,

    /** The start was refused (the platform, or the app was not on screen). */
    REFUSED,

    /** The user turned it on but Android ended it; the user has to start it again. */
    PAUSED,

    /** Android admitted the relay's foreground service. */
    ACTIVE,
}

object HeadsetBackground {
    /** First Android release (API 35) whose audio focus rules need this. */
    const val FOCUS_GATED_SDK = 35

    fun state(useHeadset: Boolean, sdk: Int, relay: BackgroundStatus, foregroundAdmitted: Boolean): HeadsetBackgroundState = when {
        !useHeadset || sdk < FOCUS_GATED_SDK -> HeadsetBackgroundState.NOT_NEEDED
        foregroundAdmitted -> HeadsetBackgroundState.ACTIVE
        relay.running -> HeadsetBackgroundState.STARTING
        relay.notice == BackgroundNotice.REFUSED || relay.notice == BackgroundNotice.NEEDS_VISIBLE -> HeadsetBackgroundState.REFUSED
        relay.wanted -> HeadsetBackgroundState.PAUSED
        else -> HeadsetBackgroundState.NEEDS_SETUP
    }

    /** The settings/banner line for [state]; null when nothing needs saying. */
    fun text(state: HeadsetBackgroundState): String? = when (state) {
        HeadsetBackgroundState.NOT_NEEDED -> null
        HeadsetBackgroundState.NEEDS_SETUP ->
            "Headset answers with this app closed: off. Android may not let the Phone play them unless the background relay is on. " +
                "Turn it on and, if Android allows it, the Phone can try to play Watch answers on your headset while this app is closed."
        HeadsetBackgroundState.STARTING -> "Headset answers with this app closed: waiting for Android to allow the background relay."
        HeadsetBackgroundState.REFUSED -> "Headset answers with this app closed: Android didn't allow the background relay. Open this app and try again."
        HeadsetBackgroundState.PAUSED -> "Headset answers with this app closed: the background relay is not running (Android stopped it or did not allow it). Turn it on again."
        HeadsetBackgroundState.ACTIVE ->
            "Headset answers with this app closed: the background relay is running, so the Phone can try to play them. Not a guarantee of audible playback."
    }

    /** The one action offered for [state] (a tap on the visible Phone); null when none. */
    fun actionLabel(state: HeadsetBackgroundState): String? = when (state) {
        HeadsetBackgroundState.NEEDS_SETUP -> "Turn on background answers"
        HeadsetBackgroundState.REFUSED, HeadsetBackgroundState.PAUSED -> "Try again"
        else -> null
    }

    /** True while the user is offered the action (the state is setup, refused or paused). */
    fun offersAction(state: HeadsetBackgroundState): Boolean = actionLabel(state) != null
}
