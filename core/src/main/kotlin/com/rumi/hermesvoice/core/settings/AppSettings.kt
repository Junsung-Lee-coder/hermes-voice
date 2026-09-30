package com.rumi.hermesvoice.core.settings

import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.ResponsePlaybackSettings
import com.rumi.hermesvoice.core.VoiceOrigin
import kotlin.math.abs
import kotlin.math.roundToLong
import org.json.JSONObject

/**
 * Where the foreground wake phrase listens. Phone-owned; OFF by default. Each device listens only
 * while its own app is visibly in the foreground; a device the mode excludes never listens or
 * responds to a wake phrase (push-to-talk is unaffected).
 */
enum class WakeLocation(val label: String) {
    OFF("Off"), WATCH("Watch"), PHONE("Phone"), BOTH("Both");

    fun listensOn(device: VoiceOrigin): Boolean = when (device) {
        VoiceOrigin.WATCH -> this == WATCH || this == BOTH
        VoiceOrigin.PHONE -> this == PHONE || this == BOTH
    }

    companion object {
        fun parse(raw: String?): WakeLocation? = values().firstOrNull { it.name == raw }

        /** Settings saved before the selector existed had only a Watch opt-in switch. */
        fun fromLegacyWatchOptIn(enabled: Boolean): WakeLocation = if (enabled) WATCH else OFF
    }
}

/**
 * The shared VAD's trailing-silence setting: how long a hands-free request may pause before it is
 * sent, on both devices. Phone-owned; [DEFAULT_SECONDS] unless the user picks another of [choices].
 */
object VadSilence {
    const val DEFAULT_SECONDS = 2.0
    const val MIN_SECONDS = 0.5
    const val MAX_SECONDS = 10.0
    const val STEP_SECONDS = 0.5

    val choices: List<Double> = (1..20).map { it * STEP_SECONDS }

    /** [seconds] if it is a finite value in range on the 0.5 s grid, else null. */
    fun validOrNull(seconds: Double?): Double? {
        if (seconds == null || !seconds.isFinite() || seconds < MIN_SECONDS || seconds > MAX_SECONDS) return null
        val steps = seconds / STEP_SECONDS
        return if (abs(steps - Math.rint(steps)) < 1e-9) Math.rint(steps) * STEP_SECONDS else null
    }

    fun millis(seconds: Double): Long = (seconds * 1000).roundToLong()

    fun label(seconds: Double): String = "%.1f s".format(java.util.Locale.ROOT, seconds)
}

/**
 * Phone-owned voice settings, replicated to the Watch over the Data Layer. The Watch never talks
 * to Hermes, so nothing here is a credential. Push-to-talk is always available; the wake phrase is
 * opt-in per device ([wakeLocation]). The Watch keeps a replica and applies a snapshot only when
 * it is valid and its [revision] is newer ([WatchSettingsReplica]).
 */
