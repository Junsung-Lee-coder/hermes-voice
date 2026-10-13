package com.rumi.hermesvoice.phone

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SCRATCH HARNESS (never packaged). The recorder's cue gate (amendment K): while a private start cue plays the microphone is
 * already open, but nothing it hears is kept, so the cue can never reach the uploaded WAV / speech-to-text. Fake [RecordPort].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneRecorderAdmissionTest {
    private class FillRecord(val fill: AtomicInteger) : RecordPort {
        override val initialized = true
        override fun setPreferredDevice(deviceId: Int) = true
        override fun routedDeviceId(): Int? = null
        override fun start() = true
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(5)
            val value = fill.get()
            for (i in offset until offset + length step 2) { buffer[i] = (value and 0xFF).toByte(); buffer[i + 1] = (value shr 8).toByte() }
            return length
        }
        override fun stop() {}
        override fun release() {}
    }

    private val fill = AtomicInteger(0x0101)

    private fun samples(wav: ByteArray): Set<Int> {
        val out = HashSet<Int>()
        var i = 44
        while (i + 1 < wav.size) { out += (wav[i].toInt() and 0xFF) or (wav[i + 1].toInt() shl 8); i += 2 }
        return out
    }

    @Test fun `nothing heard before admission is kept and the audio after it is`() {
        val wav = WavRecorder(records = RecordFactory { FillRecord(fill) })
        wav.start(admitted = false) {}
        assertTrue(wav.isRecording)
        Thread.sleep(150)
        fill.set(0x0202)
        wav.admit()
        Thread.sleep(700)
        val out = wav.stop()
        assertNotNull(out)
        assertEquals("only what was heard after admission", setOf(0x0202), samples(out!!))
    }

    @Test fun `a recording that is never admitted is empty - too short`() {
        val wav = WavRecorder(records = RecordFactory { FillRecord(fill) })
        wav.start(admitted = false) {}
        Thread.sleep(700)
        assertNull(wav.stop())
        assertFalse(wav.isRecording)
    }

    @Test fun `by default a recording is admitted from the start exactly as before`() {
        val wav = WavRecorder(records = RecordFactory { FillRecord(fill) })
        wav.start {}
        Thread.sleep(700)
        assertEquals(setOf(0x0101), samples(wav.stop()!!))
    }

    @Test fun `admit is idempotent and a new start is gated again`() {
        val wav = WavRecorder(records = RecordFactory { FillRecord(fill) })
        wav.start(admitted = false) {}
        wav.admit()
        wav.admit()
        Thread.sleep(500)
        assertNotNull(wav.stop())
        fill.set(0x0303)
        wav.start(admitted = false) {}
        Thread.sleep(300)
        fill.set(0x0404)
        wav.admit()
        Thread.sleep(700)
        assertEquals(setOf(0x0404), samples(wav.stop()!!))
    }

    @Test fun `the maximum length counts only admitted audio`() {
        val wav = WavRecorder(maxSeconds = 1, records = RecordFactory { FillRecord(fill) })
        wav.start(admitted = false) {}
        Thread.sleep(400)
        wav.admit()
        Thread.sleep(700)
        val out = wav.stop()
        assertNotNull(out)
        assertTrue("about the first admitted second, not cut by the dropped time", out!!.size > 44 + 16_000 * 2 / 5)
    }
}
