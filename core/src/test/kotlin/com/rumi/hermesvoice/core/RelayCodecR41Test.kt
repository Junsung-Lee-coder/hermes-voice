package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.*
import org.junit.Test
import org.junit.Assert.*

/**
 * R41's two app-local streaming stages, alone: [RelayAdpcm] (IMA ADPCM, one stateless 20 ms packet of 16 kHz mono: 320 samples in,
 * 164 bytes out) and [WatchRelayDownlink] (the Phone's 48 -> 16 kHz converter for the provider's remote-sink output). Counts, order,
 * response, split/coalescing invariance, bounded state and generation reset; no platform, no dependency.
 */
class RelayCodecR41Test {
    private fun pcm(values: IntArray) = ByteArray(2 * values.size).also { b -> values.forEachIndexed { i, v -> b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() } }
    private fun samples(pcm: ByteArray) = IntArray(pcm.size / 2) { ((pcm[2 * it].toInt() and 255) or (pcm[2 * it + 1].toInt() shl 8)).toShort().toInt() }
    private fun tone48(n: Int, vararg parts: Pair<Double, Double>, from: Int = 0) = IntArray(n) { i -> Math.round(parts.sumOf { (hz, a) -> a * Math.sin(2 * Math.PI * hz * (from + i) / 48000.0) }).toInt() }
    /** One DFT bin's amplitude over the longest prefix of 160 samples: whole periods of every test frequency here at 16 kHz. */
    private fun amplitude(x: IntArray, hz: Double, rate: Double = 16000.0): Double {
        val n = x.size / 160 * 160; var re = 0.0; var im = 0.0
        for (i in 0 until n) { val a = 2 * Math.PI * hz * i / rate; re += x[i] * Math.cos(a); im -= x[i] * Math.sin(a) }
        return 2 * Math.sqrt(re * re + im * im) / n
    }
    private fun snrDb(x: IntArray, y: IntArray): Double { var s = 0.0; var e = 0.0; for (i in x.indices) { s += x[i].toDouble() * x[i]; e += (x[i] - y[i]).toDouble() * (x[i] - y[i]) }; return 10 * Math.log10(s / maxOf(e, 1e-9)) }
    private fun convert(c: WatchRelayDownlink, x: IntArray, chunk: Int) = ArrayList<ByteArray>().also { out ->
        var at = 0; while (at < x.size) { val n = minOf(chunk, x.size - at); out += c.push(pcm(x.copyOfRange(at, at + n))); at += n } }

