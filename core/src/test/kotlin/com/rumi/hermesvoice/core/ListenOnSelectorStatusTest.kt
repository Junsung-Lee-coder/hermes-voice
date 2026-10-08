package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundStatus
import com.rumi.hermesvoice.core.background.BackgroundText
import com.rumi.hermesvoice.core.settings.SettingsHelp
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.wake.WakeLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenOnSelectorStatusTest {
    private val running = BackgroundStatus(wanted = true, running = true, microphone = true, notice = BackgroundNotice.LISTENING)

    @Test
    fun `a hidden armed Phone that Listen on leaves out reports NOT_SELECTED and says why`() {
        val dev = listenDev(VoiceOrigin.PHONE, WakeLocation.WATCH) as PhoneDev
        dev.boot()
        assertFalse(dev.rig.wake.selected)
        assertEquals(WakeLoop.NOT_SELECTED, dev.rig.status().loop)
        assertFalse(dev.rig.status().listeningNow)
        assertTrue(BackgroundText.phoneWakeNotification(dev.rig.status())!!.contains("Listen on does not include this phone"))
        assertTrue(BackgroundText.phoneWakeStatus(dev.rig.status(), notifications = true).contains("Listen on does not include this phone"))
    }

    @Test
    fun `a hidden armed Watch that Listen on leaves out reports NOT_SELECTED and says why`() {
        val dev = listenDev(VoiceOrigin.WATCH, WakeLocation.PHONE) as WatchDev
        dev.boot()
        assertFalse(dev.wake.selected)
        assertEquals(WakeLoop.NOT_SELECTED, dev.w.status.loop)
        assertTrue(BackgroundText.watchNotification(dev.w.status.session, dev.w.status.loop)!!.contains("Listen on does not include it"))
        assertTrue(BackgroundText.watchLabel(dev.w.status.session, dev.w.status.loop).contains("not selected in Listen on"))
    }

    @Test
    fun `selecting the device again clears the not-selected status`() {
        val phone = listenDev(VoiceOrigin.PHONE, WakeLocation.OFF) as PhoneDev
        phone.boot()
        assertEquals(WakeLoop.NOT_SELECTED, phone.rig.status().loop)
        phone.listenOn(WakeLocation.PHONE)
        assertTrue(phone.rig.wake.selected)
        assertNotEquals(WakeLoop.NOT_SELECTED, phone.rig.status().loop)
        val watch = listenDev(VoiceOrigin.WATCH, WakeLocation.OFF) as WatchDev
        watch.boot()
        assertEquals(WakeLoop.NOT_SELECTED, watch.w.status.loop)
        watch.listenOn(WakeLocation.BOTH)
        assertNotEquals(WakeLoop.NOT_SELECTED, watch.w.status.loop)
    }

    @Test
    fun `the not-selected wording never claims to listen`() {
        val texts = listOf(
            BackgroundText.watchNotification(running, WakeLoop.NOT_SELECTED)!!,
            BackgroundText.watchLabel(running, WakeLoop.NOT_SELECTED),
        )
        for (t in texts) assertFalse(t, t.contains("Listening for", ignoreCase = false) || t == "Background: listening")
    }

    @Test
    fun `the help says Listen on decides with the app open or closed and standby is only an extra condition`() {
        val wake = SettingsHelp.wakePhrase().text
        assertTrue(wake, wake.contains("with its app open or closed"))
        assertTrue(wake, wake.contains("never makes a device listen that \"Listen on\" leaves out"))
        val standby = SettingsHelp.standby().text
        assertTrue(standby, standby.contains("needs both"))
        assertTrue(standby, standby.contains("selected in \"Listen on\""))
    }
}
