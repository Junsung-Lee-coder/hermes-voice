package com.rumi.hermesvoice.core.settings

import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.ResponsePlaybackSettings
import org.json.JSONObject

/**
 * Settings the Phone pushes to the Watch. The Watch never talks to Hermes, so nothing here is a
 * credential. Push-to-talk is always available; the wake phrase is opt-in. The Phone owns these
 * values: the Watch keeps a replica and applies a snapshot only when its [revision] is newer.
 */
data class WatchSettings(
    val wakePhraseEnabled: Boolean = false,
    val wakePatterns: String = WakePhrasePatterns.DEFAULT_PATTERNS,
    /** Push-to-talk recording limit. Wake-phrase requests have no duration cap (they end on silence). */
    val maxTurnSeconds: Int = DEFAULT_MAX_TURN_SECONDS,
    val hapticsEnabled: Boolean = true,
    /** Phone wall-clock millis of the save; 0 for a legacy payload or a never-synced Watch. */
    val revision: Long = 0L,
) {
    fun toJson(): String = JSONObject().put("wake_phrase_enabled", wakePhraseEnabled).put("wake_patterns", wakePatterns)
        .put("max_turn_seconds", maxTurnSeconds).put("haptics_enabled", hapticsEnabled).put("revision", revision).toString()

    companion object {
        const val DEFAULT_MAX_TURN_SECONDS = 60
        const val MIN_TURN_SECONDS = 5
        const val MAX_TURN_SECONDS = 300

        /** Tolerant: unknown or out-of-range values fall back to safe defaults (wake phrase stays OFF). */
        fun fromJson(raw: String?): WatchSettings {
            val json = runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: return WatchSettings()
            return WatchSettings(
                wakePhraseEnabled = json.optBoolean("wake_phrase_enabled", false),
                wakePatterns = WakePhrasePatterns.normalize(json.optString("wake_patterns")),
                maxTurnSeconds = json.optInt("max_turn_seconds", DEFAULT_MAX_TURN_SECONDS).coerceIn(MIN_TURN_SECONDS, MAX_TURN_SECONDS),
                hapticsEnabled = json.optBoolean("haptics_enabled", true),
                revision = json.optLong("revision", 0L).coerceAtLeast(0L),
            )
        }

        /**
         * Whether the Watch should replace [current] with [incoming]: only a strictly newer Phone
         * snapshot wins (stale and equal-revision conflicts are ignored). A Watch that never synced,
         * or a legacy Phone that sends no revision, takes what it is given.
         */
        fun shouldApply(current: WatchSettings, incoming: WatchSettings): Boolean =
            current.revision == 0L || incoming.revision > current.revision
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

    var watchWakePhraseEnabled: Boolean
        get() = store.getBoolean(KEY_WATCH_WAKE, false)
        set(value) = store.putBoolean(KEY_WATCH_WAKE, value)

    var watchWakePatterns: String
        get() = WakePhrasePatterns.normalize(store.getString(KEY_WATCH_WAKE_PATTERNS))
        set(value) = store.putString(KEY_WATCH_WAKE_PATTERNS, WakePhrasePatterns.normalize(value))

    var watchMaxTurnSeconds: Int
        get() = store.getInt(KEY_WATCH_MAX_TURN, WatchSettings.DEFAULT_MAX_TURN_SECONDS)
            .coerceIn(WatchSettings.MIN_TURN_SECONDS, WatchSettings.MAX_TURN_SECONDS)
        set(value) = store.putInt(KEY_WATCH_MAX_TURN, value.coerceIn(WatchSettings.MIN_TURN_SECONDS, WatchSettings.MAX_TURN_SECONDS))

    var watchHapticsEnabled: Boolean
        get() = store.getBoolean(KEY_WATCH_HAPTICS, true)
        set(value) = store.putBoolean(KEY_WATCH_HAPTICS, value)

    /** Revision of the last saved Watch settings snapshot (see [WatchSettings.revision]). */
    val watchSettingsRevision: Long
        get() = store.getString(KEY_WATCH_REVISION)?.toLongOrNull() ?: 0L

    /** Saves all Watch settings as one snapshot with a strictly increasing revision, and returns it. */
    @Synchronized
    fun saveWatchSettings(settings: WatchSettings, nowMs: Long = System.currentTimeMillis()): WatchSettings {
        watchWakePhraseEnabled = settings.wakePhraseEnabled
        watchWakePatterns = settings.wakePatterns
        watchMaxTurnSeconds = settings.maxTurnSeconds
        watchHapticsEnabled = settings.hapticsEnabled
        store.putString(KEY_WATCH_REVISION, maxOf(nowMs, watchSettingsRevision + 1).toString())
        return watchSettings()
    }

    /** Phone appearance; DARK unless the user picks another mode. Unknown stored values read as DARK. */
    var themeMode: ThemeMode
        get() = ThemeMode.parse(store.getString(KEY_THEME_MODE))
        set(value) = store.putString(KEY_THEME_MODE, value.name)

    fun playback(): ResponsePlaybackSettings = ResponsePlaybackSettings(playFirstResponse, playMiddleResponses)

    fun watchSettings(): WatchSettings =
        WatchSettings(watchWakePhraseEnabled, watchWakePatterns, watchMaxTurnSeconds, watchHapticsEnabled, watchSettingsRevision)

    companion object {
        const val PREFERENCES_NAME = "hermes_voice_settings"
        const val KEY_DASHBOARD_URL = "dashboard_url"
        const val KEY_PROFILE = "profile"
        const val KEY_PLAY_FIRST = "play_first_response"
        const val KEY_PLAY_MIDDLE = "play_middle_responses"
        const val KEY_WATCH_WAKE = "watch_wake_phrase_enabled"
        const val KEY_WATCH_WAKE_PATTERNS = "watch_wake_patterns"
        const val KEY_WATCH_MAX_TURN = "watch_max_turn_seconds"
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
