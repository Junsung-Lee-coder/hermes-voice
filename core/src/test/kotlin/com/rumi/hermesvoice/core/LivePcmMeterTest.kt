package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.LivePcmMeter
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** R29: the fixed-number PCM16 meter behind a Live call's input/playback observations. Pure; no audio is kept. */
class LivePcmMeterTest {
    /** One 10 ms 48 kHz mono PCM16 chunk whose every sample is [value]. */
    private fun chunk(value: Int, samples: Int = 480) = ByteArray(samples * 2).also { b ->
        for (i in 0 until samples) { b[2 * i] = (value and 255).toByte(); b[2 * i + 1] = (value shr 8).toByte() }
    }

    @Test fun exactZerosAreNoChunkQuietNoiseIsAChunkWithoutSpeechAndSpeechEnergyAddsItsDuration() {
        val m = LivePcmMeter()
        m.count(chunk(0), 1, 48_000)
        assertEquals("a silenced or never-filled source hands on exact zeros", 0L, m.chunks)
        m.count(chunk(3), 1, 48_000)
        assertEquals(1L, m.chunks); assertEquals(0L, m.speechMs)
        m.count(chunk(-2_000), 1, 48_000); m.count(chunk(LivePcmMeter.SPEECH), 1, 48_000)
        assertEquals(3L, m.chunks); assertEquals("two 10 ms chunks at speech energy", 20L, m.speechMs)
        m.count(chunk(LivePcmMeter.SPEECH - 1), 1, 48_000)
        assertEquals(4L, m.chunks); assertEquals("just below the activity threshold", 20L, m.speechMs)
    }

    @Test fun aDirectBufferIsReadInPlaceWithoutMovingItAndStereoTimeCountsFrames() {
        val m = LivePcmMeter()
        val buffer = ByteBuffer.allocateDirect(960).put(chunk(1_000)); buffer.rewind()
        m.count(buffer, buffer.capacity(), 1, 48_000)
        assertEquals("position untouched for the native consumer", 0, buffer.position()); assertEquals(960, buffer.limit())
        assertEquals(1L, m.chunks); assertEquals(10L, m.speechMs)
        m.count(chunk(1_000, samples = 960), 2, 48_000)
        assertEquals("960 interleaved stereo samples are 480 frames: 10 ms", 20L, m.speechMs)
        m.count(ByteArray(1), 1, 48_000); m.count(chunk(1_000), 0, 48_000); m.count(chunk(1_000), 1, 0)
        assertEquals("empty or malformed chunks count nothing", 2L, m.chunks)
    }
}
