package com.rumi.hermesvoice.phone

import android.os.Looper
import com.rumi.hermesvoice.core.FakeDevices
import com.rumi.hermesvoice.core.Gear
import com.rumi.hermesvoice.core.headset.AudioEndpoint
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.headset.RecordingCueTone
import java.time.Duration
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The private recording cue (amendment K) with a fake [CueOutput]: it plays only to the one
 * eligible personal headset output, never otherwise, and ends exactly once. No real AudioTrack, headset or sound is involved.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneRecordingCuesTest {
    private class Played(val pcm: ShortArrayHolder, val rate: Int, val target: AudioEndpoint, val done: (CueResult) -> Unit) {
        var closed = 0
    }

    private class ShortArrayHolder(val bytes: ByteArray)

    private class FakeOutput : CueOutput {
        val plays = mutableListOf<Played>()
        override fun play(pcm: ByteArray, sampleRate: Int, target: AudioEndpoint, done: (CueResult) -> Unit): AutoCloseable {
            val played = Played(ShortArrayHolder(pcm), sampleRate, target, done)
            plays += played
            return AutoCloseable { played.closed++ }
        }
    }

    private var setting = true
    private val devices = FakeDevices(listOf(Gear.speaker, Gear.wiredHeadset), listOf(Gear.wiredHeadsetMic))
    private val output = FakeOutput()
    private val cues = RecordingCues(HeadsetPolicy({ setting }, devices), output)
    private val results = mutableListOf<CueResult>()

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun `with no private output nothing plays and nothing is returned - there is no other path`() {
        devices.disconnectAll()
        assertTrue(!cues.available())
        assertNull(cues.play(CueKind.START) { results += it })
        assertTrue(output.plays.isEmpty())
        assertTrue(results.isEmpty())
        setting = false
        devices.connect(Gear.wiredHeadset)
        assertNull(cues.play(CueKind.STOP) { results += it })
        assertTrue("off: nothing plays even with a headset connected", output.plays.isEmpty())
    }

    @Test fun `a cue goes only to the connected headset output with the generated tone for its kind`() {
        assertTrue(cues.available())
        assertNotNull(cues.play(CueKind.START) { results += it })
        val one = output.plays.single()
        assertEquals(Gear.wiredHeadset, one.target)
        assertEquals(RecordingCueTone.SAMPLE_RATE, one.rate)
        assertArrayEquals(RecordingCueTone.pcm(1), one.pcm.bytes)
        one.done(CueResult.PLAYED)
        assertNotNull(cues.play(CueKind.STOP) { results += it })
        assertArrayEquals(RecordingCueTone.pcm(2), output.plays.last().pcm.bytes)
        assertEquals(listOf(CueResult.PLAYED), results)
    }

    @Test fun `a Bluetooth headset is targeted by its own output endpoint`() {
        val bluetooth = FakeDevices(listOf(Gear.speaker, Gear.scoOut), listOf(Gear.scoIn))
        val own = RecordingCues(HeadsetPolicy({ true }, bluetooth), output)
        own.play(CueKind.START) {}
        assertEquals(Gear.scoOut, output.plays.single().target)
    }

    @Test fun `done fires exactly once whatever the output does afterwards`() {
        cues.play(CueKind.START) { results += it }
        val one = output.plays.single()
        one.done(CueResult.PLAYED)
        one.done(CueResult.FAILED)
        devices.disconnectAll()
        idle(5_000)
        assertEquals(listOf(CueResult.PLAYED), results)
        assertEquals("the output was released", 1, one.closed)
    }

    @Test fun `every output result is passed on`() {
        for (result in listOf(CueResult.FOCUS_DENIED, CueResult.UNROUTED, CueResult.FAILED)) {
            results.clear()
            cues.play(CueKind.START) { results += it }
            output.plays.last().done(result)
            assertEquals(listOf(result), results)
        }
    }

    @Test fun `a disconnect cancels the cue as lost and a later reconnect plays nothing`() {
        cues.play(CueKind.START) { results += it }
        val one = output.plays.single()
        devices.disconnectAll()
        assertEquals(listOf(CueResult.LOST), results)
        assertEquals(1, one.closed)
        devices.connect(Gear.wiredHeadset)
        idle(5_000)
        assertEquals("no stale sound on reconnect", 1, output.plays.size)
        assertEquals(listOf(CueResult.LOST), results)
        assertEquals("no watcher left behind", 0, devices.watching)
    }

    @Test fun `switching to another headset during a cue is a lost output`() {
        cues.play(CueKind.START) { results += it }
        devices.set(listOf(Gear.speaker, Gear.usbOut), listOf(Gear.usbIn))
        assertEquals(listOf(CueResult.LOST), results)
    }

    @Test fun `the setting turned off mid cue is a lost output once rechecked`() {
        cues.play(CueKind.STOP) { results += it }
        setting = false
        cues.recheck()
        assertEquals(listOf(CueResult.LOST), results)
        assertEquals(1, output.plays.single().closed)
    }

    @Test fun `a cue that never finishes ends as a timeout`() {
        cues.play(CueKind.START) { results += it }
        idle(RecordingCues.CUE_TIMEOUT_MS - 100)
        assertTrue(results.isEmpty())
        idle(400)
        assertEquals(listOf(CueResult.TIMEOUT), results)
        assertEquals(1, output.plays.single().closed)
    }

    @Test fun `close cancels without telling the caller and a new cue cancels the old one`() {
        val handle = cues.play(CueKind.START) { results += it }!!
        handle.close()
        assertTrue(results.isEmpty())
        assertEquals(1, output.plays.single().closed)
        idle(5_000)
        assertTrue("the timeout is gone too", results.isEmpty())

        cues.play(CueKind.START) { results += it }
        cues.play(CueKind.STOP) { results += it }
        assertEquals(listOf(CueResult.CANCELLED), results)
        assertEquals(3, output.plays.size)
        assertEquals(1, output.plays[1].closed)
        output.plays.last().done(CueResult.PLAYED)
        assertEquals(listOf(CueResult.CANCELLED, CueResult.PLAYED), results)
    }

    @Test fun `a finished cue leaves no watcher`() {
        cues.play(CueKind.START) {}
        assertEquals(1, devices.watching)
        output.plays.single().done(CueResult.PLAYED)
        assertEquals(0, devices.watching)
    }
}
