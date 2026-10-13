package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.ReplicaUpdate
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.settings.WatchSettingsReplica
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal const val R7_PHONE_CHARGING = "phone_wake_while_charging"
internal const val R7_WATCH_CHARGING = "watch_wake_while_charging"

/** Wire-input adapter, not an implementation of charging eligibility. The predecessor really decodes these bytes. */
internal fun WatchSettings.r7Charging(phone: Boolean? = null, watch: Boolean? = null): WatchSettings {
    val json = JSONObject(toJson())
    phone?.let { json.put(R7_PHONE_CHARGING, it) }
    watch?.let { json.put(R7_WATCH_CHARGING, it) }
    return requireNotNull(WatchSettings.parse(json.toString()))
}
internal fun WatchSettings.r7Preference(device: VoiceOrigin): Boolean = JSONObject(toJson()).optBoolean(
    if (device == VoiceOrigin.PHONE) R7_PHONE_CHARGING else R7_WATCH_CHARGING, false,
)

class ChargingWakeR7SettingsTest {
    @Test fun defaultsAreExplicitlyOffAndDoNotEnableOtherPreferences() {
        val value = AppSettings(InMemoryKeyValueStore()).watchSettings()
        val json = JSONObject(value.toJson())
        assertTrue("canonical Phone field must be carried", json.has(R7_PHONE_CHARGING))
        assertTrue("canonical Watch field must be carried", json.has(R7_WATCH_CHARGING))
        assertFalse(json.getBoolean(R7_PHONE_CHARGING)); assertFalse(json.getBoolean(R7_WATCH_CHARGING))
        assertEquals(WakeLocation.OFF, value.wakeLocation)
        assertFalse(value.phoneBackgroundWakeEnabled); assertFalse(value.watchBackgroundWakeEnabled)
        assertFalse(value.phoneBackgroundWakeScreenOffEnabled); assertFalse(value.watchBackgroundWakeScreenOffEnabled)
    }

    @Test fun legacyDecodeAndStoredMigrationKeepChargingOffAndRetainExistingSettings() {
        val old = WatchSettings.parse("""{"wake_location":"BOTH","revision":7,"phone_background_wake_enabled":true,"watch_background_wake_enabled":true,"watch_background_wake_screen_off_enabled":true}""")!!
        for (d in VoiceOrigin.values()) assertFalse(old.r7Preference(d))
        assertTrue(old.watchBackgroundWakeScreenOffEnabled)
        val store = InMemoryKeyValueStore()
        store.putString(AppSettings.KEY_STANDBY_RECORD, old.toJson())
        val read = AppSettings(store).watchSettings()
        assertEquals(7L, read.revision)
        assertTrue(read.phoneBackgroundWakeEnabled); assertTrue(read.watchBackgroundWakeEnabled)
        for (d in VoiceOrigin.values()) assertFalse(read.r7Preference(d))
    }

    @Test fun independentOptionsRoundTripThroughPhoneStoreAndWatchColdMirror() {
        val store = InMemoryKeyValueStore()
        var now = 100L
        for (phone in listOf(false, true)) for (watch in listOf(false, true)) {
            val owner = AppSettings(store)
            val saved = owner.saveWatchSettings(owner.watchSettings().r7Charging(phone, watch), now++)
            val restarted = AppSettings(store).watchSettings()
            assertEquals(phone, restarted.r7Preference(VoiceOrigin.PHONE))
            assertEquals(watch, restarted.r7Preference(VoiceOrigin.WATCH))
            val mirror = WatchSettingsReplica(saved.toJson())
            assertEquals(restarted, mirror.current)
            assertEquals(phone, mirror.current.r7Preference(VoiceOrigin.PHONE))
            assertEquals(watch, mirror.current.r7Preference(VoiceOrigin.WATCH))
        }
    }

