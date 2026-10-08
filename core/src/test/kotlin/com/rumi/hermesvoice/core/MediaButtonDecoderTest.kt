package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.headset.MediaButtonDecoder
import com.rumi.hermesvoice.core.headset.RecordingCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Amendment K: what a headset media key means for Phone recording. Key codes are the platform's (KeyEvent): PLAY_PAUSE 85,
 * HEADSETHOOK 79, PLAY 126, PAUSE 127; NEXT 87, PREVIOUS 88, STOP 86, VOLUME_UP 24, VOLUME_DOWN 25, MUTE 164, FAST_FORWARD 90.
 */
class MediaButtonDecoderTest {
    private val down = 0
    private val up = 1
    private var now = 10_000L
    private fun decoder() = MediaButtonDecoder { now }

    @Test
    fun `play-pause and the headset hook toggle  play starts  pause stops`() {
        val d = decoder()
        assertEquals(RecordingCommand.TOGGLE, d.key(85, down, 0, 1_000))
        now += 1_000
        assertEquals(RecordingCommand.TOGGLE, d.key(79, down, 0, 2_000))
        now += 1_000
        assertEquals(RecordingCommand.START, d.key(126, down, 0, 3_000))
        now += 1_000
        assertEquals(RecordingCommand.STOP, d.key(127, down, 0, 4_000))
    }

    @Test
    fun `only the press itself counts - the key up and a long-held repeat are not a second transition`() {
        val d = decoder()
        assertEquals(RecordingCommand.TOGGLE, d.key(85, down, 0, 1_000))
        now += 1_000
        assertNull(d.key(85, up, 0, 1_000))
        now += 1_000
        assertNull("a held key repeating", d.key(85, down, 1, 5_000))
        assertNull(d.key(85, down, 7, 5_000))
        now += 1_000
        assertNull("an up with no down is nothing", d.key(126, up, 0, 9_000))
    }

    @Test
    fun `the same key event delivered twice is one transition`() {
        val d = decoder()
        assertEquals(RecordingCommand.TOGGLE, d.key(85, down, 0, 1_000))
        now += 5_000
        assertNull("identical down time and key code, long after: still the same event", d.key(85, down, 0, 1_000))
    }

    @Test
    fun `a duplicate callback of one press inside the window is dropped  and a later press is a new one`() {
        val d = decoder()
        assertEquals(RecordingCommand.TOGGLE, d.key(85, down, 0, 1_000))
        now += 100
        assertNull("a second delivery path, a moment later", d.key(85, down, 0, 1_100))
        assertNull("the manufacturer's other key for the same press", d.key(79, down, 0, 1_100))
        assertNull("a transport-control callback for the same press", d.transport(RecordingCommand.START))
        now += MediaButtonDecoder.DUPLICATE_WINDOW_MS
        assertEquals("a real second press", RecordingCommand.TOGGLE, d.key(85, down, 0, 5_000))
    }

    @Test
    fun `transport callbacks map to the idempotent commands and collapse like keys`() {
        val d = decoder()
        assertEquals(RecordingCommand.START, d.transport(RecordingCommand.START))
        assertNull(d.transport(RecordingCommand.START))
        now += 1_000
        assertEquals(RecordingCommand.STOP, d.transport(RecordingCommand.STOP))
    }

    @Test
    fun `next  previous  volume  stop  mute and seeking are ignored and not claimed`() {
        val d = decoder()
        for (code in listOf(87, 88, 86, 24, 25, 164, 90, 89, 0, 4)) {
            assertFalse("key $code is not ours", d.handles(code))
            assertNull(d.key(code, down, 0, 1_000L + code))
            now += 1_000
        }
        for (code in listOf(85, 79, 126, 127)) assertTrue(d.handles(code))
    }

    @Test
    fun `an ignored key does not use up the duplicate window of a real one`() {
        val d = decoder()
        assertNull(d.key(87, down, 0, 1_000))
        now += 10
        assertEquals(RecordingCommand.TOGGLE, d.key(85, down, 0, 1_010))
    }
}
