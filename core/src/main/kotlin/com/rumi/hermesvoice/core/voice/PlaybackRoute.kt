package com.rumi.hermesvoice.core.voice

import com.rumi.hermesvoice.core.VoiceOrigin

/** A device that can play spoken audio: the Phone speaker, or the Watch node a voice request came from. */
class PlaybackTarget(val device: VoiceOrigin, val sink: PlaybackSink)

/**
 * Phone-owned playback route. Every spoken utterance (the ack and the recipient's first, middle
 * and final responses, of any turn) plays on the device that most recently submitted an accepted
 * voice request, Phone or Watch.
 *
 * - A request is accepted when the orchestrator admits its turn id (a replayed id is not a new
 *   request and does not move the route). Text chat never moves it.
 * - The target is resolved at handoff: after the utterance's audio is fetched, immediately before
 *   it is given to a sink. So a newer voice request that is accepted before an older turn's
 *   handoff takes that older turn's audio. An utterance already handed off finishes, or is
 *   interrupted, on the device it was handed to.
 * - It is in memory only: turns do not survive the Phone process, and the next accepted voice
 *   request sets the route before anything plays.
 */
class PlaybackRoute {
    private var latest: PlaybackTarget? = null

    @Synchronized
    fun accept(target: PlaybackTarget) {
        latest = target
    }

    /** The current target; null only before any voice request was accepted. */
    @Synchronized
    fun current(): PlaybackTarget? = latest
}
