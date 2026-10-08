package com.rumi.hermesvoice.core.watchlink

import com.rumi.hermesvoice.core.audio.AudioInputGate
import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.audio.CaptureEnd
import com.rumi.hermesvoice.core.audio.PcmCaptureLoop

/** What the Watch's capture lifecycle does to the device: the microphone, haptics, prompt and upload. */
interface CapturePort {
    /**
     * Stops the microphone for [captureId], the capture being ended for [reason] (only ever the
     * accepted stop of the active capture); the WAV, or null when too little was captured.
     */
    fun stopRecorder(captureId: String, reason: CaptureStop): ByteArray?
    fun haptic(event: HapticEvent)
    fun cue(line: String)
    fun upload(captureId: String, trigger: TurnTrigger, wav: ByteArray)
    fun uploadRecognized(turnId: String, text: String)
    fun discard(message: String)
}

/**
 * Why a Watch capture stopped. There is deliberately no duration limit: push-to-talk ends only
 * when the user taps, and a wake-phrase request on trailing silence or the no-speech timeout.
 * [SIZE_LIMIT] is the recorder's storage bound (on the Watch the Data Layer frame, about 13 minutes
 * of audio), a storage failure: the recording is not sent rather than sent truncated.
 * [INPUT_LOST] (Phone only): the headset microphone it records from disconnected; the capture so far is finalized like a tap.
 */
enum class CaptureStop {
    TAP_SEND, SILENCE, NO_SPEECH, SIZE_LIMIT, MIC_ERROR, LIFECYCLE, START_FAILED, INPUT_LOST;

    companion object {
        /** How a recorder's own end ([PcmCaptureLoop]) stops the capture, on either device. */
        fun of(end: CaptureEnd): CaptureStop = when (end) {
            CaptureEnd.SILENCE -> SILENCE
            CaptureEnd.NO_SPEECH -> NO_SPEECH
            CaptureEnd.LIMIT -> SIZE_LIMIT
            CaptureEnd.MIC_ERROR -> MIC_ERROR
        }
    }
}

/**
 * The Watch capture lifecycle, independent of Android. One capture at a time; every callback
 * names its capture, so late callbacks from an earlier capture are ignored. The start haptic fires
 * once when the microphone really delivers audio (push-to-talk) or when calibration completes and
 * the user is cued (wake phrase); the end haptic fires once, after the microphone stopped, and
 * only if the start one did. Whoever stops a capture first (tap, endpoint, lifecycle, failure)
 * decides; later stops are no-ops. A finished recording with no usable audio is discarded as
 * "No speech detected" instead of being uploaded.
 */
class CaptureCoordinator(
    private val port: CapturePort,
    private val inputGate: (ByteArray) -> AudioInputVerdict = { AudioInputGate.assess(it, WatchTurnUpload.MIME_WAV) },
) {
    private val haptics = RecordingHapticLatch()
    private var trigger: TurnTrigger? = null

    var activeId: String? = null
        private set

    fun begin(captureId: String, trigger: TurnTrigger): Boolean {
        if (activeId != null) return false
        activeId = captureId
        this.trigger = trigger
        return true
    }

    /** First positive microphone read. */
    fun onLive(captureId: String) {
        if (captureId != activeId || trigger != TurnTrigger.PUSH_TO_TALK) return
        haptics.onStarted(captureId)?.let(port::haptic)
    }

    /** Noise floor measured: cue a wake-phrase request. */
    fun onCalibrated(captureId: String) {
        if (captureId != activeId || trigger != TurnTrigger.WAKE_PHRASE) return
        haptics.onStarted(captureId)?.let {
            port.cue("Speak now…")
            port.haptic(it)
        }
    }

    fun tap(): Boolean = activeId?.let { stop(it, CaptureStop.TAP_SEND) } ?: false

    fun lifecycle(): Boolean = activeId?.let { stop(it, CaptureStop.LIFECYCLE) } ?: false

    /** Ends [captureId] exactly once; false when it is not the active capture any more. */
    fun stop(captureId: String, reason: CaptureStop): Boolean {
        if (captureId != activeId) return false
        val turnTrigger = trigger!!
        activeId = null
        trigger = null
        val wav = port.stopRecorder(captureId, reason)
        haptics.onEnded(captureId)?.let(port::haptic)
        when (reason) {
            // A recording with no usable audio is never uploaded (see AudioInputGate).
            CaptureStop.TAP_SEND, CaptureStop.SILENCE, CaptureStop.INPUT_LOST -> when {
                wav == null -> port.discard("Too short")
                inputGate(wav) != AudioInputVerdict.USABLE -> port.discard("No speech detected")
                else -> port.upload(captureId, turnTrigger, wav)
            }
            CaptureStop.NO_SPEECH -> port.discard("Didn't hear a request")
            CaptureStop.SIZE_LIMIT -> port.discard("Recording too long; nothing was sent")
            CaptureStop.MIC_ERROR, CaptureStop.START_FAILED -> port.discard("Microphone unavailable")
            CaptureStop.LIFECYCLE -> port.discard("Cancelled")
        }
        return true
    }

    /** A request the wake recognizer heard in full: its capture ended there, so one end pulse, then send. */
    fun sendRecognized(turnId: String, text: String) {
        port.haptic(HapticEvent.RECORDING_END)
        port.uploadRecognized(turnId, text)
    }
}
