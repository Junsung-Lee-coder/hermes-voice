package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.HermesTokenStore
import com.rumi.hermesvoice.core.net.AcceptedTurnReconciler
import com.rumi.hermesvoice.core.net.AcceptedTurnStore
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
import com.rumi.hermesvoice.core.watchlink.WatchFailureOutbox
import com.rumi.hermesvoice.core.watchlink.WatchTurnIntake
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient

/** Result of a text-chat send; the transcript itself is re-read from history after the turn. */
sealed class ChatSendResult {
    data class Replied(val text: String, val status: String?) : ChatSendResult()

    /** Hermes accepted the message but its reply could not be attributed (busy session); refresh history later. */
    data class Accepted(val submitStatus: String) : ChatSendResult()

    /**
     * [reason] is the legacy text the UI already shows. [stage], [cause] and [acceptanceObserved] are what the diagnostics observed
     * (fixed words only): [acceptanceObserved] is true only when Hermes was seen to accept the message before the failure.
     */
    data class Failed(
        val reason: String,
        val authRequired: Boolean = false,
        val stage: com.rumi.hermesvoice.core.diag.DiagStage? = null,
        val cause: com.rumi.hermesvoice.core.diag.DiagCause? = null,
        val acceptanceObserved: Boolean = false,
        /** Another Hermes process holds the session: the gateway refused before accepting, so nothing was sent. */
        val sessionInUse: Boolean = false,
        /** Typed delivery truth, never inferred from wording. Only NOT_SENT can offer same-request Retry. */
        val delivery: SendDelivery = if (acceptanceObserved) SendDelivery.ACCEPTED else SendDelivery.NOT_SENT,
    ) : ChatSendResult()
}

