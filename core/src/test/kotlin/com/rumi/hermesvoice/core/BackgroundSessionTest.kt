package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundPort
import com.rumi.hermesvoice.core.background.BackgroundSession
import com.rumi.hermesvoice.core.background.BackgroundStatus
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.background.WakeHolds
import com.rumi.hermesvoice.core.background.WakeLockPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The opt-in background session of one device (Phone relay, Watch voice): off by default, started
 * only from a visible app, its microphone armed only from a visible app, stopped for good by the
 * user, and never restarted on its own. Plus the scoped, time-bounded wake locks.
 */
class BackgroundSessionTest {
    private class Service : BackgroundPort {
        val calls = mutableListOf<String>()
        var refuseStart = false
        var refuseMicrophone = false
        override fun startService(microphone: Boolean): Boolean {
            calls += "start:$microphone"
            return !refuseStart && !(microphone && refuseMicrophone)
        }
        override fun retypeService(microphone: Boolean): Boolean {
            calls += "retype:$microphone"
            return !(microphone && refuseMicrophone)
        }
        override fun stopService() { calls += "stop" }
    }

    private class Fixture(resumeWhenVisible: Boolean = false, val store: InMemoryKeyValueStore = InMemoryKeyValueStore()) {
        val service = Service()
        val seen = mutableListOf<BackgroundStatus>()
        val session = BackgroundSession(store, "background", service, resumeWhenVisible) { seen += it }
    }

    @Test
    fun `it is off on a fresh install and on an upgraded one, and nothing starts by itself`() {
        val fresh = Fixture()
        assertEquals(BackgroundStatus(wanted = false, running = false, microphone = false, notice = BackgroundNotice.OFF), fresh.session.status)
        // An upgraded install has other settings but never this key.
        val upgraded = Fixture(store = InMemoryKeyValueStore().apply { putString("wake_location", "WATCH"); putBoolean("watch_wake_phrase_enabled", true) })
        assertFalse(upgraded.session.status.wanted)
        upgraded.session.onVisible(microphoneWanted = true, microphonePermission = true)
        upgraded.session.onMicrophoneWanted(wanted = true, visible = true, permission = true)
        assertEquals("no service without the user's start", emptyList<String>(), upgraded.service.calls)
    }

    @Test
    fun `it starts only from a visible app and the choice is remembered`() {
        val f = Fixture()
        val hidden = f.session.start(visible = false, microphoneWanted = true, microphonePermission = true)
        assertEquals(BackgroundNotice.NEEDS_VISIBLE, hidden.notice)
        assertFalse(hidden.wanted)
        assertEquals(emptyList<String>(), f.service.calls)
        val started = f.session.start(visible = true, microphoneWanted = true, microphonePermission = true)
        assertEquals(BackgroundStatus(true, true, true, BackgroundNotice.LISTENING), started)
        assertEquals(listOf("start:true"), f.service.calls)
        assertTrue(f.store.getBoolean("background", false))
        // A second start changes nothing: one service, one session.
        val generation = f.session.generation
        f.session.start(visible = true, microphoneWanted = true, microphonePermission = true)
        assertEquals(listOf("start:true"), f.service.calls)
        assertEquals(generation, f.session.generation)
    }

    @Test
    fun `without the microphone permission or a wake setting for this device it runs for playback only`() {
        val denied = Fixture()
        assertEquals(BackgroundStatus(true, true, false, BackgroundNotice.NEEDS_PERMISSION),
            denied.session.start(visible = true, microphoneWanted = true, microphonePermission = false))
        assertEquals(listOf("start:false"), denied.service.calls)
        val notListening = Fixture()
        assertEquals(BackgroundStatus(true, true, false, BackgroundNotice.RUNNING),
            notListening.session.start(visible = true, microphoneWanted = false, microphonePermission = true))
        assertEquals(listOf("start:false"), notListening.service.calls)
    }

    @Test
    fun `a platform refusal is reported and leaves nothing running`() {
        val f = Fixture()
        f.service.refuseStart = true
        val status = f.session.start(visible = true, microphoneWanted = true, microphonePermission = true)
        assertEquals(BackgroundStatus(true, false, false, BackgroundNotice.REFUSED), status)
        // A refused microphone type alone falls back to playback, and says listening needs the app on screen.
        val g = Fixture()
        g.service.refuseMicrophone = true
        assertEquals(BackgroundStatus(true, true, false, BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN),
            g.session.start(visible = true, microphoneWanted = true, microphonePermission = true))
        assertEquals(listOf("start:true", "start:false"), g.service.calls)
    }

