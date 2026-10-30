package com.rumi.hermesvoice.core.live

/**
 * R41: the Phone's streaming converter for the provider's Watch downlink. The pinned remote sink delivers the provider's 48 kHz mono and
 * has no rate to choose, so the app converts it itself: a 128-tap Blackman-windowed-sinc low-pass at 7.2 kHz (flat to 6 kHz, -43 dB at
 * 8 kHz, below -77 dB from 8.8 kHz, 1.3 ms of delay) evaluated at every third input sample, so 480 input samples (10 ms) always give
 * exactly 160. Outputs are gathered into whole 20 ms packets of PCM16LE 16 kHz mono ([WatchRelayAudio.DOWNLINK_FRAME_BYTES]).
 * One instance is one downlink generation: a new one starts from silence, and dropping it drops its partial packet, which is therefore
 * never sent after Stop, an output disable or a private handoff. Bounded state (128 samples and one partial packet); not thread-safe.
 */
class WatchRelayDownlink {
    private val ring = DoubleArray(TAPS)
    private var at = 0
    private var phase = 0
    private val packet = ByteArray(WatchRelayAudio.DOWNLINK_FRAME_BYTES)
    private var filled = 0

    /** [pcm48] PCM16LE 48 kHz mono; returns the whole 16 kHz packets it completed, oldest first (often none, at most a few). */
    fun push(pcm48: ByteArray): List<ByteArray> {
        require(pcm48.size % 2 == 0)
        val out = ArrayList<ByteArray>(2)
        for (i in 0 until pcm48.size / 2) {
            ring[at] = ((pcm48[2 * i].toInt() and 255) or (pcm48[2 * i + 1].toInt() shl 8)).toShort().toDouble()
            at = (at + 1) % TAPS
            if (++phase < 3) continue
            phase = 0
            var acc = 0.0
            var j = at
            for (k in 0 until TAPS) { j = if (j == 0) TAPS - 1 else j - 1; acc += COEFFS[k] * ring[j] } // newest sample first
            val v = Math.round(acc).coerceIn(-32768L, 32767L).toInt()
            packet[filled] = v.toByte(); packet[filled + 1] = (v shr 8).toByte(); filled += 2
            if (filled == packet.size) { out += packet.copyOf(); filled = 0 }
        }
        return out
    }

    companion object {
        const val TAPS = 128
        private const val CUTOFF_HZ = 7200.0
        private const val INPUT_HZ = 48000.0
        /** Unity-gain at DC; computed once with StrictMath so every runtime gets the same coefficients. */
        internal val COEFFS: DoubleArray = DoubleArray(TAPS).also { h ->
            val middle = (TAPS - 1) / 2.0; val a = 2 * CUTOFF_HZ / INPUT_HZ; var sum = 0.0
            for (k in 0 until TAPS) {
                val x = Math.PI * a * (k - middle)
                val sinc = if (x == 0.0) 1.0 else StrictMath.sin(x) / x
                val window = 0.42 - 0.5 * StrictMath.cos(2 * Math.PI * k / (TAPS - 1)) + 0.08 * StrictMath.cos(4 * Math.PI * k / (TAPS - 1))
                h[k] = a * sinc * window; sum += h[k]
            }
            for (k in 0 until TAPS) h[k] /= sum
        }
    }
}
