package com.rumi.hermesvoice.core.headset

import kotlin.math.PI
import kotlin.math.sin

/** What a headset media key asks of Phone recording: [TOGGLE] flips it, [START] and [STOP] are idempotent. */
enum class RecordingCommand { TOGGLE, START, STOP }

/**
 * Turns Android key events (and the media session's own play / pause callbacks) into at most one [RecordingCommand] per physical
 * press. Only the press itself counts: a key up, a held key's repeats, the same event delivered again, and a second callback for the
 * same press within [DUPLICATE_WINDOW_MS] are dropped. Every other key (next, previous, volume, stop, seeking) is not ours.
 */
class MediaButtonDecoder(private val clock: () -> Long) {
    private var lastAt = Long.MIN_VALUE
    private var lastKey = Int.MIN_VALUE
    private var lastDownTime = Long.MIN_VALUE

    fun handles(keyCode: Int): Boolean = commandFor(keyCode) != null

    @Synchronized
    fun key(keyCode: Int, action: Int, repeatCount: Int, downTime: Long): RecordingCommand? {
        val command = commandFor(keyCode) ?: return null
        if (action != ACTION_DOWN || repeatCount != 0) return null
        if (keyCode == lastKey && downTime == lastDownTime) return null
        return accept(command, keyCode, downTime)
    }

    @Synchronized
    fun transport(command: RecordingCommand): RecordingCommand? = accept(command, Int.MIN_VALUE, Long.MIN_VALUE)

    private fun accept(command: RecordingCommand, keyCode: Int, downTime: Long): RecordingCommand? {
        val now = clock()
        if (lastAt != Long.MIN_VALUE && now - lastAt < DUPLICATE_WINDOW_MS) return null
        lastAt = now
        lastKey = keyCode
        lastDownTime = downTime
        return command
    }

    private fun commandFor(keyCode: Int): RecordingCommand? = when (keyCode) {
        KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_HEADSETHOOK -> RecordingCommand.TOGGLE
        KEYCODE_MEDIA_PLAY -> RecordingCommand.START
        KEYCODE_MEDIA_PAUSE -> RecordingCommand.STOP
        else -> null
    }

    companion object {
        const val DUPLICATE_WINDOW_MS = 350L
        private const val ACTION_DOWN = 0
        private const val KEYCODE_HEADSETHOOK = 79
        private const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        private const val KEYCODE_MEDIA_PLAY = 126
        private const val KEYCODE_MEDIA_PAUSE = 127
    }
}

/**
 * The recording cue, generated here: one short ding when a recording really started, two when it really stopped. 16-bit mono PCM, a
 * soft sine with a ramped start and end (no click) and a peak well below full scale (no amplification). Never speech, a file or a
 * system sound.
 */
object RecordingCueTone {
    const val SAMPLE_RATE = 16_000
    private const val DING_MS = 110
    private const val GAP_MS = 90
    private const val RAMP_MS = 12
    private const val FREQUENCY_HZ = 1175.0
    private const val PEAK = 0.30

    fun pcm(dings: Int): ByteArray {
        if (dings <= 0) return ByteArray(0)
        val ding = SAMPLE_RATE * DING_MS / 1000
        val gap = SAMPLE_RATE * GAP_MS / 1000
        val ramp = SAMPLE_RATE * RAMP_MS / 1000
        val total = dings * ding + (dings - 1) * gap
        val out = ByteArray(total * 2)
        for (n in 0 until dings) {
            val start = n * (ding + gap)
            for (i in 0 until ding) {
                val envelope = when {
                    i < ramp -> i.toDouble() / ramp
                    i >= ding - ramp -> (ding - 1 - i).toDouble() / ramp
                    else -> 1.0
                }
                val value = (sin(2 * PI * FREQUENCY_HZ * i / SAMPLE_RATE) * envelope * PEAK * Short.MAX_VALUE).toInt()
                val at = (start + i) * 2
                out[at] = (value and 0xFF).toByte()
                out[at + 1] = ((value shr 8) and 0xFF).toByte()
            }
        }
        return out
    }
}

/** Status and notice lines for the headset media control; they never claim that Android delivers the buttons to this app. */
object HeadsetMediaText {
    const val WAITING_FOR_HEADSET = "Headset play/pause: waiting for a headset"
    const val APP_CLOSED = "Headset play/pause works only while this app is open"
    const val UNAVAILABLE = "Headset play/pause is unavailable: Android did not give this app a media session"
    const val REGISTERED = "Headset play/pause is ready to be used while this app is open; Android decides which app receives the buttons, and this was not verified on your headset"
    const val CUE_SKIPPED = "The start tone could not be played to the headset: recording without it"
    const val PERMISSION = "Headset play/pause cannot record: the microphone permission is not granted"
}
