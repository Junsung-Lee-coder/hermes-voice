package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.InMemoryHermesTokenStore
import com.rumi.hermesvoice.core.background.LaterReplyConsent
import com.rumi.hermesvoice.core.headset.AudioEndpoint
import com.rumi.hermesvoice.core.headset.HeadsetDevices
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.headset.HeadsetText
import com.rumi.hermesvoice.core.notify.ReplyAlertLedger
import com.rumi.hermesvoice.core.notify.ReplyAlerts
import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.sessions.OwnedSessionRegistry
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * The Phone's speaker sink as the orchestrator sees it (PhoneSpeakerSink's contract, which the Phone tests pin): a cue bound to a
 * headset plays only while that device is connected and ends with a playback error if it goes; nothing ever falls back to the
 * speaker. [hold] keeps a clip "playing" until completed.
 */
class HeadsetSink(private val devices: FakeDevices, private val decode: (SpokenAudio) -> String) : PlaybackSink {
    val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val cues: MutableList<PlaybackCue> = Collections.synchronizedList(mutableListOf())
    /** Every cue offered to the sink, including those it then refuses. */
    val offered: MutableList<PlaybackCue> = Collections.synchronizedList(mutableListOf())
    val cut = AtomicInteger()
    val starts = AtomicInteger()
    @Volatile var hold: CompletableDeferred<Unit>? = null
    @Volatile var failNext: String? = null

    /** Runs when a cue reaches the sink, before it checks its device: lets a test unplug the headset between admission and playback. */
    @Volatile var onCue: ((PlaybackCue) -> Unit)? = null

    override suspend fun play(audio: SpokenAudio, cue: PlaybackCue) {
        offered += cue
        onCue?.invoke(cue)
        val bound = cue.headset
        if (bound != null && devices.outputs().none { it.sameDevice(bound) }) throw HermesPlaybackException(HeadsetText.PLAYBACK_LOST)
        failNext?.let { failNext = null; throw HermesPlaybackException(it) }
        starts.incrementAndGet()
        cues += cue
        val gate = hold
        try {
            while (gate != null && gate.isActive) {
                if (bound != null && devices.outputs().none { it.sameDevice(bound) }) throw HermesPlaybackException(HeadsetText.PLAYBACK_LOST)
                delay(10)
            }
        } catch (stopped: kotlinx.coroutines.CancellationException) {
            cut.incrementAndGet()
            throw stopped
        }
        played += "${cue.role}:${decode(audio)}"
    }
}

/** The production [HermesVoiceCore.connect] wiring with the Phone's headset policy and sink, like PhoneApp; against [FakeHermesDashboard]. */
class HeadsetRig(
    scope: CoroutineScope,
    val devices: FakeDevices = FakeDevices(),
    voiceListener: VoiceTurnListener = object : VoiceTurnListener {},
    laterOptIn: Boolean = false,
    headsetOn: Boolean = false,
    laterWindowMs: Long = 60_000L,
) : AutoCloseable {
    val fake = FakeHermesDashboard()
    val store: KeyValueStore = InMemoryKeyValueStore()
    val localStore: KeyValueStore = InMemoryKeyValueStore()
    val http: OkHttpClient = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    val tokens = InMemoryHermesTokenStore(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))
    val settings = AppSettings(store).apply { dashboardUrl = fake.baseUrl }
    val registry = OwnedSessionRegistry(store)
    val laterConsent = LaterReplyConsent(localStore).also { it.enabled = laterOptIn }
    val policy = HeadsetPolicy({ settings.useHeadset }, devices)
    val sink = HeadsetSink(devices) { fake.decodeSpoken(it) }
    val alertsShown: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val alerts = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), { alertsShown += "${it.identity}@${it.storedSessionId}"; true }, scope)
    private val wiring = HermesVoiceCore.connect(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens, registry, settings, voiceListener,
        laterScope = scope, laterWindowMs = laterWindowMs, laterEnabled = { laterConsent.enabled }, replyAlerts = alerts,
        headset = policy, phoneSink = sink)
    val core: HermesVoiceCore = wiring.first

    init {
        settings.useHeadset = headsetOn
    }

    override fun close() {
        wiring.second.close()
        fake.close()
    }

    fun existing(alias: String, firstReply: String): String {
        val owned = runBlocking { core.sessions.createConversation(alias.replaceFirstChar { it.uppercase() }, alias, "$alias things") }
        fake.scripts[owned.storedSessionId] = { listOf(FakeHermesDashboard.complete(firstReply)) }
        return owned.storedSessionId
    }

    /** A Phone voice request straight to [stored], spoken on [sink]; returns the outcome. */
    fun phoneTurn(turnId: String, stored: String): VoiceTurnOutcome? = runBlocking {
        core.orchestrator.run(VoiceTurnRequest(turnId, VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", sink, routing = TurnRouting.Direct(stored)))
    }

    fun later(stored: String, text: String) = fake.pushLaterTurn(stored, FakeHermesDashboard.complete(text))

    fun waitFor(what: String, ms: Long = 10_000, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(ms) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what (played=${sink.played}, alerts=$alertsShown)")
        }
    }

    fun settle(ms: Long = 400) = runBlocking { delay(ms) }
}
