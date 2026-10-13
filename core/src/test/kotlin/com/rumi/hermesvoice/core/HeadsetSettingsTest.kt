package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.SettingsHelp
import com.rumi.hermesvoice.core.settings.WatchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one Phone setting: default off, persistent, Phone-only (never in the Watch snapshot), explained on demand. */
class HeadsetSettingsTest {
    @Test
    fun `use headset is off by default and persists`() {
        val store = InMemoryKeyValueStore()
        assertFalse(AppSettings(store).useHeadset)
        AppSettings(store).useHeadset = true
        assertTrue(AppSettings(store).useHeadset)
        AppSettings(store).useHeadset = false
        assertFalse(AppSettings(store).useHeadset)
    }

    @Test
    fun `a store written before the setting existed reads off`() {
        val store = InMemoryKeyValueStore()
        AppSettings(store).playFirstResponse = true
        assertFalse(AppSettings(store).useHeadset)
    }

    @Test
    fun `the Watch snapshot does not carry it and does not change when it toggles`() {
        val store = InMemoryKeyValueStore()
        val settings = AppSettings(store)
        val before = settings.watchSettings()
        val json = before.toJson()
        settings.useHeadset = true
        assertEquals(before, settings.watchSettings())
        assertEquals(json, settings.watchSettings().toJson())
        assertFalse(json.contains("headset", ignoreCase = true))
        assertFalse(WatchSettings::class.java.declaredFields.any { it.name.contains("headset", ignoreCase = true) })
    }

    @Test
    fun `the help explains the default  the boundary  the disconnect behaviour  the microphone fallback and the wake recognizer`() {
        val text = SettingsHelp.useHeadset().text
        for (needle in listOf("Off by default", "Bluetooth", "wired", "USB", "disconnect", "microphone", "wake")) {
            assertTrue("help mentions '$needle': $text", text.contains(needle, ignoreCase = true))
        }
        assertTrue(SettingsHelp.all(30).any { it.id == SettingsHelp.USE_HEADSET })
        assertTrue(SettingsHelp.topicIds.contains(SettingsHelp.USE_HEADSET))
    }
}
