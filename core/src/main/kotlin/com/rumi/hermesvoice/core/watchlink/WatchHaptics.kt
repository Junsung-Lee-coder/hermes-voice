package com.rumi.hermesvoice.core.watchlink

/**
 * Watch haptics, pure so the timing rules are testable off-device. Durations follow the original
 * Recorder Watch policy: recording start 50 ms, recording end 2 × 30 ms with a 12 ms pause, and a
 * 10 ms tick per rotary/bezel scroll step. There is no haptic for button intent, thinking, timers
 * or recomposition.
 */
enum class HapticEvent { RECORDING_START, RECORDING_END, SCROLL_STEP }

data class HapticPattern(val pulseMs: Long, val pulses: Int, val pauseMs: Long = 12) {
    /** `VibrationEffect.createWaveform` timings: leading 0 ms delay, then pulse/pause alternating. */
    fun timings(): LongArray = buildList {
        add(0L)
        repeat(pulses.coerceAtLeast(1)) { index ->
            add(pulseMs)
            if (index != pulses - 1) add(pauseMs)
        }
    }.toLongArray()
}

/**
 * How a haptic is attributed to the system. Recording start/end tell the wearer the microphone
 * state changed (hardware feedback), so a "touch feedback off" setting does not silence them;
 * scroll steps are touch feedback. Neither bypasses Do Not Disturb or the user's vibration settings.
 */
enum class HapticUsage { HARDWARE_FEEDBACK, TOUCH }

object WatchHapticPolicy {
    fun usageFor(event: HapticEvent): HapticUsage = when (event) {
        HapticEvent.RECORDING_START, HapticEvent.RECORDING_END -> HapticUsage.HARDWARE_FEEDBACK
        HapticEvent.SCROLL_STEP -> HapticUsage.TOUCH
    }

    fun patternFor(event: HapticEvent): HapticPattern = when (event) {
        HapticEvent.RECORDING_START -> HapticPattern(50, 1)
        HapticEvent.RECORDING_END -> HapticPattern(30, 2)
        HapticEvent.SCROLL_STEP -> HapticPattern(10, 1)
    }
}

/**
 * Start fires once, after the microphone is actually delivering audio for that capture; end
 * fires once when that capture leaves recording for any reason (send, cancel, no speech, error,
 * lifecycle). An end without a started capture, or for an older capture, is silent.
 */
class RecordingHapticLatch {
    private var started: String? = null

    @Synchronized
    fun onStarted(captureId: String): HapticEvent? {
        if (started == captureId) return null
        started = captureId
        return HapticEvent.RECORDING_START
    }

    @Synchronized
    fun onEnded(captureId: String): HapticEvent? {
        if (started != captureId) return null
        started = null
        return HapticEvent.RECORDING_END
    }
}

/** A scroll tick only when the list really moved, at most once per [minIntervalMs] (no buzz at the bounds). */
class ScrollHapticGate(private val minIntervalMs: Long = 50) {
    private var lastPulseMs = Long.MIN_VALUE

    fun shouldPulse(requestedPx: Float, consumedPx: Float, nowMs: Long): Boolean {
        if (requestedPx == 0f || consumedPx == 0f) return false
        if (lastPulseMs != Long.MIN_VALUE && nowMs - lastPulseMs < minIntervalMs) return false
        lastPulseMs = nowMs
        return true
    }
}

/**
 * Coalesces high-rate rotary events: deltas that arrive while a scroll is running are summed and
 * applied by the running drain loop, so no rotation is lost and scrolls never pile up.
 */
class RotaryScrollAccumulator {
    private var pending = 0f
    private var draining = false

    /** Adds [px]; true when the caller must start a drain loop. */
    fun add(px: Float): Boolean {
        pending += px
        if (draining) return false
        draining = true
        return true
    }

    /** Takes everything accumulated; 0 ends the drain loop. */
    fun drain(): Float {
        val value = pending
        pending = 0f
        if (value == 0f) draining = false
        return value
    }

    /** The drain loop stopped (possibly cancelled): pending rotation is dropped, never replayed later. */
    fun finish() {
        pending = 0f
        draining = false
    }
}

/**
 * Bezel scrolling for one list: [offer] each rotary delta; when it returns true, run [drain] until
 * it returns. The list is scrolled by everything accumulated, and a tick is requested only when
 * the list actually moved ([ScrollHapticGate]).
 */
class RotaryScrollDriver(private val haptics: ScrollHapticGate = ScrollHapticGate()) {
    private val accumulator = RotaryScrollAccumulator()

    fun offer(px: Float): Boolean = accumulator.add(px)

    suspend fun drain(scrollBy: suspend (Float) -> Float, nowMs: () -> Long, onTick: () -> Unit) {
        try {
            while (true) {
                val requested = accumulator.drain()
                if (requested == 0f) break
                val consumed = scrollBy(requested)
                if (haptics.shouldPulse(requested, consumed, nowMs())) onTick()
            }
        } finally {
            accumulator.finish()
        }
    }
}
