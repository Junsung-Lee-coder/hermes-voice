package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.ReplicaUpdate
import com.rumi.hermesvoice.core.settings.VadSilence
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.settings.WatchSettingsReplica
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Phone-owned trailing-silence and wake-location settings: validation, migration, persistence and the Watch replica. */
class VoiceSettingsTest {
    @Test
    fun `trailing silence is 0_5 to 10 seconds in 0_5 second steps, 2 seconds by default`() {
        assertEquals(2.0, VadSilence.DEFAULT_SECONDS, 0.0)
        assertEquals(20, VadSilence.choices.size)
        assertEquals(0.5, VadSilence.choices.first(), 0.0)
        assertEquals(10.0, VadSilence.choices.last(), 0.0)
        for (value in VadSilence.choices) assertEquals(value, VadSilence.validOrNull(value)!!, 0.0)
        for (bad in listOf(0.0, 0.25, 0.49, 10.5, 11.0, 2.3, 1.75, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, null)) {
            assertNull("$bad", VadSilence.validOrNull(bad))
        }
        assertEquals(500L, VadSilence.millis(0.5))
        assertEquals(10_000L, VadSilence.millis(10.0))
    }

    @Test
    fun `wake location labels and which device each mode lets listen`() {
        assertEquals(listOf("Off", "Watch", "Phone", "Both"), WakeLocation.values().map { it.label })
        val expected = mapOf(
            WakeLocation.OFF to setOf<VoiceOrigin>(),
            WakeLocation.WATCH to setOf(VoiceOrigin.WATCH),
            WakeLocation.PHONE to setOf(VoiceOrigin.PHONE),
            WakeLocation.BOTH to setOf(VoiceOrigin.WATCH, VoiceOrigin.PHONE),
        )
        for ((mode, devices) in expected) {
            assertEquals("$mode", devices, VoiceOrigin.values().filter { mode.listensOn(it) }.toSet())
        }
        assertEquals(WakeLocation.OFF, WatchSettings().wakeLocation)
    }

    @Test
    fun `phone settings default to off and 2 seconds, persist, and refuse invalid silence`() {
        val store = InMemoryKeyValueStore()
        val settings = AppSettings(store)
        assertEquals(WakeLocation.OFF, settings.wakeLocation)
        assertEquals(2.0, settings.vadSilenceSeconds, 0.0)
        for (mode in WakeLocation.values()) {
            for (seconds in listOf(0.5, 3.5, 10.0)) {
                val saved = settings.saveWatchSettings(settings.watchSettings().copy(wakeLocation = mode, vadSilenceSeconds = seconds))
                assertEquals(saved, AppSettings(store).watchSettings())
                assertEquals(mode, AppSettings(store).wakeLocation)
                assertEquals(seconds, AppSettings(store).vadSilenceSeconds, 0.0)
                assertEquals("legacy switch kept in step", mode.listensOn(VoiceOrigin.WATCH), store.getBoolean(AppSettings.KEY_WATCH_WAKE, false))
            }
        }
        for (bad in listOf(0.25, 12.0, Double.NaN)) {
            try {
                settings.vadSilenceSeconds = bad
                fail("$bad accepted")
            } catch (_: IllegalArgumentException) {
            }
            try {
                settings.watchSettings().copy(vadSilenceSeconds = bad)
                fail("$bad accepted in a snapshot")
            } catch (_: IllegalArgumentException) {
            }
        }
        for (corrupt in listOf("abc", "NaN", "Infinity", "12", "2.3", "")) {
            store.putString(AppSettings.KEY_VAD_SILENCE, corrupt)
            assertEquals("'$corrupt' reads as the default", 2.0, AppSettings(store).vadSilenceSeconds, 0.0)
        }
    }

    @Test
    fun `stored settings migrate durably from the old watch opt-in`() {
        for ((legacy, expected) in listOf(true to WakeLocation.WATCH, false to WakeLocation.OFF, null to WakeLocation.OFF)) {
            val store = InMemoryKeyValueStore()
            if (legacy != null) store.putBoolean(AppSettings.KEY_WATCH_WAKE, legacy)
            val settings = AppSettings(store)
            assertEquals("$legacy", expected, settings.wakeLocation)
            assertTrue(settings.migrate())
            assertEquals(expected.name, store.getString(AppSettings.KEY_WAKE_LOCATION))
            assertEquals(2.0, store.getString(AppSettings.KEY_VAD_SILENCE)!!.toDouble(), 0.0)
            assertFalse("idempotent", AppSettings(store).migrate())
            assertEquals(expected, AppSettings(store).wakeLocation)
        }
        val corrupt = InMemoryKeyValueStore().apply {
            putBoolean(AppSettings.KEY_WATCH_WAKE, true)
            putString(AppSettings.KEY_WAKE_LOCATION, "SOMETIMES")
            putString(AppSettings.KEY_VAD_SILENCE, "NaN")
        }
        assertEquals("an unreadable mode is OFF, never the legacy opt-in", WakeLocation.OFF, AppSettings(corrupt).wakeLocation)
        assertTrue(AppSettings(corrupt).migrate())
        assertEquals("OFF", corrupt.getString(AppSettings.KEY_WAKE_LOCATION))
        assertFalse(corrupt.getBoolean(AppSettings.KEY_WATCH_WAKE, true))
        assertEquals(2.0, AppSettings(corrupt).vadSilenceSeconds, 0.0)
    }

