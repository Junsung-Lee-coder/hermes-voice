package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*

/**
 * R40 wire identity through the real shared codec: the Watch microphone uplink is speech-grade PCM16 16 kHz mono in 20 ms packets
 * (640 bytes), the provider downlink stays the pinned remote sink's PCM16 48 kHz mono, and nothing else is accepted in either
 * direction. The formats are literal on purpose: the test states the wire, not the production constants.
 */
class WatchRelayAudioR40Test {
    private fun pcm(bytes: Int, seed: Int) = ByteArray(bytes) { ((it * 31 + seed) % 251 + 1).toByte() }
    private fun encode(direction: Int, f: LivePcmFrame) = ByteArrayOutputStream().also { WatchRelayAudio.write(DataOutputStream(it), direction, f) }.toByteArray()
    private fun header(direction: Int, seq: Long, rate: Int, channels: Int, size: Int) = ByteArrayOutputStream().also {
        DataOutputStream(it).apply { writeInt(0x48565233); writeInt(direction); writeLong(seq); writeInt(rate); writeInt(channels); writeInt(size) }
    }.toByteArray()
    private fun read(bytes: ByteArray, direction: Int) = WatchRelayAudio.read(DataInputStream(ByteArrayInputStream(bytes)), direction)

    @Test fun C01_aSpeechGradeUplinkPacketIs20msOf16kHzMonoAndCrossesTheRealCodecByteExact() {
        val f = LivePcmFrame(WatchRelayAudio.sequence(2, 7), 16000, 1, pcm(640, 1))
        assertTrue("a 20 ms 16 kHz mono packet is the uplink", WatchRelayAudio.valid(f, WatchRelayAudio.UPLINK))
        assertEquals("the uplink packet size", 640, WatchRelayAudio.FRAME_BYTES)
        val bytes = encode(WatchRelayAudio.UPLINK, f)
        assertArrayEquals("header then PCM, field by field", header(1, f.sequence, 16000, 1, 640) + f.pcm, bytes)
        val back = read(bytes, WatchRelayAudio.UPLINK)
        assertEquals(f.sequence, back.sequence); assertEquals(16000, back.sampleRate); assertEquals(1, back.channels); assertArrayEquals(f.pcm, back.pcm)
        assertEquals(2L, WatchRelayAudio.captureEpoch(back.sequence)); assertEquals(7L, WatchRelayAudio.counter(back.sequence))
    }

    @Test fun C02_theRaw48kHzUplinkAndEveryOtherUplinkShapeIsRefused() {
        // The T39 relay frame, a 10 ms half packet, 40 ms, stereo, 8 kHz and 48 kHz at the new size.
        for ((rate, channels, size) in listOf(Triple(48000, 1, 960), Triple(16000, 1, 320), Triple(16000, 1, 1280), Triple(16000, 2, 1280), Triple(8000, 1, 320), Triple(48000, 1, 640))) {
            assertFalse("$rate/$channels/$size is not the uplink", WatchRelayAudio.valid(LivePcmFrame(1, rate, channels, ByteArray(size) { 3 }), WatchRelayAudio.UPLINK))
            assertThrows("$rate/$channels/$size is refused on read", IllegalArgumentException::class.java) { read(header(1, 1, rate, channels, size) + ByteArray(size) { 3 }, WatchRelayAudio.UPLINK) }
        }
    }

    @Test fun C03_theDownlinkStaysThePinnedRemoteSinks48kHzMonoFramesByteIdentical() {
        val f = LivePcmFrame(1, 48000, 1, pcm(960, 2))
        assertTrue(WatchRelayAudio.valid(f, WatchRelayAudio.DOWNLINK))
        val bytes = encode(WatchRelayAudio.DOWNLINK, f)
        assertArrayEquals("the downlink wire is unchanged", header(2, 1, 48000, 1, 960) + f.pcm, bytes)
        assertArrayEquals(f.pcm, read(bytes, WatchRelayAudio.DOWNLINK).pcm)
        // A 16 kHz downlink is not what the provider's remote sink delivers: there is no resampler, so it is refused.
        assertFalse(WatchRelayAudio.valid(LivePcmFrame(1, 16000, 1, pcm(320, 3)), WatchRelayAudio.DOWNLINK))
        assertThrows(IllegalArgumentException::class.java) { read(header(2, 1, 16000, 1, 320) + pcm(320, 3), WatchRelayAudio.DOWNLINK) }
    }

    @Test fun C04_aStreamOfPacketsKeepsOrderAndEveryTruncationIsEofNeverAShortPacket() {
        val frames = (1..3).map { LivePcmFrame(it.toLong(), 16000, 1, pcm(640, it)) }
        val stream = frames.fold(ByteArray(0)) { acc, f -> acc + encode(WatchRelayAudio.UPLINK, f) }
        val input = DataInputStream(ByteArrayInputStream(stream))
        frames.forEach { f -> val back = WatchRelayAudio.read(input, WatchRelayAudio.UPLINK); assertEquals(f.sequence, back.sequence); assertArrayEquals(f.pcm, back.pcm) }
        val one = encode(WatchRelayAudio.UPLINK, frames[0])
        for (cut in 0 until one.size) assertThrows("cut at $cut", EOFException::class.java) { read(one.copyOf(cut), WatchRelayAudio.UPLINK) }
    }
}
