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

    /** The Phone's opt-in background listening for the wake phrase (off unless the user switched it on here). */
    const val KEY_PHONE_WAKE = "phone_background_wake"
    const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
    private const val KEY_LEGACY_DROPPED = "legacy_flags_dropped_v1"

    /** The Phone's informed opt-in to speak later replies ([LaterReplyConsent]). */
    const val KEY_LATER_REPLIES = "later_replies_consent"

    /** Where an earlier, undelivered build kept that opt-in: the backed-up settings file. Never read as consent. */
    const val KEY_LEGACY_LATER_REPLIES = "speak_later_replies"

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

/**
 * The informed opt-in to speak later replies (Settings). It is this install's own and OFF unless the
 * user switched it on here, after the warning beside the switch: it lives in the device-local file
 * ([DeviceLocalFlags.PHONE_PREFERENCES]) that backup and device transfer exclude, so a restored or
 * transferred install starts with it off and shows the warning again.
 */
class LaterReplyConsent(private val local: KeyValueStore) {
    var enabled: Boolean
        get() = local.getBoolean(DeviceLocalFlags.KEY_LATER_REPLIES, false)
        set(value) = local.putBoolean(DeviceLocalFlags.KEY_LATER_REPLIES, value)

    companion object {
        /**
         * An earlier build (never delivered) kept this opt-in in the backed-up settings file, so a
         * restore could carry it ON: that copy is switched off and never becomes consent. Returns
         * whether one was found on.
         */
        fun dropLegacy(shared: KeyValueStore): Boolean {
            val found = shared.getBoolean(DeviceLocalFlags.KEY_LEGACY_LATER_REPLIES, false)
            if (found) shared.putBoolean(DeviceLocalFlags.KEY_LEGACY_LATER_REPLIES, false)
            return found
        }
    }
}
