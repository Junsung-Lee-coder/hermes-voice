package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.ReplicaUpdate
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.settings.WatchSettingsReplica
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundStandbySettingsTest {
    private val combos = listOf(false to false, true to false, false to true, true to true)

    @Test
    fun `a new install and a legacy snapshot have both standby switches off`() {
        val fresh = AppSettings(InMemoryKeyValueStore()).watchSettings()
        assertFalse(fresh.phoneBackgroundWakeEnabled)
        assertFalse(fresh.watchBackgroundWakeEnabled)
        val legacy = WatchSettings.parse("""{"wake_location":"BOTH","wake_patterns":"루미","haptics_enabled":true,"revision":5}""")!!
        assertEquals(WakeLocation.BOTH, legacy.wakeLocation)
        assertFalse(legacy.phoneBackgroundWakeEnabled)
        assertFalse(legacy.watchBackgroundWakeEnabled)
    }

    @Test
    fun `all four combinations persist across a process start and mutate independently`() {
        val store = InMemoryKeyValueStore()
        var settings = AppSettings(store)
        var now = 1_000L
        for ((phone, watch) in combos) {
            val saved = settings.saveWatchSettings(settings.watchSettings().copy(phoneBackgroundWakeEnabled = phone, watchBackgroundWakeEnabled = watch), now++)
            assertEquals(phone, saved.phoneBackgroundWakeEnabled)
            assertEquals(watch, saved.watchBackgroundWakeEnabled)
            settings = AppSettings(store)
            assertEquals(phone, settings.watchSettings().phoneBackgroundWakeEnabled)
            assertEquals(watch, settings.watchSettings().watchBackgroundWakeEnabled)
        }
        settings.saveWatchSettings(settings.watchSettings().copy(phoneBackgroundWakeEnabled = true, watchBackgroundWakeEnabled = false), now++)
        settings.saveWatchSettings(settings.watchSettings().copy(watchBackgroundWakeEnabled = true), now++)
        assertTrue(AppSettings(store).watchSettings().phoneBackgroundWakeEnabled)
        settings.saveWatchSettings(settings.watchSettings().copy(phoneBackgroundWakeEnabled = false), now)
        assertTrue(AppSettings(store).watchSettings().watchBackgroundWakeEnabled)
    }

    @Test
    fun `saving the standby switches keeps the other settings and a strictly increasing revision`() {
        val settings = AppSettings(InMemoryKeyValueStore())
        settings.wakeLocation = WakeLocation.PHONE
        settings.watchWakePatterns = "hermes"
        settings.watchHapticsEnabled = false
        val first = settings.saveWatchSettings(settings.watchSettings().copy(watchBackgroundWakeEnabled = true), 500L)
        val second = settings.saveWatchSettings(first.copy(phoneBackgroundWakeEnabled = true), 400L)
        assertTrue(second.revision > first.revision)
        assertEquals(WakeLocation.PHONE, second.wakeLocation)
        assertEquals("hermes", second.wakePatterns)
        assertFalse(second.hapticsEnabled)
    }

    @Test
    fun `the wire format carries both canonical fields and a Wear mirror round trips them`() {
        val json = JSONObject(WatchSettings(revision = 9, phoneBackgroundWakeEnabled = true, watchBackgroundWakeEnabled = false).toJson())
        assertEquals(true, json.get("phone_background_wake_enabled"))
        assertEquals(false, json.get("watch_background_wake_enabled"))
        for ((phone, watch) in combos) {
            val sent = WatchSettings(WakeLocation.OFF, revision = 3, phoneBackgroundWakeEnabled = phone, watchBackgroundWakeEnabled = watch)
            val replica = WatchSettingsReplica(null)
            assertEquals(ReplicaUpdate.APPLIED, replica.offer(sent.toJson()))
            assertEquals(sent, replica.current)
            assertEquals(sent, WatchSettings.fromJson(replica.current.toJson()))
        }
    }

    @Test
    fun `a stale replayed or conflicting snapshot never overwrites a newer standby value`() {
        val replica = WatchSettingsReplica(null)
        val on = WatchSettings(revision = 10, watchBackgroundWakeEnabled = true)
        val off = WatchSettings(revision = 11, watchBackgroundWakeEnabled = false)
        assertEquals(ReplicaUpdate.APPLIED, replica.offer(on.toJson()))
        assertEquals(ReplicaUpdate.APPLIED, replica.offer(off.toJson()))
        assertEquals(ReplicaUpdate.STALE, replica.offer(on.toJson()))
        assertEquals(ReplicaUpdate.STALE, replica.offer(off.copy(watchBackgroundWakeEnabled = true).toJson()))
        assertFalse(replica.current.watchBackgroundWakeEnabled)
    }

    @Test
    fun `a malformed standby field is rejected instead of silently turning a standby on`() {
        assertNull(WatchSettings.parse("""{"wake_location":"OFF","revision":1,"watch_background_wake_enabled":"yes"}"""))
        assertNull(WatchSettings.parse("""{"wake_location":"OFF","revision":1,"phone_background_wake_enabled":1}"""))
    }

    @Test
    fun `the standby switches and the revision are written as one record so a crash cannot split them`() {
        val store = InMemoryKeyValueStore()
        val writes = mutableListOf<String>()
        val spying = object : KeyValueStore by store {
            override fun putString(key: String, value: String) { writes += key; store.putString(key, value) }
            override fun putBoolean(key: String, value: Boolean) { writes += key; store.putBoolean(key, value) }
        }
        AppSettings(spying).saveWatchSettings(WatchSettings(phoneBackgroundWakeEnabled = true, watchBackgroundWakeEnabled = true), 700L)
        assertEquals("both switches share one key", 1, writes.count { it == AppSettings.KEY_STANDBY_RECORD })
        val record = JSONObject(store.getString(AppSettings.KEY_STANDBY_RECORD)!!)
        assertEquals(true, record.getBoolean("phone_background_wake_enabled"))
        assertEquals(true, record.getBoolean("watch_background_wake_enabled"))
        assertEquals("the record carries its revision", AppSettings(store).watchSettingsRevision, record.getLong("revision"))
    }

    @Test
    fun `a standby record newer than the revision key still wins after a crash between the two writes`() {
        val store = InMemoryKeyValueStore()
        store.putString(AppSettings.KEY_WATCH_REVISION, "100")
        store.putString(AppSettings.KEY_STANDBY_RECORD, """{"phone_background_wake_enabled":true,"watch_background_wake_enabled":false,"revision":250}""")
        val settings = AppSettings(store)
        assertEquals(250L, settings.watchSettingsRevision)
        assertTrue(settings.watchSettings().phoneBackgroundWakeEnabled)
        val next = settings.saveWatchSettings(settings.watchSettings().copy(phoneBackgroundWakeEnabled = false), 10L)
        assertTrue(next.revision > 250L)
    }

    @Test
    fun `an unreadable stored standby record reads as both off and migration keeps the other settings`() {
        val store = InMemoryKeyValueStore()
        store.putString(AppSettings.KEY_STANDBY_RECORD, "{not json")
        store.putString(AppSettings.KEY_PROFILE, "kept")
        val settings = AppSettings(store)
        assertFalse(settings.watchSettings().phoneBackgroundWakeEnabled)
        assertFalse(settings.watchSettings().watchBackgroundWakeEnabled)
        settings.migrate()
        assertEquals("kept", settings.profile)
    }

    @Test
    fun `listening arbitration and the Both claim depend on who may listen not on the foreground location alone`() {
        val offBoth = WatchSettings(WakeLocation.OFF, phoneBackgroundWakeEnabled = true, watchBackgroundWakeEnabled = true)
        assertTrue("both standbys on with location OFF still need arbitration", offBoth.arbitrationRequired)
        assertTrue(offBoth.mayListen(VoiceOrigin.PHONE))
        assertTrue(offBoth.mayListen(VoiceOrigin.WATCH))
        val watchOnly = WatchSettings(WakeLocation.OFF, watchBackgroundWakeEnabled = true)
        assertFalse(watchOnly.arbitrationRequired)
        assertFalse(watchOnly.mayListen(VoiceOrigin.PHONE))
        assertTrue(WatchSettings(WakeLocation.PHONE, watchBackgroundWakeEnabled = true).arbitrationRequired)
        assertTrue(WatchSettings(WakeLocation.BOTH).arbitrationRequired)
        assertFalse(WatchSettings(WakeLocation.WATCH).arbitrationRequired)
    }
}
