package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.background.NotificationCapability
import com.rumi.hermesvoice.core.settings.WakeLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N1 of the second review: Start (or any eligibility change) between the app being shown and that
 * show's settings read applying. The adapters produce exactly this order: WatchActivity.onResume
 * calls `onActivityResumed()`, launches the settings read (which suspends at once), and then runs a
 * Start whose notification-prompt result arrived before RESUMED; a quick tap does the same. Leaving
 * the screen cancels the read. Written against the API the reviewed candidate already had, so these
 * also run against it. Simulated clock: source/JVM evidence, not device time.
 */
class WatchVoiceN1Test {
    /** Hidden for [ms] simulated milliseconds: windows time out and gaps come due as the runtime's timers would. */
    private fun ComposedWatch.runHidden(ms: Long) {
        val until = now + ms
        while (now < until) {
            when {
                windowOpen -> windowTimeout()
                rearmIn != null -> rearmDue()
                else -> now = until
            }
        }
    }

    /** Never says it listens while nothing can: a microphone type is only held by a loop that opens windows. */
    private fun ComposedWatch.assertNoStall(step: String, listensBefore: Int, logFrom: Int = 0) {
        if (status.session.notice == BackgroundNotice.LISTENING || serviceType == "microphone|mediaPlayback") {
            assertTrue("$step: armed", coordinator.presence.armed)
            assertTrue("$step: windows opened while hidden (${listens()} vs $listensBefore)", listens() > listensBefore)
            assertFalse("$step: never silently disabled while armed", log.drop(logFrom).any { it.endsWith(":DISABLED") })
        }
    }

    @Test
    fun `Start before the show's settings read asks for no microphone, and the read arms it while visible`() {
        for (accepts in listOf(true, false)) {
            val w = ComposedWatch()
            w.serviceAcceptsMic = accepts
            val visit = w.resume()
            w.start()
            assertEquals("replies only until the read applies (accepts=$accepts)", 0, w.micRequests())
            assertEquals("mediaPlayback", w.serviceType)
            assertTrue(w.status.session.wanted && w.status.session.running)
            assertNotEquals(BackgroundNotice.LISTENING, w.notice)
            assertFalse(w.coordinator.presence.armed)
            assertTrue(w.pulled(visit))
            assertEquals(1, w.micRequests())
            if (accepts) {
                assertEquals(BackgroundNotice.LISTENING, w.notice)
                assertTrue("a window opens at once", w.windowOpen)
                assertTrue(HoldReason.LISTEN in w.held())
                val before = w.listens()
                val mark = w.log.size
                w.hide()
                w.runHidden(10 * 60_000L)
                w.assertNoStall("hidden after a visible read", before, mark)
            } else {
                assertNotEquals("refused: never listening", BackgroundNotice.LISTENING, w.notice)
                assertEquals("mediaPlayback", w.serviceType)
            }
        }
    }

    @Test
    fun `hidden before the read applies, the session plays replies only and never claims to listen`() {
        for (accepts in listOf(true, false)) {
            val w = ComposedWatch()
            w.serviceAcceptsMic = accepts
            w.resume()
            w.start()
            w.hide()
            val before = w.listens()
            w.runHidden(10 * 60_000L)
            assertEquals("no microphone type requested (accepts=$accepts)", 0, w.micRequests())
            assertEquals("mediaPlayback", w.serviceType)
            assertFalse(w.coordinator.presence.armed)
            assertEquals(BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN, w.notice)
            assertTrue("nothing held for a loop that doesn't run", w.held().isEmpty())
            assertNull(w.rearmIn)
            w.assertNoStall("hidden before the read", before)
            // A settings sync arriving while hidden changes nothing.
            w.settingsChange(WakeLocation.BOTH)
            assertEquals(0, w.micRequests())
            // The next complete visible show arms it.
            w.show()
            if (accepts) {
                assertEquals(BackgroundNotice.LISTENING, w.notice)
                assertTrue(w.windowOpen)
            }
        }
    }

    @Test
    fun `a stale read, while hidden or from an earlier show, never arms`() {
        val w = ComposedWatch()
        val first = w.resume()
        w.start()
        w.hide()
        assertFalse("read of a hidden show", w.pulled(first))
        assertEquals(0, w.micRequests())
        val second = w.resume()
        assertFalse("read of the earlier show after reopening", w.pulled(first))
        assertEquals(0, w.micRequests())
        assertNotEquals(BackgroundNotice.LISTENING, w.notice)
        assertTrue(w.pulled(second))
        assertEquals(BackgroundNotice.LISTENING, w.notice)
        assertTrue(w.windowOpen)
    }

