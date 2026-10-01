package com.rumi.hermesvoice.core.background

import com.rumi.hermesvoice.core.KeyValueStore

/**
 * The background opt-ins and the "notification permission asked" flag belong to one install on
 * one device. They live in their own preferences file ([PHONE_PREFERENCES], [WATCH_PREFERENCES]),
 * which the apps' backup and device-transfer rules exclude, so a restored or transferred install
 * starts with background operation off and asks again.
 */
object DeviceLocalFlags {
    const val PHONE_PREFERENCES = "hermes_voice_device_local"
    const val WATCH_PREFERENCES = "hermes_voice_watch_local"
    const val KEY_RELAY = "phone_background_relay"
    const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
    private const val KEY_LEGACY_DROPPED = "legacy_flags_dropped_v1"

    /**
     * An earlier build kept the Phone's relay opt-in and asked-once flag in the shared settings
     * file, which is backed up: those copies are switched off there, once, and never carried over
     * (the relay is off after this update or a restore until the user switches it on here). Other
     * settings are left as they are. Returns whether a legacy copy was found on.
     */
    fun dropLegacy(shared: KeyValueStore, local: KeyValueStore): Boolean {
        if (local.getBoolean(KEY_LEGACY_DROPPED, false)) return false
        val found = shared.getBoolean(KEY_RELAY, false) || shared.getBoolean(KEY_NOTIFICATIONS_ASKED, false)
        if (found) {
            shared.putBoolean(KEY_RELAY, false)
            shared.putBoolean(KEY_NOTIFICATIONS_ASKED, false)
        }
        local.putBoolean(KEY_LEGACY_DROPPED, true)
        return found
    }
}
