package com.rumi.hermesvoice.watch

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.rumi.hermesvoice.core.audio.CaptureStats
import com.rumi.hermesvoice.core.audio.PcmCaptureLoop
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.TurnTrigger

/**
 * 16 kHz mono PCM16 capture to an in-memory WAV on the app's own `AudioRecord`, running the shared
 * [PcmCaptureLoop] (the Phone runs the same loop) with an optional hands-free [SilenceEndpoint].
 * Callbacks run on the capture thread; the caller hops to the UI. No capture has a duration cap:
 * [limitBytes] is only the Data Layer frame bound (a storage limit; reaching it discards).
 */
class WatchCapture(
    val turnId: String,
    val trigger: TurnTrigger,
    private val limitBytes: Long,
    private val endpoint: SilenceEndpoint?,
    private val listener: PcmCaptureLoop.Listener,
) {
    private var record: AudioRecord? = null
    private var loop: PcmCaptureLoop? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // Checked by the activity before starting.
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, min.coerceAtLeast(FRAME_BYTES * 2))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return false
        val started = runCatching { recorder.startRecording() }.isSuccess &&
            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) {
            recorder.release()
            return false
        }
        record = recorder
        val captureLoop = PcmCaptureLoop({ recorder.read(it, 0, it.size) }, limitBytes, endpoint, listener, chunkBytes = FRAME_BYTES)
        loop = captureLoop
        worker = Thread(captureLoop::run, "hermes-voice-watch-capture").apply { start() }
        return true
    }

    fun stats(): CaptureStats? = loop?.stats()

    /** Stops the microphone and returns the WAV, or null when too little was captured. */
    fun stop(): ByteArray? {
        loop?.stop()
        runCatching { record?.stop() }
        if (Thread.currentThread() !== worker) runCatching { worker?.join(1_500) }
        runCatching { record?.release() }
        record = null
        return loop?.let { PcmCaptureLoop.wav(it.pcm(), SAMPLE_RATE) }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 3_200

        /** The largest WAV one Data Layer turn frame can carry (about 13 minutes): a storage bound, not a time limit. */
        const val FRAME_BOUND_PCM_BYTES: Long = LinkFrame.MAX_PAYLOAD_BYTES - 44L
    }
}
