package com.rumi.hermesvoice.phone

import android.app.Application
import android.content.Context
import com.rumi.hermesvoice.core.HermesVoiceCore
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import com.rumi.hermesvoice.core.net.HermesGatewayConnector
import com.rumi.hermesvoice.core.sessions.OwnedSessionRegistry
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundPort
import com.rumi.hermesvoice.core.background.BackgroundSession
import com.rumi.hermesvoice.core.background.BackgroundStatus
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.background.WakeHolds
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.voice.AssembledRoute
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackRoute
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnStage
import com.rumi.hermesvoice.core.wake.WakeEpisode
import com.rumi.hermesvoice.core.wake.WakeEpochItem
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Process-wide graph. One [HermesVoiceCore] per (dashboard URL, profile), shared by the Phone UI,
 * the Phone push-to-talk button and the Watch listener service, so both origins go through the
 * same orchestrator (ordering, speaker ownership, turn-id dedup). Voice turns of either origin run
 * in [appScope], not in an activity or view model, so closing or recreating the screen never ends
 * one. The optional background relay ([relay], [PhoneRelayService]) keeps this process running
 * with the app closed; it adds no second client, orchestrator or store.
 */
class PhoneApp : Application() {
    /** Background work (Watch turns) must never crash the process; failures are logged. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
        Log.e("HermesVoice", "background task failed: ${error.javaClass.simpleName}")
    })

    private val settingsStore by lazy { SharedPreferencesKeyValueStore(prefs(AppSettings.PREFERENCES_NAME)) }
    val settings: AppSettings by lazy { AppSettings(settingsStore) }

    /** Elapsed-realtime millis when Phone speaker playback last ended (wake-phrase cooldown). */
    @Volatile var lastPhonePlaybackEndedAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        // Durable, idempotent: the wake location from the old Watch opt-in, and the default trailing silence.
        val migrated = settings.migrate()
        Log.i("HermesVoice", "voice settings wake_location=${settings.wakeLocation} vad_silence_s=${settings.vadSilenceSeconds} " +
            "revision=${settings.watchSettingsRevision} migrated=$migrated")
        PhoneRelayService.createChannel(this)
        _relayStatus.value = relay.status
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Exported so `adb shell am broadcast` reaches it; never registered in a release build.
            ContextCompat.registerReceiver(this, qaReceiver, android.content.IntentFilter(QA_ACTION), ContextCompat.RECEIVER_EXPORTED)
        }
    }

    /**
     * Debug builds only: `adb shell am broadcast -a com.rumi.hermesvoice.QA_PHONE --es hv_qa_relay stop`
     * runs the same Stop as the relay notification's action, which a test cannot tap while the
     * notification is not allowed. Nothing else is accepted here.
     */
    private val qaReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.getStringExtra(QA_RELAY) != "stop") return
            Log.i(RELAY_TAG, "qa relay stop (debug builds only; the notification's Stop path)")
            android.os.Handler(android.os.Looper.getMainLooper()).post { stopRelay() }
        }
    }

    // ── background relay ─────────────────────────────────────────────────────────────────────

    /** CPU wake locks, one per reason and each with a timeout (see [WakeHolds]). */
    val holds: WakeHolds by lazy { WakeHolds(AndroidWakeLocks(this), SystemClock::elapsedRealtime) }

    private val _relayStatus = MutableStateFlow(BackgroundStatus(false, false, false, BackgroundNotice.OFF))

    /** The background relay as it really is now (not the saved switch alone). */
    val relayStatus: StateFlow<BackgroundStatus> = _relayStatus

    private val relayPort = object : BackgroundPort {
        override fun startService(microphone: Boolean): Boolean = runCatching {
            ContextCompat.startForegroundService(this@PhoneApp, PhoneRelayService.start(this@PhoneApp))
        }.onFailure { Log.w(RELAY_TAG, "background relay start refused: ${it.javaClass.simpleName}") }.isSuccess

        // The relay never uses this Phone's microphone: there is nothing to re-type.
        override fun retypeService(microphone: Boolean): Boolean = !microphone

        // A service whose start is still on its way ends itself when it arrives (it must enter the foreground first).
        override fun stopService() {
            PhoneRelayService.running?.finish()
        }
    }

    /**
     * Device-local opt-in, off unless the user switched it on here. It has no microphone, so after
     * Android ended it (or the phone restarted) it resumes when the app is next opened; it never
     * starts from the background.
     */
    val relay: BackgroundSession by lazy {
        BackgroundSession(settingsStore, KEY_BACKGROUND_RELAY, relayPort, resumeWhenVisible = true) { status ->
            Log.i(RELAY_TAG, "background relay wanted=${status.wanted} running=${status.running} notice=${status.notice}")
            _relayStatus.value = status
            PhoneRelayService.running?.refresh(status)
        }
    }

    /** An activity of this app is visible (started): the only time the relay may be started. */
    @Volatile var activityVisible = false
        private set

    fun onActivityStarted() {
        activityVisible = true
        _relayStatus.value = relay.onVisible(microphoneWanted = false, microphonePermission = false)
    }

    fun onActivityStopped() {
        activityVisible = false
    }

    /** The user's switch, from the visible app. */
    fun startRelay(): BackgroundStatus =
        relay.start(visible = activityVisible, microphoneWanted = false, microphonePermission = false).also { _relayStatus.value = it }

    /**
     * The user's Stop (switch or notification), safe to repeat: the relay is off for good. With the
     * app closed, turns in flight are cancelled (a Watch is told its turn was stopped; nothing more
     * is played) and the wake lock is let go. With the app on screen, foreground use goes on.
     */
    fun stopRelay() {
        val hidden = !activityVisible
        relay.stop()
        _relayStatus.value = relay.status
        if (!hidden) return
        turns.keys.toList().forEach { it.cancel() }
        holds.releaseAll()
    }

    /** True the first time only: whether the relay's notification may show is asked once, not at every switch-on. */
    fun askNotificationsOnce(): Boolean {
        if (settingsStore.getBoolean(KEY_NOTIFICATIONS_ASKED, false)) return false
        settingsStore.putBoolean(KEY_NOTIFICATIONS_ASKED, true)
        return true
    }

    fun onRelayServiceGone(generation: Long) {
        _relayStatus.value = relay.onServiceGone(generation)
    }

    // ── voice turns ──────────────────────────────────────────────────────────────────────────

    private val turns = ConcurrentHashMap<Job, String>()

    /** How many Phone-origin voice turns are being sent, answered or played. */
    val phoneTurns = MutableStateFlow(0)

    /**
     * Runs one voice turn (Phone or Watch origin) in the application scope, with a time-limited
     * CPU hold for as long as any turn runs. [phoneOrigin] turns are counted for the Phone's UI.
     */
    fun launchTurn(turnId: String, phoneOrigin: Boolean, block: suspend () -> Unit): Job {
        if (phoneOrigin) phoneTurns.value += 1
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                turns.remove(coroutineContext[Job])
                if (phoneOrigin) phoneTurns.value -= 1
                if (turns.isEmpty()) holds.release(HoldReason.TURN)
            }
        }
        turns[job] = turnId
        holds.acquire(HoldReason.TURN)
        job.start()
        return job
    }
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
        val (core, connector) = HermesVoiceCore.connect(endpoint, http, tokens, registry, settings, voiceTrace)
        val dashboard = core.speech as HermesDashboardClient
        // A new core has a new (empty) playback route.
        currentRoute.value = core.orchestrator.playbackRoute
        // Both: every admitted wake request is told to the Phone's own wake flow and to the Watch,
        // so a window that was already listening cannot answer the same phrase again.
        core.onWakeEpisode = { episode ->
            Log.i(VOICE_TAG, "wake episode answered epoch=${episode.epoch} by=${episode.origin}")
            wakeEpisodes.value = episode
            publishWakeEpoch(WakeEpochItem(episode.epoch, episode.claimId))
        }
        publishWakeEpoch(WakeEpochItem(core.wakeAdmission.epoch))
        return Wiring(key, endpoint, core, dashboard, connector).also { wiring = it }
    }

    private fun prefs(name: String) = getSharedPreferences(name, Context.MODE_PRIVATE)

    /** The latest wake request admitted in Both (see [com.rumi.hermesvoice.core.wake.WakeAdmission]); null before any. */
    val wakeEpisodes = MutableStateFlow<WakeEpisode?>(null)

    private fun publishWakeEpoch(item: WakeEpochItem) {
        appScope.launch { runCatching { WakeEpochSync.publish(this@PhoneApp, item) } }
    }

    private val currentRoute = MutableStateFlow<PlaybackRoute?>(null)

    /** Counts conversations created by voice turns (Phone or Watch origin), so the UI can reload its list. */
    val conversationsCreated = MutableStateFlow(0)

    /**
     * Where spoken acks and replies play now: the device of the latest accepted voice request (null
     * before any). Read from the route itself, which updates it under the same lock that picks the
     * playing device, so the label cannot disagree with where audio goes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val playbackDevice: StateFlow<VoiceOrigin?> = currentRoute
        .flatMapLatest { route -> route?.device ?: flowOf(null) }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    /** Turn ids, stages and where each utterance was confirmed played; no transcript or audio. */
    private val voiceTrace = object : VoiceTurnListener {
        override fun onInputRejected(turnId: String, origin: VoiceOrigin, verdict: AudioInputVerdict) =
            log("input rejected turn=${turnId.take(12)} origin=$origin verdict=$verdict (not transcribed, target unchanged)")

        override fun onNotAdmitted(turnId: String, origin: VoiceOrigin, reason: String) =
            log("not admitted turn=${turnId.take(12)} origin=$origin reason=$reason (nothing sent, target unchanged)")

        override fun onAccepted(turnId: String, origin: VoiceOrigin) =
            log("accepted turn=${turnId.take(12)} origin=$origin playback_target=$origin")

        override fun onStage(turnId: String, stage: VoiceTurnStage) = log("stage turn=${turnId.take(12)} $stage")

        override fun onRouted(route: AssembledRoute) {
            log("routed turn=${route.turnId.take(12)} alias=${route.destination.alias} created=${route.created}" +
                // Debug builds only: the acknowledgement the Phone composed for a conversation it created
                // (its title and alias; never the transcript or the router's sentence).
                if (route.created && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) " phone_ack=\"${route.ackText}\"" else "")
            // A conversation the router asked for now exists: lists on screen should show it.
            if (route.created) conversationsCreated.value += 1
        }

        override fun onPlayed(cue: PlaybackCue) = log("played turn=${cue.turnId.take(12)} seq=${cue.sequence} " +
            "role=${cue.role} origin=${cue.origin} device=${cue.device} " +
            "confirmed_by=${if (cue.device == VoiceOrigin.WATCH) "watch_played_ack" else "phone_player_completion"}")

        private fun log(line: String) {
            Log.i(VOICE_TAG, line)
        }
    }

    companion object {
        private const val VOICE_TAG = "HermesVoiceTurn"
        private const val RELAY_TAG = "HermesVoiceRelay"
        const val KEY_BACKGROUND_RELAY = "phone_background_relay"
        private const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
        private const val QA_ACTION = "com.rumi.hermesvoice.QA_PHONE"
        private const val QA_RELAY = "hv_qa_relay"
        const val REGISTRY_PREFS_PREFIX = "hermes_voice_sessions_"

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

        fun from(context: Context): PhoneApp = context.applicationContext as PhoneApp
    }
}
