package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.headset.HeadsetBackgroundState
import com.rumi.hermesvoice.core.headset.HeadsetMediaText

/** Presentation only. The admitted relay state and its explicit setup action remain authoritative. */
internal fun compactHeadsetBackgroundLine(state: HeadsetBackgroundState): String? = when (state) {
    HeadsetBackgroundState.NOT_NEEDED -> null
    HeadsetBackgroundState.NEEDS_SETUP -> "Background answers: off · Turn on below"
    HeadsetBackgroundState.STARTING -> "Background answers: waiting for Android"
    HeadsetBackgroundState.REFUSED -> "Background answers: blocked · Try again with this app open"
    HeadsetBackgroundState.PAUSED -> "Background answers: relay stopped · Try again"
    HeadsetBackgroundState.ACTIVE -> "Background answers: relay running"
}

/** Compact media-session status, not a claim that a physical button was delivered or audio heard. */
internal fun compactHeadsetControlLine(line: String): String = when (line) {
    HeadsetMediaText.WAITING_FOR_HEADSET -> "Connect a headset to use play/pause"
    HeadsetMediaText.APP_CLOSED -> "Open this app to start a recording"
    HeadsetMediaText.UNAVAILABLE -> "Session unavailable · Try play/pause here or use the mic"
    HeadsetMediaText.REGISTERED -> "Headset control ready · Try play/pause"
    else -> "Use the on-screen mic to record"
}
