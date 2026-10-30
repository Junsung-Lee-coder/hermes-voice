package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*

/**
 * R41 wire identity through the real shared codec, stated literally (the wire, not the production constants): BOTH directions are
 * 20 ms packets of 16 kHz mono speech carried as 4-bit IMA ADPCM, 28 header bytes then 164 payload bytes (the predictor int16 LE, the
 * step index, a zero byte, then 320 codes low nibble first), 9.6 kB/s each way. Each packet decodes alone; silence is exact; an R40
 * PCM frame, a wrong payload size, a bad step index or reserved byte, and every truncation are refused.
 */
class WatchRelayAudioR41Test {
    private fun encode(direction: Int, f: LivePcmFrame) = ByteArrayOutputStream().also { WatchRelayAudio.write(DataOutputStream(it), direction, f) }.toByteArray()
    private fun read(bytes: ByteArray, direction: Int) = WatchRelayAudio.read(DataInputStream(ByteArrayInputStream(bytes)), direction)
    private fun header(magic: Int, direction: Int, seq: Long, rate: Int, channels: Int, size: Int) = ByteArrayOutputStream().also {
        DataOutputStream(it).apply { writeInt(magic); writeInt(direction); writeLong(seq); writeInt(rate); writeInt(channels); writeInt(size) }
    }.toByteArray()
    private fun pcm(values: IntArray) = ByteArray(2 * values.size).also { b -> values.forEachIndexed { i, v -> b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() } }
    private fun samples(pcm: ByteArray) = IntArray(pcm.size / 2) { ((pcm[2 * it].toInt() and 255) or (pcm[2 * it + 1].toInt() shl 8)).toShort().toInt() }
    /** [packets] packets of a speech-like signal at 16 kHz: 180, 720 and 2400 Hz under a 3 Hz envelope, peak [peak]. */
    private fun speech(packets: Int, peak: Double = 16000.0) = IntArray(320 * packets) { n -> val t = n / 16000.0
        (peak / 0.95 * (0.5 * Math.sin(2 * Math.PI * 180 * t) + 0.3 * Math.sin(2 * Math.PI * 720 * t) + 0.15 * Math.sin(2 * Math.PI * 2400 * t)) * (0.6 + 0.4 * Math.sin(2 * Math.PI * 3 * t))).toInt() }
    private fun packets(x: IntArray) = (0 until x.size / 320).map { pcm(x.copyOfRange(320 * it, 320 * it + 320)) }
    private fun snrDb(x: IntArray, y: IntArray): Double { var s = 0.0; var e = 0.0; for (i in x.indices) { s += x[i].toDouble() * x[i]; e += (x[i] - y[i]).toDouble() * (x[i] - y[i]) }; return 10 * Math.log10(s / maxOf(e, 1e-9)) }
    private fun carried(direction: Int, x: IntArray) = packets(x).flatMapIndexed { k, p -> samples(read(encode(direction, LivePcmFrame(k + 1L, 16000, 1, p)), direction).pcm).toList() }.toIntArray()

    @Test fun K01_bothDirectionsAre20msPacketsOf16kHzMonoSpeechNamedByOneR41Offer() {
        for (direction in listOf(WatchRelayAudio.UPLINK, WatchRelayAudio.DOWNLINK)) {
            assertEquals("direction $direction rate", 16000, WatchRelayAudio.rateOf(direction))
            assertTrue("direction $direction: a 20 ms 16 kHz mono packet", WatchRelayAudio.valid(LivePcmFrame(1, 16000, 1, ByteArray(640)), direction))
            for ((rate, size) in listOf(48000 to 960, 48000 to 640, 16000 to 320, 16000 to 1280, 8000 to 320))
                assertFalse("direction $direction: $rate/$size is not a packet", WatchRelayAudio.valid(LivePcmFrame(1, rate, 1, ByteArray(size)), direction))
            assertFalse(WatchRelayAudio.valid(LivePcmFrame(1, 16000, 2, ByteArray(1280)), direction))
        }
        assertEquals(16000, WatchRelayAudio.UPLINK_RATE); assertEquals(640, WatchRelayAudio.FRAME_BYTES)
        assertEquals("the Watch hears 16 kHz", 16000, WatchRelayAudio.DOWNLINK_RATE); assertEquals(640, WatchRelayAudio.DOWNLINK_FRAME_BYTES)
        assertEquals("phone-relay/ima-adpcm/16000/20ms/mono", WatchRelayAudio.OFFER)
    }

