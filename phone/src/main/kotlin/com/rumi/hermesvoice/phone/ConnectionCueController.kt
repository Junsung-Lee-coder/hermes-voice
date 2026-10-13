package com.rumi.hermesvoice.phone

import android.util.Log
import com.rumi.hermesvoice.core.headset.AudioEndpoint
import com.rumi.hermesvoice.core.headset.HeadsetPolicy

/** One best-effort real private sound per observed new attachment. Startup/setting replay is never a connection event. */
class ConnectionCueController(
    private val policy: HeadsetPolicy,
    private val cues: RecordingCues,
    private val busy: () -> Boolean,
    private val playback: (Boolean) -> Unit,
) {
    private var observed: AudioEndpoint? = policy.output()
    private var enabled = policy.enabled
    private var generation = 0L
    private var handle: AutoCloseable? = null
    private val watch = policy.watch { changed() }

    /** Settings hydration or foreground return is only a seed, never evidence of a fresh output attachment. */
    fun refresh() {
        if (!policy.enabled || enabled != policy.enabled) cancel()
        enabled = policy.enabled
        observed = policy.output()
    }

    private fun changed() {
        val current = policy.output()
        val previous = observed
        observed = current // Commit before play/watch callbacks: reentrant reports cannot replay this epoch.
        if (!policy.enabled || enabled != policy.enabled) {
            enabled = policy.enabled
            cancel()
            Log.i("HermesVoice", "connection cue skipped: setting transition")
            return
        }
        if (current == null) { cancel(); return }
        if (previous != null) {
            // Same attachment refresh, or a route switch while already connected: not disconnected-to-connected proof.
            if (!previous.sameDevice(current)) cancel()
            return
        }
        cancel()
        if (busy()) { Log.i("HermesVoice", "connection cue skipped: Phone microphone busy"); return }
        val token = ++generation
        playback(true)
        val made = cues.play(CueKind.CONNECTION) { result ->
            if (token == generation) {
                handle = null
                playback(false)
                Log.i("HermesVoice", "connection cue ended: $result")
            }
        }
        if (token == generation) {
            handle = made
            if (made == null) playback(false)
        } else runCatching { made?.close() }
    }

    /** Cancel without making the same live route replayable. */
    fun cancel() {
        ++generation
        val previous = handle
        handle = null
        runCatching { previous?.close() }
        playback(false)
    }
}
