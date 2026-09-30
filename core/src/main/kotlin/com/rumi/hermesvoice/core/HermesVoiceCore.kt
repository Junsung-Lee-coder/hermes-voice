package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.HermesTokenStore
import com.rumi.hermesvoice.core.net.GatewayConversationPort
import com.rumi.hermesvoice.core.net.HermesConversationPort
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import com.rumi.hermesvoice.core.net.HermesGatewayConnector
import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.net.SubmittedTurn
import com.rumi.hermesvoice.core.sessions.AppSessionRepository
import com.rumi.hermesvoice.core.sessions.HermesSessionsApi
import com.rumi.hermesvoice.core.sessions.OwnedSessionRegistry
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.voice.RecipientEvent
import com.rumi.hermesvoice.core.voice.VoiceTurnConfig
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import com.rumi.hermesvoice.core.watchlink.WatchAckRegistry
import com.rumi.hermesvoice.core.watchlink.WatchTurnIntake
import java.io.IOException
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
) {
    val sessions = AppSessionRepository(sessionsApi, conversations, registry)
    val chat = ChatService(sessions)
    val orchestrator = VoiceTurnOrchestrator(speech, sessions.guardedPort(), config = ::voiceConfig, listener = voiceListener,
        recipientCreator = sessions.recipientCreator())
    val watchAcks = WatchAckRegistry()
    val watchIntake = WatchTurnIntake(orchestrator, watchAcks)

    /** Snapshotted per turn. The allowlist may be empty: the router can then ask for a new conversation. */
    private suspend fun voiceConfig(): VoiceTurnConfig {
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
        ): Pair<HermesVoiceCore, HermesGatewayConnector> {
            val dashboard = HermesDashboardClient(endpoint, http, tokens, settings.profile.ifBlank { null })
            val connector = HermesGatewayConnector(dashboard, http)
            val core = HermesVoiceCore(dashboard, dashboard, GatewayConversationPort(settings.profile.ifBlank { null }) { connector.connection() }, registry, settings,
                voiceListener)
            return core to connector
        }
    }
}