    @Test fun K02_onePacketIs28HeaderBytesThenA164ByteAdpcmPayloadInBothDirections() {
        for (direction in listOf(WatchRelayAudio.UPLINK, WatchRelayAudio.DOWNLINK)) {
            val values = speech(1); val f = LivePcmFrame(WatchRelayAudio.sequence(3, 9), 16000, 1, pcm(values))
            val bytes = encode(direction, f)
            assertEquals("192 bytes a packet: 9.6 kB/s at 50 packets a second", 192, bytes.size)
            assertArrayEquals("the header, field by field", header(0x48564134, direction, f.sequence, 16000, 1, 164), bytes.copyOf(28))
            assertEquals("the predictor is the packet's own first sample", values[0], ((bytes[28].toInt() and 255) or (bytes[29].toInt() shl 8)).toShort().toInt())
            assertTrue("a step index", (bytes[30].toInt() and 255) in 0..88); assertEquals("the reserved byte", 0, bytes[31].toInt())
            val back = read(bytes, direction)
            assertEquals(f.sequence, back.sequence); assertEquals(16000, back.sampleRate); assertEquals(1, back.channels); assertEquals(640, back.pcm.size)
            assertEquals(3L, WatchRelayAudio.captureEpoch(back.sequence)); assertEquals(9L, WatchRelayAudio.counter(back.sequence))
        }
    }

    @Test fun K03_speechSurvivesEachPacketDecodesAloneAndSilenceIsExact() {
        val loud = speech(50); val quiet = speech(50, peak = 328.0)
        for (direction in listOf(WatchRelayAudio.UPLINK, WatchRelayAudio.DOWNLINK)) {
            assertTrue("speech at -6 dBFS", snrDb(loud, carried(direction, loud)) >= 20.0)
            assertTrue("speech at -40 dBFS", snrDb(quiet, carried(direction, quiet)) >= 20.0)
            assertArrayEquals("silence decodes to exact silence", ByteArray(640), read(encode(direction, LivePcmFrame(1, 16000, 1, ByteArray(640))), direction).pcm)
        }
        // A stream of packets decodes exactly as each packet does alone: no state crosses packets, mute epochs or generations.
        val ps = packets(loud).take(4)
        val stream = ps.foldIndexed(ByteArray(0)) { k, acc, p -> acc + encode(WatchRelayAudio.UPLINK, LivePcmFrame(k + 1L, 16000, 1, p)) }
        val input = DataInputStream(ByteArrayInputStream(stream))
        ps.forEachIndexed { k, p -> assertArrayEquals("packet $k", read(encode(WatchRelayAudio.UPLINK, LivePcmFrame(9L, 16000, 1, p)), WatchRelayAudio.UPLINK).pcm, WatchRelayAudio.read(input, WatchRelayAudio.UPLINK).pcm) }
        // Full scale never wraps: a +-32767 square stays on its own side.
        val square = IntArray(320) { if ((it / 40) % 2 == 0) 32767 else -32767 }
        val back = samples(read(encode(WatchRelayAudio.UPLINK, LivePcmFrame(1, 16000, 1, pcm(square))), WatchRelayAudio.UPLINK).pcm)
        for (half in 0 until 8) assertTrue("half $half", if (half % 2 == 0) back[40 * half + 39] > 16000 else back[40 * half + 39] < -16000)
    }

    @Test fun K04_anR40PcmFrameAWrongSizeABadStepIndexOrReservedByteAndEveryTruncationAreRefused() {
        val r40 = header(0x48565233, WatchRelayAudio.UPLINK, 1, 16000, 1, 640) + pcm(speech(1))
        assertThrows("an R40 PCM frame", IllegalArgumentException::class.java) { read(r40, WatchRelayAudio.UPLINK) }
        val r40down = header(0x48565233, WatchRelayAudio.DOWNLINK, 1, 48000, 1, 960) + ByteArray(960) { 3 }
        assertThrows("an R40 48 kHz downlink frame", IllegalArgumentException::class.java) { read(r40down, WatchRelayAudio.DOWNLINK) }
        val one = encode(WatchRelayAudio.UPLINK, LivePcmFrame(1, 16000, 1, pcm(speech(1))))
        for (size in listOf(0, 2, 163, 165, 640, 7681)) assertThrows("payload $size", IllegalArgumentException::class.java) { read(header(0x48564134, 1, 1, 16000, 1, size) + ByteArray(maxOf(0, size)), WatchRelayAudio.UPLINK) }
        assertThrows("step index 89", IllegalArgumentException::class.java) { read(one.copyOf().also { it[30] = 89 }, WatchRelayAudio.UPLINK) }
        assertThrows("reserved byte", IllegalArgumentException::class.java) { read(one.copyOf().also { it[31] = 1 }, WatchRelayAudio.UPLINK) }
        for (cut in 0 until one.size) assertThrows("cut at $cut", EOFException::class.java) { read(one.copyOf(cut), WatchRelayAudio.UPLINK) }
    }
}
