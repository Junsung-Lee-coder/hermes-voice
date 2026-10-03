package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.HermesTokenStore
import com.rumi.hermesvoice.core.net.GatewayConversationPort
import com.rumi.hermesvoice.core.net.HermesConversationPort
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import com.rumi.hermesvoice.core.net.HermesGatewayConnector
import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.net.RuntimeSetupLimits
import com.rumi.hermesvoice.core.net.SubmittedTurn
import com.rumi.hermesvoice.core.sessions.AppSessionRepository
import com.rumi.hermesvoice.core.sessions.HermesSessionsApi
import com.rumi.hermesvoice.core.sessions.OwnedSessionRegistry
import com.rumi.hermesvoice.core.sessions.RouterRuntime
import com.rumi.hermesvoice.core.net.SessionRuntimeSpec
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.voice.AudioOwnership
import com.rumi.hermesvoice.core.voice.RecipientEvent
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnConfig
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import com.rumi.hermesvoice.core.wake.WakeAdmission
import com.rumi.hermesvoice.core.wake.WakeEpisode
import com.rumi.hermesvoice.core.wake.WakeEpochStore
import com.rumi.hermesvoice.core.watchlink.WatchAckRegistry
import com.rumi.hermesvoice.core.watchlink.WatchTurnIntake
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/** Result of a text-chat send; the transcript itself is re-read from history after the turn. */
sealed class ChatSendResult {
    data class Replied(val text: String, val status: String?) : ChatSendResult()

    /** Hermes accepted the message but its reply could not be attributed (busy session); refresh history later. */
    data class Accepted(val submitStatus: String) : ChatSendResult()
    data class Failed(val reason: String, val authRequired: Boolean = false) : ChatSendResult()
}

/** Text chat into an app-owned conversation. Replies are shown, not spoken. */
class ChatService(private val sessions: AppSessionRepository, private val replyTimeoutMs: Long = 15 * 60_000L) {
    suspend fun send(storedSessionId: String, text: String, attachments: List<OutgoingAttachment> = emptyList()): ChatSendResult {
        val turn: SubmittedTurn = try {
            sessions.sendMessage(storedSessionId, text, attachments)
        } catch (error: HermesAuthRequiredException) {
            return ChatSendResult.Failed(error.message ?: "sign-in required", authRequired = true)
        } catch (error: HermesException) {
            return ChatSendResult.Failed(error.message ?: error.javaClass.simpleName)
        } catch (error: IllegalArgumentException) {
            return ChatSendResult.Failed(error.message ?: "invalid message")
        } catch (error: IOException) {
            return ChatSendResult.Failed("network: ${error.javaClass.simpleName}")
        }
        if (!turn.attributable) {
            turn.release()
            return ChatSendResult.Accepted(turn.submitStatus)
        }
        var reply: RecipientEvent.Complete? = null
        return try {
            turn.collect(replyTimeoutMs) { event -> if (event is RecipientEvent.Complete) reply = event }
            reply?.let { ChatSendResult.Replied(it.text, it.status) } ?: ChatSendResult.Accepted(turn.submitStatus)
        } catch (error: HermesException) {
            ChatSendResult.Failed("sent, but the reply failed: ${error.message}")
        } catch (error: IOException) {
            ChatSendResult.Failed("sent, but the connection dropped: ${error.javaClass.simpleName}")
        } finally {
            // Text replies are shown, never followed or spoken.
            turn.release()
        }
    }
}

/**
 * Composition root shared by the Phone UI, the Phone voice button and the Watch link, so turn
 * ordering, speaker ownership, turn-id deduplication and the playback route (the latest voice
 * sender) are shared by Phone and Watch turns.
 */
