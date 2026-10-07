package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundPort
import com.rumi.hermesvoice.core.background.BackgroundText
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.background.NotificationCapability
import com.rumi.hermesvoice.core.background.WakeHolds
import com.rumi.hermesvoice.core.background.WakeLockPort
import com.rumi.hermesvoice.core.background.WatchVoiceCoordinator
import com.rumi.hermesvoice.core.background.WatchVoiceHost
import com.rumi.hermesvoice.core.background.WatchVoiceStatus
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
import com.rumi.hermesvoice.core.wake.WakeLoop
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Watch's background session composed as WatchVoiceRuntime composes it: the real
 * [WatchVoiceCoordinator] with the real [WakeDeviceController] (built on the coordinator's
 * device port), [com.rumi.hermesvoice.core.wake.WakePresence], [com.rumi.hermesvoice.core.background.BackgroundSession]
 * and [WakeHolds]. Only the platform is fake: the foreground service (which may refuse the
 * microphone), the recognizer, timers on a fake clock, and live platform facts. Events are driven
 * in the order the Android adapters produce them, including work posted to run after an event.
 */
class WatchVoiceCoordinatorTest {
    // ── F1: arming only from the visible app, truthful status ────────────────────────────────

    @Test
    fun `a settings read that finishes after the app was hidden never asks for the microphone, whatever the platform would say`() {
        for (accepts in listOf(true, false)) {
            val w = ComposedWatch(WakeLocation.PHONE)
            w.serviceAcceptsMic = accepts
            w.show()
            w.start()
            assertEquals(BackgroundNotice.RUNNING, w.notice)
            w.hide()
            // The Phone includes the Watch while it is hidden: nothing is armed from the background.
            w.settingsChange(WakeLocation.WATCH)
            assertEquals(BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN, w.notice)
            // Opened, and left before the reachability check and settings read of that show finished.
            val visit = w.resume()
            w.hide()
            val requests = w.micRequests()
            assertFalse("the late read is ignored", w.pulled(visit))
            assertEquals("no microphone type asked for by a hidden app (platform accepts=$accepts)", requests, w.micRequests())
            assertEquals(BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN, w.notice)
            assertFalse(w.coordinator.presence.armed)
            assertEquals("mediaPlayback", w.serviceType)
            // A normal reopen arms it and a window opens; hidden, the loop keeps going.
            w.show()
            if (accepts) {
                assertEquals(BackgroundNotice.LISTENING, w.notice)
                assertTrue(w.windowOpen)
                w.hide()
                w.windowTimeout()
                w.rearmDue()
                assertTrue("listening hidden after the reopen", w.windowOpen)
            } else {
                assertNotEquals("a refused microphone never reads as listening", BackgroundNotice.LISTENING, w.status.session.notice)
            }
        }
    }

    @Test
    fun `a platform refusal of the microphone type is shown, never reported as listening`() {
        val w = ComposedWatch()
        w.serviceAcceptsMic = false
        w.show()
        w.start()
        assertEquals(BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN, w.notice)
        assertFalse(w.status.session.microphone)
        assertEquals("mediaPlayback", w.serviceType)
        assertEquals("replies only until opened", "Background: replies only until opened", BackgroundText.watchLabel(w.status.session, w.status.loop))
        // Accepted at the next visible show.
        w.serviceAcceptsMic = true
        w.hide()
        w.show()
        assertEquals(BackgroundNotice.LISTENING, w.notice)
    }

    @Test
    fun `a microphone that reaches a hidden app anyway is given back at once`() {
        val w = ComposedWatch()
        w.show()
        w.hide()
        // A stray path hands the session its microphone while nothing is visible.
        w.coordinator.session.start(visible = true, block = null)
        w.drain()
        assertFalse(w.coordinator.presence.armed)
        assertFalse(w.status.session.microphone)
        assertEquals("the type is narrowed again", "mediaPlayback", w.serviceType)
        assertEquals(BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN, w.notice)
    }

