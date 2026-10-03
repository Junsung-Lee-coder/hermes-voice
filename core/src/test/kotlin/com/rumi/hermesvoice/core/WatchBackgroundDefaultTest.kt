package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundStatus
import com.rumi.hermesvoice.core.background.BackgroundText
import com.rumi.hermesvoice.core.background.NotificationCapability
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.wake.WakeLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Watch's background operation is on by default: every real user open of the app ensures it (once per open), the
 * user's Stop holds until the next real open, and a start is only a REQUEST: the microphone counts as armed (and the
 * notification says it listens) only once the foreground service really entered with the microphone type.
 */
class WatchBackgroundDefaultTest {
    private fun watch(mode: WakeLocation = WakeLocation.WATCH) = ComposedWatch(mode, asyncService = true)

    @Test
    fun `a user open ensures the session once - repeated ensures neither start again nor stop`() {
        val w = watch()
        w.coordinator.onUserOpened()
        w.show()
        assertTrue(w.coordinator.ensureDefault())
        w.serviceEnters()
        assertTrue(w.status.session.running && w.status.session.microphone && w.coordinator.presence.armed)
        val generation = w.coordinator.session.generation
        repeat(3) { assertFalse("already running: reused", w.coordinator.ensureDefault()) }
        w.hide(); w.show()
        assertFalse(w.coordinator.ensureDefault())
        assertEquals(generation, w.coordinator.session.generation)
        assertEquals(1, w.log.count { it.startsWith("startService") })
        assertFalse(w.log.contains("stopService"))
    }

    @Test
    fun `the user's Stop holds until the next real open - resumes and swipes never restart it`() {
        val w = watch()
        w.coordinator.onUserOpened()
        w.show()
        w.coordinator.ensureDefault()
        w.serviceEnters()
        w.stop()
        assertFalse(w.status.session.running)
        w.hide(); w.show() // a permission dialog or any other pause/resume of the same open
        assertFalse(w.coordinator.ensureDefault())
        assertFalse(w.status.session.running)
        w.hide()
        w.coordinator.onUserOpened() // the user opens the app again
        w.show()
        assertTrue(w.coordinator.ensureDefault())
        assertEquals(2, w.log.count { it.startsWith("startService") })
    }

    @Test
    fun `a service the system ended is not started again within the same open`() {
        val w = watch()
        w.coordinator.onUserOpened()
        w.show()
        w.coordinator.ensureDefault()
        w.serviceEnters()
        w.coordinator.onServiceGone(w.coordinator.session.generation)
        assertFalse(w.coordinator.ensureDefault())
        assertEquals(BackgroundNotice.PAUSED, w.notice)
        w.coordinator.onUserOpened()
        assertTrue(w.coordinator.ensureDefault())
    }

    @Test
    fun `a refused start is not retried within the open`() {
        val w = watch()
        w.failStart = true
        w.coordinator.onUserOpened()
        w.show()
        assertFalse("refused", w.coordinator.ensureDefault())
        assertEquals(BackgroundNotice.REFUSED, w.notice)
        w.failStart = false
        assertFalse(w.coordinator.ensureDefault())
        assertEquals(1 + 1, w.log.count { it.startsWith("startService") }) // the microphone start and its playback-only fallback
    }

    @Test
    fun `requested is not admitted - not armed, not listening, until the service entered with the microphone`() {
        val w = watch()
        w.coordinator.onUserOpened()
        w.show()
        w.coordinator.ensureDefault()
        assertEquals("startService(mic=true)", w.log.last { it.startsWith("startService") })
        assertFalse("not armed on a request", w.coordinator.presence.armed)
        assertEquals(WakeLoop.OFF, w.status.loop)
        assertFalse(BackgroundText.watchNotification(w.status.session, w.status.loop)!!.contains("Listening"))
        w.serviceEnters()
        assertTrue(w.coordinator.presence.armed)
        assertEquals("Listening for the wake phrase. Replies play here", BackgroundText.watchNotification(w.status.session, w.status.loop))
    }

