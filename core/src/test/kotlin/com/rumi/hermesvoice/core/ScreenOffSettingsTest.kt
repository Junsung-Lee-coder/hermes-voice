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

/**
 * The two subordinate "recognition while the screen is off" preferences (one per device) as settings: defaults and
 * migration, the wire format, the one atomic Phone-owned record, and the Wear mirror's rejection rules. Settings are
 * built through the wire format ([withScreenOff]) so this file's bytes are independent of the production field names.
 */
class ScreenOffSettingsTest {
    private val combos = listOf(false to false, true to false, false to true, true to true)
    private val phone = VoiceOrigin.PHONE
    private val watch = VoiceOrigin.WATCH

    @Test
    fun `a new install has both screen-off preferences off`() {
        val fresh = AppSettings(InMemoryKeyValueStore()).watchSettings()
        assertFalse(fresh.screenOffPreference(phone))
        assertFalse(fresh.screenOffPreference(watch))
        assertTrue("the carried wire format names both fields", JSONObject(fresh.toJson()).let { it.has(PHONE_SCREEN_OFF_KEY) && it.has(WATCH_SCREEN_OFF_KEY) })
        assertEquals(false, JSONObject(fresh.toJson()).opt(PHONE_SCREEN_OFF_KEY))
        assertEquals(false, JSONObject(fresh.toJson()).opt(WATCH_SCREEN_OFF_KEY))
    }

    @Test
    fun `a snapshot from before the fields existed migrates both to off and keeps the masters and the rest`() {
        val legacy = WatchSettings.parse(
            """{"wake_location":"BOTH","wake_patterns":"루미","haptics_enabled":false,"revision":7,""" +
                """"phone_background_wake_enabled":true,"watch_background_wake_enabled":true}""",
        )!!
        assertFalse(legacy.screenOffPreference(phone))
        assertFalse(legacy.screenOffPreference(watch))
        assertTrue(legacy.phoneBackgroundWakeEnabled)
        assertTrue(legacy.watchBackgroundWakeEnabled)
        assertEquals(WakeLocation.BOTH, legacy.wakeLocation)
        assertFalse(legacy.hapticsEnabled)
        assertEquals(7L, legacy.revision)
    }

    @Test
    fun `a stored record from before the fields existed reads both off after a process start`() {
        val store = InMemoryKeyValueStore()
        store.putString(AppSettings.KEY_WATCH_REVISION, "40")
        store.putString(AppSettings.KEY_STANDBY_RECORD, """{"phone_background_wake_enabled":true,"watch_background_wake_enabled":true,"revision":41}""")
        val read = AppSettings(store).watchSettings()
        assertTrue(read.phoneBackgroundWakeEnabled)
        assertTrue(read.watchBackgroundWakeEnabled)
        assertFalse(read.screenOffPreference(phone))
        assertFalse(read.screenOffPreference(watch))
        assertEquals(41L, read.revision)
    }

    @Test
    fun `each preference mutates alone and survives a process start in all four combinations`() {
        val store = InMemoryKeyValueStore()
        var settings = AppSettings(store)
        var now = 1_000L
        for ((p, w) in combos) {
            val saved = settings.saveWatchSettings(settings.watchSettings().withScreenOff(phone = p, watch = w), now++)
            assertEquals(p, saved.screenOffPreference(phone))
            assertEquals(w, saved.screenOffPreference(watch))
            settings = AppSettings(store)
            assertEquals(p, settings.watchSettings().screenOffPreference(phone))
            assertEquals(w, settings.watchSettings().screenOffPreference(watch))
        }
        settings.saveWatchSettings(settings.watchSettings().withScreenOff(phone = true, watch = false), now++)
        settings.saveWatchSettings(settings.watchSettings().withScreenOff(watch = true), now++)
        assertTrue("changing the Watch preference leaves the Phone's", AppSettings(store).watchSettings().screenOffPreference(phone))
        settings.saveWatchSettings(settings.watchSettings().withScreenOff(phone = false), now)
        assertTrue("changing the Phone preference leaves the Watch's", AppSettings(store).watchSettings().screenOffPreference(watch))
        assertFalse(AppSettings(store).watchSettings().screenOffPreference(phone))
    }