data class WatchSettings(
    val wakeLocation: WakeLocation = WakeLocation.OFF,
    val wakePatterns: String = WakePhrasePatterns.DEFAULT_PATTERNS,
    val hapticsEnabled: Boolean = true,
    /** Trailing silence that ends a hands-free request on either device (see [VadSilence]). */
    val vadSilenceSeconds: Double = VadSilence.DEFAULT_SECONDS,
    /** Phone wall-clock millis of the save; 0 for a legacy payload or a never-synced Watch. */
    val revision: Long = 0L,
) {
    init {
        require(VadSilence.validOrNull(vadSilenceSeconds) == vadSilenceSeconds) { "invalid trailing silence" }
    }

    /** Whether the Watch listens for the wake phrase. */
    val watchWakeEnabled: Boolean get() = wakeLocation.listensOn(VoiceOrigin.WATCH)

    /**
     * `wake_phrase_enabled` mirrors the Watch part of [wakeLocation] for a Watch app that predates
     * the selector: it then listens exactly when the new mode lets the Watch listen.
     */
    fun toJson(): String = JSONObject()
        .put("wake_location", wakeLocation.name)
        .put("wake_phrase_enabled", watchWakeEnabled)
        .put("wake_patterns", wakePatterns)
        .put("haptics_enabled", hapticsEnabled)
        .put("vad_silence_seconds", vadSilenceSeconds)
        .put("revision", revision)
        .toString()

    companion object {
        /**
         * Strict: null for anything that is not a valid snapshot (not a JSON object, a known field of
         * the wrong type, an unknown wake location, a trailing silence that is NaN, infinite, off
         * the 0.5 s grid or outside 0.5–10 s, a negative revision). Missing fields are migrated: no
         * `wake_location` → the legacy Watch opt-in (true → WATCH, else OFF); no silence → 2.0 s.
         * A legacy `max_turn_seconds` is ignored: recordings have no total duration limit.
         */
        fun parse(raw: String?): WatchSettings? {
            val json = runCatching { JSONObject(raw ?: return null) }.getOrNull() ?: return null
            val location = when (val value = json.opt("wake_location")) {
                null -> when (val legacy = json.opt("wake_phrase_enabled")) {
                    null -> WakeLocation.OFF
                    is Boolean -> WakeLocation.fromLegacyWatchOptIn(legacy)
                    else -> return null
                }
                is String -> WakeLocation.parse(value) ?: return null
                else -> return null
            }
            val silence = when (val value = json.opt("vad_silence_seconds")) {
                null -> VadSilence.DEFAULT_SECONDS
                is Number -> VadSilence.validOrNull(value.toDouble()) ?: return null
                else -> return null
            }
            val patterns = when (val value = json.opt("wake_patterns")) {
                null, is String -> WakePhrasePatterns.normalize(value as String?)
                else -> return null
            }
            val haptics = when (val value = json.opt("haptics_enabled")) {
                null -> true
                is Boolean -> value
                else -> return null
            }
            val revision = when (val value = json.opt("revision")) {
                null -> 0L
                is Int, is Long -> (value as Number).toLong().takeIf { it >= 0 } ?: return null
                else -> return null
            }
            return WatchSettings(location, patterns, haptics, silence, revision)
        }

        /** The Watch's own stored copy; a missing or unreadable one is the safe default (wake OFF). */
        fun fromJson(raw: String?): WatchSettings = parse(raw) ?: WatchSettings()

        /**
         * Whether the Watch should replace [current] with [incoming]: only a strictly newer Phone
         * snapshot wins (stale and equal-revision conflicts are ignored). A Watch that never synced,
         * or a legacy Phone that sends no revision, takes what it is given.
         */
        fun shouldApply(current: WatchSettings, incoming: WatchSettings): Boolean =
            current.revision == 0L || incoming.revision > current.revision
    }
}

enum class ReplicaUpdate { APPLIED, STALE, INVALID }

/**
 * The Watch's replica of the Phone-owned settings: starts from its stored copy and accepts a Data
 * Layer snapshot only if [WatchSettings.parse] validates it and it is newer, so an invalid,
 * stale or replayed snapshot can never re-enable a listener the Phone disabled.
 */
class WatchSettingsReplica(stored: String?) {
    @Volatile var current: WatchSettings = WatchSettings.fromJson(stored)
        private set

    @Synchronized
    fun offer(raw: String?): ReplicaUpdate {
        val incoming = WatchSettings.parse(raw) ?: return ReplicaUpdate.INVALID
        if (!WatchSettings.shouldApply(current, incoming)) return ReplicaUpdate.STALE
        current = incoming
        return ReplicaUpdate.APPLIED
    }
}

/**
 * Phone app settings. The two recipient-response switches are independent persistent booleans
 * that default OFF. There is deliberately no key for the FINAL response or the routing
 * acknowledgement: both always play and cannot be switched off.
 */
class AppSettings(private val store: KeyValueStore) {
    var dashboardUrl: String
        get() = store.getString(KEY_DASHBOARD_URL).orEmpty()
        set(value) = store.putString(KEY_DASHBOARD_URL, value.trim())

    var profile: String
        get() = store.getString(KEY_PROFILE).orEmpty()
        set(value) = store.putString(KEY_PROFILE, value.trim())

    var playFirstResponse: Boolean
        get() = store.getBoolean(KEY_PLAY_FIRST, false)
        set(value) = store.putBoolean(KEY_PLAY_FIRST, value)

    var playMiddleResponses: Boolean
        get() = store.getBoolean(KEY_PLAY_MIDDLE, false)
        set(value) = store.putBoolean(KEY_PLAY_MIDDLE, value)

    /**
     * Where the wake phrase listens. Before the selector existed only a Watch opt-in was stored:
     * missing → migrated from it (true → WATCH, else OFF); an unreadable value → OFF.
     */
    var wakeLocation: WakeLocation
        get() {
            val raw = store.getString(KEY_WAKE_LOCATION)
                ?: return WakeLocation.fromLegacyWatchOptIn(store.getBoolean(KEY_WATCH_WAKE, false))
            return WakeLocation.parse(raw) ?: WakeLocation.OFF
        }
        set(value) {
            store.putString(KEY_WAKE_LOCATION, value.name)
            // Kept in step so an older build reading only the legacy switch never listens more.
            store.putBoolean(KEY_WATCH_WAKE, value.listensOn(VoiceOrigin.WATCH))
        }

