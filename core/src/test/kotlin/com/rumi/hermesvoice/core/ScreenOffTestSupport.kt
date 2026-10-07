package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.WatchSettings
import org.json.JSONObject

internal const val PHONE_SCREEN_OFF_KEY = "phone_background_wake_screen_off_enabled"
internal const val WATCH_SCREEN_OFF_KEY = "watch_background_wake_screen_off_enabled"

private fun keyOf(device: VoiceOrigin) = if (device == VoiceOrigin.PHONE) PHONE_SCREEN_OFF_KEY else WATCH_SCREEN_OFF_KEY

/**
 * The settings with the screen-off recognition preferences set, built through the wire format so the same test bytes
 * compile against a build that does not know the fields yet (there the preference is simply not carried).
 */
internal fun WatchSettings.withScreenOff(phone: Boolean? = null, watch: Boolean? = null): WatchSettings {
    val json = JSONObject(toJson())
    phone?.let { json.put(PHONE_SCREEN_OFF_KEY, it) }
    watch?.let { json.put(WATCH_SCREEN_OFF_KEY, it) }
    return WatchSettings.parse(json.toString())!!
}

/** Whether [device]'s screen-off recognition preference is carried as ON by these settings (false when absent). */
internal fun WatchSettings.screenOffPreference(device: VoiceOrigin): Boolean = JSONObject(toJson()).optBoolean(keyOf(device), false)