    @Test
    fun `a preference is independent of its master and is retained when the master is off`() {
        val store = InMemoryKeyValueStore()
        val settings = AppSettings(store)
        settings.saveWatchSettings(
            settings.watchSettings().copy(phoneBackgroundWakeEnabled = true, watchBackgroundWakeEnabled = true)
                .withScreenOff(phone = true, watch = true),
            100L,
        )
        settings.saveWatchSettings(settings.watchSettings().copy(phoneBackgroundWakeEnabled = false, watchBackgroundWakeEnabled = false), 200L)
        val after = AppSettings(store).watchSettings()
        assertFalse(after.phoneBackgroundWakeEnabled)
        assertFalse(after.watchBackgroundWakeEnabled)
        assertTrue("OFF master keeps the Phone preference", after.screenOffPreference(phone))
        assertTrue("OFF master keeps the Watch preference", after.screenOffPreference(watch))
        settings.saveWatchSettings(settings.watchSettings().withScreenOff(phone = true, watch = true), 300L)
        assertFalse("ON preference never turns a master on (Phone)", AppSettings(store).watchSettings().phoneBackgroundWakeEnabled)
        assertFalse("ON preference never turns a master on (Watch)", AppSettings(store).watchSettings().watchBackgroundWakeEnabled)
    }

    @Test
    fun `saving the other settings keeps both preferences and the revision stays strictly increasing`() {
        val settings = AppSettings(InMemoryKeyValueStore())
        val first = settings.saveWatchSettings(settings.watchSettings().withScreenOff(phone = true, watch = true), 500L)
        settings.wakeLocation = WakeLocation.PHONE
        val second = settings.saveWatchSettings(settings.watchSettings().copy(hapticsEnabled = false), 400L)
        assertTrue(second.revision > first.revision)
        assertTrue(second.screenOffPreference(phone))
        assertTrue(second.screenOffPreference(watch))
        assertFalse(second.hapticsEnabled)
        assertEquals(WakeLocation.PHONE, second.wakeLocation)
    }

    @Test
    fun `the wire format carries both canonical fields and a Wear mirror round trips them`() {
        val json = JSONObject(WatchSettings(revision = 9).withScreenOff(phone = true, watch = false).toJson())
        assertEquals(true, json.get(PHONE_SCREEN_OFF_KEY))
        assertEquals(false, json.get(WATCH_SCREEN_OFF_KEY))
        for ((p, w) in combos) {
            val sent = WatchSettings(WakeLocation.OFF, revision = 3, phoneBackgroundWakeEnabled = true, watchBackgroundWakeEnabled = true).withScreenOff(p, w)
            val replica = WatchSettingsReplica(null)
            assertEquals(ReplicaUpdate.APPLIED, replica.offer(sent.toJson()))
            assertEquals(sent, replica.current)
            assertEquals(p, replica.current.screenOffPreference(phone))
            assertEquals(w, replica.current.screenOffPreference(watch))
            assertEquals(sent, WatchSettings.fromJson(replica.current.toJson()))
        }
    }

    @Test
    fun `the preferences and both masters and the revision are one record, so a crash cannot split them`() {
        val store = InMemoryKeyValueStore()
        val writes = mutableListOf<String>()
        val spying = object : KeyValueStore by store {
            override fun putString(key: String, value: String) { writes += key; store.putString(key, value) }
            override fun putBoolean(key: String, value: Boolean) { writes += key; store.putBoolean(key, value) }
        }
        AppSettings(spying).saveWatchSettings(
            WatchSettings(phoneBackgroundWakeEnabled = true, watchBackgroundWakeEnabled = true).withScreenOff(phone = true, watch = true), 700L,
        )
        assertEquals("one write carries every standby value", 1, writes.count { it == AppSettings.KEY_STANDBY_RECORD })
        assertTrue("no separate key is written for a preference", writes.none { it.contains("screen_off") })
        val record = JSONObject(store.getString(AppSettings.KEY_STANDBY_RECORD)!!)
        assertEquals(true, record.getBoolean("phone_background_wake_enabled"))
        assertEquals(true, record.getBoolean("watch_background_wake_enabled"))
        assertEquals(true, record.getBoolean(PHONE_SCREEN_OFF_KEY))
        assertEquals(true, record.getBoolean(WATCH_SCREEN_OFF_KEY))
        assertEquals(AppSettings(store).watchSettingsRevision, record.getLong("revision"))
    }

