package com.rumi.hermesvoice.phone.live

/** Actual native playback and Live share one short admission lock; no network or player calls run under it. */
class PhoneLiveAudioGate {
    private var live = false
    private var players = 0
    @Synchronized fun live(): AutoCloseable? {
        if (live || players != 0) return null
        live = true
        var held = true
        return AutoCloseable { synchronized(this) { if (held) { held = false; live = false } } }
    }
    @Synchronized fun playback(): AutoCloseable? {
        if (live) return null
        players++
        var held = true
        return AutoCloseable { synchronized(this) { if (held) { held = false; players-- } } }
    }
}
