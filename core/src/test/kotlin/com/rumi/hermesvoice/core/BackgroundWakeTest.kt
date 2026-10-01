package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.ContinuousWakePolicy
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeBlock
import com.rumi.hermesvoice.core.wake.WakeClaimPort
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakePresence
import com.rumi.hermesvoice.core.wake.WakePresencePort
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake flow of a device whose background session is armed: it keeps listening, recording and
 * claiming with its app hidden and the screen off, window after window, and stops at once when the
 * session is stopped. Without a session the foreground-only rules are unchanged.
 */
class BackgroundWakeTest {
    private class Device(mode: WakeLocation = WakeLocation.WATCH, arbitrated: Boolean = false) :
        WakeRecognizerPort, WakeTimerPort, WakeDevicePort, WakePresencePort, WakeClaimPort {
        val calls = mutableListOf<String>()
        var now = 1_000L
        var available = true
        var busy = false

        /** The recording in progress is push-to-talk, which the wake flow never touches. */
        var pushToTalk = false
        var interactive = true
        var permission = true
        var reachable: Boolean? = true
        var cooldownUntil = 0L
        var startFails = false
        var windowTimer: Long? = null
        var rearmIn: Long? = null
        var claimTimer: Long? = null
        var epoch = 0L
        private var ids = 0
        val controller = WakeDeviceController(VoiceOrigin.WATCH, this, this, this, { now },
            WatchSettings(mode, "루미", revision = 1), if (arbitrated) this else null)
        val presence = WakePresence(controller, this)

        override fun available() = available
        override fun start(generation: Long): Boolean { calls += "listen:$generation"; return !startFails }
        override fun release() { calls += "release" }
        override fun schedule(delayMs: Long) { windowTimer = delayMs }
        override fun cancel() { windowTimer = null }
        override fun windowChanged(open: Boolean) {}
        override fun scheduleHandoff(delayMs: Long) { calls += "handoff_in:$delayMs" }
        override fun cancelHandoff() {}
        override fun startRequestCapture(silenceMs: Long): Boolean { calls += "capture"; busy = true; return true }
        override fun cancelRequestCapture(reason: String) { if (busy && !pushToTalk) { calls += "cancel_capture:$reason"; busy = false } }
        override fun sendRecognized(request: String) { calls += "send:$request" }
        override fun closed(reason: String) { calls += "closed:$reason"; presence.onWindowClosed(reason) }
        // What the Android runtime reports: the app counts as present while it is visible or a session is armed.
        override fun armInputs() = WakeArmInputs(enabled = true, resumed = presence.present, interactive = interactive || presence.armed,
            ambient = false, permission = permission, microphoneMuted = false, talkIdle = !busy, phoneReachable = reachable,
            nowMs = now, cooldownUntilMs = cooldownUntil, generation = 0, lastArmedGeneration = null)
        override fun armBlocked(source: String, block: WakeBlock) {
            calls += "blocked:$block"
            presence.onArmBlocked(block, (cooldownUntil - now).coerceAtLeast(0))
        }
        override fun scheduleRearm(delayMs: Long) { rearmIn = delayMs }
        override fun cancelRearm() { rearmIn = null }
        override fun cancelCapture(reason: String) { if (busy) { calls += "cancel_any_capture:$reason"; busy = false } }
        override fun newClaimId() = "claim-${++ids}"
        override fun epoch() = epoch
        override fun request(claimId: String, settingsRevision: Long, generation: Long, epoch: Long) { calls += "claim:$claimId" }
        override fun renew(claimId: String) { calls += "renew:$claimId" }
        override fun release(claimId: String) { calls += "release_claim:$claimId" }
        override fun scheduleTimer(delayMs: Long) { claimTimer = delayMs }
        override fun cancelTimer() { claimTimer = null }

        fun listened() = calls.count { it.startsWith("listen:") }
        fun rearmDue() { val due = rearmIn; rearmIn = null; if (due != null) { now += due; presence.onRearmDue() } }
        fun windowTimeout(afterMs: Long) { now += afterMs; controller.onTimer() }

        /** The user starts a background session from the visible app. */
        fun startArmed() { presence.onActivityResumed(settingsPending = false); presence.onArmed(true) }
    }