    @Test
    fun `the data layer snapshot round-trips and carries the legacy switch for an older watch`() {
        for (mode in WakeLocation.values()) {
            for (seconds in VadSilence.choices) {
                val settings = WatchSettings(mode, "hermes 루미", hapticsEnabled = false, vadSilenceSeconds = seconds, revision = 42)
                val json = settings.toJson()
                assertEquals(settings, WatchSettings.parse(json))
                assertEquals(mode.listensOn(VoiceOrigin.WATCH), JSONObject(json).getBoolean("wake_phrase_enabled"))
            }
        }
        val legacy = WatchSettings.parse("""{"wake_phrase_enabled":true,"wake_patterns":"루미","haptics_enabled":true,"revision":5}""")!!
        assertEquals(WakeLocation.WATCH, legacy.wakeLocation)
        assertEquals(2.0, legacy.vadSilenceSeconds, 0.0)
        assertEquals(WakeLocation.OFF, WatchSettings.parse("""{"revision":5}""")!!.wakeLocation)
        assertEquals("a legacy duration cap is ignored", WatchSettings(), WatchSettings.parse("""{"max_turn_seconds":1}"""))
    }

    @Test
    fun `invalid snapshots are rejected whole, never partly applied`() {
        val invalid = listOf(
            "not json", "[]", "", """{"wake_location":"LOUD"}""", """{"wake_location":2}""", """{"wake_location":null}""",
            """{"wake_phrase_enabled":"yes"}""", """{"vad_silence_seconds":"2.0"}""", """{"vad_silence_seconds":NaN}""",
            """{"vad_silence_seconds":Infinity}""", """{"vad_silence_seconds":1e999}""", """{"vad_silence_seconds":0.25}""",
            """{"vad_silence_seconds":10.5}""", """{"vad_silence_seconds":2.3}""", """{"vad_silence_seconds":true}""",
            """{"vad_silence_seconds":-2}""", """{"haptics_enabled":"no"}""", """{"revision":-1}""", """{"revision":1.5}""",
            """{"wake_patterns":7}""",
        )
        for (raw in invalid) assertNull(raw, WatchSettings.parse(raw))
        assertNull(WatchSettings.parse(null))
        assertEquals("the watch's own unreadable copy falls back to wake OFF", WatchSettings(), WatchSettings.fromJson("""{"wake_location":"LOUD"}"""))
    }

    @Test
    fun `the watch replica takes only valid, strictly newer snapshots`() {
        val replica = WatchSettingsReplica("""{"wake_phrase_enabled":true,"revision":100}""")
        assertEquals("stored legacy copy migrates", WakeLocation.WATCH, replica.current.wakeLocation)
        val disable = WatchSettings(WakeLocation.PHONE, vadSilenceSeconds = 4.0, revision = 200)
        assertEquals(ReplicaUpdate.APPLIED, replica.offer(disable.toJson()))
        assertFalse(replica.current.watchWakeEnabled)
        assertEquals(4.0, replica.current.vadSilenceSeconds, 0.0)
        // A stale or replayed snapshot that would re-enable the Watch listener is ignored.
        assertEquals(ReplicaUpdate.STALE, replica.offer(WatchSettings(WakeLocation.WATCH, revision = 150).toJson()))
        assertEquals(ReplicaUpdate.STALE, replica.offer(WatchSettings(WakeLocation.BOTH, revision = 200).toJson()))
        assertEquals(ReplicaUpdate.INVALID, replica.offer("""{"wake_location":"WATCH","vad_silence_seconds":NaN,"revision":300}"""))
        assertEquals(disable, replica.current)
        assertEquals(ReplicaUpdate.APPLIED, replica.offer(WatchSettings(WakeLocation.BOTH, vadSilenceSeconds = 0.5, revision = 300).toJson()))
        assertTrue(replica.current.watchWakeEnabled)
    }
}