    @Test
    fun `settings that change while the settings read is pending decide, and only the current visit applies`() {
        val w = ComposedWatch()
        w.show()
        w.start()
        w.hide()
        w.settingsChange(WakeLocation.OFF)
        assertEquals(BackgroundNotice.RUNNING, w.notice)
        val first = w.resume()
        w.hide()
        val second = w.resume()
        // The Phone switched the Watch back on while this show's read is still pending: it waits for that read (N1).
        w.settingsChange(WakeLocation.BOTH)
        assertEquals(BackgroundNotice.NEEDS_SETTINGS, w.notice)
        assertEquals("mediaPlayback", w.serviceType)
        assertFalse("the first visit's read is stale", w.pulled(first))
        assertEquals(BackgroundNotice.NEEDS_SETTINGS, w.notice)
        assertTrue(w.pulled(second))
        assertEquals(BackgroundNotice.LISTENING, w.notice)
        assertTrue(w.windowOpen)
        // Excluded again (a newer revision) while hidden: disarmed at once.
        w.hide()
        w.settingsChange(WakeLocation.PHONE)
        assertEquals(BackgroundNotice.RUNNING, w.notice)
        assertFalse(w.windowOpen)
        assertEquals("mediaPlayback", w.serviceType)
    }

    @Test
    fun `visible Stop and Start, and a normal reopen, always end with a listening loop`() {
        val w = ComposedWatch()
        w.show()
        w.start()
        w.hide()
        w.show()
        assertTrue("normal reopen of an armed session: a window is open", w.windowOpen)
        w.stop()
        assertEquals(BackgroundNotice.OFF, w.notice)
        w.start()
        assertEquals(BackgroundNotice.LISTENING, w.notice)
        w.hide()
        w.windowTimeout()
        w.rearmDue()
        assertTrue(w.windowOpen)
        assertEquals(WakeContract.BACKGROUND_WINDOW_MS, w.wake.windowMs)
    }

    @Test
    fun `whenever the session says it listens, the loop can actually listen`() {
        val w = ComposedWatch()
        fun check(step: String) {
            val s = w.status
            if (s.session.notice == BackgroundNotice.LISTENING) {
                assertTrue("$step: armed", w.coordinator.presence.armed)
                assertTrue("$step: a window, a pending one, or a request under way",
                    w.windowOpen || w.coordinator.presence.pendingRearm != null || w.capturing || w.handoffIn != null || w.busy)
                assertFalse("$step: never blocked as not in the foreground", w.log.any { it.endsWith(":NOT_FOREGROUND") })
            }
        }
        w.show(); check("show")
        w.start(); check("start")
        w.hide(); check("hide")
        w.screenOn = false; w.coordinator.onScreenOff(); w.drain(); check("screen off")
        repeat(3) { w.windowTimeout(); check("timeout $it"); w.rearmDue(); check("rearm $it") }
        val visit = w.resume(); w.hide(); w.pulled(visit); check("late read")
        w.show(); check("reopen")
        w.hide(); w.heard("루미"); check("phrase"); w.handoffDue(); check("recording")
    }

    // ── F2: some finite hold covers every armed step ─────────────────────────────────────────

    @Test
    fun `the window already open when the session is armed is held`() {
        val w = ComposedWatch()
        w.show()
        assertTrue("the foreground window of the show", w.windowOpen)
        assertTrue(w.held().isEmpty())
        w.start()
        assertTrue(HoldReason.LISTEN in w.held())
    }

    @Test
    fun `a recognizer result never shortens the window's hold`() {
        val w = ComposedWatch()
        w.show(); w.start(); w.hide()
        w.windowTimeout(); w.rearmDue()
        val opened = w.now
        assertTrue(HoldReason.LISTEN in w.held())
        w.now += 1_000
        w.heard("오늘 날씨", final = false)
        w.now = opened + WakeContract.BACKGROUND_WINDOW_MS - 100
        assertTrue("still open", w.windowOpen)
        assertTrue("and still held close to its deadline", HoldReason.LISTEN in w.held())
        // A leading phrase in a partial moves the deadline later: the hold follows it.
        w.heard("루미 불", final = false)
        w.now = w.wake.windowDeadlineMs() - 100
        assertTrue(w.windowOpen)
        assertTrue(HoldReason.LISTEN in w.held())
    }

