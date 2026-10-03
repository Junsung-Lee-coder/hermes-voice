package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeClaimPort
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.HapticPattern
import com.rumi.hermesvoice.core.watchlink.HapticUsage
import com.rumi.hermesvoice.core.watchlink.WatchHapticPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two wake cues, at the shared wake flow both recognizer adapters drive:
 * - Watch: one short pulse when its recognizer is really ready to listen (the adapter's
 *   onReadyForSpeech), once per armed listening session; and
 * - Phone: one short pulse when a wake phrase was accepted (a final match that passed every guard,
 *   and in Both only on the device the Phone admitted).
 */
class WakeCueTest {
    private class Device(origin: VoiceOrigin, settings: WatchSettings, claims: WakeClaimPort? = null) :
        WakeRecognizerPort, WakeTimerPort, WakeDevicePort {
        val calls = mutableListOf<String>()
        var busy = false
        var accepted = 0
        val controller = WakeDeviceController(origin, this, this, this, { 0L }, settings, claims)

        override fun available() = true
        override fun start(generation: Long): Boolean { calls += "listen:$generation"; return true }
        override fun release() { calls += "release" }
        override fun schedule(delayMs: Long) {}
        override fun cancel() {}
        override fun windowChanged(open: Boolean) {}
        override fun scheduleHandoff(delayMs: Long) { calls += "handoff" }
        override fun cancelHandoff() {}
        override fun startRequestCapture(silenceMs: Long): Boolean = true
        override fun cancelRequestCapture(reason: String) {}
        override fun sendRecognized(request: String) { calls += "send:$request" }
        override fun closed(reason: String) { calls += "closed:$reason" }
        override fun wakeAccepted() { accepted += 1 }
        override fun armInputs() = WakeArmInputs(enabled = true, resumed = true, interactive = true, ambient = false, permission = true,
            microphoneMuted = false, talkIdle = !busy, phoneReachable = true, nowMs = 0, cooldownUntilMs = 0, generation = 0,
            lastArmedGeneration = null)
    }

    private class Claims : WakeClaimPort {
        val requested = mutableListOf<String>()
        private var next = 0
        override fun newClaimId() = "c-${++next}"
        override fun epoch() = 0L
        override fun request(claimId: String, settingsRevision: Long, generation: Long, epoch: Long) { requested += claimId }
        override fun renew(claimId: String) {}
        override fun release(claimId: String) {}
        override fun scheduleTimer(delayMs: Long) {}
        override fun cancelTimer() {}
    }

    private fun settings(mode: WakeLocation, revision: Long = 1) = WatchSettings(mode, "루미", revision = revision)

    // ── Watch: ready to listen ───────────────────────────────────────────────────────────────

    @Test
    fun `the ready pulse is 20 ms of hardware feedback and the recording pulses are unchanged`() {
        assertEquals(HapticPattern(20, 1), WatchHapticPolicy.patternFor(HapticEvent.WAKE_READY))
        assertEquals(HapticUsage.HARDWARE_FEEDBACK, WatchHapticPolicy.usageFor(HapticEvent.WAKE_READY))
        assertEquals(HapticPattern(50, 1), WatchHapticPolicy.patternFor(HapticEvent.RECORDING_START))
        assertEquals(HapticPattern(30, 2), WatchHapticPolicy.patternFor(HapticEvent.RECORDING_END))
        assertEquals(HapticPattern(10, 1), WatchHapticPolicy.patternFor(HapticEvent.SCROLL_STEP))
    }

    @Test
    fun `ready pulses once per show, never for a duplicate or a stale recognizer`() {
        val watch = Device(VoiceOrigin.WATCH, settings(WakeLocation.WATCH))
        watch.controller.onResume()
        val gen = watch.controller.generation
        assertTrue(watch.controller.onRecognizerReady(gen))
        assertFalse("duplicate ready", watch.controller.onRecognizerReady(gen))
        watch.controller.onPause()
        watch.controller.onResume()
        assertFalse("the previous show's recognizer", watch.controller.onRecognizerReady(gen))
        assertTrue("a new show, once ready", watch.controller.onRecognizerReady(watch.controller.generation))
    }

    @Test
    fun `window rollover inside one armed background session does not pulse again`() {
        val watch = Device(VoiceOrigin.WATCH, settings(WakeLocation.WATCH))
        watch.controller.onResume()
        assertTrue(watch.controller.onRecognizerReady(watch.controller.generation))
        watch.controller.onError(watch.controller.generation, 7) // the window ended (no match)
        watch.controller.rearm("background")
        assertTrue(watch.calls.count { it.startsWith("listen:") } >= 2)
        assertFalse(watch.controller.onRecognizerReady(watch.controller.generation))
    }