    @Test
    fun `without a session, leaving the screen stops listening and any recording, as before`() {
        val d = Device()
        d.presence.onActivityResumed(settingsPending = false)
        assertEquals(1, d.listened())
        assertEquals(WakeContract.WINDOW_MS, d.windowTimer)
        d.controller.onResults(d.controller.generation, listOf("루미"), final = true)
        assertTrue(d.controller.onHandoffDue(captureIdle = true))
        d.presence.onActivityPaused()
        assertTrue(d.calls.contains("cancel_capture:pause"))
        d.presence.onActivityResumed(settingsPending = false)
        d.windowTimeout(WakeContract.WINDOW_MS)
        assertNull("one window per app show: no loop in the foreground-only mode", d.rearmIn)
        d.presence.onScreenOff()
        d.presence.onScreenOn()
        assertEquals("a push-to-talk recording is ended too when the app leaves the screen", true, run {
            d.busy = true; d.pushToTalk = true; d.presence.onActivityPaused(); d.calls.contains("cancel_any_capture:pause")
        })
    }

    @Test
    fun `an armed session keeps listening with the app hidden and the screen off`() {
        val d = Device()
        d.startArmed()
        val before = d.listened()
        d.presence.onActivityPaused()
        d.interactive = false
        d.presence.onScreenOff()
        assertFalse("the window stays open", d.calls.any { it.startsWith("closed:") })
        // The phrase is heard with the app hidden: the recorder starts, nothing is cancelled.
        d.controller.onResults(d.controller.generation, listOf("루미"), final = true)
        assertTrue(d.calls.contains("handoff_in:${WakeContract.MIC_HANDOFF_MS}"))
        assertTrue(d.controller.onHandoffDue(captureIdle = true))
        assertEquals(1, d.calls.count { it == "capture" })
        assertEquals(before, d.listened())
        assertFalse(d.calls.any { it.startsWith("cancel_") })
    }

    @Test
    fun `windows follow one another, each a new generation, and a late result of an old one does nothing`() {
        val d = Device()
        d.startArmed()
        val first = d.controller.generation
        d.presence.onActivityPaused()
        d.windowTimeout(WakeContract.BACKGROUND_WINDOW_MS)
        assertEquals("closed:timeout", d.calls.last())
        assertEquals(ContinuousWakePolicy.REARM_MS, d.rearmIn)
        d.rearmDue()
        assertEquals(2, d.listened())
        assertEquals("hidden windows are longer than the one opened by showing the app", WakeContract.BACKGROUND_WINDOW_MS, d.windowTimer)
        assertTrue(d.controller.generation > first)
        d.controller.onResults(first, listOf("루미"), final = true)
        assertFalse("a result of the closed window starts nothing", d.calls.any { it.startsWith("handoff_in") })
        // Hours of nobody speaking: one window at a time, no pile-up.
        repeat(200) { d.windowTimeout(WakeContract.BACKGROUND_WINDOW_MS); d.rearmDue() }
        assertEquals(202, d.listened())
        assertEquals(d.calls.count { it.startsWith("listen:") }, d.calls.count { it == "release" } + 1)
    }