    @Test
    fun `Stop before the read is final, and Stop then Start waits for the read`() {
        val w = ComposedWatch()
        val visit = w.resume()
        w.start()
        w.stop()
        assertEquals(BackgroundNotice.OFF, w.notice)
        assertTrue("the read of the same show doesn't restart it", w.pulled(visit))
        assertEquals(BackgroundNotice.OFF, w.notice)
        assertNull(w.serviceType)
        assertEquals(0, w.micRequests())
        val next = w.resume()
        w.start(); w.stop(); w.start()
        assertEquals(0, w.micRequests())
        assertTrue(w.pulled(next))
        assertEquals(BackgroundNotice.LISTENING, w.notice)
    }

    @Test
    fun `a read that completes without new settings is still a completed read`() {
        // The Phone was unreachable and the item couldn't be read: the adapter still reports the read of this show (cached replica).
        val w = ComposedWatch()
        w.reachable = false
        val visit = w.resume()
        w.start()
        assertTrue(w.pulled(visit))
        assertEquals(1, w.micRequests())
        assertEquals(BackgroundNotice.LISTENING, w.notice)
    }

    @Test
    fun `eligibility that changes during the read keeps its own reason and never arms early`() {
        // The Phone's setting excludes the Watch.
        val a = ComposedWatch()
        val va = a.resume(); a.start()
        a.settingsChange(WakeLocation.PHONE)
        assertEquals(BackgroundNotice.RUNNING, a.notice)
        a.settingsChange(WakeLocation.WATCH)
        assertEquals("included again before the read: still no microphone", 0, a.micRequests())
        a.pulled(va)
        assertEquals(BackgroundNotice.LISTENING, a.notice)
        // Notifications, permission, recognizer: their specific reasons, before and after the read.
        for ((setup, reason) in listOf<Pair<(ComposedWatch) -> Unit, BackgroundNotice>>(
            { w: ComposedWatch -> w.notifications = NotificationCapability.CHANNEL_OFF } to BackgroundNotice.NEEDS_NOTIFICATIONS,
            { w: ComposedWatch -> w.micPermission = false } to BackgroundNotice.NEEDS_PERMISSION,
            { w: ComposedWatch -> w.recognizer = false } to BackgroundNotice.NO_RECOGNIZER)) {
            val w = ComposedWatch()
            val v = w.resume()
            w.start()
            setup(w)
            w.coordinator.onEligibilityChanged(); w.drain()
            assertEquals(reason, w.notice)
            w.pulled(v)
            assertEquals(reason, w.notice)
            assertEquals(0, w.micRequests())
        }
        // A permission answer (the prompt path) during the read doesn't arm either.
        val p = ComposedWatch()
        val vp = p.resume(); p.start()
        p.coordinator.onEligibilityChanged(); p.drain()
        p.coordinator.start(); p.drain()
        assertEquals("Start again and a permission answer don't bypass the read", 0, p.micRequests())
        p.pulled(vp)
        assertEquals(BackgroundNotice.LISTENING, p.notice)
    }

    @Test
    fun `an already armed session keeps listening across a reopen whose read is still pending`() {
        val w = ComposedWatch()
        w.show(); w.start(); w.hide()
        w.windowTimeout(); w.rearmDue()
        assertTrue(w.windowOpen)
        val requests = w.micRequests()
        val mark = w.log.size
        // Shown again and hidden before that show's read applied, twice.
        repeat(2) {
            w.resume()
            assertEquals(BackgroundNotice.LISTENING, w.notice)
            assertTrue("the loop's window stays open", w.windowOpen)
            assertTrue(HoldReason.LISTEN in w.held())
            w.coordinator.onEligibilityChanged(); w.drain()
            assertEquals("a pending read doesn't tear a running loop down", BackgroundNotice.LISTENING, w.notice)
            w.hide()
        }
        assertEquals(requests, w.micRequests())
        assertEquals("microphone|mediaPlayback", w.serviceType)
        val before = w.listens()
        w.runHidden(5 * 60_000L)
        w.assertNoStall("armed loop after reopen", before, mark)
        // A phrase heard in the next window still goes to the recorder, once.
        if (!w.windowOpen) w.rearmDue()
        assertTrue(w.windowOpen)
        w.heard("루미")
        w.handoffDue()
        assertTrue(w.capturing)
        assertEquals(1, w.log.count { it == "capture" })
    }

    @Test
    fun `without a session, foreground wake and recording are as before`() {
        val w = ComposedWatch()
        val visit = w.resume()
        w.pulled(visit)
        assertTrue(w.windowOpen)
        w.heard("루미")
        w.handoffDue()
        assertTrue(w.capturing)
        w.hide()
        assertTrue(w.log.contains("cancel_capture:pause"))
        assertEquals(0, w.micRequests())
    }
}