    /** Trailing silence for hands-free requests; a missing or invalid stored value reads as 2.0 s. */
    var vadSilenceSeconds: Double
        get() = VadSilence.validOrNull(store.getString(KEY_VAD_SILENCE)?.toDoubleOrNull()) ?: VadSilence.DEFAULT_SECONDS
        set(value) = store.putString(KEY_VAD_SILENCE,
            requireNotNull(VadSilence.validOrNull(value)) { "trailing silence must be 0.5–10 s in 0.5 s steps" }.toString())

    /**
     * Durable migration, idempotent: writes the migrated wake location and trailing silence when
     * missing or unreadable, so later reads never depend on the legacy key. Returns whether it wrote.
     */
    @Synchronized
    fun migrate(): Boolean {
        var changed = false
        if (WakeLocation.parse(store.getString(KEY_WAKE_LOCATION)) == null) {
            wakeLocation = wakeLocation
            changed = true
        }
        if (VadSilence.validOrNull(store.getString(KEY_VAD_SILENCE)?.toDoubleOrNull()) == null) {
            vadSilenceSeconds = VadSilence.DEFAULT_SECONDS
            changed = true
        }
        return changed
    }

    var watchWakePatterns: String
        get() = WakePhrasePatterns.normalize(store.getString(KEY_WATCH_WAKE_PATTERNS))
        set(value) = store.putString(KEY_WATCH_WAKE_PATTERNS, WakePhrasePatterns.normalize(value))

    var watchHapticsEnabled: Boolean
        get() = store.getBoolean(KEY_WATCH_HAPTICS, true)
        set(value) = store.putBoolean(KEY_WATCH_HAPTICS, value)

    /** Revision of the last saved Watch settings snapshot (see [WatchSettings.revision]). */
    val watchSettingsRevision: Long
        get() = store.getString(KEY_WATCH_REVISION)?.toLongOrNull() ?: 0L

    /** Saves all shared voice settings as one snapshot with a strictly increasing revision, and returns it. */
    @Synchronized
    fun saveWatchSettings(settings: WatchSettings, nowMs: Long = System.currentTimeMillis()): WatchSettings {
        wakeLocation = settings.wakeLocation
        watchWakePatterns = settings.wakePatterns
        watchHapticsEnabled = settings.hapticsEnabled
        vadSilenceSeconds = settings.vadSilenceSeconds
        store.putString(KEY_WATCH_REVISION, maxOf(nowMs, watchSettingsRevision + 1).toString())
        return watchSettings()
    }

    /** Phone appearance; DARK unless the user picks another mode. Unknown stored values read as DARK. */
    var themeMode: ThemeMode
        get() = ThemeMode.parse(store.getString(KEY_THEME_MODE))
        set(value) = store.putString(KEY_THEME_MODE, value.name)

    fun playback(): ResponsePlaybackSettings = ResponsePlaybackSettings(playFirstResponse, playMiddleResponses)

    fun watchSettings(): WatchSettings =
        WatchSettings(wakeLocation, watchWakePatterns, watchHapticsEnabled, vadSilenceSeconds, watchSettingsRevision)

    companion object {
        const val PREFERENCES_NAME = "hermes_voice_settings"
        const val KEY_DASHBOARD_URL = "dashboard_url"
        const val KEY_PROFILE = "profile"
        const val KEY_PLAY_FIRST = "play_first_response"
        const val KEY_PLAY_MIDDLE = "play_middle_responses"
        /** Legacy Watch opt-in; read only to migrate, then kept in step with [KEY_WAKE_LOCATION]. */
        const val KEY_WATCH_WAKE = "watch_wake_phrase_enabled"
        const val KEY_WAKE_LOCATION = "wake_location"
        const val KEY_VAD_SILENCE = "vad_silence_seconds"
        const val KEY_WATCH_WAKE_PATTERNS = "watch_wake_patterns"
        const val KEY_WATCH_HAPTICS = "watch_haptics_enabled"
        const val KEY_WATCH_REVISION = "watch_settings_revision"
        const val KEY_THEME_MODE = "theme_mode"
    }
}

/** Phone appearance choice. The Watch is always dark. */
enum class ThemeMode {
    DARK, LIGHT, SYSTEM;