    // ── RelayAdpcm ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun A01_onePacketIs320SamplesIn164BytesOutAndDecodesBackTo320() {
        val x = IntArray(320) { Math.round(8000 * Math.sin(2 * Math.PI * 440 * it / 16000.0)).toInt() }
        val coded = RelayAdpcm.encode(pcm(x)); assertEquals(164, coded.size); assertEquals(WatchRelayAudio.CODED_BYTES, coded.size)
        assertEquals("the predictor is the first sample", x[0], ((coded[0].toInt() and 255) or (coded[1].toInt() shl 8)).toShort().toInt())
        assertTrue((coded[2].toInt() and 255) in 0..88); assertEquals(0, coded[3].toInt())
        val back = RelayAdpcm.decode(coded); assertEquals(640, back.size)
        assertTrue("a speech-band tone: ${snrDb(x, samples(back))} dB", snrDb(x, samples(back)) >= 25.0)
        assertArrayEquals("a pure function of the packet", coded, RelayAdpcm.encode(pcm(x)))
        for (bad in listOf(0, 638, 642, 960)) assertThrows(IllegalArgumentException::class.java) { RelayAdpcm.encode(ByteArray(bad)) }
        for (bad in listOf(0, 163, 165)) assertThrows(IllegalArgumentException::class.java) { RelayAdpcm.decode(ByteArray(bad)) }
        assertThrows(IllegalArgumentException::class.java) { RelayAdpcm.decode(coded.copyOf().also { it[2] = 89 }) }
        assertThrows(IllegalArgumentException::class.java) { RelayAdpcm.decode(coded.copyOf().also { it[3] = 1 }) }
    }

    @Test fun A02_noiseQuietSpeechAndSilenceAreCarriedAndFullScaleNeverWraps() {
        val rng = java.util.Random(41)
        val noise = IntArray(320 * 25) { (rng.nextGaussian() * 1036).toInt().coerceIn(-32768, 32767) }
        val quiet = IntArray(320 * 25) { Math.round(300 * Math.sin(2 * Math.PI * 300 * it / 16000.0) + 60 * Math.sin(2 * Math.PI * 1900 * it / 16000.0)).toInt() }
        fun carried(x: IntArray) = (0 until x.size / 320).flatMap { k -> samples(RelayAdpcm.decode(RelayAdpcm.encode(pcm(x.copyOfRange(320 * k, 320 * k + 320))))).toList() }.toIntArray()
        assertTrue("white noise at -30 dBFS", snrDb(noise, carried(noise)) >= 10.0)
        assertTrue("quiet speech-band audio", snrDb(quiet, carried(quiet)) >= 20.0)
        assertArrayEquals("silence is exact", ByteArray(640), RelayAdpcm.decode(RelayAdpcm.encode(ByteArray(640))))
        val extreme = IntArray(320) { if (it % 2 == 0) 32767 else -32768 }
        val back = samples(RelayAdpcm.decode(RelayAdpcm.encode(pcm(extreme))))
        assertTrue("every decoded sample is a PCM16 value", back.all { it in -32768..32767 })
    }

    // ── WatchRelayDownlink ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun D01_every480InputSamplesGive160OutputSamplesInWhole640BytePacketsWhateverTheSplit() {
        val x = tone48(48000, 1000.0 to 8000.0, 9000.0 to 4000.0) // one second
        val whole = convert(WatchRelayDownlink(), x, 480)
        assertEquals("48000 samples in, 16000 out, as 50 whole packets", 50, whole.size)
        assertTrue(whole.all { it.size == WatchRelayAudio.DOWNLINK_FRAME_BYTES })
        for (chunk in listOf(1, 7, 160, 479, 481, 960, 4800)) {
            val split = convert(WatchRelayDownlink(), x, chunk)
            assertEquals("chunk $chunk: the same packets", whole.size, split.size)
            whole.indices.forEach { assertArrayEquals("chunk $chunk packet $it", whole[it], split[it]) }
        }
        val c = WatchRelayDownlink()
        assertTrue("half a packet completes nothing", c.push(pcm(x.copyOf(480))).isEmpty())
        assertEquals("the other half completes exactly one", 1, c.push(pcm(x.copyOfRange(480, 960))).size)
        assertThrows("PCM16 only", IllegalArgumentException::class.java) { c.push(ByteArray(3)) }
    }

    @Test fun D02_speechBandPassesAtLevelAndWhatA16kHzWatchCannotCarryNeverFoldsBack() {
        val out = convert(WatchRelayDownlink(), tone48(96000, 1000.0 to 8000.0, 9000.0 to 4000.0), 480).flatMap { samples(it).toList() }.drop(320).toIntArray()
        val tone = amplitude(out, 1000.0); val folded = amplitude(out, 7000.0)
        assertEquals("1 kHz at its level", 8000.0, tone, 8000 * 0.01)
        assertTrue("9 kHz folds to 7 kHz at 16 kHz: $folded, at least 60 dB under its 4000", folded < 4.0)
        for ((hz, low) in listOf(300.0 to 0.99, 3400.0 to 0.99, 6000.0 to 0.94)) {
            val y = convert(WatchRelayDownlink(), tone48(48000, hz to 10000.0), 480).flatMap { samples(it).toList() }.drop(320).toIntArray()
            val a = amplitude(y, hz)
            assertTrue("$hz Hz passes: $a", a in 10000 * low..10000 * 1.01)
        }
        val dc = convert(WatchRelayDownlink(), IntArray(4800) { 1000 }, 480).flatMap { samples(it).toList() }
        assertTrue("unity gain at DC", dc.drop(64).all { it in 999..1001 })
        val loud = convert(WatchRelayDownlink(), IntArray(4800) { 32767 }, 480).flatMap { samples(it).toList() }
        assertTrue("full scale clamps, never wraps", loud.drop(64).all { it in 32000..32767 })
    }

    @Test fun D03_eachInstanceIsOneGenerationStartingFromSilenceAndItsPartialPacketDiesWithIt() {
        val x = tone48(9600, 1000.0 to 8000.0)
        val first = convert(WatchRelayDownlink(), x, 480); val again = convert(WatchRelayDownlink(), x, 480)
        first.indices.forEach { assertArrayEquals("a new generation is the same function of its own input", first[it], again[it]) }
        assertTrue("it starts from silence: the first output sample sees only the newest inputs", Math.abs(samples(first[0])[0]) < 100)
        val held = WatchRelayDownlink(); assertTrue(held.push(pcm(x.copyOf(480))).isEmpty()) // a partial packet held back
        val next = WatchRelayDownlink(); assertTrue("nothing of the dropped generation's partial packet crosses into the next", next.push(pcm(x.copyOf(480))).isEmpty())
        assertArrayEquals(first[0], next.push(pcm(x.copyOfRange(480, 960))).single())
        assertEquals("bounded history: 128 taps", 128, WatchRelayDownlink.TAPS)
    }
}