    @Test
    fun `from the phrase to the recorder a hold is taken before the previous one is let go`() {
        val w = ComposedWatch()
        w.show(); w.start(); w.hide()
        w.screenOn = false
        w.lockLog.clear()
        w.heard("루미")
        assertFalse(w.windowOpen)
        assertTrue(HoldReason.HANDOFF in w.held())
        assertFalse(HoldReason.LISTEN in w.held())
        val handoff = w.lockLog.indexOfFirst { it.startsWith("acquire:HANDOFF") }
        assertTrue("HANDOFF before LISTEN is released: ${w.lockLog}", handoff in 0 until w.lockLog.indexOf("release:LISTEN"))
        w.lockLog.clear()
        w.handoffDue()
        assertTrue(w.capturing)
        assertEquals(setOf(HoldReason.CAPTURE), w.held())
        assertTrue("CAPTURE before HANDOFF is released: ${w.lockLog}",
            w.lockLog.indexOfFirst { it.startsWith("acquire:CAPTURE") } in 0 until w.lockLog.indexOf("release:HANDOFF"))
    }

    @Test
    fun `in Both the wait for the Phone's answer is held, and a refusal leaves the gap to the next window unheld`() {
        val w = ComposedWatch(WakeLocation.BOTH, arbitrated = true)
        w.show(); w.start(); w.hide()
        w.heard("루미")
        assertTrue(w.claimsSent.contains("claim:claim-1"))
        assertNull("nothing recorded before the claim is granted", w.handoffIn)
        assertEquals(setOf(HoldReason.HANDOFF), w.held())
        w.lockLog.clear()
        w.wake.onClaimVerdict("claim-1", ClaimVerdict.HELD_BY_OTHER); w.drain()
        assertTrue("no CPU hold across the idle gap", w.held().isEmpty())
        assertTrue(w.lockLog.none { it.contains("REARM") })
        assertNotNull("the timer alone brings the next window", w.rearmIn)
        w.rearmDue()
        w.heard("루미")
        w.wake.onClaimVerdict("claim-2", ClaimVerdict.GRANTED); w.drain()
        assertEquals(WakeContract.MIC_HANDOFF_MS, w.handoffIn)
        assertTrue(HoldReason.HANDOFF in w.held())
        w.handoffDue()
        assertTrue(w.capturing)
    }

    @Test
    fun `window after window, every window is held, the idle gap holds nothing, and holds end with the loop`() {
        val w = ComposedWatch()
        w.show(); w.start(); w.hide()
        w.screenOn = false; w.coordinator.onScreenOff(); w.drain()
        repeat(200) {
            assertTrue("window $it open and held", w.windowOpen && HoldReason.LISTEN in w.held())
            w.lockLog.clear()
            w.windowTimeout()
            assertTrue("gap $it holds nothing", w.held().isEmpty())
            assertTrue("LISTEN let go at the window's end", w.lockLog.contains("release:LISTEN"))
            w.lockLog.clear()
            w.rearmDue()
            assertTrue("LISTEN taken for the next window", w.lockLog.any { l -> l.startsWith("acquire:LISTEN") })
        }
        for (entry in w.lockLog.filter { it.startsWith("acquire:") }) {
            val (_, reason, timeout) = entry.split(":")
            assertTrue(entry, timeout.toLong() in 1..HoldReason.valueOf(reason).maxMs)
        }
        w.stop()
        assertTrue("Stop lets go of everything", w.held().isEmpty())
        val acquires = w.lockLog.count { it.startsWith("acquire:") }
        w.stop()
        w.coordinator.sync()
        assertEquals("a second Stop takes nothing and lets go of nothing more", acquires, w.lockLog.count { it.startsWith("acquire:") })
    }

