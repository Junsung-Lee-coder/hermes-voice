package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.KeyValueStore

/**
 * This phone's own display preferences. They live in the device-local preferences file (excluded from backup and transfer), are not a
 * Watch or server setting, and never touch the shared settings or the secure sign-in storage.
 */
class PhoneDisplaySettings(private val store: KeyValueStore) {
    /** Percent multiplier on the Android system font scale: 80..140 in steps of 10, 100 by default. */
    var fontScalePercent: Int
        get() = sanitize(runCatching { store.getString(KEY) }.getOrNull())
        set(value) = store.putString(KEY, sanitize(value.toString()).toString())

    companion object {
        const val KEY = "phone_font_scale_percent"
        const val DEFAULT = 100
        const val MIN = 80
        const val MAX = 140
        const val STEP = 10

        /** A stored or requested value as a usable percent: unreadable or non-finite is the default; others are bounded and put on a step. */
        fun sanitize(raw: String?): Int {
            val parsed = raw?.trim()?.toDoubleOrNull()
            if (parsed == null || parsed.isNaN() || parsed.isInfinite()) return DEFAULT
            val bounded = parsed.coerceIn(MIN.toDouble(), MAX.toDouble())
            return (Math.round(bounded / STEP) * STEP).toInt()
        }
    }
}
