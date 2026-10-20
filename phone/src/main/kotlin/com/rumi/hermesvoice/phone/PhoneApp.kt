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
import com.rumi.hermesvoice.core.headset.HeadsetMicRoute
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.voice.AssembledRoute
import com.rumi.hermesvoice.core.voice.AudioOwnership
import com.rumi.hermesvoice.core.voice.MicrophoneClaim
import com.rumi.hermesvoice.core.voice.PendingTurn
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackRoute
import com.rumi.hermesvoice.core.voice.RoutedNavigation
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
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
import com.rumi.hermesvoice.core.notify.ConversationPresence
import com.rumi.hermesvoice.core.notify.ReplyAlertLedger
import com.rumi.hermesvoice.core.notify.ReplyAlerts
import okhttp3.OkHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import com.rumi.hermesvoice.core.sessions.AppConversation
import com.rumi.hermesvoice.core.net.StoredSession

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

    /** This phone's display preferences (text size), kept in [localStore]. */
    val display: PhoneDisplaySettings by lazy { PhoneDisplaySettings(localStore) }

    // A small display-only snapshot. It never participates in registry/marker authorization.
    private val sessionTitlesWrites = Mutex()
    private val sessionTitlesEpoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var sessionTitlesBlocked = false
    private data class SessionTitlesHint(val scope: String, val version: Long, val rows: String)
    private val sessionTitlesHintsLock = Any()
    @Volatile private var sessionTitlesHints = emptyMap<Boolean, SessionTitlesHint>()
    private val sessionTitlesRequests = java.util.concurrent.atomic.AtomicLongArray(2)
    internal val sessionTitlesVersion: Long get() = sessionTitlesEpoch.get()

    // Request order is process-wide (two screen callers), but active and archived scans are independent.
    internal fun beginSessionTitlesRefresh(archived: Boolean): Long = sessionTitlesRequests.incrementAndGet(if (archived) 1 else 0)
    internal fun isSessionTitlesRefreshCurrent(archived: Boolean, request: Long): Boolean =
        sessionTitlesRequests.get(if (archived) 1 else 0) == request

    /** Existing native-auth identity and endpoint/profile, not bearer bytes or an invented account id. */
    fun sessionListScope(): String? {
        return runCatching {
            val auth = tokens.load() ?: return@runCatching null
            if (auth.provider.isBlank() || auth.userId.isBlank()) return@runCatching null
            val raw = "${HermesDashboardEndpoint.parse(settings.dashboardUrl)}|${settings.profile}|${auth.provider}|${auth.userId}"
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    /** Synchronous private metadata read BEFORE first state publication; never awaits network or a writer. */
    fun cachedSessionTitles(archived: Boolean): List<AppConversation> {
        val scope = sessionListScope() ?: return emptyList()
        val version = sessionTitlesEpoch.get()
        val recent = sessionTitlesHints[archived]?.takeIf { it.scope == scope && it.version == version }
        if (recent == null && sessionTitlesBlocked) return emptyList()
        val restored = runCatching {
            val rows = if (recent != null) JSONArray(recent.rows) else {
                val raw = localStore.getString(SESSION_TITLES_KEY) ?: return emptyList()
                if (raw.length > 200_000) return emptyList()
                val saved = JSONObject(raw)
                if (saved.optInt("v") != 1 || saved.optString("scope") != scope) return emptyList()
                saved.getJSONArray(if (archived) "archived" else "active")
            }
            if (rows.length() > 100) return emptyList()
            val seen = HashSet<String>()
            (0 until rows.length()).mapNotNull { i ->
                val row = rows.getJSONObject(i)
                val id = row.getString("id")
                if (!com.rumi.hermesvoice.core.voice.DestinationAllowlist.isValidSessionId(id) || !seen.add(id)) return@mapNotNull null
                val title = row.getString("title").take(512)
                // No registry read or invented routing alias: this shell is display metadata ONLY.
                // Every open/history/send/archive/recipient still resolves and verifies the real registry.
                val display = com.rumi.hermesvoice.core.sessions.OwnedSession(id,
                    com.rumi.hermesvoice.core.sessions.OwnedRole.CONVERSATION, title, "", "", archived, 0L)
                AppConversation(display, StoredSession(id, "", title, "", 0.0,
                    row.optDouble("last_active", 0.0).takeIf { it.isFinite() } ?: 0.0, 0, archived))
            }
        }.getOrDefault(emptyList())
        return restored.takeIf { sessionTitlesEpoch.get() == version && sessionListScope() == scope }.orEmpty()
    }

    /** Coupled to progressive UI publication, not a durable write or eligibility grant. */
    internal fun rememberSessionTitles(scope: String?, version: Long, archived: Boolean, rows: List<AppConversation>, current: () -> Boolean): String? {
        if (scope == null || sessionListScope() != scope || sessionTitlesEpoch.get() != version || !current()) return null
        val metadata = JSONArray().apply {
            rows.distinctBy { it.owned.storedSessionId }.take(100).forEach {
                put(JSONObject().put("id", it.owned.storedSessionId).put("title", it.stored.title.ifBlank { it.owned.title }.take(512))
                    .put("last_active", it.stored.lastActive))
            }
        }.toString()
        // Remember only title metadata BEFORE the IO dispatch/writer lock. This is not a disk-commit receipt.
        synchronized(sessionTitlesHintsLock) {
            if (sessionListScope() != scope || sessionTitlesEpoch.get() != version || !current()) return null
            sessionTitlesHints = sessionTitlesHints.filterValues { it.scope == scope && it.version == version } +
                (archived to SessionTitlesHint(scope, version, metadata))
        }
        return metadata
    }

    /** null means superseded, false means an actual persistence failure. UI was already published. */
    suspend fun saveSessionTitles(scope: String?, version: Long, archived: Boolean, rows: List<AppConversation>, current: () -> Boolean): Boolean? {
        val metadata = rememberSessionTitles(scope, version, archived, rows, current) ?: return null
        return withContext(Dispatchers.IO) {
            sessionTitlesWrites.withLock {
                if (sessionListScope() != scope || sessionTitlesEpoch.get() != version || !current()) return@withLock null
                try {
                    val old = runCatching { JSONObject(localStore.getString(SESSION_TITLES_KEY) ?: "{}") }.getOrDefault(JSONObject())
                    val saved = if (old.optInt("v") == 1 && old.optString("scope") == scope) old else
                        JSONObject().put("v", 1).put("scope", scope).put("active", JSONArray()).put("archived", JSONArray())
                    saved.put(if (archived) "archived" else "active", JSONArray(metadata))
                    localStore.commitString(SESSION_TITLES_KEY, saved.toString()).also { ok ->
                        if (!ok) sessionTitlesBlocked = true
                        else if (sessionTitlesEpoch.get() == version && current()) sessionTitlesBlocked = false
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { sessionTitlesBlocked = true; false }
            }
        }
    }

    /** Private recent canonical transcript pages; display-only and excluded with localStore from backup. */
    /** Login-generation fencing is private to body/receive authority; preserve the title/list protocol scope. */
    fun bodyScope(): String? = sessionListScope()?.let { stable ->
        MessageDigest.getInstance("SHA-256").digest("$stable|${tokens.displayGeneration()}".toByteArray()).joinToString("") { "%02x".format(it) }
    }
    val recentBodies by lazy {
        com.rumi.hermesvoice.core.sessions.RecentBodies(localStore, "conversation_bodies_v1", ::bodyScope, appScope) {
            cacheNotice.value = "Recent messages not saved. Check device storage; online messages remain available."
        }
    }
    val cacheNotice = MutableStateFlow<String?>(null)
    val received by lazy { com.rumi.hermesvoice.core.sessions.ReceivedConversations(localStore, ::bodyScope) }
    val finalRecovery by lazy { FinalRecovery(this) }
    val readerLedger = com.rumi.hermesvoice.core.watchlink.ReaderRequestLedger()

    /** Immediate in-memory fence, then serialized durable clearing; never waits for disk on the UI thread. */
    fun invalidateSessionTitles() {
        finalRecovery.invalidate()
        recentBodies.invalidate()
        val version = synchronized(sessionTitlesHintsLock) {
            sessionTitlesBlocked = true
            sessionTitlesHints = emptyMap()
            sessionTitlesEpoch.incrementAndGet()
        }
        appScope.launch(Dispatchers.IO) {
            sessionTitlesWrites.withLock {
                if (sessionTitlesEpoch.get() != version) return@withLock
                val ok = try { localStore.commitString(SESSION_TITLES_KEY, "{}") }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                if (!ok) Log.w("HermesVoice", "Session list invalidation was not saved")
            }
        }
    }

    private val SESSION_TITLES_KEY = "session_list_titles_v1"

    /** Typed diagnostic events and the user-started, private export of them (see [PhoneDiagnostics]). */
    val diagnostics: PhoneDiagnostics by lazy { PhoneDiagnostics(this) }

    /** "Speak later replies": this install's own informed opt-in, off unless switched on here (device-local, never restored). */
    val laterConsent: LaterReplyConsent by lazy { LaterReplyConsent(localStore) }

    /** The "not sent" states owed to Watch nodes until they acknowledge them; app-wide, so a rebuilt core keeps them. */
    val watchFailureOutbox: com.rumi.hermesvoice.core.watchlink.WatchFailureOutbox by lazy { com.rumi.hermesvoice.core.watchlink.WatchFailureOutbox(localStore) }

    /**
     * "Use headset" (Phone-only, off by default): the live audio endpoints, the one policy over them and the preference, and the
     * choice of microphone for each new recording. Nothing here polls or runs as a service; endpoint changes are watched only
     * around one playback or one recording.
     */
    val headsetDevices: AndroidHeadsetDevices by lazy { AndroidHeadsetDevices(this) }
    val headset: HeadsetPolicy by lazy { HeadsetPolicy({ settings.useHeadset }, headsetDevices) }
    val headsetMic: HeadsetMicRoute by lazy { HeadsetMicRoute(headset, AndroidCommsLink(this)) }
    val recordFactory: RecordFactory by lazy { AndroidRecords.factory(this) }

    /**
     * Headset play/pause as a Phone recording control, and the private recording tones: both exist only while "Use headset" is on
     * and a personal headset output is connected, and neither touches the Watch.
     */
    val mediaSessions: MediaSessionFactory by lazy { AndroidMediaSessions(this) }
    val cueOutput: CueOutput by lazy { AndroidCueOutput(this, actualPlayback = { headsetMedia.cuePlaying(it) }) }
    val recordingCues: RecordingCues by lazy { RecordingCues(headset, cueOutput) }
    val phoneMic = PhoneMicArbiter()
    val manualAdmission: ManualAdmission by lazy { PhoneManualService.admission(this) }
    val manualCapture: PhoneManualCapture by lazy { PhoneManualCapture(this) }
    val headsetMedia: HeadsetMediaControl by lazy {
        HeadsetMediaControl(headset, mediaSessions, { activityVisible }).also { it.attach(manualCapture) }
    }
    val connectionCue: ConnectionCueController by lazy {
        ConnectionCueController(headset, recordingCues, { manualCapture.busy || phoneMic.occupied }, { /* pending is not native playback */ })
    }

    /**
     * Tells the core and the Watch when the headset is the private output (the setting on and a personal headset output connected),
     * so no reply is spoken on the Watch's or the Phone's speaker then. Started with the core; the device watch exists only while the setting is on.
     */
    val privateAudio: PrivateAudioHub by lazy {
        PrivateAudioHub(settings, headset, appScope,
            publish = { snapshot -> WatchSettingsSync.publish(this, snapshot) },
            onCore = { active -> wiring?.core?.orchestrator?.onPrivateOutputChanged(active) })
    }

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
        PhoneManualService.createChannel(this)
        ReplyAlertNotifier.createChannel(this)
        privateAudio.refresh()
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

    private val _relayForeground = MutableStateFlow(false)

    /** True only while Android has ADMITTED the relay's foreground service (a start that was merely asked for is not this). */
    val relayForeground: StateFlow<Boolean> = _relayForeground

    fun onRelayForegroundChanged() {
        _relayForeground.value = PhoneRelayService.foregroundActive
    }

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

    /** Android 15+ gives audio focus only to the top app or a foreground service of this app (the relay or the wake listening). */
    private fun playbackFocusEligible(): Boolean =
        focusEligible(android.os.Build.VERSION.SDK_INT, activityVisible, PhoneRelayService.foregroundActive, PhoneWakeService.foregroundActive)

    fun onActivityStarted() {
        activityVisible = true
        presence.setForeground(true)
        // Replies of accepted requests that finished while nothing here was waiting for them.
        wiring?.core?.reconcileAccepted()
        finalRecovery.start()
        _relayStatus.value = relay.onVisible(microphoneWanted = false, microphonePermission = false)
        // The app's own wake flow resumes next: the background listening gives the microphone back first.
        phoneWake.onAppShown()
        headsetMedia.refresh()
    }

    fun onActivityStopped() {
        activityVisible = false
        presence.setForeground(false)
        phoneWake.onAppHidden()
        headsetMedia.refresh()
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

    /**
     * True once: the visible first text send asks whether replies may alert (Android 13+ only, and only while the permission is
     * missing). The send itself never waits on the answer, and a refusal changes nothing else.
     */
    fun askReplyAlertsOnce(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        if (localStore.getBoolean(DeviceLocalFlags.KEY_REPLY_ALERT_ASKED, false)) return false
        localStore.putBoolean(DeviceLocalFlags.KEY_REPLY_ALERT_ASKED, true)
        return true
    }

    fun onRelayServiceRefused(generation: Long) {
        _relayStatus.value = relay.onServiceRefused(generation)
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
    private var unplayedJob: Job? = null

    /** Voice turns (either origin) in their sending phase; the CPU hold of a turn lasts exactly that long (see [launchTurn]). */
    private val transmitting = java.util.concurrent.atomic.AtomicInteger()
    private val transmissionEnds = ConcurrentHashMap<String, () -> Unit>()

    /** Per turn: give back / take again the CPU claim and the "sending" count around the speech-recognition backoff waits (a waiting request neither holds the CPU nor blocks a new request). */
    private class SendingHold(val pause: () -> Unit, val resume: () -> Unit)
    private val sendingHolds = ConcurrentHashMap<String, SendingHold>()

    private fun onSttRecovery(turnId: String, event: com.rumi.hermesvoice.core.voice.SttRecoveryEvent) {
        when (event.phase) {
            com.rumi.hermesvoice.core.voice.SttPhase.WAIT_STARTED -> sendingHolds[turnId]?.pause?.invoke()
            com.rumi.hermesvoice.core.voice.SttPhase.ATTEMPT_STARTED -> sendingHolds[turnId]?.resume?.invoke()
            else -> Unit
        }
    }

    /** The turn's sending phase ended (delivered, or it ends without delivery): nothing is held on its behalf any more. */
    private fun onTransmitted(turnId: String) {
        transmissionEnds[turnId]?.invoke()
    }

    /** Stops ONE pending voice request wherever it is (a Stop of the pending list); false when it already ended. */
    fun stopPendingTurn(turnId: String): Boolean = wiring?.core?.orchestrator?.stopTurn(turnId) == true

    /** The replies whose audio did not play and can be played again (metadata only; in memory, so a process end forgets them). */
    val unplayedReplies = MutableStateFlow<List<VoiceTurnOrchestrator.UnplayedReply>>(emptyList())

    /** "Play again" for one of them: the same text, nothing sent to Hermes again. [done] gets whether it was heard, and a fixed reason when not. */
    fun replayReply(identity: String, expectedSessionId: String? = null, done: (heard: Boolean, detail: String) -> Unit): Boolean =
        wiring?.core?.orchestrator?.replayAudio(identity, null, null, expectedSessionId, done) == true

    /**
     * Forgets every cached unplayed reply of this device and refuses any later one from the current core (sign-out, or the dashboard/profile
     * identity changed). Touches only the unplayed-reply cache: not conversations, recordings, credentials or settings. Done BEFORE the next
     * key/wiring is made: the old core is ended and dropped, so a late callback of it cannot repopulate the cache and the next wiring starts
     * from an empty one. The cache file is cleared as a whole (it holds nothing else). True when the stored copy was cleared; false: the
     * commit failed, nothing is shown and nothing is claimed deleted.
     */
    @Synchronized
    fun revokeUnplayedReplies(): Boolean {
        asyncConnection.incrementAndGet()
        deliveryGeneration.incrementAndGet()
        val current = wiring
        current?.core?.orchestrator?.stopFollowing()
        val orchestratorCleared = current?.core?.orchestrator?.revokeUnplayed() ?: true
        current?.connector?.close()
        wiring = null
        unplayedJob?.cancel()
        val fileCleared = purgeUnplayedFile()
        unplayedReplies.value = emptyList()
        return orchestratorCleared && fileCleared
    }

    /**
     * Where the unplayed-reply cache lives (the whole file holds nothing else). Overridable only so a test can make its commit fail.
     */
    internal var unplayedPrefs: () -> android.content.SharedPreferences = { prefs(com.rumi.hermesvoice.core.voice.UnplayedReplyStore.PREFERENCES_NAME) }

    private val unplayedFenceLock = Any()

    /** True from a failed clear until a clear works: the stored text is then neither read nor added to by any wiring of this process. */
    @Volatile private var unplayedPurgePending = false

    private fun purgeUnplayedFile(): Boolean = synchronized(unplayedFenceLock) {
        unplayedPurgePending = !runCatching { unplayedPrefs().edit().clear().commit() }.getOrDefault(false)
        !unplayedPurgePending
    }

    /** While a clear is owed it is tried again at every access; the cached text stays invisible and nothing new is stored until it works. */
    private fun unplayedCacheBlocked(): Boolean {
        if (!unplayedPurgePending) return false
        return !purgeUnplayedFile()
    }

    /**
     * Runs one voice turn (Phone or Watch origin) in the application scope. The CPU is held awake only while a turn
     * is being SENT (bounded), never while it waits for its reply or an earlier request. [phoneOrigin] turns are counted
     * for the Phone's UI while they are sent. [microphone]: the recording's claim, taken over by the orchestrator when the
     * turn starts; given back when the job ends in any way (also cancelled before it ran), so it is never left held.
     */
    fun launchTurn(turnId: String, phoneOrigin: Boolean, microphone: MicrophoneClaim? = null, block: suspend () -> Unit): Job {
        if (phoneOrigin) phoneTurns.update { it + 1 }
        val sending = java.util.concurrent.atomic.AtomicBoolean(true)
        val holding = Any()
        var held = true
        val releaseHold = {
            synchronized(holding) {
                if (held) {
                    held = false
                    if (phoneOrigin) phoneTurns.update { it - 1 }
                    if (transmitting.decrementAndGet() == 0) holds.release(HoldReason.TURN)
                }
            }
        }
        val endSending = {
            if (sending.compareAndSet(true, false)) releaseHold()
        }
        transmissionEnds[turnId] = endSending
        sendingHolds[turnId] = SendingHold(
            pause = { releaseHold() },
            resume = {
                synchronized(holding) {
                    if (sending.get() && !held) {
                        held = true
                        if (phoneOrigin) phoneTurns.update { it + 1 }
                        transmitting.incrementAndGet()
                        holds.acquire(HoldReason.TURN)
                    }
                }
            },
        )
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                turns.remove(coroutineContext[Job])
                transmissionEnds.remove(turnId)
                sendingHolds.remove(turnId)
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
                 val dashboard: HermesDashboardClient, val connector: HermesGatewayConnector, val namespace: String?)

    /** Captured by the initiating caller, not reconstructed by a replacement screen at completion.
     * Scope contains only the existing endpoint/profile/provider/user identity, never bearer bytes. */
    class AsyncActionOrigin internal constructor(internal val wiring: Wiring, internal val scope: String?,
        internal val url: String, internal val profile: String, internal val connection: Long, internal val namespace: String?)
    private val asyncConnection = java.util.concurrent.atomic.AtomicLong()
    private val deliveryGeneration = java.util.concurrent.atomic.AtomicLong()

    internal fun asyncActionOrigin(wiring: Wiring) = AsyncActionOrigin(wiring, sessionListScope(),
        settings.dashboardUrl, settings.profile, asyncConnection.get(), wiring.namespace)

    internal fun asyncActionCurrent(origin: AsyncActionOrigin, authLost: Boolean = false): Boolean {
        if (wiring !== origin.wiring || asyncConnection.get() != origin.connection ||
            settings.dashboardUrl != origin.url || settings.profile != origin.profile) return false
        val scope = sessionListScope()
        // The current client's rejected refresh clears tokens before reporting auth loss. An
        // explicit sign-out/connection replacement has already changed this application epoch.
        return (scope == origin.scope && bodyScope() == origin.namespace) || (authLost && scope == null && origin.scope != null)
    }

    /** The exchange has not touched live credentials yet. Commit only to its original login. */
    @Synchronized
    internal fun completeNativeSignIn(origin: AsyncActionOrigin, session: com.rumi.hermesvoice.core.auth.HermesBearerSession): Boolean {
        if (!asyncActionCurrent(origin)) return false
        revokeUnplayedReplies()
        invalidateSessionTitles()
        selectedConversationId = null
        tokens.saveLogin(session)
        wiring() // Bind the new core now, never dynamically relabel an old core's callbacks.
        return true
    }

    @Volatile private var wiring: Wiring? = null

    /** Refresh may rotate a token in this construction's epoch. Retired work cannot load,
     * save or clear a replacement login's credentials, even when the user id is unchanged. */
    private fun tokensFor(namespace: String?, generation: Long) = object : com.rumi.hermesvoice.core.auth.HermesTokenStore {
        private fun current() = deliveryGeneration.get() == generation && bodyScope() == namespace
        override fun load(): com.rumi.hermesvoice.core.auth.HermesBearerSession? = synchronized(this@PhoneApp.tokens) {
            if(namespace == null || !current()) null else this@PhoneApp.tokens.load()
        }
        override fun save(session: com.rumi.hermesvoice.core.auth.HermesBearerSession) = synchronized(this@PhoneApp.tokens) {
            if(!current()) throw com.rumi.hermesvoice.core.HermesAuthRequiredException("Sign-in connection changed")
            this@PhoneApp.tokens.save(session)
        }
        override fun clear() = synchronized(this@PhoneApp.tokens) {
            if(current()) this@PhoneApp.tokens.clear()
        }
    }

    /** Throws [IllegalArgumentException] when the dashboard URL is missing or not an allowed transport. */
    @Synchronized
    fun wiring(): Wiring {
        val endpoint = HermesDashboardEndpoint.parse(settings.dashboardUrl)
        val key = "$endpoint|${settings.profile}"
        val namespaceForReplies = bodyScope()
        wiring?.takeIf { it.key == key && it.namespace == namespaceForReplies }?.let { return it }
        // The old core's follows end with its connection (their replies are reported as not played).
        wiring?.core?.orchestrator?.stopFollowing()
        wiring?.connector?.close()
        // Owned session ids are only meaningful on the dashboard/profile that minted them.
        val registry = OwnedSessionRegistry(SharedPreferencesKeyValueStore(prefs(REGISTRY_PREFS_PREFIX + digest(key))))
        // Later replies of delivered requests are followed in the application, only with this device's
        // informed opt-in (Settings, off by default, device-local). The CPU is kept awake for each attempt
        // to synthesize and hand one off (bounded), never while it waits. Recordings claim this Phone's
        // microphone in [audio] before opening it, and a later reply is admitted there only when none does.
        val generationForReplies = deliveryGeneration.incrementAndGet()
        val (core, connector) = HermesVoiceCore.connect(endpoint, http, tokensFor(namespaceForReplies, generationForReplies), registry, settings, DiagVoiceListener(voiceTrace, diagnostics.log),
            laterScope = appScope, laterWork = ::laterReplyHold,
            wakeListening = { device -> device == VoiceOrigin.PHONE && phoneWakeListening() },
            laterEnabled = { laterConsent.enabled }, ownership = audio,
            laterSpeaker = { device, holding -> if (device == VoiceOrigin.PHONE) phoneSpeakerLater(holding) },
            diag = diagnostics.log, replyAlerts = replyAlerts,
            headset = headset, phoneSink = PhoneSpeakerSink(this, headsetDevices, focusEligible = ::playbackFocusEligible),
            recoveryClock = android.os.SystemClock::elapsedRealtime,
            unplayedStore = com.rumi.hermesvoice.core.voice.UnplayedReplyStore(FencedKeyValueStore({ SharedPreferencesKeyValueStore(unplayedPrefs()) }, ::unplayedCacheBlocked),
                key = com.rumi.hermesvoice.core.voice.UnplayedReplyStore.KEY + "_" + digest(key)),
            watchFailures = watchFailureOutbox, replyNamespace = { namespaceForReplies },
            onConversationCreated = { id -> finalRecovery.created(id, namespaceForReplies) },
            onConversationVerified = { id,after -> finalRecovery.verified(id,after,namespaceForReplies) },
            replyGeneration = generationForReplies)
        val dashboard = core.speech as HermesDashboardClient
        pendingJob?.cancel()
        pendingJob = appScope.launch { core.orchestrator.pending.collect { pendingTurns.value = it } }
        unplayedJob?.cancel()
        unplayedJob = appScope.launch { core.orchestrator.unplayedReplies.collect { unplayedReplies.value = it } }
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
        return Wiring(key, endpoint, core, dashboard, connector, namespaceForReplies).also { wiring = it }
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
     * The arrival alert for a final answer that was not spoken: decides once per answer (bounded, durable, device-local) and
     * shows it here, or hands a Watch-target answer to the Watch's own alert (see [ReplyAlerts]).
     */
    val replyAlerts: ReplyAlerts by lazy {
        ReplyAlerts(ReplyAlertLedger(localStore), ReplyAlertNotifier(this), appScope, presence, everyFinal = true).also { alerts ->
            alerts.currentNamespace = ::bodyScope
            alerts.currentGeneration = deliveryGeneration::get
            alerts.canonicalFinal = finalRecovery::capture
            alerts.confirmDisplay = { reply -> conversationDisplay?.confirmArrival(reply) ?: false }
            alerts.confirmOpened = { id -> conversationDisplay?.confirmOpened(id) ?: false }
        }
    }

    /** The text requests being sent and answered; they outlive any screen (see [TextSends]). */
    val textSends: TextSends by lazy { TextSends(appScope) }

    /** The conversation screen that can refresh and confirm what it shows (set by the view model while it lives). */
    @Volatile var conversationDisplay: ConversationDisplay? = null

    /**
     * Which conversation is really in front of the user (foreground AND on the Chat tab). Each time one becomes visible this
     * app's stacked reply alerts for exactly that conversation are removed ([ReplyAlerts.onConversationOpened]).
     */
    val presence: ConversationPresence by lazy { ConversationPresence().also { it.onOpened = { id -> replyAlerts.onConversationVisible(id) } } }

    /** The current wiring if one was already made (never creates one, so a missing dashboard URL is not reported). */
    fun existingWiring(): Wiring? = wiring

    private val _pendingOpen = MutableStateFlow<String?>(null)

    /**
     * The conversation a tapped arrival alert asks the screen to open. Kept until a screen has opened it (the app may still be
     * starting or signing in), never replayed after that, and independent of the "open the routed conversation" preference.
     */
    val pendingOpen: StateFlow<String?> = _pendingOpen

    private val _pendingOpenTaps = MutableStateFlow(0)

    /** Counts taps, so a tap on an alert whose conversation did not open (its request was kept) tries again. */
    val pendingOpenTaps: StateFlow<Int> = _pendingOpenTaps

    fun requestOpenConversation(storedSessionId: String) {
        _pendingOpen.value = storedSessionId
        _pendingOpenTaps.update { it + 1 }
    }

    fun consumePendingOpen(storedSessionId: String) {
        _pendingOpen.compareAndSet(storedSessionId, null)
    }

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

        override fun onSttRecovery(turnId: String, event: com.rumi.hermesvoice.core.voice.SttRecoveryEvent) {
            log("stt turn=${turnId.take(12)} phase=${event.phase} attempt=${event.attempt} elapsed_ms=${event.elapsedMs} bytes=${event.audioBytes} " +
                "code=${event.code?.wire ?: "none"} status=${event.status ?: "none"} wait_ms=${event.waitMs ?: 0}")
            this@PhoneApp.onSttRecovery(turnId, event)
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
        /** Android 15+ gives audio focus only to the top app or an ADMITTED foreground service of this app; earlier releases do not gate it. */
        internal fun focusEligible(sdk: Int, activityVisible: Boolean, relayForeground: Boolean, wakeForeground: Boolean): Boolean =
            sdk < 35 || activityVisible || relayForeground || wakeForeground

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