    @Test
    fun `retries are held within a bound and said honestly, never as listening continuously`() {
        val w = ComposedWatch()
        w.show(); w.start(); w.hide()
        w.windowTimeout(); w.rearmDue()
        w.reachable = false
        w.windowTimeout()
        w.rearmDue()
        assertTrue(w.log.last().endsWith(":PHONE_UNREACHABLE"))
        assertEquals(ContinuousWakePolicy.BLOCKED_RETRY_MS, w.rearmIn)
        assertTrue("the wait between retries holds no wake lock", w.held().isEmpty())
        assertEquals(WakeLoop.RETRYING, w.status.loop)
        assertEquals("Background: retrying", BackgroundText.watchLabel(w.status.session, w.status.loop))
        assertTrue(BackgroundText.watchNotification(w.status.session, w.status.loop)!!.startsWith("Wake phrase paused, retrying"))
        w.reachable = true
        w.rearmDue()
        assertTrue(w.windowOpen)
        assertEquals(WakeLoop.ACTIVE, w.status.loop)
        // A failing recognizer backs off up to a minute, each gap held for a bounded time.
        repeat(8) { w.wake.onError(w.wake.generation, 5); w.drain(); assertTrue(w.held().isEmpty()); assertNotNull(w.rearmIn); w.rearmDue() }
        assertEquals(WakeLoop.ACTIVE, w.status.loop)
    }

    @Test
    fun `stop, a disarming settings change and leaving an unarmed app release the session's holds exactly once`() {
        val w = ComposedWatch()
        w.show(); w.start(); w.hide()
        w.heard("루미")
        assertTrue(HoldReason.HANDOFF in w.held())
        w.settingsChange(WakeLocation.PHONE)
        assertTrue(w.held().isEmpty())
        assertEquals(1, w.lockLog.count { it == "release:HANDOFF" })
        w.settingsChange(WakeLocation.WATCH)
        w.show(); w.hide()
        assertTrue(HoldReason.LISTEN in w.held())
        w.stop()
        assertTrue(w.held().isEmpty())
        assertEquals(w.lockLog.count { it.startsWith("release:LISTEN") }, w.lockLog.filter { it.startsWith("release:LISTEN") }.size)
        // Timeouts end holds on their own: nothing is counted as held past its bound.
        val v = ComposedWatch()
        v.show(); v.start(); v.hide()
        v.now += HoldReason.LISTEN.maxMs + 1
        assertFalse(HoldReason.LISTEN in v.held())
    }

    // ── F3: no hidden microphone without its notification ────────────────────────────────────

    @Test
    fun `without a visible notification the session only plays replies, and says how to listen`() {
        for (missing in listOf(NotificationCapability.NOT_ALLOWED, NotificationCapability.APP_OFF, NotificationCapability.CHANNEL_OFF)) {
            val w = ComposedWatch()
            w.notifications = missing
            w.show()
            w.start()
            assertEquals(missing.name, BackgroundNotice.NEEDS_NOTIFICATIONS, w.notice)
            assertEquals("no microphone type is ever asked for", 0, w.micRequests())
            assertEquals("mediaPlayback", w.serviceType)
            assertFalse(w.coordinator.presence.armed)
            w.hide()
            assertFalse("nothing listens hidden", w.windowOpen)
            assertTrue(w.held().isEmpty())
            assertEquals("Background: replies only. Allow notifications, then open this app to listen",
                BackgroundText.watchLabel(w.status.session, w.status.loop))
            assertEquals("the in-app control is the Stop", "Notification hidden. Tap here to stop",
                BackgroundText.watchAction(w.status.session, w.status.notification))
            assertEquals("Replies play here. Not listening: notifications are off", BackgroundText.watchNotification(w.status.session, w.status.loop))
        }
    }