    @Test
    fun `hidden before the service entered - its microphone is dropped, replies only, never listening hidden`() {
        val w = watch()
        w.coordinator.onUserOpened()
        w.show()
        w.coordinator.ensureDefault()
        w.hide()
        w.serviceEnters()
        assertFalse(w.coordinator.presence.armed)
        assertFalse(w.status.session.microphone)
        assertEquals(BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN, w.notice)
        assertFalse(BackgroundText.watchNotification(w.status.session, w.status.loop)!!.contains("Listening"))
    }

    @Test
    fun `settings pending at the open - replies-only start, the microphone only after the read, whichever finishes first`() {
        val readFirst = watch()
        readFirst.coordinator.onUserOpened()
        val visit = readFirst.resume()
        readFirst.coordinator.ensureDefault()
        assertEquals("startService(mic=false)", readFirst.log.last { it.startsWith("startService") })
        readFirst.pulled(visit) // before the platform delivered the service
        assertFalse(readFirst.coordinator.presence.armed)
        readFirst.serviceEnters()
        assertTrue("armed once both happened", readFirst.coordinator.presence.armed)

        val serviceFirst = watch()
        serviceFirst.coordinator.onUserOpened()
        val v = serviceFirst.resume()
        serviceFirst.coordinator.ensureDefault()
        serviceFirst.serviceEnters()
        assertFalse("still waiting for the settings read", serviceFirst.coordinator.presence.armed)
        assertEquals(BackgroundNotice.NEEDS_SETTINGS, serviceFirst.notice)
        serviceFirst.pulled(v)
        assertTrue(serviceFirst.coordinator.presence.armed)
    }

    @Test
    fun `the Phone's setting excludes the Watch - a replies-only session, the wake phrase is not forced on`() {
        val w = watch(WakeLocation.PHONE)
        w.coordinator.onUserOpened()
        w.show()
        w.coordinator.ensureDefault()
        w.serviceEnters()
        assertTrue(w.status.session.running)
        assertFalse(w.status.session.microphone)
        assertEquals(BackgroundNotice.RUNNING, w.notice)
        assertEquals(0, w.listens())
    }

    @Test
    fun `no permission, no notifications or no recognizer - truthful replies only, never listening`() {
        for (setup in listOf<(ComposedWatch) -> Unit>({ it.micPermission = false }, { it.notifications = NotificationCapability.NOT_ALLOWED },
            { it.recognizer = false })) {
            val w = watch()
            setup(w)
            w.coordinator.onUserOpened()
            w.show()
            w.coordinator.ensureDefault()
            w.serviceEnters()
            assertTrue(w.status.session.running)
            assertFalse(w.status.session.microphone || w.coordinator.presence.armed)
            assertTrue(BackgroundText.watchNotification(w.status.session, w.status.loop)!!.startsWith("Replies play here. "))
        }
    }

    @Test
    fun `a late entry of a stopped session's service changes nothing`() {
        val w = watch()
        w.coordinator.onUserOpened()
        w.show()
        w.coordinator.ensureDefault()
        val started = w.coordinator.session.generation
        w.stop()
        // The platform delivers the stopped session's start anyway: the service enters (it must) and reports it.
        w.coordinator.onServiceEntered(started, true)
        w.drain()
        assertFalse(w.status.session.running || w.coordinator.presence.armed)
        assertNull(w.status.session.takeIf { it.running })
        assertFalse("no restart", w.coordinator.ensureDefault())
    }

    @Test
    fun `the notification line while the microphone is asked for but not yet armed says so`() {
        val asked = BackgroundStatus(true, true, true, BackgroundNotice.LISTENING)
        assertFalse(BackgroundText.watchNotification(asked, WakeLoop.OFF)!!.contains("Listening"))
        assertEquals("Listening for the wake phrase. Replies play here", BackgroundText.watchNotification(asked, WakeLoop.ACTIVE))
    }
}