    @Test
    fun `the microphone is armed only while the app is visible`() {
        val f = Fixture()
        f.session.start(visible = true, microphoneWanted = true, microphonePermission = true)
        // The Phone's settings exclude this device: disarmed at once, from wherever.
        assertEquals(BackgroundNotice.RUNNING, f.session.onMicrophoneWanted(wanted = false, visible = false, permission = true).notice)
        assertFalse(f.session.status.microphone)
        assertEquals(listOf("start:true", "retype:false"), f.service.calls)
        // Included again while hidden: nothing is armed from the background.
        val hidden = f.session.onMicrophoneWanted(wanted = true, visible = false, permission = true)
        assertEquals(BackgroundStatus(true, true, false, BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN), hidden)
        assertEquals(listOf("start:true", "retype:false"), f.service.calls)
        // The app is on screen again: armed.
        assertEquals(BackgroundStatus(true, true, true, BackgroundNotice.LISTENING), f.session.onVisible(microphoneWanted = true, microphonePermission = true))
        assertEquals(listOf("start:true", "retype:false", "retype:true"), f.service.calls)
        // Repeated signals do not re-type again.
        f.session.onVisible(microphoneWanted = true, microphonePermission = true)
        f.session.onMicrophoneWanted(wanted = true, visible = true, permission = true)
        assertEquals(3, f.service.calls.size)
    }

    @Test
    fun `a microphone the platform refuses when the service really starts leaves the session playing replies only`() {
        val f = Fixture()
        f.session.start(visible = true, microphoneWanted = true, microphonePermission = true)
        val generation = f.session.generation
        assertEquals(BackgroundStatus(true, true, false, BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN), f.session.onMicrophoneRefused(generation))
        assertEquals("nothing is retried from wherever the refusal arrived", listOf("start:true"), f.service.calls)
        f.session.onMicrophoneRefused(generation - 1)
        f.session.stop()
        assertEquals(BackgroundNotice.OFF, f.session.onMicrophoneRefused(generation).notice)
    }

    @Test
    fun `a revoked microphone permission disarms the session`() {
        val f = Fixture()
        f.session.start(visible = true, microphoneWanted = true, microphonePermission = true)
        assertEquals(BackgroundStatus(true, true, false, BackgroundNotice.NEEDS_PERMISSION), f.session.onMicrophoneWanted(true, visible = false, permission = false))
        assertEquals("retype:false", f.service.calls.last())
    }

    @Test
    fun `stop is final and idempotent, and ends the session's generation`() {
        val f = Fixture()
        f.session.start(visible = true, microphoneWanted = true, microphonePermission = true)
        val generation = f.session.generation
        assertTrue(f.session.isCurrent(generation))
        assertEquals(BackgroundStatus(false, false, false, BackgroundNotice.OFF), f.session.stop())
        f.session.stop()
        assertEquals(listOf("start:true", "stop"), f.service.calls)
        assertFalse(f.store.getBoolean("background", true))
        assertFalse("callbacks of the stopped session are stale", f.session.isCurrent(generation))
        // Nothing brings it back but the user's own start.
        f.session.onVisible(microphoneWanted = true, microphonePermission = true)
        f.session.onMicrophoneWanted(true, visible = true, permission = true)
        f.session.onServiceGone(generation)
        assertEquals(2, f.service.calls.size)
        assertEquals(BackgroundNotice.OFF, f.session.status.notice)
    }

    @Test
    fun `an interrupted session is shown as paused and is not restarted invisibly`() {
        val f = Fixture()
        f.session.start(visible = true, microphoneWanted = true, microphonePermission = true)
        val generation = f.session.generation
        assertEquals(BackgroundStatus(true, false, false, BackgroundNotice.PAUSED), f.session.onServiceGone(generation))
        assertEquals("the system ended it; the app does not start it again", listOf("start:true"), f.service.calls)
        f.session.onServiceGone(generation)
        f.session.onMicrophoneWanted(true, visible = false, permission = true)
        assertEquals(1, f.service.calls.size)
        // A Watch (microphone) needs the user's start even when its app is opened.
        assertEquals(BackgroundNotice.PAUSED, f.session.onVisible(microphoneWanted = true, microphonePermission = true).notice)
        assertEquals(1, f.service.calls.size)
        assertEquals(BackgroundNotice.LISTENING, f.session.start(visible = true, microphoneWanted = true, microphonePermission = true).notice)
    }