    @Test fun changingOneOptionKeepsTheOtherAndDoesNotTurnStandbyOrScreenOffOn() {
        val store = InMemoryKeyValueStore(); val owner = AppSettings(store)
        owner.saveWatchSettings(owner.watchSettings().r7Charging(phone = true), 100)
        owner.saveWatchSettings(owner.watchSettings().r7Charging(watch = true), 101)
        owner.saveWatchSettings(owner.watchSettings().r7Charging(phone = false), 102)
        val final = AppSettings(store).watchSettings()
        assertFalse(final.r7Preference(VoiceOrigin.PHONE)); assertTrue(final.r7Preference(VoiceOrigin.WATCH))
        assertFalse(final.phoneBackgroundWakeEnabled); assertFalse(final.watchBackgroundWakeEnabled)
        assertFalse(final.phoneBackgroundWakeScreenOffEnabled); assertFalse(final.watchBackgroundWakeScreenOffEnabled)
    }

    @Test fun oneAtomicRecordContainsBothOptionsMastersAndRevision() {
        val store = InMemoryKeyValueStore(); val writes = mutableListOf<String>()
        val spy = object : KeyValueStore by store {
            override fun putString(key: String, value: String) { writes += key; store.putString(key, value) }
            override fun putBoolean(key: String, value: Boolean) { writes += key; store.putBoolean(key, value) }
        }
        val saved = AppSettings(spy).saveWatchSettings(WatchSettings(phoneBackgroundWakeEnabled = true).r7Charging(true, true), 500)
        assertEquals(1, writes.count { it == AppSettings.KEY_STANDBY_RECORD })
        assertTrue(writes.none { it.contains("while_charging") })
        val record = JSONObject(store.getString(AppSettings.KEY_STANDBY_RECORD)!!)
        assertTrue(record.getBoolean(R7_PHONE_CHARGING)); assertTrue(record.getBoolean(R7_WATCH_CHARGING))
        assertTrue(record.getBoolean("phone_background_wake_enabled"))
        assertEquals(saved.revision, record.getLong("revision"))
    }

    @Test fun newerAtomicRecordWinsAfterInterruptedRevisionWrite() {
        val store = InMemoryKeyValueStore()
        store.putString(AppSettings.KEY_WATCH_REVISION, "10")
        store.putString(AppSettings.KEY_STANDBY_RECORD, WatchSettings(revision = 20).r7Charging(true, true).toJson())
        val owner = AppSettings(store)
        assertEquals(20L, owner.watchSettingsRevision)
        assertTrue(owner.watchSettings().r7Preference(VoiceOrigin.PHONE))
        val saved = owner.saveWatchSettings(owner.watchSettings().r7Charging(watch = false), 5)
        assertTrue(saved.revision > 20)
        assertTrue(AppSettings(store).watchSettings().r7Preference(VoiceOrigin.PHONE))
        assertFalse(AppSettings(store).watchSettings().r7Preference(VoiceOrigin.WATCH))
    }

    @Test fun malformedKnownFieldsAreRejectedRatherThanCoerced() {
        for (key in listOf(R7_PHONE_CHARGING, R7_WATCH_CHARGING)) {
            for (bad in listOf<Any>("true", 1, JSONObject.NULL, JSONObject(), org.json.JSONArray())) {
                val raw = JSONObject().put("revision", 2).put(key, bad).toString()
                assertNull("$key must reject $bad", WatchSettings.parse(raw))
            }
        }
    }

    @Test fun staleReplayEqualConflictAndPlaceholderCannotRestoreAnOption() {
        val on = WatchSettings(revision = 10).r7Charging(true, true)
        val off = WatchSettings(revision = 11).r7Charging(false, false)
        val replica = WatchSettingsReplica(null)
        assertEquals(ReplicaUpdate.APPLIED, replica.offer(on.toJson()))
        assertTrue(replica.current.r7Preference(VoiceOrigin.WATCH))
        assertEquals(ReplicaUpdate.APPLIED, replica.offer(off.toJson()))
        assertEquals(ReplicaUpdate.STALE, replica.offer(on.toJson()))
        assertEquals(ReplicaUpdate.STALE, replica.offer(off.r7Charging(watch = true).toJson()))
        assertEquals(ReplicaUpdate.STALE, replica.offer(WatchSettings().toJson()))
        assertFalse(replica.current.r7Preference(VoiceOrigin.WATCH)); assertEquals(11L, replica.current.revision)
    }
}