    @Test
    fun `speech that is not the phrase, an unfinished request and recognizer hiccups re-arm, real failures back off`() {
        val d = Device()
        d.startArmed()
        d.presence.onActivityPaused()
        d.controller.onResults(d.controller.generation, listOf("날씨 좋다"), final = true)
        assertEquals("closed:not_matched", d.calls.last())
        assertEquals(ContinuousWakePolicy.REARM_MS, d.rearmIn)
        d.rearmDue()
        for (code in listOf(6, 7)) {
            d.controller.onError(d.controller.generation, code)
            assertEquals("no speech / no match is the normal end of a quiet window", ContinuousWakePolicy.REARM_MS, d.rearmIn)
            d.rearmDue()
        }
        val delays = (1..9).map {
            d.controller.onError(d.controller.generation, 5)
            d.rearmIn!!.also { d.rearmDue() }
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L), delays)
        // One good window resets the back-off.
        d.windowTimeout(WakeContract.BACKGROUND_WINDOW_MS)
        d.rearmDue()
        d.controller.onError(d.controller.generation, 5)
        assertEquals(1_000L, d.rearmIn)
        // A recognizer that cannot start backs off the same way.
        d.startFails = true
        d.rearmDue()
        assertEquals(2_000L, d.rearmIn)
    }

    @Test
    fun `no recognizer ends the loop instead of spinning`() {
        val d = Device()
        d.available = false
        d.startArmed()
        assertTrue(d.calls.contains("closed:unavailable"))
        assertNull(d.rearmIn)
        assertEquals(0, d.listened())
        // Shown again later (a recognizer may have been installed): one more try, still no loop.
        d.presence.onActivityPaused()
        d.presence.onActivityResumed(settingsPending = false)
        assertEquals(2, d.calls.count { it == "closed:unavailable" })
        assertNull(d.rearmIn)
    }

    @Test
    fun `a recognizer that was missing at an earlier show does not keep a later session from re-arming`() {
        val d = Device()
        d.available = false
        d.presence.onActivityResumed(settingsPending = false)
        assertTrue(d.calls.contains("closed:unavailable"))
        d.presence.onActivityPaused()
        // A recognizer is there now; the app is shown again and the user starts a session.
        d.available = true
        d.startArmed()
        d.presence.onActivityPaused()
        d.windowTimeout(WakeContract.BACKGROUND_WINDOW_MS)
        assertEquals(ContinuousWakePolicy.REARM_MS, d.rearmIn)
        d.rearmDue()
        assertEquals("the hidden session keeps opening windows", 2, d.listened())
    }

    @Test
    fun `after a request and its reply the session listens again, waiting out the playback cooldown`() {
        val d = Device()
        d.startArmed()
        d.presence.onActivityPaused()
        d.controller.onResults(d.controller.generation, listOf("루미"), final = true)
        d.controller.onHandoffDue(captureIdle = true)
        d.presence.onBusy()
        // Recording ended and was sent; the reply played until now.
        d.controller.onRequestCaptureEnded(sent = true)
        d.busy = false
        d.cooldownUntil = d.now + WakeContract.PLAYBACK_COOLDOWN_MS
        d.presence.onIdle()
        assertEquals("blocked:COOLDOWN", d.calls.last())
        assertEquals(WakeContract.PLAYBACK_COOLDOWN_MS + ContinuousWakePolicy.COOLDOWN_MARGIN_MS, d.rearmIn)
        d.rearmDue()
        assertEquals(2, d.listened())
    }

    @Test
    fun `an unreachable phone is retried slowly and a lost permission is not retried`() {
        val d = Device()
        d.reachable = false
        d.startArmed()
        assertEquals("blocked:PHONE_UNREACHABLE", d.calls.last())
        assertEquals(ContinuousWakePolicy.BLOCKED_RETRY_MS, d.rearmIn)
        d.reachable = true
        d.rearmDue()
        assertEquals(1, d.listened())
        d.windowTimeout(WakeContract.BACKGROUND_WINDOW_MS)
        d.permission = false
        d.rearmDue()
        assertEquals("blocked:PERMISSION", d.calls.last())
        assertNull(d.rearmIn)
    }

    @Test
    fun `showing, hiding and recreating the screen during a session never opens a second window or cancels a recording`() {
        val d = Device()
        d.startArmed()
        repeat(5) {
            d.presence.onActivityPaused()
            d.presence.onActivityResumed(settingsPending = true)
            d.presence.onSettingsCurrent()
            d.presence.onArmed(true)
        }
        assertEquals(1, d.listened())
        d.controller.onResults(d.controller.generation, listOf("루미"), final = true)
        d.controller.onHandoffDue(captureIdle = true)
        d.presence.onActivityPaused()
        d.presence.onActivityResumed(settingsPending = true)
        d.presence.onSettingsCurrent()
        d.presence.onScreenOff()
        d.presence.onScreenOn()
        assertTrue(d.busy)
        assertEquals(1, d.calls.count { it == "capture" })
        assertFalse(d.calls.any { it.startsWith("cancel_") })
        // A re-arm timer that fires during the recording does not start the recognizer on top of it.
        d.presence.onRearmDue()
        assertEquals(1, d.listened())
    }

    @Test
    fun `stopping the session while hidden closes the window, ends a recording unsent and silences later timers`() {
        val listening = Device()
        listening.startArmed()
        listening.presence.onActivityPaused()
        listening.presence.onArmed(false)
        assertEquals("closed:pause", listening.calls.last { it.startsWith("closed:") })
        assertNull(listening.rearmIn)
        listening.presence.onRearmDue()
        listening.presence.onIdle()
        listening.presence.onArmed(false)
        assertEquals("nothing listens after Stop", 1, listening.listened())

        val recording = Device(WakeLocation.BOTH, arbitrated = true)
        recording.startArmed()
        recording.presence.onActivityPaused()
        recording.controller.onResults(recording.controller.generation, listOf("루미"), final = true)
        recording.controller.onClaimVerdict("claim-1", ClaimVerdict.GRANTED)
        assertTrue(recording.controller.onHandoffDue(captureIdle = true))
        recording.presence.onArmed(false)
        assertTrue(recording.calls.contains("cancel_capture:pause"))
        assertTrue("the claim is given back", recording.calls.contains("release_claim:claim-1"))
        // The stopped session's recognizer result and claim answer arrive late: ignored.
        val before = recording.calls.size
        recording.controller.onResults(recording.controller.generation, listOf("루미 불 꺼"), final = true)
        recording.controller.onClaimVerdict("claim-1", ClaimVerdict.GRANTED)
        recording.controller.onClaimTimer()
        assertEquals(before, recording.calls.size)
    }

    @Test
    fun `stopping the session while the app is visible leaves foreground use as it was`() {
        val d = Device()
        d.startArmed()
        d.presence.onArmed(false)
        assertFalse("the open window is not torn down", d.calls.any { it.startsWith("closed:") })
        d.windowTimeout(WakeContract.BACKGROUND_WINDOW_MS)
        assertNull("but it is not re-armed: one window per show again", d.rearmIn)
        d.presence.onActivityPaused()
        d.presence.onActivityResumed(settingsPending = false)
        assertEquals(WakeContract.WINDOW_MS, d.windowTimer)
    }

    @Test
    fun `in Both a hidden device still claims before it records, and a refused claim re-arms`() {
        val d = Device(WakeLocation.BOTH, arbitrated = true)
        d.startArmed()
        d.presence.onActivityPaused()
        d.controller.onResults(d.controller.generation, listOf("루미"), final = true)
        assertTrue(d.calls.contains("claim:claim-1"))
        assertFalse("nothing is recorded before the Phone grants the claim", d.calls.any { it.startsWith("handoff_in") })
        d.controller.onClaimVerdict("claim-1", ClaimVerdict.HELD_BY_OTHER)
        assertEquals("closed:wake_taken", d.calls.last())
        assertEquals(ContinuousWakePolicy.REARM_MS, d.rearmIn)
        d.rearmDue()
        // The next window knows the newer count of answered requests and may claim again.
        d.controller.onResults(d.controller.generation, listOf("루미"), final = true)
        d.controller.onClaimVerdict("claim-2", ClaimVerdict.GRANTED)
        assertTrue(d.controller.onHandoffDue(captureIdle = true))
        // The other device's request was admitted while this one was listening: the window closes and re-arms.
        val e = Device(WakeLocation.BOTH, arbitrated = true)
        e.startArmed()
        e.presence.onActivityPaused()
        e.epoch = 1
        e.controller.onEpisodeAnswered(1, "claim-phone")
        assertEquals("closed:wake_taken", e.calls.last())
        e.rearmDue()
        assertEquals(2, e.listened())
    }

    @Test
    fun `settings that exclude the device stop everything at once, and the mode changing mid-request cancels it unsent`() {
        val d = Device(WakeLocation.BOTH, arbitrated = true)
        d.startArmed()
        d.presence.onActivityPaused()
        d.controller.onResults(d.controller.generation, listOf("루미"), final = true)
        d.controller.onClaimVerdict("claim-1", ClaimVerdict.GRANTED)
        d.controller.onHandoffDue(captureIdle = true)
        d.controller.onSettings(WatchSettings(WakeLocation.WATCH, "루미", revision = 2))
        assertTrue(d.calls.contains("cancel_capture:wake_mode_changed"))
        d.rearmDue()
        d.controller.onSettings(WatchSettings(WakeLocation.PHONE, "루미", revision = 3))
        assertEquals("closed:opt_out", d.calls.last { it.startsWith("closed:") })
        // The runtime disarms the session for an excluded device; its timers then do nothing.
        d.presence.onArmed(false)
        val listened = d.listened()
        d.presence.onRearmDue()
        d.controller.onSettings(WatchSettings(WakeLocation.WATCH, "루미", revision = 4))
        assertEquals("included again while hidden and disarmed: nothing listens", listened, d.listened())
    }

    @Test
    fun `the continuous policy names every close reason`() {
        val rearm = listOf("timeout", "not_matched", "unfinished_request", "request_too_long", "wake_taken", "wake_claim_failed",
            "wake_claim_timeout", "wake_mode_changed", "recognizer_error_6", "recognizer_error_7")
        for (reason in rearm) assertEquals(reason, ContinuousWakePolicy.REARM_MS, ContinuousWakePolicy.next(reason, failures = 0)?.delayMs)
        for (reason in listOf("unavailable", "busy", "opt_out", "pause", "screen_off", "resume", "rearm", "qa_handoff")) {
            assertNull(reason, ContinuousWakePolicy.next(reason, failures = 0))
        }
        assertTrue(ContinuousWakePolicy.next("recognizer_error_9", failures = 40)!!.delayMs <= ContinuousWakePolicy.MAX_BACKOFF_MS)
        assertTrue("an unknown reason backs off rather than spins", ContinuousWakePolicy.next("something_new", failures = 0)!!.failure)
    }
}