    @Test
    fun `after a process restart the saved choice reads as paused, and only the relay resumes when its app is shown`() {
        val store = InMemoryKeyValueStore()
        Fixture(store = store).session.start(visible = true, microphoneWanted = true, microphonePermission = true)
        val watch = Fixture(resumeWhenVisible = false, store = store)
        assertEquals(BackgroundStatus(true, false, false, BackgroundNotice.PAUSED), watch.session.status)
        watch.session.onVisible(microphoneWanted = true, microphonePermission = true)
        assertEquals(emptyList<String>(), watch.service.calls)
        val phone = Fixture(resumeWhenVisible = true, store = store)
        assertEquals(BackgroundNotice.PAUSED, phone.session.status.notice)
        assertEquals(BackgroundStatus(true, true, false, BackgroundNotice.RUNNING), phone.session.onVisible(microphoneWanted = false, microphonePermission = false))
        assertEquals(listOf("start:false"), phone.service.calls)
    }

    @Test
    fun `a service that ended under an older session cannot end the current one`() {
        val f = Fixture()
        f.session.start(visible = true, microphoneWanted = false, microphonePermission = true)
        val old = f.session.generation
        f.session.stop()
        f.session.start(visible = true, microphoneWanted = false, microphonePermission = true)
        f.session.onServiceGone(old)
        assertTrue(f.session.status.running)
    }

    @Test
    fun `listeners hear every change once`() {
        val f = Fixture()
        f.session.start(visible = true, microphoneWanted = true, microphonePermission = true)
        f.session.start(visible = true, microphoneWanted = true, microphonePermission = true)
        f.session.stop()
        f.session.stop()
        assertEquals(listOf(BackgroundNotice.LISTENING, BackgroundNotice.OFF), f.seen.map { it.notice })
    }

    // ── wake locks ───────────────────────────────────────────────────────────────────────────

    private class Locks : WakeLockPort {
        val calls = mutableListOf<String>()
        override fun acquire(reason: HoldReason, timeoutMs: Long) { calls += "acquire:$reason:$timeoutMs" }
        override fun release(reason: HoldReason) { calls += "release:$reason" }
    }

    @Test
    fun `every wake lock has a reason and a timeout, and is released once`() {
        var now = 0L
        val port = Locks()
        val holds = WakeHolds(port) { now }
        holds.acquire(HoldReason.CAPTURE)
        holds.acquire(HoldReason.LISTEN, 35_000)
        assertEquals(listOf("acquire:CAPTURE:${HoldReason.CAPTURE.maxMs}", "acquire:LISTEN:35000"), port.calls)
        assertEquals(setOf(HoldReason.CAPTURE, HoldReason.LISTEN), holds.held())
        holds.release(HoldReason.LISTEN)
        holds.release(HoldReason.LISTEN)
        holds.release(HoldReason.PLAYBACK)
        assertEquals("a lock that is not held is not released again", 3, port.calls.size)
        // A timeout is never unbounded, zero or longer than the reason allows.
        holds.acquire(HoldReason.LISTEN, Long.MAX_VALUE)
        holds.acquire(HoldReason.PLAYBACK, 0)
        assertEquals("acquire:LISTEN:${HoldReason.LISTEN.maxMs}", port.calls[3])
        assertEquals("acquire:PLAYBACK:1", port.calls[4])
        for (reason in HoldReason.values()) assertTrue("$reason", reason.maxMs in 1..20 * 60_000L)
        // The platform lets go by itself at the timeout: it is no longer counted as held.
        now += HoldReason.LISTEN.maxMs + 1
        assertEquals(setOf(HoldReason.CAPTURE), holds.held())
        holds.releaseAll()
        assertEquals(emptySet<HoldReason>(), holds.held())
        assertEquals("release:CAPTURE", port.calls.last())
    }
}
