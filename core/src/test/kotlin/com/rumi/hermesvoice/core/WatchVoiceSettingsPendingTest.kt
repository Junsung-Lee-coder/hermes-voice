package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundText
import com.rumi.hermesvoice.core.background.MicBlock
import com.rumi.hermesvoice.core.background.NotificationCapability
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.wake.WakeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state a Start before the show's settings read is in (N1): what it's called, what it says,
 * where it ranks among the other reasons, and the stall guard behind it. Composed as the runtime
 * composes it ([ComposedWatch]).
 */
class WatchVoiceSettingsPendingTest {
    @Test
    fun `a session started before the read says it is checking the Phone's settings and plays replies`() {
        val w = ComposedWatch()
        val visit = w.resume()
        // The notification prompt's result arrived before RESUMED: Start runs in onResume, before the read.
        w.start()
        assertEquals(MicBlock.SETTINGS_PENDING, w.coordinator.block())
        assertEquals(BackgroundNotice.NEEDS_SETTINGS, w.notice)
        assertEquals("Background: checking Phone settings (replies only)", BackgroundText.watchLabel(w.status.session, w.status.loop))
        assertEquals("Replies play here. Not listening: Phone settings check not complete",
            BackgroundText.watchNotification(w.status.session, w.status.loop))
        assertFalse(w.coordinator.settingsReady)
        w.pulled(visit)
        assertTrue(w.coordinator.settingsReady)
        assertNull(w.coordinator.block())
        assertEquals(BackgroundNotice.LISTENING, w.notice)
    }

    @Test
    fun `hidden, a pending read isn't reported as checking, the session replies until opened`() {
        val w = ComposedWatch()
        w.resume(); w.start()
        assertEquals(BackgroundNotice.NEEDS_SETTINGS, w.notice)
        w.hide()
        assertEquals(BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN, w.notice)
        assertEquals("Background: replies only until opened", BackgroundText.watchLabel(w.status.session, w.status.loop))
        // Shown again: checking again, until this show's read applies.
        val v = w.resume()
        assertEquals(BackgroundNotice.NEEDS_SETTINGS, w.notice)
        w.pulled(v)
        assertEquals(BackgroundNotice.LISTENING, w.notice)
    }

    @Test
    fun `every specific reason outranks a pending read, which is only the last precondition`() {
        val w = ComposedWatch()
        w.resume()
        assertEquals(MicBlock.SETTINGS_PENDING, w.coordinator.block())
        w.notifications = NotificationCapability.NOT_ALLOWED
        assertEquals(MicBlock.NOTIFICATIONS, w.coordinator.block())
        w.recognizer = false
        assertEquals(MicBlock.NO_RECOGNIZER, w.coordinator.block())
        w.micPermission = false
        assertEquals(MicBlock.PERMISSION, w.coordinator.block())
        w.settingsChange(WakeLocation.PHONE)
        assertEquals(MicBlock.NOT_WANTED, w.coordinator.block())
    }

    @Test
    fun `an already armed session is never blocked by a pending read`() {
        val w = ComposedWatch()
        w.show(); w.start()
        assertTrue(w.status.session.microphone)
        w.hide(); w.resume()
        assertNull("armed by an earlier show: the new read is no precondition for it", w.coordinator.block())
        assertEquals(BackgroundNotice.LISTENING, w.notice)
    }

    @Test
    fun `an armed flow that could only report DISABLED gives the microphone back instead of claiming to listen`() {
        val w = ComposedWatch()
        w.show(); w.start(); w.hide()
        w.windowTimeout()
        // Force the flow into the state N1 left it in (settings not current), behind the coordinator's back.
        w.wake.onResume(settingsPending = true)
        w.drain()
        w.rearmDue()
        assertTrue(w.log.any { it.endsWith(":DISABLED") })
        assertFalse(w.status.session.microphone)
        assertEquals("mediaPlayback", w.serviceType)
        assertFalse(w.coordinator.presence.armed)
        assertTrue(w.held().isEmpty())
        assertEquals(BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN, w.notice)
        assertEquals(0, WakeBlock.values().count { it.name == "SETTINGS_PENDING" })
    }
}