    /** Whether to draw dark, given whether the system is currently in night mode. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        DARK -> true
        LIGHT -> false
        SYSTEM -> systemDark
    }

    companion object {
        fun parse(raw: String?): ThemeMode = values().firstOrNull { it.name == raw } ?: DARK
    }
}

/** The bounded wake-phrase grammar (reused from the old Recorder Watch): whitespace-separated tokens, `*` wildcards. */
object WakePhrasePatterns {
    const val DEFAULT_PATTERNS: String = "루미 루미야 룸이 룸이야 루미봇 룸이봇 룸미 룸미야 룸미봇"
    const val MAX_PATTERN_TEXT_LENGTH: Int = 240
    const val MAX_PATTERN_COUNT: Int = 24
    const val MAX_PATTERN_LENGTH: Int = 40

    fun normalize(raw: String?): String {
        val canonical = raw.orEmpty().take(MAX_PATTERN_TEXT_LENGTH).trim().split(Regex("\\s+")).asSequence()
            .map { it.take(MAX_PATTERN_LENGTH) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_PATTERN_COUNT)
            .joinToString(" ")
        return canonical.ifBlank { DEFAULT_PATTERNS }
    }

    fun matches(configuredPatterns: String?, recognizedText: String): Boolean = requestAfterWake(configuredPatterns, recognizedText) != null

    /**
     * The words spoken after the first wake token in [recognizedText]: null when no whole token
     * matches a pattern, "" when nothing follows it. Leading punctuation and spaces are dropped.
     */
    fun requestAfterWake(configuredPatterns: String?, recognizedText: String): String? {
        val patterns = normalize(configuredPatterns).split(' ').map { it.lowercase() }
        val tokens = (RAW_TOKEN.findAll(recognizedText) + LEXICAL_TOKEN.findAll(recognizedText)).sortedBy { it.range.first }
        val wake = tokens.firstOrNull { token -> patterns.any { globMatches(it, token.value.lowercase()) } } ?: return null
        return recognizedText.substring(wake.range.last + 1).trimStart { !it.isLetterOrDigit() }.trimEnd()
    }

    /**
     * The request after a wake phrase that LEADS [recognizedText]: the first word, or the second
     * after one short greeting from [LEADING_GREETINGS] ("hey Hermes, …", "안녕, 루미! …"). Null for
     * no leading wake phrase, e.g. an ambient mention in the middle of a sentence; "" when nothing
     * follows it.
     */
    fun leadingRequest(configuredPatterns: String?, recognizedText: String): String? {
        val patterns = normalize(configuredPatterns).split(' ').map { it.lowercase() }
        for (stream in listOf(RAW_TOKEN, LEXICAL_TOKEN)) {
            val tokens = stream.findAll(recognizedText).take(2).toList()
            val first = tokens.getOrNull(0) ?: continue
            val greeting = LEXICAL_TOKEN.find(first.value)?.value?.lowercase() in LEADING_GREETINGS
            val wake = listOfNotNull(first, tokens.getOrNull(1)?.takeIf { greeting })
                .firstOrNull { token -> patterns.any { globMatches(it, token.value.lowercase()) } } ?: continue
            return recognizedText.substring(wake.range.last + 1).trimStart { !it.isLetterOrDigit() }.trimEnd()
        }
        return null
    }

    /** The only words allowed before a leading wake phrase. */
    val LEADING_GREETINGS: Set<String> = setOf("hey", "hi", "hello", "ok", "okay", "안녕", "저기")

    private val RAW_TOKEN = Regex("[^\\s]+")
    private val LEXICAL_TOKEN = Regex("[\\p{L}\\p{N}]+")

    /** Linear wildcard matcher; only '*' is syntax and it never crosses a whitespace token. */
    private fun globMatches(pattern: String, token: String): Boolean {
        var patternIndex = 0
        var tokenIndex = 0
        var starIndex = -1
        var retryTokenIndex = -1
        while (tokenIndex < token.length) {
            when {
                patternIndex < pattern.length && pattern[patternIndex] == token[tokenIndex] -> {
                    patternIndex += 1
                    tokenIndex += 1
                }
                patternIndex < pattern.length && pattern[patternIndex] == '*' -> {
                    starIndex = patternIndex
                    patternIndex += 1
                    retryTokenIndex = tokenIndex
                }
                starIndex >= 0 -> {
                    patternIndex = starIndex + 1
                    retryTokenIndex += 1
                    tokenIndex = retryTokenIndex
                }
                else -> return false
            }
        }
        while (patternIndex < pattern.length && pattern[patternIndex] == '*') patternIndex += 1
        return patternIndex == pattern.length
    }
}