    @Test
    fun `a standby record newer than the revision key still wins with its preferences after a crash between the two writes`() {
        val store = InMemoryKeyValueStore()
        store.putString(AppSettings.KEY_WATCH_REVISION, "100")
        store.putString(
            AppSettings.KEY_STANDBY_RECORD,
            """{"phone_background_wake_enabled":true,"watch_background_wake_enabled":false,"$PHONE_SCREEN_OFF_KEY":false,"$WATCH_SCREEN_OFF_KEY":true,"revision":250}""",
        )
        val settings = AppSettings(store)
        assertEquals(250L, settings.watchSettingsRevision)
        assertTrue(settings.watchSettings().screenOffPreference(watch))
        assertFalse(settings.watchSettings().screenOffPreference(phone))
        val next = settings.saveWatchSettings(settings.watchSettings().withScreenOff(phone = true), 10L)
        assertTrue(next.revision > 250L)
        assertTrue(next.screenOffPreference(watch))
        assertTrue(next.screenOffPreference(phone))
    }

    @Test
    fun `an unreadable stored record reads every standby value and both preferences as off`() {
        val store = InMemoryKeyValueStore()
        store.putString(AppSettings.KEY_STANDBY_RECORD, "{not json")
        val read = AppSettings(store).watchSettings()
        assertFalse(read.phoneBackgroundWakeEnabled)
        assertFalse(read.screenOffPreference(phone))
        assertFalse(read.screenOffPreference(watch))
    }

    @Test
    fun `a malformed preference is rejected instead of silently turning recognition on`() {
        assertNull(WatchSettings.parse("""{"wake_location":"OFF","revision":1,"$WATCH_SCREEN_OFF_KEY":"yes"}"""))
        assertNull(WatchSettings.parse("""{"wake_location":"OFF","revision":1,"$PHONE_SCREEN_OFF_KEY":1}"""))
        assertNull(WatchSettings.parse("""{"wake_location":"OFF","revision":1,"$PHONE_SCREEN_OFF_KEY":null,"$WATCH_SCREEN_OFF_KEY":[true]}"""))
    }

    @Test
    fun `a stale replayed or equal-revision conflicting snapshot never overwrites a newer preference`() {
        val replica = WatchSettingsReplica(null)
        val on = WatchSettings(revision = 10).withScreenOff(watch = true)
        val off = WatchSettings(revision = 11).withScreenOff(watch = false)
        assertEquals(ReplicaUpdate.APPLIED, replica.offer(on.toJson()))
        assertEquals(ReplicaUpdate.APPLIED, replica.offer(off.toJson()))
        assertEquals("a replay of the older snapshot", ReplicaUpdate.STALE, replica.offer(on.toJson()))
        assertEquals("an equal revision with a conflicting preference", ReplicaUpdate.STALE, replica.offer(off.withScreenOff(watch = true).toJson()))
        assertFalse(replica.current.screenOffPreference(watch))
        assertEquals(11L, replica.current.revision)
    }

    @Test
    fun `the initial placeholder snapshot and an invalid one never overwrite stored preferences`() {
        val stored = WatchSettings(revision = 20).withScreenOff(phone = true, watch = true)
        val replica = WatchSettingsReplica(stored.toJson())
        assertEquals("the UI's revision-0 placeholder is stale", ReplicaUpdate.STALE, replica.offer(WatchSettings().toJson()))
        assertEquals(ReplicaUpdate.INVALID, replica.offer("""{"revision":99,"$WATCH_SCREEN_OFF_KEY":"on"}"""))
        assertEquals(ReplicaUpdate.INVALID, replica.offer("not json"))
        assertTrue(replica.current.screenOffPreference(phone))
        assertTrue(replica.current.screenOffPreference(watch))
        assertEquals(20L, replica.current.revision)
    }

    @Test
    fun `a newer snapshot turning a preference off reaches the mirror, a never-synced mirror takes what it is given`() {
        val replica = WatchSettingsReplica(null)
        assertEquals(ReplicaUpdate.APPLIED, replica.offer(WatchSettings(revision = 5).withScreenOff(watch = true).toJson()))
        assertTrue(replica.current.screenOffPreference(watch))
        assertEquals(ReplicaUpdate.APPLIED, replica.offer(WatchSettings(revision = 6).withScreenOff(watch = false).toJson()))
        assertFalse(replica.current.screenOffPreference(watch))
    }
}
