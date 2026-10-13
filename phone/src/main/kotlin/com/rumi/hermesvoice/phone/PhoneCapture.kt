package com.rumi.hermesvoice.phone

import android.media.AudioFormat
import android.media.AudioRecord
import com.rumi.hermesvoice.core.audio.CaptureStats
import com.rumi.hermesvoice.core.audio.PcmCaptureLoop
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import com.rumi.hermesvoice.core.headset.HeadsetText
import com.rumi.hermesvoice.core.headset.MicPlan
import com.rumi.hermesvoice.core.headset.MicReport
import java.util.concurrent.atomic.AtomicBoolean

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
    private val records: RecordFactory,
) {
    private var record: RecordPort? = null
    private var loop: PcmCaptureLoop? = null
    private var worker: Thread? = null
    private var plan: MicPlan? = null

    /**
     * Starts recording. With a [MicPlan] it asks Android for the headset input before the first read and, after the first
     * positive read, hands [onSource] the source Android's readback confirms (never the setter's result). A plan whose input
     * cannot be requested is released and the Phone microphone is used, said so through [onSource].
     */
    fun start(plan: MicPlan? = null, onSource: (MicReport) -> Unit = {}): Boolean {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = records.create(min.coerceAtLeast(CHUNK_BYTES * 2))?.takeIf { it.initialized }
        if (recorder == null) {
            plan?.release()
            return false
        }
        val wanted = plan?.preferred
        var requested = true
        if (wanted != null) requested = recorder.setPreferredDevice(wanted.id)
        if (!recorder.start()) {
            recorder.release()
            plan?.release()
            return false
        }
        this.plan = plan
        record = recorder
        val reported = AtomicBoolean(false)
        val captureLoop = PcmCaptureLoop({
            val n = recorder.read(it, 0, it.size)
            if (n > 0 && plan != null && reported.compareAndSet(false, true)) {
                onSource(if (wanted != null && !requested) MicReport(false, HeadsetText.NOT_ROUTED).also { plan.release() } else plan.report(recorder.routedDeviceId()))
            }
            n
        }, LIMIT_BYTES, endpoint, listener, chunkBytes = CHUNK_BYTES)
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
        plan?.release()
        plan = null
        return loop?.let { PcmCaptureLoop.wav(it.pcm(), SAMPLE_RATE) }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 160 ms per read, like the push-to-talk recorder. */
        const val CHUNK_BYTES = 5_120
        const val LIMIT_BYTES: Long = SAMPLE_RATE * 2L * 60 * 13
    }
}
