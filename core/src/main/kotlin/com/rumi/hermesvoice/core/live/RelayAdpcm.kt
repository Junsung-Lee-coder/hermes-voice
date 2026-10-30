package com.rumi.hermesvoice.core.live

/**
 * R41: IMA ADPCM (4 bits a sample) for one 20 ms relay packet of PCM16LE 16 kHz mono: 320 samples become 164 bytes. Every packet
 * decodes alone: its header is the predictor it starts from (its own first sample, int16 little-endian), the step index (0..88) and a
 * zero byte, then 160 bytes of codes, low nibble first. The start index comes from a 16-sample warm-up pass over the packet itself, so
 * encoding is a pure function of the packet: no stream state crosses packets, mute epochs or generations. Integer arithmetic only.
 */
internal object RelayAdpcm {
    private const val SAMPLES = WatchRelayAudio.FRAME_BYTES / 2
    private val STEPS = intArrayOf(7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
        107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166,
        1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493,
        10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767)
    private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
    init { check(STEPS.size == 89 && 4 + SAMPLES / 2 == WatchRelayAudio.CODED_BYTES) }

    private fun sample(pcm: ByteArray, i: Int) = ((pcm[2 * i].toInt() and 255) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toInt()

    /** The reconstruction step shared by the encoder and the decoder: [code] applied to [state] (predictor, index). */
    private fun apply(code: Int, state: IntArray) {
        val size = STEPS[state[1]]
        var delta = size shr 3
        if ((code and 4) != 0) delta += size
        if ((code and 2) != 0) delta += size shr 1
        if ((code and 1) != 0) delta += size shr 2
        state[0] = (if ((code and 8) != 0) state[0] - delta else state[0] + delta).coerceIn(-32768, 32767)
        state[1] = (state[1] + INDEX[code and 7]).coerceIn(0, 88)
    }

    /** The code for [sample] from [state], which then holds exactly what the decoder will reconstruct. */
    private fun code(sample: Int, state: IntArray): Int {
        val size = STEPS[state[1]]
        var diff = sample - state[0]
        var code = 0
        if (diff < 0) { code = 8; diff = -diff }
        if (diff >= size) { code = code or 4; diff -= size }
        if (diff >= size shr 1) { code = code or 2; diff -= size shr 1 }
        if (diff >= size shr 2) code = code or 1
        apply(code, state)
        return code
    }

    fun encode(pcm: ByteArray): ByteArray {
        require(pcm.size == WatchRelayAudio.FRAME_BYTES)
        val first = sample(pcm, 0)
        val warm = intArrayOf(first, 0)
        for (i in 0 until 16) code(sample(pcm, i), warm)
        val out = ByteArray(WatchRelayAudio.CODED_BYTES)
        out[0] = first.toByte(); out[1] = (first shr 8).toByte(); out[2] = warm[1].toByte(); out[3] = 0
        val state = intArrayOf(first, warm[1])
        for (i in 0 until SAMPLES) {
            val c = code(sample(pcm, i), state); val at = 4 + i / 2
            out[at] = if (i % 2 == 0) c.toByte() else (out[at].toInt() or (c shl 4)).toByte()
        }
        return out
    }

    fun decode(coded: ByteArray): ByteArray {
        require(coded.size == WatchRelayAudio.CODED_BYTES)
        val index = coded[2].toInt() and 255
        require(index in 0..88 && coded[3].toInt() == 0)
        val state = intArrayOf(((coded[0].toInt() and 255) or (coded[1].toInt() shl 8)).toShort().toInt(), index)
        val pcm = ByteArray(WatchRelayAudio.FRAME_BYTES)
        for (i in 0 until SAMPLES) {
            apply((coded[4 + i / 2].toInt() shr (4 * (i % 2))) and 15, state)
            pcm[2 * i] = state[0].toByte(); pcm[2 * i + 1] = (state[0] shr 8).toByte()
        }
        return pcm
    }
}
