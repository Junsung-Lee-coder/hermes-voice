package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.ThemeMode
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WakePhrasePatterns
import com.rumi.hermesvoice.core.settings.WatchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun `first and middle switches default off and are independent and persistent`() {
        val store = InMemoryKeyValueStore()
        val settings = AppSettings(store)
        assertEquals(ResponsePlaybackSettings(false, false), settings.playback())
        for ((first, middle) in listOf(true to false, false to true, true to true, false to false)) {
            settings.playFirstResponse = first
            settings.playMiddleResponses = middle
            assertEquals(ResponsePlaybackSettings(first, middle), AppSettings(store).playback())
        }
    }

    @Test
    fun `there is no persisted key that could disable the final response or the ack`() {
        val keys = AppSettings::class.java.declaredFields.filter { it.name.startsWith("KEY_") }.map { it.name.lowercase() }
        assertTrue(keys.contains("key_play_first"))
        assertTrue(keys.none { it.contains("final") || it.contains("ack") })
    }

    @Test
    fun `watch settings default to push-to-talk only, carry no recording cap, and round trip`() {
        val settings = AppSettings(InMemoryKeyValueStore())
        assertEquals(WatchSettings(), settings.watchSettings())
        assertFalse(settings.watchSettings().watchWakeEnabled)
        assertFalse(WatchSettings::class.java.declaredFields.any { it.name.contains("maxTurn", ignoreCase = true) })
        settings.wakeLocation = WakeLocation.BOTH
        settings.watchWakePatterns = "  hermes  헤르메스 hermes "
        val decoded = WatchSettings.fromJson(settings.watchSettings().toJson())
        assertEquals(settings.watchSettings(), decoded)
        assertEquals("hermes 헤르메스", decoded.wakePatterns)
        assertEquals(WatchSettings(), WatchSettings.fromJson("not json"))
        assertEquals(WatchSettings(), WatchSettings.fromJson("""{"max_turn_seconds":1}"""))
    }

    @Test
    fun `phone theme defaults to dark, persists a choice, and reads unknown values as dark`() {
        val store = InMemoryKeyValueStore()
        assertEquals(ThemeMode.DARK, AppSettings(store).themeMode)
        assertTrue("dark by default even when the system is light", AppSettings(store).themeMode.isDark(systemDark = false))
        AppSettings(store).themeMode = ThemeMode.LIGHT
        assertEquals(ThemeMode.LIGHT, AppSettings(store).themeMode)
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        AppSettings(store).themeMode = ThemeMode.SYSTEM
        assertTrue(AppSettings(store).themeMode.isDark(systemDark = true))
        assertFalse(AppSettings(store).themeMode.isDark(systemDark = false))
        store.putString(AppSettings.KEY_THEME_MODE, "sepia")
        assertEquals(ThemeMode.DARK, AppSettings(store).themeMode)
    }

    @Test
    fun `wake patterns match whole tokens with wildcards`() {
        assertTrue(WakePhrasePatterns.matches("hermes", "Hey, Hermes!"))
        assertTrue(WakePhrasePatterns.matches("루미*", "루미야 일정 알려줘"))
        assertFalse(WakePhrasePatterns.matches("hermes", "hermetic seal"))
        assertTrue(WakePhrasePatterns.matches("", "루미"))
    }
}