    @Test
    fun `no pulse before ready, after a failure, when excluded or while settings are unconfirmed`() {
        val failing = Device(VoiceOrigin.WATCH, settings(WakeLocation.WATCH))
        failing.controller.onResume()
        val gen = failing.controller.generation
        failing.controller.onError(gen, 7)
        assertFalse("the window closed before it was ready", failing.controller.onRecognizerReady(gen))

        val off = Device(VoiceOrigin.WATCH, settings(WakeLocation.PHONE))
        off.controller.onResume()
        assertFalse(off.controller.onRecognizerReady(off.controller.generation))

        val pending = Device(VoiceOrigin.WATCH, settings(WakeLocation.WATCH))
        pending.controller.onResume(settingsPending = true)
        assertFalse("not listening yet", pending.controller.onRecognizerReady(pending.controller.generation))
        pending.controller.onSettingsCurrent()
        assertTrue(pending.controller.onRecognizerReady(pending.controller.generation))
    }

    @Test
    fun `turning the Watch back on is a fresh arming and pulses once more`() {
        val watch = Device(VoiceOrigin.WATCH, settings(WakeLocation.WATCH))
        watch.controller.onResume()
        assertTrue(watch.controller.onRecognizerReady(watch.controller.generation))
        watch.controller.onSettings(settings(WakeLocation.OFF, revision = 2))
        watch.controller.onSettings(settings(WakeLocation.WATCH, revision = 3))
        watch.controller.onScreenOff()
        watch.controller.onScreenOn()
        assertTrue(watch.controller.onRecognizerReady(watch.controller.generation))
    }

    // ── Phone: wake phrase accepted ──────────────────────────────────────────────────────────

    @Test
    fun `an accepted final phrase pulses once, a partial or a stale result never`() {
        val phone = Device(VoiceOrigin.PHONE, settings(WakeLocation.PHONE))
        phone.controller.onResume()
        val gen = phone.controller.generation
        phone.controller.onResults(gen, listOf("루미"), final = false)
        assertEquals("a partial is not a match", 0, phone.accepted)
        phone.controller.onResults(gen, listOf("루미"), final = true)
        assertEquals(1, phone.accepted)
        assertTrue(phone.calls.contains("handoff"))
        phone.controller.onResults(gen, listOf("루미"), final = true)
        assertEquals("the same window's duplicate", 1, phone.accepted)

        val sameBreath = Device(VoiceOrigin.PHONE, settings(WakeLocation.PHONE))
        sameBreath.controller.onResume()
        val old = sameBreath.controller.generation
        sameBreath.controller.onPause()
        sameBreath.controller.onResume()
        sameBreath.controller.onResults(old, listOf("루미 불 꺼"), final = true)
        assertEquals("a stale window", 0, sameBreath.accepted)
        sameBreath.controller.onResults(sameBreath.controller.generation, listOf("루미 불 꺼"), final = true)
        assertEquals(1, sameBreath.accepted)
        assertTrue(sameBreath.calls.contains("send:불 꺼"))
    }

    @Test
    fun `an excluded, busy or ambient phone never pulses`() {
        val off = Device(VoiceOrigin.PHONE, settings(WakeLocation.WATCH))
        off.controller.onResume()
        off.controller.onResults(off.controller.generation, listOf("루미"), final = true)
        val ambient = Device(VoiceOrigin.PHONE, settings(WakeLocation.PHONE))
        ambient.controller.onResume()
        ambient.controller.onResults(ambient.controller.generation, listOf("오늘 루미 얘기 했어"), final = true)
        val busy = Device(VoiceOrigin.PHONE, settings(WakeLocation.PHONE))
        busy.controller.onResume()
        val gen = busy.controller.generation
        busy.busy = true
        busy.controller.onBusy()
        busy.controller.onResults(gen, listOf("루미"), final = true)
        assertEquals(listOf(0, 0, 0), listOf(off.accepted, ambient.accepted, busy.accepted))
    }

    @Test
    fun `in Both only the device the Phone admitted pulses, once`() {
        val claims = Claims()
        val phone = Device(VoiceOrigin.PHONE, settings(WakeLocation.BOTH), claims)
        phone.controller.onResume()
        phone.controller.onResults(phone.controller.generation, listOf("루미"), final = true)
        assertEquals("waiting for the claim", 0, phone.accepted)
        phone.controller.onClaimVerdict(claims.requested.single(), ClaimVerdict.GRANTED)
        assertEquals(1, phone.accepted)
        phone.controller.onClaimVerdict(claims.requested.single(), ClaimVerdict.GRANTED)
        assertEquals(1, phone.accepted)

        val otherClaims = Claims()
        val refused = Device(VoiceOrigin.PHONE, settings(WakeLocation.BOTH), otherClaims)
        refused.controller.onResume()
        refused.controller.onResults(refused.controller.generation, listOf("루미"), final = true)
        refused.controller.onClaimVerdict(otherClaims.requested.single(), ClaimVerdict.HELD_BY_OTHER)
        assertEquals("the other device answered", 0, refused.accepted)
    }
}
