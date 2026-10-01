package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.background.WakeHolds
import com.rumi.hermesvoice.core.background.WakeLockPort
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeBlock
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakePresence
import com.rumi.hermesvoice.core.wake.WakePresencePort
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions of the independent review of the background candidate, written against the APIs the
 * reviewed candidate already had, so they also run (and fail) on it: arming a hidden device, an
 * armed device whose flow was paused, and a CPU hold shortened by a later acquire.
 */
class BackgroundReviewRegressionTest {
    private class Device : WakeRecognizerPort, WakeTimerPort, WakeDevicePort, WakePresencePort {
        var listens = 0
        val blocks = mutableListOf<WakeBlock>()
        val controller = WakeDeviceController(VoiceOrigin.WATCH, this, this, this, { 0L }, WatchSettings(WakeLocation.WATCH, "루미", revision = 1))
        val presence = WakePresence(controller, this)
        override fun available() = true
        override fun start(generation: Long): Boolean { listens += 1; return true }
        override fun release() {}
        override fun schedule(delayMs: Long) {}
        override fun cancel() {}
        override fun windowChanged(open: Boolean) {}
        override fun scheduleHandoff(delayMs: Long) {}
        override fun cancelHandoff() {}
        override fun startRequestCapture(silenceMs: Long) = true
        override fun cancelRequestCapture(reason: String) {}
        override fun sendRecognized(request: String) {}
        override fun closed(reason: String) {}
        override fun armInputs() = WakeArmInputs(enabled = true, resumed = presence.present, interactive = true, ambient = false, permission = true,
            microphoneMuted = false, talkIdle = true, phoneReachable = true, nowMs = 0, cooldownUntilMs = 0, generation = 0, lastArmedGeneration = null)
        override fun armBlocked(source: String, block: WakeBlock) { blocks += block }
        override fun scheduleRearm(delayMs: Long) {}
        override fun cancelRearm() {}
        override fun cancelCapture(reason: String) {}
    }

    @Test
    fun `a device that is not visible is never armed, and a normal reopen listens`() {
        val d = Device()
        d.presence.onActivityResumed(settingsPending = false)
        d.presence.onActivityPaused()
        val before = d.listens
        d.presence.onArmed(true)
        assertFalse("arming is refused while the app is hidden", d.presence.armed)
        assertEquals("and nothing listens", before, d.listens)
        assertFalse("no window blocked as not in the foreground either", d.blocks.contains(WakeBlock.NOT_FOREGROUND))
        d.presence.onActivityResumed(settingsPending = false)
        assertEquals("a normal reopen opens a window", before + 1, d.listens)
    }

    @Test
    fun `an armed device whose flow was paused listens again after a normal reopen`() {
        val d = Device()
        d.presence.onActivityResumed(settingsPending = false)
        d.presence.onArmed(true)
        // A stale pause of the flow (as a late event could leave it) while the session stays armed.
        d.controller.onPause()
        d.presence.onActivityPaused()
        val before = d.listens
        d.presence.onActivityResumed(settingsPending = false)
        assertEquals("the reopen restores the flow and a window opens", before + 1, d.listens)
        assertFalse(d.blocks.contains(WakeBlock.NOT_FOREGROUND))
    }

    @Test
    fun `a later acquire never shortens a hold that lasts longer`() {
        var now = 0L
        val calls = mutableListOf<String>()
        val holds = WakeHolds(object : WakeLockPort {
            override fun acquire(reason: HoldReason, timeoutMs: Long) { calls += "acquire:$reason:$timeoutMs" }
            override fun release(reason: HoldReason) { calls += "release:$reason" }
        }) { now }
        holds.acquire(HoldReason.LISTEN, 40_000)
        now = 1_000
        holds.acquire(HoldReason.LISTEN, 18_000)
        now = 21_000
        assertTrue("still held 21 s in: the 40 s hold was not cut to 19 s", HoldReason.LISTEN in holds.held())
        assertEquals("the shorter acquire did not reach the platform (which would restart its timeout)", listOf("acquire:LISTEN:40000"), calls)
        now = 30_000
        holds.acquire(HoldReason.LISTEN, 20_000)
        assertEquals("a longer one extends it", "acquire:LISTEN:20000", calls.last())
    }
}
