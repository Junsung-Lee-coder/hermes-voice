package com.rumi.hermesvoice.phone

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.rumi.hermesvoice.core.audio.CaptureStats
import com.rumi.hermesvoice.core.audio.PcmCaptureLoop
import com.rumi.hermesvoice.core.audio.SilenceEndpoint

/**
 * The Phone's hands-free recorder: 16 kHz mono PCM16 on the app's own `AudioRecord`, running the
 * shared [PcmCaptureLoop] and [SilenceEndpoint] exactly as the Watch does. It ends after the
 * trailing-silence setting it was started with, on no speech, or when the caller stops it (tap,
 * pause, opt-out). No duration cap: [LIMIT_BYTES] is a memory bound (about 13 minutes of audio);
 * reaching it discards the recording rather than sending it truncated. Listener callbacks run on
 * the capture thread.
 */
class PhoneCapture(
    val captureId: String,
    private val endpoint: SilenceEndpoint,
    private val listener: PcmCaptureLoop.Listener,
) {
    private var record: AudioRecord? = null
    private var loop: PcmCaptureLoop? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // Checked before a hands-free capture is started.
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, min.coerceAtLeast(CHUNK_BYTES * 2))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return false
        val started = runCatching { recorder.startRecording() }.isSuccess &&
            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) {
            recorder.release()
            return false
        }
        record = recorder
        val captureLoop = PcmCaptureLoop({ recorder.read(it, 0, it.size) }, LIMIT_BYTES, endpoint, listener, chunkBytes = CHUNK_BYTES)
        loop = captureLoop
        worker = Thread(captureLoop::run, "hermes-voice-phone-hands-free").apply { start() }
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

        /** 160 ms per read, like the push-to-talk recorder. */
        const val CHUNK_BYTES = 5_120
        const val LIMIT_BYTES: Long = SAMPLE_RATE * 2L * 60 * 13
    }
}
