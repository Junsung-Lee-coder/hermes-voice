package com.rumi.hermesvoice.phone

import android.content.Intent
import android.media.session.MediaSession
import android.view.KeyEvent
import com.rumi.hermesvoice.core.FakeDevices
import com.rumi.hermesvoice.core.Gear
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.headset.MediaButtonDecoder
import com.rumi.hermesvoice.core.headset.RecordingCommand
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
 * SCRATCH HARNESS (never packaged). The scoped media-button registration (amendment K) with a fake session factory: the REAL
 * [HeadsetMediaControl] and the REAL [HeadsetMediaCallback] that it hands to the session. No real MediaSession, key, headset or
 * Android media routing is exercised; whether Android delivers a physical key to this app is NOT_RUN.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneHeadsetMediaControlTest {
    private class FakePort : MediaSessionPort {
        val recording = mutableListOf<Boolean>()
        var released = 0
        override fun setRecording(recording: Boolean) { this.recording += recording }
        override fun release() { released++ }
    }

    private class FakeSessions(var available: Boolean = true) : MediaSessionFactory {
        val callbacks = mutableListOf<MediaSession.Callback>()
        val ports = mutableListOf<FakePort>()
        override fun open(callback: MediaSession.Callback): MediaSessionPort? {
            if (!available) return null
            callbacks += callback
            return FakePort().also { ports += it }
        }
        val live get() = ports.count { it.released == 0 }
    }

    private class Target(var outcome: HeadsetOutcome = HeadsetOutcome.STARTED) : HeadsetRecordingTarget {
        val got = mutableListOf<RecordingCommand>()
        override fun onHeadsetCommand(command: RecordingCommand): HeadsetOutcome { got += command; return outcome }
    }

    private var setting = true
    private var visible = true
    private var now = 10_000L
    private val devices = FakeDevices(listOf(Gear.speaker, Gear.wiredHeadset), listOf(Gear.wiredHeadsetMic))
    private val sessions = FakeSessions()
    private val target = Target()
    private val control = HeadsetMediaControl(HeadsetPolicy({ setting }, devices), sessions, { visible }, { now })

    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0, down: Long = now) =
        Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(down, down, action, code, repeat))

    private fun press(code: Int, down: Long = now): Boolean {
        val callback = sessions.callbacks.last()
        val handled = callback.onMediaButtonEvent(key(code, KeyEvent.ACTION_DOWN, 0, down))
        callback.onMediaButtonEvent(key(code, KeyEvent.ACTION_UP, 0, down))
        return handled
    }

    private fun ready() { control.attach(target); control.refresh() }

    @Test fun `registered only when on with a connected headset output a screen target and a visible app`() {
        control.refresh()
        assertEquals("nothing attached yet", 0, sessions.live)
        assertEquals(MediaControlStatus.APP_CLOSED, control.status.value)
        control.attach(target)
        assertEquals(MediaControlStatus.REGISTERED, control.status.value)
        assertEquals(1, sessions.live)
    }

    @Test fun `status names why it is not registered`() {
        setting = false
        control.attach(target)
        assertEquals(MediaControlStatus.OFF, control.status.value)
        setting = true
        devices.disconnectAll()
        control.refresh()
        assertEquals(MediaControlStatus.NO_HEADSET, control.status.value)
        devices.connect(Gear.wiredHeadset, inputs = listOf(Gear.wiredHeadsetMic))
        visible = false
        control.refresh()
        assertEquals(MediaControlStatus.APP_CLOSED, control.status.value)
        visible = true
        sessions.available = false
        control.refresh()
        assertEquals("a refused session is reported, never claimed", MediaControlStatus.UNAVAILABLE, control.status.value)
        assertEquals(0, sessions.live)
        sessions.available = true
        control.refresh()
        assertEquals(MediaControlStatus.REGISTERED, control.status.value)
    }

    @Test fun `repeated refresh keeps one session and every release is exactly once`() {
        ready()
        repeat(4) { control.refresh() }
        assertEquals(1, sessions.ports.size)
        setting = false
        control.refresh()
        assertEquals(1, sessions.ports.single().released)
        control.refresh()
        control.close()
        assertEquals("never released twice", 1, sessions.ports.single().released)
    }

    @Test fun `turning the setting off releases the session and a disconnect releases it as well`() {
        ready()
        setting = false
        control.refresh()
        assertEquals(0, sessions.live)
        setting = true
        control.refresh()
        assertEquals(1, sessions.live)
        devices.disconnectAll()
        assertEquals("the device watch alone releases it", 0, sessions.live)
        assertEquals(MediaControlStatus.NO_HEADSET, control.status.value)
    }

    @Test fun `reconnecting registers again and the old callback stays dead`() {
        ready()
        val old = sessions.callbacks.single()
        devices.disconnectAll()
        devices.connect(Gear.wiredHeadset, inputs = listOf(Gear.wiredHeadsetMic))
        assertEquals(2, sessions.ports.size)
        now += 1000
        old.onPlay()
        assertTrue("a released session's callback does nothing", target.got.isEmpty())
        now += 1000
        sessions.callbacks.last().onPlay()
        assertEquals(listOf(RecordingCommand.START), target.got)
    }

    @Test fun `detach hidden and close release the session`() {
        ready()
        visible = false
        control.refresh()
        assertEquals(0, sessions.live)
        visible = true
        control.refresh()
        assertEquals(1, sessions.live)
        control.detach(target)
        assertEquals(0, sessions.live)
        control.attach(target)
        assertEquals(1, sessions.live)
        control.close()
        assertEquals(0, sessions.live)
        assertEquals("no device watcher is left behind", 0, devices.watching)
    }

    @Test fun `detaching another target leaves the registration alone`() {
        ready()
        control.detach(Target())
        assertEquals(1, sessions.live)
    }

    @Test fun `the device watch exists only while the setting is on`() {
        setting = false
        control.attach(target)
        control.refresh()
        assertEquals(0, devices.watching)
        setting = true
        control.refresh()
        assertEquals(1, devices.watching)
        setting = false
        control.refresh()
        assertEquals(0, devices.watching)
    }

    @Test fun `play pause and headset hook toggle - one command per physical press whatever the key phases`() {
        ready()
        assertTrue(press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(listOf(RecordingCommand.TOGGLE), target.got)
        now += 1000
        assertTrue(press(KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(listOf(RecordingCommand.TOGGLE, RecordingCommand.TOGGLE), target.got)
    }

    @Test fun `play is a start and pause is a stop`() {
        ready()
        assertTrue(press(KeyEvent.KEYCODE_MEDIA_PLAY))
        now += 1000
        assertTrue(press(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(listOf(RecordingCommand.START, RecordingCommand.STOP), target.got)
    }

    @Test fun `a repeat or a duplicate callback of the same press is one transition`() {
        ready()
        val callback = sessions.callbacks.single()
        callback.onMediaButtonEvent(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 0, now))
        callback.onMediaButtonEvent(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 1, now))
        callback.onMediaButtonEvent(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 0, now))
        callback.onMediaButtonEvent(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_UP, 0, now))
        callback.onPlay()
        assertEquals(listOf(RecordingCommand.TOGGLE), target.got)
    }

    @Test fun `the transport callbacks of the session map to start and stop once`() {
        ready()
        val callback = sessions.callbacks.single()
        callback.onPlay()
        now += 1000
        callback.onPause()
        assertEquals(listOf(RecordingCommand.START, RecordingCommand.STOP), target.got)
    }

    @Test fun `other keys are neither handled nor sent to the recording`() {
        ready()
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_CALL)) {
            now += 1000
            assertFalse("key $code is not ours", press(code))
        }
        assertTrue(target.got.isEmpty())
    }

    @Test fun `a malformed media button intent is not handled`() {
        ready()
        val callback = sessions.callbacks.single()
        assertFalse(callback.onMediaButtonEvent(Intent(Intent.ACTION_MEDIA_BUTTON)))
        assertFalse(callback.onMediaButtonEvent(Intent("something.else")))
        assertTrue(target.got.isEmpty())
    }

    @Test fun `the control state mirrors the recording and is a control signal only`() {
        ready()
        val port = sessions.ports.single()
        control.recording(true)
        control.recording(false)
        assertEquals(listOf(true, false), port.recording)
        control.detach(target)
        control.recording(true)
        assertEquals("a released session is not touched", listOf(true, false), port.recording)
    }

    @Test fun `a new registration with no recording open starts in the idle state`() {
        ready()
        devices.disconnectAll()
        devices.connect(Gear.wiredHeadset, inputs = listOf(Gear.wiredHeadsetMic))
        assertEquals("the new session has not been told it records", emptyList<Boolean>(), sessions.ports.last().recording)
    }

    // Owner repair r3 (F2) supersedes the K expectation that a re-registered session is always idle: while a recording is open
    // the new session mirrors the true state, so the headset still shows and can finish that exact recording.
    @Test fun `a new registration while a recording is open shows the true recording state`() {
        ready()
        control.recording(true)
        devices.disconnectAll()
        devices.connect(Gear.wiredHeadset, inputs = listOf(Gear.wiredHeadsetMic))
        assertEquals("the new session is told the recording is open", listOf(true), sessions.ports.last().recording)
    }

    @Test fun `the decoder the control uses is the core's`() {
        assertNotNull(MediaButtonDecoder { now })
        assertNull(MediaButtonDecoder { now }.key(KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.ACTION_DOWN, 0, now))
    }
}