    @Test
    fun `notifications allowed later arm only at a visible show, never from the background`() {
        val w = ComposedWatch()
        w.notifications = NotificationCapability.NOT_ALLOWED
        w.show(); w.start(); w.hide()
        // Allowed (in the system's settings) while the app is hidden; a settings sync arrives meanwhile.
        w.notifications = NotificationCapability.SHOWN
        w.coordinator.onEligibilityChanged(); w.drain()
        w.settingsChange(WakeLocation.WATCH)
        assertEquals(0, w.micRequests())
        assertNotEquals(BackgroundNotice.LISTENING, w.notice)
        val visit = w.resume(); w.hide()
        w.pulled(visit)
        assertEquals("a late read of a hidden visit doesn't either", 0, w.micRequests())
        w.show()
        assertEquals(BackgroundNotice.LISTENING, w.notice)
        assertEquals(1, w.micRequests())
    }

    @Test
    fun `notifications switched off during a hidden session disarm it at once or at the next window`() {
        for (viaBroadcast in listOf(true, false)) {
            val w = ComposedWatch()
            w.show(); w.start(); w.hide()
            assertTrue(w.coordinator.presence.armed)
            w.notifications = NotificationCapability.CHANNEL_OFF
            if (viaBroadcast) { w.coordinator.onEligibilityChanged(); w.drain() } else { w.windowTimeout(); w.rearmDue() }
            assertFalse(w.coordinator.presence.armed)
            assertFalse(w.windowOpen)
            assertEquals("mediaPlayback", w.serviceType)
            assertEquals(BackgroundNotice.NEEDS_NOTIFICATIONS, w.notice)
            assertTrue(w.held().isEmpty())
        }
    }

    @Test
    fun `a lost microphone permission disarms a hidden session`() {
        val w = ComposedWatch()
        w.show(); w.start(); w.hide()
        w.micPermission = false
        w.windowTimeout(); w.rearmDue()
        assertEquals(BackgroundNotice.NEEDS_PERMISSION, w.notice)
        assertEquals("mediaPlayback", w.serviceType)
        assertFalse(w.coordinator.presence.armed)
    }

    // ── F5: no microphone kept when nothing can listen ───────────────────────────────────────

    @Test
    fun `without a recognizer the session keeps only playback and retries only at a visible show`() {
        val w = ComposedWatch()
        w.recognizer = false
        w.show()
        w.start()
        assertEquals(BackgroundNotice.NO_RECOGNIZER, w.notice)
        assertEquals(0, w.micRequests())
        assertEquals("mediaPlayback", w.serviceType)
        // Found missing while armed (it was there at Start), by the window itself: narrowed, no loop, no holds.
        val u = ComposedWatch()
        u.show(); u.start(); u.hide()
        u.windowTimeout()
        u.recognizer = false
        u.rearmIn = null
        u.coordinator.onIdle(); u.drain()
        assertTrue(u.log.contains("closed:unavailable"))
        assertEquals(BackgroundNotice.NO_RECOGNIZER, u.notice)
        assertEquals("mediaPlayback", u.serviceType)
        assertTrue(u.held().isEmpty())
        // ... or by the check before the next window.
        val v = ComposedWatch()
        v.show(); v.start(); v.hide()
        v.recognizer = false
        v.windowTimeout(); v.rearmDue()
        assertEquals(BackgroundNotice.NO_RECOGNIZER, v.notice)
        assertEquals("mediaPlayback", v.serviceType)
        assertNull("no spinning", v.rearmIn)
        assertTrue(v.held().isEmpty())
        val listens = v.listens()
        v.coordinator.onIdle(); v.drain()
        assertEquals(listens, v.listens())
        assertEquals("Replies play here. Wake phrase unavailable on this watch", BackgroundText.watchNotification(v.status.session, v.status.loop))
        // Still missing at the next show: nothing asked for. Installed later: the next visible show arms it.
        v.show()
        assertEquals(BackgroundNotice.NO_RECOGNIZER, v.notice)
        v.hide()
        v.recognizer = true
        v.show()
        assertEquals(BackgroundNotice.LISTENING, v.notice)
        assertEquals("microphone|mediaPlayback", v.serviceType)
    }
}
