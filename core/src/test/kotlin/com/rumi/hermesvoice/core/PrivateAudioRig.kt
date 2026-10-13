package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.InMemoryHermesTokenStore
import com.rumi.hermesvoice.core.background.LaterReplyConsent
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.headset.PrivateAudioMonitor
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.notify.ReplyAlertLedger
import com.rumi.hermesvoice.core.notify.ReplyAlertPort
import com.rumi.hermesvoice.core.notify.ReplyAlerts
import com.rumi.hermesvoice.core.notify.ReplyPreview
import com.rumi.hermesvoice.core.sessions.OwnedSessionRegistry
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

/**
 * The Watch's playback sink as the Phone sees it: everything handed to it is a sound out of the Watch's speaker. [hold] keeps a
 * clip "playing" until completed; a cancellation is the STOP the real link sends to the Watch ([cut]).
 */
class WatchFakeSink(private val decode: (SpokenAudio) -> String) : PlaybackSink {
    val offered: MutableList<PlaybackCue> = Collections.synchronizedList(mutableListOf())
    val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val watchAlerts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val starts = AtomicInteger()
    val cut = AtomicInteger()
    @Volatile var hold: CompletableDeferred<Unit>? = null

    override suspend fun play(audio: SpokenAudio, cue: PlaybackCue) {
        offered += cue
        starts.incrementAndGet()
        val gate = hold
        try {
            while (gate != null && gate.isActive) delay(10)
        } catch (stopped: kotlinx.coroutines.CancellationException) {
            cut.incrementAndGet()
            throw stopped
        }
        played += "${cue.role}:${decode(audio)}"
    }

    override suspend fun deliverReplyAlert(alert: ReplyAlert): Boolean {
        watchAlerts += alert.identity
        return true
    }
}

class PrivateShownAlert(val identity: String, val quiet: Boolean)

/**
 * The production [HermesVoiceCore.connect] wiring with BOTH devices' sinks: the Phone's headset-aware sink (the core's private
 * sink) and a Watch sink the voice requests of the Watch play on. [privateMonitor] is the Phone's lifecycle object that tells the
 * core and the Watch about the private-output state; [watchPrivate] records what it published for the Watch.
 */
class PrivateAudioRig(
    scope: CoroutineScope,
    val devices: FakeDevices = FakeDevices(),
    laterOptIn: Boolean = false,
    headsetOn: Boolean = true,
    laterWindowMs: Long = 60_000L,
    withPhoneSink: Boolean = true,
) : AutoCloseable {
    val fake = FakeHermesDashboard()
    private val store: KeyValueStore = InMemoryKeyValueStore()
    private val http: OkHttpClient = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val tokens = InMemoryHermesTokenStore(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))
    val settings = AppSettings(store).apply { dashboardUrl = fake.baseUrl }
    private val registry = OwnedSessionRegistry(store)
    private val laterConsent = LaterReplyConsent(InMemoryKeyValueStore()).also { it.enabled = laterOptIn }
    val policy = HeadsetPolicy({ settings.useHeadset }, devices)
    val phone = HeadsetSink(devices) { fake.decodeSpoken(it) }
    val watch = WatchFakeSink { fake.decodeSpoken(it) }
    val shown: MutableList<PrivateShownAlert> = Collections.synchronizedList(mutableListOf())
    val watchPrivate: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    private val port = object : ReplyAlertPort {
        override fun show(alert: ReplyAlert): Boolean {
            shown += PrivateShownAlert(alert.identity, quiet = false)
            return true
        }

        override fun show(alert: ReplyAlert, preview: ReplyPreview?, quiet: Boolean): Boolean {
            shown += PrivateShownAlert(alert.identity, quiet)
            return true
        }
    }
    private val alerts = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), port, scope)
    private val wiring = HermesVoiceCore.connect(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens, registry, settings,
        laterScope = scope, laterWindowMs = laterWindowMs, laterEnabled = { laterConsent.enabled }, replyAlerts = alerts,
        headset = policy, phoneSink = if (withPhoneSink) phone else null)
    val core: HermesVoiceCore = wiring.first
    val privateMonitor = PrivateAudioMonitor(policy) { active ->
        watchPrivate += active
        core.orchestrator.onPrivateOutputChanged(active)
    }

    init {
        settings.useHeadset = headsetOn
        privateMonitor.refresh()
    }

    override fun close() {
        privateMonitor.close()
        wiring.second.close()
        fake.close()
    }

    fun existing(alias: String, firstReply: String): String {
        val owned = runBlocking { core.sessions.createConversation(alias.replaceFirstChar { it.uppercase() }, alias, "$alias things") }
        fake.scripts[owned.storedSessionId] = { listOf(FakeHermesDashboard.complete(firstReply)) }
        return owned.storedSessionId
    }

    fun phoneTurn(turnId: String, stored: String) = runBlocking {
        core.orchestrator.run(VoiceTurnRequest(turnId, VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", phone, routing = TurnRouting.Direct(stored)))
    }

    fun watchTurn(turnId: String, stored: String) = runBlocking {
        core.orchestrator.run(VoiceTurnRequest(turnId, VoiceOrigin.WATCH, TestAudio.speechWav(), "audio/wav", watch, routing = TurnRouting.Direct(stored)))
    }

    fun later(stored: String, text: String) = fake.pushLaterTurn(stored, FakeHermesDashboard.complete(text))

    fun phoneFinals() = phone.played.filter { it.startsWith("FINAL") }

    fun waitFor(what: String, ms: Long = 10_000, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(ms) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what (phone=${phone.played}, watch=${watch.played}, shown=${shown.map { it.identity to it.quiet }})")
        }
    }

    fun settle(ms: Long = 400) = runBlocking { delay(ms) }
}
