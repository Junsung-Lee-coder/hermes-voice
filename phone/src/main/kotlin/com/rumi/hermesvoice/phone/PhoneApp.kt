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
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagOrigin
import com.rumi.hermesvoice.core.diag.DiagVoiceListener
import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundPort
import com.rumi.hermesvoice.core.background.BackgroundSession
import com.rumi.hermesvoice.core.background.BackgroundStatus
import com.rumi.hermesvoice.core.background.DeviceLocalFlags
import com.rumi.hermesvoice.core.background.LaterReplyConsent
import com.rumi.hermesvoice.core.background.NotificationCapability
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.background.WakeHolds
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.voice.AssembledRoute
import com.rumi.hermesvoice.core.voice.AudioOwnership
import com.rumi.hermesvoice.core.voice.MicrophoneClaim
import com.rumi.hermesvoice.core.voice.PendingTurn
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackRoute
import com.rumi.hermesvoice.core.voice.RoutedNavigation
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    /** Main-thread work of the application (opening a microphone once a later reply has stopped). */
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
        Log.e("HermesVoice", "main task failed: ${error.javaClass.simpleName}")
    })

    private val settingsStore by lazy { SharedPreferencesKeyValueStore(prefs(AppSettings.PREFERENCES_NAME)) }
    val settings: AppSettings by lazy { AppSettings(settingsStore) }

    /** This install's own flags (the relay opt-in, notifications asked), excluded from backup and transfer. */
    val localStore by lazy { SharedPreferencesKeyValueStore(prefs(DeviceLocalFlags.PHONE_PREFERENCES)) }

    /** Typed diagnostic events and the user-started, private export of them (see [PhoneDiagnostics]). */
    val diagnostics: PhoneDiagnostics by lazy { PhoneDiagnostics(this) }

    /** "Speak later replies": this install's own informed opt-in, off unless switched on here (device-local, never restored). */
    val laterConsent: LaterReplyConsent by lazy { LaterReplyConsent(localStore) }

    /**
     * This process's microphones and later-reply speaker: every Phone recording claims the
     * microphone here BEFORE opening it, and later replies are admitted here, under one lock.
     */
    val audio = AudioOwnership()

    /** Elapsed-realtime millis when Phone speaker playback last ended (wake-phrase cooldown). */
    @Volatile var lastPhonePlaybackEndedAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        // Durable, idempotent: the wake location from the old Watch opt-in, and the default trailing silence.
        val migrated = settings.migrate()
        Log.i("HermesVoice", "voice settings wake_location=${settings.wakeLocation} vad_silence_s=${settings.vadSilenceSeconds} " +
            "revision=${settings.watchSettingsRevision} migrated=$migrated")
        // Legacy copies of the relay flags in the backed-up settings file are switched off, never carried over.
        if (DeviceLocalFlags.dropLegacy(settingsStore, localStore)) Log.i(RELAY_TAG, "legacy relay flags dropped from the backed-up settings")
        // An undelivered earlier build kept "speak later replies" in the backed-up settings: never taken as consent here.
        if (LaterReplyConsent.dropLegacy(settingsStore)) Log.i(VOICE_TAG, "legacy later-reply opt-in dropped from the backed-up settings")
        appScope.launch(Dispatchers.IO) { runCatching { diagnostics.pruneOnStart() } }
        PhoneRelayService.createChannel(this)
        PhoneWakeService.createChannel(this)
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
        BackgroundSession(localStore, DeviceLocalFlags.KEY_RELAY, relayPort, resumeWhenVisible = true) { status ->
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
        // The app's own wake flow resumes next: the background listening gives the microphone back first.
        phoneWake.onAppShown()
    }

    fun onActivityStopped() {
        activityVisible = false
        phoneWake.onAppHidden()
    }

    private val phoneWakeLazy = lazy { PhoneBackgroundRuntime(this) }

    /**
     * The opt-in background listening for the wake phrase (off by default; [PhoneBackgroundRuntime]).
     * Separate from the relay: either works without the other.
     */
    val phoneWake: PhoneBackgroundRuntime by phoneWakeLazy

    /** The conversation open on this Phone's screen (routing off sends this Phone's voice requests there). */
    @Volatile var selectedConversationId: String? = null

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
        // A Stop also ends following later replies, whether the app is open or not.
        stopLaterReplies()
        if (!hidden) return
        turns.keys.toList().forEach { it.cancel() }
        holds.releaseAll()
    }

    /** Ends following later replies, and every later reply waiting or playing (a Stop, or the option switched off). */
    fun stopLaterReplies() {
        diagnostics.log.record(DiagCode.STOP_ALL, DiagOrigin.PHONE)
        wiring?.core?.orchestrator?.stopFollowing()
    }

    // ── the Phone's wake windows, as later replies must respect them ─────────────────────────

    /** The app's own (foreground) wake window is listening ([PhoneViewModel]). */
    @Volatile var foregroundWakeListening = false

    /** A wake window listens on this Phone now (it closes while a later reply holds the Phone's speaker). */
    fun phoneWakeListening(): Boolean = foregroundWakeListening || (phoneWakeLazy.isInitialized() && phoneWake.listening)

    /** Whether the relay's notification (and its Stop) can be seen now, from the platform itself. */
    fun relayNotificationCapability(): NotificationCapability {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return NotificationCapability.NOT_ALLOWED
        }
        val manager = getSystemService(android.app.NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return NotificationCapability.APP_OFF
        val channel = manager.getNotificationChannel(PhoneRelayService.CHANNEL)
        if (channel != null && channel.importance == android.app.NotificationManager.IMPORTANCE_NONE) return NotificationCapability.CHANNEL_OFF
        return NotificationCapability.SHOWN
    }

    /** True the first time only: whether the relay's notification may show is asked once, not at every switch-on. */
    fun askNotificationsOnce(): Boolean {
        if (localStore.getBoolean(DeviceLocalFlags.KEY_NOTIFICATIONS_ASKED, false)) return false
        localStore.putBoolean(DeviceLocalFlags.KEY_NOTIFICATIONS_ASKED, true)
        return true
    }

    fun onRelayServiceGone(generation: Long) {
        _relayStatus.value = relay.onServiceGone(generation)
    }

    // ── voice turns ──────────────────────────────────────────────────────────────────────────

    private val turns = ConcurrentHashMap<Job, String>()

    /**
     * How many Phone-origin voice turns are being SENT (recorded audio or text on its way to the destination, its
     * acknowledgement spoken): not while a delivered turn only waits for its reply, or for an earlier request to its
     * conversation, so a new request is accepted meanwhile. The waiting turns are in [pendingTurns].
     */
    val phoneTurns = MutableStateFlow(0)

    /** The accepted voice requests (either device) that are not finished: transmitting, queued, awaiting a reply or speaking it. */
    val pendingTurns = MutableStateFlow<List<PendingTurn>>(emptyList())

    private var pendingJob: Job? = null

    /** Voice turns (either origin) in their sending phase; the CPU hold of a turn lasts exactly that long (see [launchTurn]). */
    private val transmitting = java.util.concurrent.atomic.AtomicInteger()
    private val transmissionEnds = ConcurrentHashMap<String, () -> Unit>()

    /** The turn's sending phase ended (delivered, or it ends without delivery): nothing is held on its behalf any more. */
    private fun onTransmitted(turnId: String) {
        transmissionEnds[turnId]?.invoke()
    }

    /** Stops ONE pending voice request wherever it is (a Stop of the pending list); false when it already ended. */
    fun stopPendingTurn(turnId: String): Boolean = wiring?.core?.orchestrator?.stopTurn(turnId) == true

    /**
     * Runs one voice turn (Phone or Watch origin) in the application scope. The CPU is held awake only while a turn
     * is being SENT (bounded), never while it waits for its reply or an earlier request. [phoneOrigin] turns are counted
     * for the Phone's UI while they are sent. [microphone]: the recording's claim, taken over by the orchestrator when the
     * turn starts; given back when the job ends in any way (also cancelled before it ran), so it is never left held.
     */
    fun launchTurn(turnId: String, phoneOrigin: Boolean, microphone: MicrophoneClaim? = null, block: suspend () -> Unit): Job {
        if (phoneOrigin) phoneTurns.update { it + 1 }
        val sending = java.util.concurrent.atomic.AtomicBoolean(true)
        val endSending = {
            if (sending.compareAndSet(true, false)) {
                if (phoneOrigin) phoneTurns.update { it - 1 }
                if (transmitting.decrementAndGet() == 0) holds.release(HoldReason.TURN)
            }
        }
        transmissionEnds[turnId] = endSending
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                turns.remove(coroutineContext[Job])
                transmissionEnds.remove(turnId)
                endSending()
            }
        }
        job.invokeOnCompletion { microphone?.release() }
        turns[job] = turnId
        transmitting.incrementAndGet()
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
        // The old core's follows end with its connection (their replies are reported as not played).
        wiring?.core?.orchestrator?.stopFollowing()
        wiring?.connector?.close()
        // Owned session ids are only meaningful on the dashboard/profile that minted them.
        val registry = OwnedSessionRegistry(SharedPreferencesKeyValueStore(prefs(REGISTRY_PREFS_PREFIX + digest(key))))
        // Later replies of delivered requests are followed in the application, only with this device's
        // informed opt-in (Settings, off by default, device-local). The CPU is kept awake for each attempt
        // to synthesize and hand one off (bounded), never while it waits. Recordings claim this Phone's
        // microphone in [audio] before opening it, and a later reply is admitted there only when none does.
        val (core, connector) = HermesVoiceCore.connect(endpoint, http, tokens, registry, settings, DiagVoiceListener(voiceTrace, diagnostics.log),
            laterScope = appScope, laterWork = ::laterReplyHold,
            wakeListening = { device -> device == VoiceOrigin.PHONE && phoneWakeListening() },
            laterEnabled = { laterConsent.enabled }, ownership = audio,
            laterSpeaker = { device, holding -> if (device == VoiceOrigin.PHONE) phoneSpeakerLater(holding) },
            diag = diagnostics.log)
        val dashboard = core.speech as HermesDashboardClient
        pendingJob?.cancel()
        pendingJob = appScope.launch { core.orchestrator.pending.collect { pendingTurns.value = it } }
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

    private val laterAttempts = java.util.concurrent.atomic.AtomicInteger()
    private val laterOnPhone = java.util.concurrent.atomic.AtomicInteger()

    /**
     * A later reply holds THIS Phone's speaker: from its admission here to its end, on every path.
     * Both wake flows close an open window for it (an episode under way owns the microphone and is
     * left alone). Never set while one waits, is synthesized, retries, or plays on the Watch.
     */
    val speakingLater = MutableStateFlow(false)

    private fun phoneSpeakerLater(holding: Boolean) {
        val now = if (holding) laterOnPhone.incrementAndGet() else laterOnPhone.decrementAndGet()
        speakingLater.value = now > 0
    }

    /** The handoff and playback of one clip of a reply, on either device: the CPU stays awake for it (bounded). Waiting for synthesis holds nothing. */
    private suspend fun laterReplyHold(work: suspend () -> Unit) {
        laterAttempts.incrementAndGet()
        holds.acquire(HoldReason.PLAYBACK, LATER_REPLY_HOLD_MS)
        try {
            work()
        } finally {
            if (laterAttempts.decrementAndGet() == 0) holds.release(HoldReason.PLAYBACK)
        }
    }

    /** The latest later reply's result for the Phone's status line ("played on …" or why not). */
    val laterReplies = MutableStateFlow<String?>(null)

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
     * A routed turn's conversation the Phone screen should open now; consumed by the screen that is
     * showing ([PhoneViewModel]). Not replayed: a screen created later never acts on an old one.
     */
    val routedOpen = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * "Open the routed conversation" (Settings): read when each turn is accepted, acted on once
     * after its delivery, and only while this app is on screen: it never brings the app forward,
     * and it moves neither the playback target nor the Watch's own conversation.
     */
    val routedNavigation = RoutedNavigation(autoNavigate = { settings.autoNavigationApplies }) { storedSessionId ->
        if (!activityVisible) {
            Log.i(VOICE_TAG, "routed conversation not opened: app not on screen")
            return@RoutedNavigation
        }
        routedOpen.tryEmit(storedSessionId)
    }

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

        override fun onAccepted(turnId: String, origin: VoiceOrigin) {
            log("accepted turn=${turnId.take(12)} origin=$origin playback_target=$origin")
            routedNavigation.onAccepted(turnId, origin)
        }

        override fun onDelivered(route: AssembledRoute) {
            log("delivered turn=${route.turnId.take(12)} alias=${route.destination.alias} direct=${route.direct}")
            routedNavigation.onDelivered(route)
        }

        override fun onStage(turnId: String, stage: VoiceTurnStage) = log("stage turn=${turnId.take(12)} $stage")

        override fun onTransmitted(turnId: String) {
            log("transmitted turn=${turnId.take(12)} (waiting for the reply holds nothing)")
            this@PhoneApp.onTransmitted(turnId)
        }

        override fun onRouted(route: AssembledRoute) {
            log("routed turn=${route.turnId.take(12)} alias=${route.destination.alias} created=${route.created} direct=${route.direct}" +
                // Debug builds only: the acknowledgement the Phone composed for a conversation it created
                // (its title and alias; never the transcript or the router's sentence).
                if (route.created && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) " phone_ack=\"${route.ackText}\"" else "")
            // A conversation the router asked for now exists: lists on screen should show it.
            if (route.created) conversationsCreated.value += 1
        }

        override fun onLaterReply(turnId: String, played: Boolean, detail: String) {
            log("later reply turn=${turnId.take(12)} played=$played ${if (played) "device" else "reason"}=$detail")
            laterReplies.value = if (played) "Later reply played on the ${if (detail == "watch") "Watch" else "phone"}"
                else "A later reply couldn't be played: ${detail.take(100)}"
        }

        /** Which replies became speech, and why not (metadata only: role, length, the tracker's reason). */
        override fun onResponse(turnId: String, decision: com.rumi.hermesvoice.core.voice.ResponseDecision) =
            log("response turn=${turnId.take(12)} role=${decision.role} chars=${decision.text.length} " +
                "spoken=${decision.speakText != null} reason=${decision.reason}")

        /** Stage diagnostics: counts, durations, sizes, device kinds and failure classes; never text or ids. */
        override fun onDiagnostic(turnId: String, stage: String, detail: String) =
            log("diag turn=${turnId.take(12).ifEmpty { "-" }} stage=$stage $detail")

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
        private const val QA_ACTION = "com.rumi.hermesvoice.QA_PHONE"
        private const val QA_RELAY = "hv_qa_relay"
        const val REGISTRY_PREFS_PREFIX = "hermes_voice_sessions_"

        /**
         * One unit of speaking a reply (the handoff and playback of one chunk): the bound of that CPU hold, renewed for
         * every chunk, so a long reply is never cut by it. A chunk of at most
         * [com.rumi.hermesvoice.core.voice.TtsBatcher.SINGLE_MAX] characters plays for minutes at most; the margin covers
         * the 3 s wake window close. Waiting for its synthesis holds nothing. It is let go as soon as the unit ends; a busy retry
         * is a new unit; nothing is held while a reply only waits.
         */
        private const val LATER_REPLY_HOLD_MS = 7 * 60_000L + 5_000L

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

        fun from(context: Context): PhoneApp = context.applicationContext as PhoneApp
    }
}