/** Text chat into an app-owned conversation. Replies are shown; they are spoken only through a connected headset with "Use headset" on. */
class ChatService(
    private val sessions: AppSessionRepository,
    private val replyTimeoutMs: Long = 15 * 60_000L,
    /** Told of each final text answer received (never spoken, so always unheard); one send, one identity. */
    private val finalReply: (com.rumi.hermesvoice.core.notify.FinalReply) -> Unit = {},
    private val newSendId: () -> String = { java.util.UUID.randomUUID().toString() },
    /**
     * The Phone's "Use headset": speaks a typed answer through the connected headset (see [VoiceTurnOrchestrator.speakTypedFinal]).
     * True when it took the answer, which it then reports through its callback exactly once; false (or null): the answer is only shown.
     */
    private val typedFinal: ((turnId: String, storedSessionId: String, text: String, done: (heard: Boolean, cancelled: Boolean, detail: String) -> Unit) -> Boolean)? = null,
    /** The headset is the private output now: a text answer's alert is then visual only. */
    private val privateOutput: () -> Boolean = { false },
    /** Where the transient "Hermes is working" slot of each attributable request is published; null: none. */
    private val activity: com.rumi.hermesvoice.core.activity.ActivityFeed? = null,
    /** The typed diagnostic events of each send attempt (a fresh opaque reference per attempt); null: none recorded. */
    private val diag: com.rumi.hermesvoice.core.diag.DiagLog? = null,
    /** The accepted sends whose reply is still owed, kept so a reply that finishes after a lost connection or a restart is still delivered once; null: none. */
    private val accepted: com.rumi.hermesvoice.core.net.AcceptedTurnStore? = null,
    /** Hands an answer or notice to the arrival alert and reports whether it is taken care of; null: [finalReply] is used and counts as handled unless it throws. */
    private val deliver: com.rumi.hermesvoice.core.notify.ReplyDelivery? = null,
    private val namespace: () -> String? = { null },
) {
    private fun handOff(reply: com.rumi.hermesvoice.core.notify.FinalReply, handled: () -> Unit) {
        val delivery = deliver
        runCatching {
            if (delivery == null) {
                finalReply(reply)
                handled()
            } else {
                delivery(reply) { ok -> if (ok) handled() }
            }
        }
    }

    /** One send attempt as the diagnostics see it: classifies by type, never reads a message, never throws. */
    private class Probe(
        val log: com.rumi.hermesvoice.core.diag.DiagLog?,
        val modality: com.rumi.hermesvoice.core.diag.DiagModality,
        val trace: com.rumi.hermesvoice.core.diag.DiagTrace,
    ) {
        val ref: String? = if (log != null) com.rumi.hermesvoice.core.diag.DiagPseudonyms.newRef() else null
        private val started = System.nanoTime()
        private val conn = log?.connectivityNow() ?: com.rumi.hermesvoice.core.diag.DiagConn.UNKNOWN
        fun elapsedMs(): Long = (System.nanoTime() - started) / 1_000_000

        fun begin(attachments: Int) {
            log?.record(com.rumi.hermesvoice.core.diag.DiagCode.REQUEST_STARTED, com.rumi.hermesvoice.core.diag.DiagOrigin.PHONE, ref,
                n = attachments.takeIf { it > 0 }, modality = modality, opaqueRef = true)
        }

        fun accepted() {
            trace.stage = com.rumi.hermesvoice.core.diag.DiagStage.WAIT
            log?.record(com.rumi.hermesvoice.core.diag.DiagCode.REQUEST_ACCEPTED, com.rumi.hermesvoice.core.diag.DiagOrigin.PHONE, ref,
                ms = elapsedMs(), stage = com.rumi.hermesvoice.core.diag.DiagStage.SUBMIT, modality = modality, opaqueRef = true)
        }

        fun completed() {
            log?.record(com.rumi.hermesvoice.core.diag.DiagCode.REQUEST_COMPLETED, com.rumi.hermesvoice.core.diag.DiagOrigin.PHONE, ref,
                ms = elapsedMs(), modality = modality, opaqueRef = true)
        }

        /** The failure result for [error] seen at the stage the request had reached; recorded once. */
        fun failed(error: Throwable, reason: String, acceptanceObserved: Boolean): ChatSendResult.Failed {
            val classified = com.rumi.hermesvoice.core.diag.DiagClassify.of(error, conn)
            val stage = trace.stage
            log?.record(com.rumi.hermesvoice.core.diag.DiagCode.REQUEST_FAILED, com.rumi.hermesvoice.core.diag.DiagOrigin.PHONE, ref,
                ms = elapsedMs(), fail = classified.fail, stage = stage, cause = classified.cause, modality = modality,
                status = classified.status, opaqueRef = true)
            return ChatSendResult.Failed(reason, classified.fail == com.rumi.hermesvoice.core.diag.DiagFail.AUTH && error is HermesAuthRequiredException,
                stage, classified.cause, acceptanceObserved, sessionInUse = error is com.rumi.hermesvoice.core.SessionInUseException,
                delivery = if (acceptanceObserved) SendDelivery.ACCEPTED else if (error is HermesPromptUncertainException) SendDelivery.POSSIBLY_SENT else SendDelivery.NOT_SENT)
        }

        /** A cancellation: the user's Stop when this coroutine is cancelled, otherwise an internal timeout that is a failure. */
        suspend fun interrupted(error: kotlinx.coroutines.CancellationException) {
            if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                failed(error, "", false)
            } else {
                log?.record(com.rumi.hermesvoice.core.diag.DiagCode.REQUEST_STOPPED, com.rumi.hermesvoice.core.diag.DiagOrigin.PHONE, ref,
                    ms = elapsedMs(), fail = com.rumi.hermesvoice.core.diag.DiagFail.CANCELLED, stage = trace.stage,
                    cause = com.rumi.hermesvoice.core.diag.DiagCause.CANCELLED, modality = modality, opaqueRef = true)
            }
        }
    }

    /** [onAccepted]: Hermes has the message (the submit succeeded); called once, before any wait for the reply. */
    suspend fun send(storedSessionId: String, text: String, attachments: List<OutgoingAttachment> = emptyList(), onAccepted: () -> Unit = {}): ChatSendResult {
        val trace = com.rumi.hermesvoice.core.diag.DiagTrace()
        val probe = Probe(diag, if (attachments.isEmpty()) com.rumi.hermesvoice.core.diag.DiagModality.TEXT else com.rumi.hermesvoice.core.diag.DiagModality.ATTACHMENT, trace)
        probe.begin(attachments.size)
        return kotlinx.coroutines.withContext(trace) { sendTraced(probe, storedSessionId, text, attachments, onAccepted) }
    }

    private suspend fun sendTraced(probe: Probe, storedSessionId: String, text: String, attachments: List<OutgoingAttachment>, onAccepted: () -> Unit): ChatSendResult {
        val sendNamespace = namespace()
        // One identity for this send, fixed before anything is submitted: the live reply and a recovered one are the same decision.
        val sendId = "chat-${newSendId()}"
        val turn: SubmittedTurn = try {
            sessions.sendMessage(storedSessionId, text, attachments)
        } catch (error: HermesPromptUncertainException) {
            val reason = error.message!!
            handOff(com.rumi.hermesvoice.core.notify.FinalReply(com.rumi.hermesvoice.core.notify.ReplyAlert.identityOf(sendId, "notice"), storedSessionId,
                VoiceOrigin.PHONE, heard = false, cancelled = false, source = com.rumi.hermesvoice.core.notify.FinalReplySource.TEXT,
                text = reason, quiet = runCatching { privateOutput() }.getOrDefault(false), noticeTitle = AcceptedTurnReconciler.NOTICE_TITLE, namespace = sendNamespace)) {}
            return probe.failed(error, reason, false)
        } catch (error: HermesAuthRequiredException) {
            return probe.failed(error, error.message ?: "sign-in required", false).copy(authRequired = true)
        } catch (error: HermesException) {
            return probe.failed(error, error.message ?: error.javaClass.simpleName, false)
        } catch (error: IllegalArgumentException) {
            return probe.failed(error, error.message ?: "invalid message", false)
        } catch (error: IOException) {
            return probe.failed(error, "network: ${error.javaClass.simpleName}", false)
        } catch (error: kotlinx.coroutines.CancellationException) {
            probe.interrupted(error)
            throw error
        } catch (error: RuntimeException) {
            probe.failed(error, "", false)
            throw error
        }
        probe.accepted()
        runCatching { onAccepted() }
        if (!turn.attributable) {
            turn.release()
            if (turn.submitStatus == "queued") {
                val error = HermesQueuedAttributionException()
                handOff(com.rumi.hermesvoice.core.notify.FinalReply(com.rumi.hermesvoice.core.notify.ReplyAlert.identityOf(sendId, "notice"), storedSessionId,
                    VoiceOrigin.PHONE, heard = false, cancelled = false, source = com.rumi.hermesvoice.core.notify.FinalReplySource.TEXT,
                    text = error.message!!, quiet = runCatching { privateOutput() }.getOrDefault(false), noticeTitle = AcceptedTurnReconciler.NOTICE_TITLE, namespace = sendNamespace)) {}
                return probe.failed(error, error.message!!, true)
            }
            return ChatSendResult.Accepted(turn.submitStatus)
        }
        turn.acceptance?.let { acceptance ->
            val saved = accepted?.begin(com.rumi.hermesvoice.core.net.AcceptedTurn(sendId, com.rumi.hermesvoice.core.net.AcceptedKind.TEXT, storedSessionId, acceptance, System.currentTimeMillis(), sendNamespace))
            if (saved == false) {
                // The note could not be saved: the live wait below still delivers the reply, but a closed app could not recover it. Never sent again.
                handOff(com.rumi.hermesvoice.core.notify.FinalReply(com.rumi.hermesvoice.core.notify.ReplyAlert.identityOf(sendId, "limit"), storedSessionId,
                    VoiceOrigin.PHONE, heard = false, cancelled = false, source = com.rumi.hermesvoice.core.notify.FinalReplySource.TEXT,
                    text = com.rumi.hermesvoice.core.notify.ReplyAlertContent.NOTICE_LIMIT_TEXT, quiet = true,
                    noticeTitle = com.rumi.hermesvoice.core.notify.ReplyAlertContent.NOTICE_LIMIT_TITLE, namespace = sendNamespace)) {}
            }
        }
        var reply: RecipientEvent.Complete? = null
        var replied = false
        val slot = "chat-activity-${java.util.UUID.randomUUID()}"
        activity?.begin(slot, storedSessionId)
        return try {
            turn.collect(replyTimeoutMs) { event ->
                probe.trace.stage = com.rumi.hermesvoice.core.diag.DiagStage.RECEIVE
                when (event) {
                    is RecipientEvent.Complete -> reply = event
                    is RecipientEvent.Activity -> activity?.update(slot, event.label)
                    is RecipientEvent.Interim -> com.rumi.hermesvoice.core.activity.ActivityLabels.interim(event.text)?.let { activity?.update(slot, it) }
                }
            }
            reply?.let { received ->
                if (received.text.isNotBlank() && (received.status == null || received.status == "complete")) {
                    replied = true
                    val identity = com.rumi.hermesvoice.core.notify.ReplyAlert.identityOf(sendId, "reply")
                    val quiet = runCatching { privateOutput() }.getOrDefault(false)
                    fun report(heard: Boolean, cancelled: Boolean) {
                        handOff(com.rumi.hermesvoice.core.notify.FinalReply(identity, storedSessionId,
                            VoiceOrigin.PHONE, heard = heard, cancelled = cancelled, source = com.rumi.hermesvoice.core.notify.FinalReplySource.TEXT, text = received.text, quiet = quiet,
                            canonicalRowId = received.finalRowId, namespace = sendNamespace)) {
                            accepted?.settle(sendId)
                        }
                    }
                    // Spoken on the headset only when the Phone took it (setting on, headset connected); it then reports what became of the audio.
                    val spoken = runCatching { typedFinal?.invoke(sendId, storedSessionId, received.text) { heard, cancelled, _ -> report(heard, cancelled) } }.getOrNull() == true
                    if (!spoken) report(heard = false, cancelled = false)
                } else {
                    handOff(com.rumi.hermesvoice.core.notify.FinalReply(com.rumi.hermesvoice.core.notify.ReplyAlert.identityOf(sendId, "notice"), storedSessionId,
                        VoiceOrigin.PHONE, heard = false, cancelled = false, source = com.rumi.hermesvoice.core.notify.FinalReplySource.TEXT,
                        text = com.rumi.hermesvoice.core.net.AcceptedTurnReconciler.NOTICE_NO_ANSWER, quiet = runCatching { privateOutput() }.getOrDefault(false),
                        noticeTitle = com.rumi.hermesvoice.core.net.AcceptedTurnReconciler.NOTICE_TITLE, namespace = sendNamespace)) {
                        accepted?.settle(sendId)
                    }
                }
                probe.completed()
                ChatSendResult.Replied(received.text, received.status)
            } ?: ChatSendResult.Accepted(turn.submitStatus)
        } catch (error: HermesException) {
            if (!error.replyMayStillArrive()) {
                // The request was accepted, even when its live reader disappeared. Owe the same
                // exact-send notice as reconstruction; refusal must leave the record recoverable.
                handOff(com.rumi.hermesvoice.core.notify.FinalReply(com.rumi.hermesvoice.core.notify.ReplyAlert.identityOf(sendId, "notice"), storedSessionId,
                    VoiceOrigin.PHONE, heard = false, cancelled = false, source = com.rumi.hermesvoice.core.notify.FinalReplySource.TEXT,
                    text = if (error is com.rumi.hermesvoice.core.HermesTurnFailedException) AcceptedTurnReconciler.NOTICE_FAILED else AcceptedTurnReconciler.NOTICE_NOT_FOUND,
                    quiet = runCatching { privateOutput() }.getOrDefault(false), noticeTitle = AcceptedTurnReconciler.NOTICE_TITLE, namespace = sendNamespace)) {
                    accepted?.settle(sendId)
                }
            }
            probe.failed(error, "sent, but the reply failed: ${error.message}", true)
        } catch (error: IOException) {
            probe.failed(error, "sent, but the connection dropped: ${error.javaClass.simpleName}", true)
        } catch (error: kotlinx.coroutines.CancellationException) {
            probe.interrupted(error)
            throw error
        } catch (error: RuntimeException) {
            probe.failed(error, "", true)
            throw error
        } finally {
            // Text replies are shown and never followed; only "Use headset" speaks one (above).
            turn.release()
            accepted?.idle(sendId)
            activity?.end(slot, replied)
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
    /**
     * A FIXED follow window for every turn (tests only). Null, as in production: each turn about to be followed reads the
     * Phone's [AppSettings.laterReplyWindowMillis] when its follow starts.
     */
    laterWindowMs: Long? = null,
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
    /** The Phone's typed diagnostic events (user-shared only; see [com.rumi.hermesvoice.core.diag.DiagExporter]). Null: none recorded. */
    diag: com.rumi.hermesvoice.core.diag.DiagLog? = null,
    /** The arrival alert for an unspoken final answer (see [com.rumi.hermesvoice.core.notify.ReplyAlerts]); null: none. */
    replyAlerts: com.rumi.hermesvoice.core.notify.ReplyAlerts? = null,
    /** The Phone's "Use headset" over the live audio endpoints; null: none (no headset behavior at all). */
    headset: com.rumi.hermesvoice.core.headset.HeadsetPolicy? = null,
    /** The Phone's own speaker sink, used for typed chat answers read through the headset (they never go to the Watch). */
    phoneSink: com.rumi.hermesvoice.core.voice.PlaybackSink? = null,
    /** How a recording is transcribed again while the speech server is temporarily unavailable (see [com.rumi.hermesvoice.core.voice.SttRecoveryPolicy]). */
    sttPolicy: com.rumi.hermesvoice.core.voice.SttRecoveryPolicy = com.rumi.hermesvoice.core.voice.SttRecoveryPolicy(),
    /** The clock that counts the recovery budget (real time; the Phone passes elapsed-realtime so a sleeping device still counts). */
    recoveryClock: (() -> Long)? = null,
    /** How the audio of a final reply is tried again after a transient synthesis failure (see [com.rumi.hermesvoice.core.voice.AudioRecoveryPolicy]). */
    audioPolicy: com.rumi.hermesvoice.core.voice.AudioRecoveryPolicy = com.rumi.hermesvoice.core.voice.AudioRecoveryPolicy(),
    /** Device-local keeping of replies that ended without audio, so a manual Replay survives a process end; null: in memory only. */
    unplayedStore: com.rumi.hermesvoice.core.voice.UnplayedReplyStore? = null,
    /** The final "not sent" states owed to a Watch until it acknowledges them (see [WatchFailureOutbox]); null: sent once only. */
    watchFailures: WatchFailureOutbox? = null,
    /** The accepted prompts whose reply is still owed (see [AcceptedTurnStore]); null: a lost connection ends the wait as before. */
    acceptedTurns: AcceptedTurnStore? = null,
    replyNamespace: () -> String? = { null },
    onConversationCreated: suspend (String) -> Unit = {},
    onConversationVerified: suspend (String,Double) -> Unit = { _,_ -> },
    replyGeneration: Long? = null,
) {
    private val envelope: (com.rumi.hermesvoice.core.notify.FinalReply) -> com.rumi.hermesvoice.core.notify.FinalReply = { r ->
        if(replyGeneration==null) r else com.rumi.hermesvoice.core.notify.FinalReply(r.identity,r.storedSessionId,r.target,r.heard,r.cancelled,r.source,r.sink,r.text,r.quiet,r.noticeTitle,r.canonicalRowId,r.namespace,replyGeneration)
    }
    private val finalReply: (com.rumi.hermesvoice.core.notify.FinalReply) -> Unit = { reply -> replyAlerts?.onFinalReply(envelope(reply)) }
    private val delivery: com.rumi.hermesvoice.core.notify.ReplyDelivery? = replyAlerts?.let { alerts -> { reply, done -> alerts.deliverAsync(envelope(reply), done) } }
    val sessions = AppSessionRepository(sessionsApi, conversations, registry, routerRuntime = routerRuntime,
        diagnostic = { line -> voiceListener.onDiagnostic("", "router", line) }).also { it.onConversationCreated = onConversationCreated;it.onConversationVerified = onConversationVerified }
    val chat: ChatService
    /** Arbitrates the wake phrase when both devices listen: one spoken wake episode, one admitted device. */
    val wakeAdmission = WakeAdmission(clock, settings::watchSettings,
        epochs = object : WakeEpochStore {
            override fun load(): Long = settings.wakeEpoch
            override fun save(epoch: Long) { settings.wakeEpoch = epoch }
        },
        onAnswered = { episode -> onWakeEpisode(episode) }).also { settings.onWatchSettingsSaved = it::settingsChanged }

    /** Told of each admitted wake request in Both (see [WakeAdmission]); the Phone app publishes it to the Watch. */
    @Volatile var onWakeEpisode: (WakeEpisode) -> Unit = {}
    /** The transient activity slots of in-flight requests (typed and voice); the Phone screen observes it, nothing is stored. */
    val activity = com.rumi.hermesvoice.core.activity.ActivityFeed()
    val orchestrator = VoiceTurnOrchestrator(speech, sessions.guardedPort(), config = ::voiceConfig, listener = voiceListener,
        recipientCreator = sessions.recipientCreator(),
        admission = { request -> wakeAdmission.admitTurn(request.wakeTurn, request.origin, request.originNodeId, request.wakeClaimId) },
        laterScope = laterScope, laterWindowMs = laterWindowMs ?: VoiceTurnOrchestrator.LATER_WINDOW_MS,
        laterWindowProvider = if (laterWindowMs != null) null else ({ settings.laterReplyWindowMillis }), laterWork = laterWork,
        laterEnabled = laterEnabled, wakeListening = wakeListening, laterDeferMaxMs = laterDeferMaxMs,
        ownership = ownership, laterSpeaker = laterSpeaker, finalReply = finalReply, headset = headset, privateSink = phoneSink, activity = activity,
        sttPolicy = sttPolicy, recoveryClock = recoveryClock ?: { System.nanoTime() / 1_000_000 }, audioPolicy = audioPolicy, unplayedStore = unplayedStore,
        acceptedTurns = acceptedTurns, deliver = delivery, replyNamespace = replyNamespace)
    init {
        chat = ChatService(sessions, finalReply = finalReply, deliver = delivery,
            typedFinal = if (phoneSink == null) null else { turnId, storedSessionId, text, done -> orchestrator.speakTypedFinal(turnId, phoneSink, text, storedSessionId, done) },
            privateOutput = { headset?.output() != null }, activity = activity, diag = diag, accepted = acceptedTurns, namespace = replyNamespace)
    }

    private val reconcileDelivery: (suspend (com.rumi.hermesvoice.core.notify.FinalReply) -> Boolean)? =
        replyAlerts?.let { alerts ->
            val handler: suspend (com.rumi.hermesvoice.core.notify.FinalReply) -> Boolean = { reply -> alerts.deliver(envelope(reply)) }
            handler
        }

    private val reconciler: AcceptedTurnReconciler? = acceptedTurns?.let { turns ->
        laterScope?.let { scope -> AcceptedTurnReconciler(turns, sessions.guardedPort(), finalReply, { headset?.output() != null }, scope, deliver = reconcileDelivery, namespace = replyNamespace) }
    }

    /**
     * Delivers the replies of accepted prompts that nobody in this process is waiting for any more (the app was restarted, or the
     * connection was lost): started once with the core, and again by the app whenever it comes to the foreground. Never sends a prompt.
     */
    fun reconcileAccepted() {
        reconciler?.reconcile()
    }
    val watchAcks = WatchAckRegistry()
    /** A Watch turn's routing is the Phone's switch when its upload arrives; routing off uses the Watch's selection. */
    val watchIntake = WatchTurnIntake(orchestrator, watchAcks,
        routing = { upload -> TurnRouting.of(settings.routingEnabled, upload.target) },
        navigation = { settings.watchAutoNavigationApplies }, diag = diag, failures = watchFailures)

    init {
        reconcileAccepted()
    }

    /**
     * Snapshotted per turn. The allowlist may be empty: the router can then ask for a new
     * conversation. A routing-off turn never touches the router session (not even to create it).
     */
    private suspend fun voiceConfig(routing: TurnRouting): VoiceTurnConfig {
        if (routing is TurnRouting.Direct) return VoiceTurnConfig(null, sessions.synchronizedAllowlist(null), settings.playback())
        val router = sessions.ensureRoutingSession()
        return VoiceTurnConfig(router.storedSessionId, sessions.synchronizedAllowlist(router.storedSessionId), settings.playback())
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
            laterWindowMs: Long? = null,
            laterWork: suspend (suspend () -> Unit) -> Unit = { it() },
            wakeListening: (VoiceOrigin) -> Boolean = { false },
            laterDeferMaxMs: Long = VoiceTurnOrchestrator.LATER_DEFER_MAX_MS,
            laterEnabled: () -> Boolean = { false },
            ownership: AudioOwnership = AudioOwnership(),
            laterSpeaker: (VoiceOrigin, Boolean) -> Unit = { _, _ -> },
            /** The routing session's model setup limits (production: 90 s in all, a switch only with 5 s left, 2 s of it for the readback; tests may shorten them). */
            runtimeSetupLimits: RuntimeSetupLimits = RuntimeSetupLimits(),
            diag: com.rumi.hermesvoice.core.diag.DiagLog? = null,
            replyAlerts: com.rumi.hermesvoice.core.notify.ReplyAlerts? = null,
            headset: com.rumi.hermesvoice.core.headset.HeadsetPolicy? = null,
            phoneSink: com.rumi.hermesvoice.core.voice.PlaybackSink? = null,
            sttPolicy: com.rumi.hermesvoice.core.voice.SttRecoveryPolicy = com.rumi.hermesvoice.core.voice.SttRecoveryPolicy(),
            recoveryClock: (() -> Long)? = null,
            audioPolicy: com.rumi.hermesvoice.core.voice.AudioRecoveryPolicy = com.rumi.hermesvoice.core.voice.AudioRecoveryPolicy(),
            unplayedStore: com.rumi.hermesvoice.core.voice.UnplayedReplyStore? = null,
            watchFailures: WatchFailureOutbox? = null,
            replyNamespace: () -> String? = { null },
            onConversationCreated: suspend (String) -> Unit = {},
            onConversationVerified: suspend (String,Double) -> Unit = { _,_ -> },
            replyGeneration: Long? = null,
        ): Pair<HermesVoiceCore, HermesGatewayConnector> {
            val dashboard = HermesDashboardClient(endpoint, http, tokens, settings.profile.ifBlank { null })
            val connector = HermesGatewayConnector(dashboard, http)
            val core = HermesVoiceCore(dashboard, dashboard, GatewayConversationPort(settings.profile.ifBlank { null }, runtimeSetupLimits) { connector.connection() }, registry, settings,
                voiceListener, clock, laterScope, laterWindowMs, laterWork, wakeListening, laterDeferMaxMs, laterEnabled, ownership, laterSpeaker, diag = diag, replyAlerts = replyAlerts, headset = headset, phoneSink = phoneSink,
                sttPolicy = sttPolicy, recoveryClock = recoveryClock, audioPolicy = audioPolicy, unplayedStore = unplayedStore,
                watchFailures = watchFailures, acceptedTurns = AcceptedTurnStore(registry.store, clock), replyNamespace = replyNamespace, onConversationCreated = onConversationCreated,
                onConversationVerified = onConversationVerified, replyGeneration = replyGeneration)
            return core to connector
        }
    }
}