class HermesVoiceCore(
    val speech: HermesSpeechGateway,
    sessionsApi: HermesSessionsApi,
    conversations: HermesConversationPort,
    registry: OwnedSessionRegistry,
    private val settings: AppSettings,
    voiceListener: VoiceTurnListener = object : VoiceTurnListener {},
    /** Wall-clock millis for the wake claim leases. */
    clock: () -> Long = System::currentTimeMillis,
    /** Where later replies of delivered voice turns are followed (see [VoiceTurnOrchestrator]); null: not followed. */
    laterScope: CoroutineScope? = null,
    laterWindowMs: Long = VoiceTurnOrchestrator.LATER_WINDOW_MS,
    /** Wraps one attempt to speak a later reply: synthesis and handoff (the Phone keeps the CPU awake for that, bounded). */
    laterWork: suspend (suspend () -> Unit) -> Unit = { it() },
    /** Whether a device's wake window listens now (it closes for a later reply; never spoken over). */
    wakeListening: (VoiceOrigin) -> Boolean = { false },
    laterDeferMaxMs: Long = VoiceTurnOrchestrator.LATER_DEFER_MAX_MS,
    /**
     * The user's informed opt-in to speak later replies: device-local consent the app owns (never a
     * backed-up setting). Read when a turn would be followed and at each later reply. Off by default.
     */
    laterEnabled: () -> Boolean = { false },
    /** This process's microphones and later-reply speaker ([AudioOwnership]); shared by every core of the process. */
    ownership: AudioOwnership = AudioOwnership(),
    /** A later reply holds a device's speaker (true), or no longer (false). */
    laterSpeaker: (VoiceOrigin, Boolean) -> Unit = { _, _ -> },
    /** The hidden routing session's own model ([RouterRuntime]); null keeps it on the profile's default. */
    routerRuntime: SessionRuntimeSpec? = RouterRuntime.LUNA_LOW,
) {
    val sessions = AppSessionRepository(sessionsApi, conversations, registry, routerRuntime = routerRuntime,
        diagnostic = { line -> voiceListener.onDiagnostic("", "router", line) })
    val chat = ChatService(sessions)
    /** Arbitrates the wake phrase when both devices listen: one spoken wake episode, one admitted device. */
    val wakeAdmission = WakeAdmission(clock, settings::watchSettings,
        epochs = object : WakeEpochStore {
            override fun load(): Long = settings.wakeEpoch
            override fun save(epoch: Long) { settings.wakeEpoch = epoch }
        },
        onAnswered = { episode -> onWakeEpisode(episode) })

    /** Told of each admitted wake request in Both (see [WakeAdmission]); the Phone app publishes it to the Watch. */
    @Volatile var onWakeEpisode: (WakeEpisode) -> Unit = {}
    val orchestrator = VoiceTurnOrchestrator(speech, sessions.guardedPort(), config = ::voiceConfig, listener = voiceListener,
        recipientCreator = sessions.recipientCreator(),
        admission = { request -> wakeAdmission.admitTurn(request.wakeTurn, request.origin, request.originNodeId, request.wakeClaimId) },
        laterScope = laterScope, laterWindowMs = laterWindowMs, laterWork = laterWork,
        laterEnabled = laterEnabled, wakeListening = wakeListening, laterDeferMaxMs = laterDeferMaxMs,
        ownership = ownership, laterSpeaker = laterSpeaker)
    val watchAcks = WatchAckRegistry()
    /** A Watch turn's routing is the Phone's switch when its upload arrives; routing off uses the Watch's selection. */
    val watchIntake = WatchTurnIntake(orchestrator, watchAcks) { upload -> TurnRouting.of(settings.routingEnabled, upload.target) }

    /**
     * Snapshotted per turn. The allowlist may be empty: the router can then ask for a new
     * conversation. A routing-off turn never touches the router session (not even to create it).
     */
    private suspend fun voiceConfig(routing: TurnRouting): VoiceTurnConfig {
        if (routing is TurnRouting.Direct) return VoiceTurnConfig(null, sessions.allowlist(null), settings.playback())
        val router = sessions.ensureRoutingSession()
        return VoiceTurnConfig(router.storedSessionId, sessions.allowlist(router.storedSessionId), settings.playback())
    }

    companion object {
        /** Production wiring against a real dashboard; [close] the returned connector when the endpoint changes. */
        fun connect(
            endpoint: HermesDashboardEndpoint,
            http: OkHttpClient,
            tokens: HermesTokenStore,
            registry: OwnedSessionRegistry,
            settings: AppSettings,
            voiceListener: VoiceTurnListener = object : VoiceTurnListener {},
            clock: () -> Long = System::currentTimeMillis,
            laterScope: CoroutineScope? = null,
            laterWindowMs: Long = VoiceTurnOrchestrator.LATER_WINDOW_MS,
            laterWork: suspend (suspend () -> Unit) -> Unit = { it() },
            wakeListening: (VoiceOrigin) -> Boolean = { false },
            laterDeferMaxMs: Long = VoiceTurnOrchestrator.LATER_DEFER_MAX_MS,
            laterEnabled: () -> Boolean = { false },
            ownership: AudioOwnership = AudioOwnership(),
            laterSpeaker: (VoiceOrigin, Boolean) -> Unit = { _, _ -> },
            /** The routing session's model setup limits (production: 90 s in all, a switch only with 5 s left, 2 s of it for the readback; tests may shorten them). */
            runtimeSetupLimits: RuntimeSetupLimits = RuntimeSetupLimits(),
        ): Pair<HermesVoiceCore, HermesGatewayConnector> {
            val dashboard = HermesDashboardClient(endpoint, http, tokens, settings.profile.ifBlank { null })
            val connector = HermesGatewayConnector(dashboard, http)
            val core = HermesVoiceCore(dashboard, dashboard, GatewayConversationPort(settings.profile.ifBlank { null }, runtimeSetupLimits) { connector.connection() }, registry, settings,
                voiceListener, clock, laterScope, laterWindowMs, laterWork, wakeListening, laterDeferMaxMs, laterEnabled, ownership, laterSpeaker)
            return core to connector
        }
    }
}
