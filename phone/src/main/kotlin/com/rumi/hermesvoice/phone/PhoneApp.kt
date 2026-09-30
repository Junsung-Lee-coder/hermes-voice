package com.rumi.hermesvoice.phone

import android.app.Application
import android.content.Context
import com.rumi.hermesvoice.core.HermesVoiceCore
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import com.rumi.hermesvoice.core.net.HermesGatewayConnector
import com.rumi.hermesvoice.core.sessions.OwnedSessionRegistry
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnStage
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/**
 * Process-wide graph. One [HermesVoiceCore] per (dashboard URL, profile), shared by the Phone UI,
 * the Phone push-to-talk button and the Watch listener service, so both origins go through the
 * same orchestrator (ordering, speaker ownership, turn-id dedup).
 */
class PhoneApp : Application() {
    /** Background work (Watch turns) must never crash the process; failures are logged. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
        Log.e("HermesVoice", "background task failed: ${error.javaClass.simpleName}")
    })

    val settings: AppSettings by lazy { AppSettings(SharedPreferencesKeyValueStore(prefs(AppSettings.PREFERENCES_NAME))) }
    val tokens: KeystoreTokenStore by lazy { KeystoreTokenStore(this) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    class Wiring(val key: String, val endpoint: HermesDashboardEndpoint, val core: HermesVoiceCore,
                 val dashboard: HermesDashboardClient, val connector: HermesGatewayConnector)

    @Volatile private var wiring: Wiring? = null

    /** Throws [IllegalArgumentException] when the dashboard URL is missing or not an allowed transport. */
    @Synchronized
    fun wiring(): Wiring {
        val endpoint = HermesDashboardEndpoint.parse(settings.dashboardUrl)
        val key = "$endpoint|${settings.profile}"
        wiring?.takeIf { it.key == key }?.let { return it }
        wiring?.connector?.close()
        // Owned session ids are only meaningful on the dashboard/profile that minted them.
        val registry = OwnedSessionRegistry(SharedPreferencesKeyValueStore(prefs(REGISTRY_PREFS_PREFIX + digest(key))))
        // A new core has a new (empty) playback route.
        _playbackDevice.value = null
        val (core, connector) = HermesVoiceCore.connect(endpoint, http, tokens, registry, settings, voiceTrace)
        val dashboard = core.speech as HermesDashboardClient
        return Wiring(key, endpoint, core, dashboard, connector).also { wiring = it }
    }

    private fun prefs(name: String) = getSharedPreferences(name, Context.MODE_PRIVATE)

    private val _playbackDevice = MutableStateFlow<VoiceOrigin?>(null)

    /** Where spoken acks and replies play now: the device of the latest accepted voice request (null before any). */
    val playbackDevice: StateFlow<VoiceOrigin?> = _playbackDevice

    /** Turn ids, stages and where each utterance was confirmed played; no transcript or audio. */
    private val voiceTrace = object : VoiceTurnListener {
        override fun onAccepted(turnId: String, origin: VoiceOrigin) {
            _playbackDevice.value = origin
            log("accepted turn=${turnId.take(12)} origin=$origin playback_target=$origin")
        }

        override fun onStage(turnId: String, stage: VoiceTurnStage) = log("stage turn=${turnId.take(12)} $stage")

        override fun onPlayed(cue: PlaybackCue) = log("played turn=${cue.turnId.take(12)} seq=${cue.sequence} " +
            "role=${cue.role} origin=${cue.origin} device=${cue.device} " +
            "confirmed_by=${if (cue.device == VoiceOrigin.WATCH) "watch_played_ack" else "phone_player_completion"}")

        private fun log(line: String) {
            Log.i(VOICE_TAG, line)
        }
    }

    companion object {
        private const val VOICE_TAG = "HermesVoiceTurn"
        const val REGISTRY_PREFS_PREFIX = "hermes_voice_sessions_"

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

        fun from(context: Context): PhoneApp = context.applicationContext as PhoneApp
    }
}
