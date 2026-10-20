package com.rumi.hermesvoice.core.watchlink

import com.rumi.hermesvoice.core.diag.DiagCause
import com.rumi.hermesvoice.core.diag.DiagClassify
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagModality
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.diag.DiagFail
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.DiagOrigin
import com.rumi.hermesvoice.core.HermesPlaybackBusyException
import com.rumi.hermesvoice.core.HermesPlaybackException
import com.rumi.hermesvoice.core.SpokenAudio
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.voice.AssembledRoute
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.voice.VoiceTurnStage
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** Data Layer sends to one Watch node (the one a turn came from). Android glue implements it. */
interface WatchTransport {
    /** The Data Layer node every send goes to; playback ACKs are accepted only from this node. */
    val nodeId: String
    suspend fun sendMessage(path: String, bytes: ByteArray)
    suspend fun sendChannel(path: String, bytes: ByteArray)
}

/**
 * Outstanding Watch playbacks, completed by `/hv/v1/played` messages from the node the audio was
 * sent to. Handing audio to the Data Layer is not playback: only the Watch's ACK completes a wait.
 * While a clip plays the Watch also reports its real player position ([PlayProgress]); an accepted, strictly
 * advancing position is the only thing that tells the waiting sink the playback is alive.
 */
class WatchAckRegistry {
    private class Waiter(val nodeId: String, val wait: WatchPlaybackWait, val played: (() -> Unit)?) {
        var position = -1L
        var duration = 0L
    }

    private val waiting = ConcurrentHashMap<String, Waiter>()

    /** [played] runs for the accepted successful ack only, before the waiting caller is resumed. */
    fun expect(turnId: String, sequence: Int, nodeId: String, played: (() -> Unit)? = null): CompletableDeferred<PlayedAck> =
        expectPlayback(turnId, sequence, nodeId, played).ack

    /** As [expect], also exposing the accepted-progress signal of this exact clip. */
    fun expectPlayback(turnId: String, sequence: Int, nodeId: String, played: (() -> Unit)? = null): WatchPlaybackWait {
        val wait = WatchPlaybackWait(CompletableDeferred(), Channel(Channel.CONFLATED))
        waiting[key(turnId, sequence)] = Waiter(nodeId, wait, played)
        return wait
    }

    fun forget(turnId: String, sequence: Int) {
        waiting.remove(key(turnId, sequence))
    }

    /**
     * Returns false for an ack nobody is waiting for (late, duplicate, or forged) or one from a node
     * other than the playback target: it is ignored, and the real wait stays open.
     */
    fun onPlayedMessage(sourceNodeId: String, bytes: ByteArray): Boolean {
        val ack = PlayedAck.decode(bytes) ?: return false
        val key = key(ack.turnId, ack.sequence)
        val waiter = waiting[key]?.takeIf { it.nodeId == sourceNodeId } ?: return false
        if (!waiting.remove(key, waiter)) return false
        // The Watch played it to the end: recorded here, on the delivering thread, before the waiter resumes.
        if (ack.ok) runCatching { waiter.played?.invoke() }
        return waiter.wait.ack.complete(ack)
    }

    /**
     * True when [bytes] is a real step forward of the clip somebody waits for, from the node it was sent to: the exact
     * turn and sequence, a duration equal to the one reported before, a position inside it and strictly beyond every
     * position accepted so far. False (ignored, the wait is not extended) for anything else.
     */
    fun onProgressMessage(sourceNodeId: String, bytes: ByteArray): Boolean {
        val progress = PlayProgress.decode(bytes) ?: return false
        val waiter = waiting[key(progress.turnId, progress.sequence)]?.takeIf { it.nodeId == sourceNodeId } ?: return false
        val accepted = synchronized(waiter) {
            when {
                waiter.wait.ack.isCompleted -> false
                waiter.duration != 0L && waiter.duration != progress.durationMs -> false
                progress.positionMs <= waiter.position -> false
                else -> {
                    waiter.duration = progress.durationMs
                    waiter.position = progress.positionMs
                    true
                }
            }
        }
        if (accepted) waiter.wait.progress.trySend(Unit)
        return accepted
    }

    private fun key(turnId: String, sequence: Int) = "$turnId#$sequence"
}

/** The wait for one clip: its final [ack], and a signal for each accepted step of the player's position. */
class WatchPlaybackWait internal constructor(val ack: CompletableDeferred<PlayedAck>, internal val progress: Channel<Unit>)

/**
 * Plays an utterance on one Watch node and returns only after that Watch confirms playback
 * finished. Cancellation (a newer turn took the speaker) tells the Watch to stop.
 *
 * How long it waits for the confirmation is not guessed from the audio's size. A Watch that reports its real player
 * position ([PlayProgress]) keeps the wait alive for as long as the position keeps advancing, however long the clip
 * is; the wait ends with the Watch's ACK, a refusal, a Stop, or when the position has not advanced for
 * [progressStallMs]. Until the first accepted progress (the clip is still being transferred and prepared, or the
 * Watch is an older one that reports none) the wait is [ackTimeoutMs] of this clip: a Watch that never reports
 * progress can therefore only play clips that finish within that time, which is a limit of such a Watch and not a claim
 * about any clip's real length. Nothing is held for the wait itself: the Phone's microphone, audio focus and CPU
 * hold belong to the orchestrator's per-clip work.
 */
class WatchPlaybackSink(
    private val transport: WatchTransport,
    private val acks: WatchAckRegistry,
    // Before any progress was accepted: 30 s plus the time a clip of this size takes at 16 kbps (2 bytes per ms), which is
    // the limit for a Watch without progress reports. With progress it is only the allowance for transfer and preparing.
    private val ackTimeoutMs: (SpokenAudio) -> Long = { audio -> 30_000L + audio.bytes.size / 2 },
    private val diag: DiagLog? = null,
    /** How long the player's position may stand still, after it advanced at least once, before playback is taken as dead. */
    private val progressStallMs: Long = PROGRESS_STALL_MS,
) : PlaybackSink {
    /** Actual paired destination of this existing route, never inferred from discovery order. */
    val nodeId: String get() = transport.nodeId
    override suspend fun play(audio: SpokenAudio, cue: PlaybackCue) = playConfirmed(audio, cue) {}

    override suspend fun deliverReplyAlert(alert: com.rumi.hermesvoice.core.notify.ReplyAlert): Boolean = try {
        transport.sendMessage(WatchLinkPaths.REPLY, ReplyAlertMessage(alert.identity, alert.storedSessionId).encode())
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        false
    }

    /** [finished] runs when the registry accepts this exact node's successful PLAYED ack, before this call resumes. */
    override suspend fun playConfirmed(audio: SpokenAudio, cue: PlaybackCue, finished: () -> Unit) {
        // The chunks of one long reply get distinct wire sequence numbers, so each chunk has its own exact ACK.
        val wireSequence = if (cue.part > 0) cue.sequence + cue.part * CHUNK_SEQUENCE_STRIDE else cue.sequence
        val waiter = acks.expectPlayback(cue.turnId, wireSequence, transport.nodeId, played = finished)
        try {
            try {
                // An own reply that may arrive while another request is recorded is refused by a recording Watch like a later one.
                transport.sendChannel(WatchLinkPaths.playPath(cue.turnId, wireSequence),
                    PlayRequest(cue.turnId, wireSequence, cue.role.name, audio.mimeType, audio.bytes, later = cue.later, deferrable = cue.deferrable).toFrame().encode())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Data Layer failures (e.g. ApiException) must surface as a playback failure, not escape the turn.
                diag?.recordFailure(DiagCode.PLAYBACK_FAILED, error, DiagOrigin.WATCH, cue.turnId, stage = DiagStage.TRANSFER, modality = DiagModality.VOICE)
                throw HermesPlaybackException("watch unreachable: ${error.javaClass.simpleName}")
            }
            val ack = awaitAck(waiter, ackTimeoutMs(audio)) ?: run {
                diag?.record(DiagCode.ACK_TIMEOUT, DiagOrigin.WATCH, cue.turnId, fail = DiagFail.TIMEOUT,
                    stage = DiagStage.PLAYBACK, cause = DiagCause.TIMEOUT, modality = DiagModality.VOICE)
                throw HermesPlaybackException("watch did not confirm playback of ${cue.role.name.lowercase()}")
            }
            if (!ack.ok && ack.error == PlayedAck.BUSY_RECORDING) {
                diag?.record(DiagCode.ACK_REFUSED, DiagOrigin.WATCH, cue.turnId, fail = DiagFail.BUSY,
                    stage = DiagStage.PLAYBACK, cause = DiagCause.LOCAL, modality = DiagModality.VOICE)
                throw HermesPlaybackBusyException("watch is recording")
            }
            if (!ack.ok) diag?.record(DiagCode.ACK_REFUSED, DiagOrigin.WATCH, cue.turnId, fail = DiagFail.UNKNOWN,
                stage = DiagStage.PLAYBACK, cause = DiagCause.UNKNOWN, modality = DiagModality.VOICE)
            if (!ack.ok) throw HermesPlaybackException("watch playback failed: ${ack.error.ifBlank { "unknown" }}")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { transport.sendMessage(WatchLinkPaths.STOP, TurnStateMessage(cue.turnId, "stopped", "", false).encode()) }
            }
            throw cancelled
        } finally {
            acks.forget(cue.turnId, wireSequence)
        }
    }

    /** The ACK, or null when nothing real happened for the allowed time: [firstMs] until the first accepted progress, then [progressStallMs] after each. */
    private suspend fun awaitAck(wait: WatchPlaybackWait, firstMs: Long): PlayedAck? {
        var window = firstMs
        while (true) {
            val step = withTimeoutOrNull(window) {
                select<Step> {
                    wait.ack.onAwait { Step.Done(it) }
                    wait.progress.onReceive { Step.Advanced }
                }
            } ?: return null
            if (step is Step.Done) return step.ack
            window = progressStallMs
        }
    }

    private sealed interface Step {
        class Done(val ack: PlayedAck) : Step

        object Advanced : Step
    }

    companion object {
        private const val CHUNK_SEQUENCE_STRIDE = 10_000

        /** No advance of the Watch's real player position for this long ends a playback that already started. */
        const val PROGRESS_STALL_MS = 30_000L
    }
}

/**
 * Receives Watch turn uploads and runs them through the SAME orchestrator as Phone turns. An
 * accepted upload makes this Watch the playback target (see
 * [com.rumi.hermesvoice.core.voice.PlaybackRoute]); stage updates are projected to its UI in call order.
 */
class WatchTurnIntake(
    private val orchestrator: VoiceTurnOrchestrator,
    private val acks: WatchAckRegistry,
    /**
     * Whether a delivered routed request should move the Watch's selection (Phone routing on AND the Watch option on),
     * read when the request is accepted and again at delivery. Off by default.
     */
    private val navigation: () -> Boolean = { false },
    private val diag: DiagLog? = null,
    /** Where a final "not sent" state is kept until the originating Watch acknowledges it; null: it is only sent once. */
    private val failures: WatchFailureOutbox? = null,
    /** The Phone's routing switch for an upload, read when it arrives (routing off uses the Watch's selection). Last, so it is the trailing lambda. */
    private val routing: (WatchTurnUpload) -> TurnRouting = { TurnRouting.Model },
) {
    /**
     * [bytes] is the channel content, or null when it could not be read or exceeded the frame
     * bound. Anything that is not a valid upload is rejected to the Watch before the orchestrator
     * sees it, so it is never accepted as a voice request and never moves the playback route.
     */
    private val origins = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Tells [transport]'s Watch, for [turnId], how the request ended when that is a failure (not sent, not delivered, or acceptance uncertain; nothing here sends it again). Kept in the outbox first, so a Watch that is away (or whose
     * message is lost) gets it when it asks ([WatchLinkPaths.STATE_SYNC]), until it acknowledges it ([WatchLinkPaths.STATE_ACK]).
     */
    private suspend fun retainFailure(turnId: String, transport: WatchTransport, detail: String) {
        // Kept (and retried by the outbox) before the live send; a refused write leaves it owed by this process only.
        runCatching { failures?.add(turnId, transport.nodeId, detail) }
        runCatching { transport.sendMessage(WatchLinkPaths.STATE, TurnStateMessage(turnId, "done", detail, true).encode()) }
    }

    /**
     * The Watch's Stop for one of its own requests ([WatchLinkPaths.CANCEL]): only the node that sent the request may stop it.
     * The request ends as stopped (the Watch is told), nothing more is sent or played for it.
     */
    fun cancelTurn(turnId: String, sourceNodeId: String): Boolean =
        origins[turnId] == sourceNodeId && orchestrator.stopTurn(turnId)

    /**
     * The Watch's "Play again" for the final reply of one of its own requests ([WatchLinkPaths.REPLAY]): only the node that sent the
     * request may ask (the orchestrator compares the node it recorded), and only for a Watch request whose reply ended without audio. Nothing is sent to Hermes again; the output
     * is decided afresh by the orchestrator. Returns when the replay has ended and the Watch was told its result (a result that cannot be
     * sent because this call was cancelled is still attempted). False: not accepted (unknown turn, other node, nothing to replay, already replaying).
     */
    suspend fun replayReply(request: ReplayRequest, sourceNodeId: String, transport: WatchTransport): Boolean {
        val result = CompletableDeferred<Pair<Boolean, String>>()
        val accepted = orchestrator.replayAudio(com.rumi.hermesvoice.core.notify.ReplyAlert.identityOf(request.turnId, "final"), sourceNodeId,
            WatchPlaybackSink(transport, acks, diag = diag)) { heard, detail -> result.complete(heard to detail) }
        if (!accepted) {
            runCatching { transport.sendMessage(WatchLinkPaths.STATE, TurnStateMessage(request.turnId, "done", REPLAY_UNAVAILABLE, true).encode()) }
            return false
        }
        val (heard, detail) = try {
            result.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { transport.sendMessage(WatchLinkPaths.STATE, replayState(request.turnId, false, "stopped")) } }
            throw cancelled
        }
        runCatching { transport.sendMessage(WatchLinkPaths.STATE, replayState(request.turnId, heard, detail)) }
        return true
    }

    private fun replayState(turnId: String, heard: Boolean, detail: String): ByteArray {
        val text = if (heard) REPLAYED else "Reply audio did not play: " +
            (com.rumi.hermesvoice.core.headset.HeadsetText.fixedNote(detail)?.removePrefix("not played: ") ?: "play it again, or read the text")
        return TurnStateMessage(turnId, "done", text, true, replay = !heard).encode()
    }

    suspend fun onTurnChannel(path: String, bytes: ByteArray?, transport: WatchTransport): VoiceTurnOutcome? {
        val upload = try {
            if (bytes == null) throw LinkProtocolException("recording transfer failed or too large")
            WatchTurnUpload.fromFrame(path, LinkFrame.decode(bytes))
        } catch (error: LinkProtocolException) {
            WatchLinkPaths.turnIdFromPath(path)?.let { turnId ->
                diag?.record(DiagCode.REQUEST_FAILED, DiagOrigin.WATCH, turnId,
                    fail = if (bytes == null) DiagFail.IO else DiagFail.PROTOCOL, stage = DiagStage.TRANSFER,
                    cause = if (bytes == null) DiagCause.DISCONNECT else DiagCause.LOCAL, modality = DiagModality.VOICE)
                val detail = if (bytes == null) "Recording transfer failed" else "Bad recording"
                runCatching { transport.sendMessage(WatchLinkPaths.STATE, TurnStateMessage(turnId, "rejected", detail, true).encode()) }
            }
            return null
        }
        val states = Channel<TurnStateMessage>(Channel.UNLIMITED)
        val navigations = Channel<WatchNavigation>(Channel.UNLIMITED)
        val listener = object : VoiceTurnListener {
            @Volatile private var navigationAtAccept = false

            /** The submission to Hermes started (or finished): from here "not sent" can no longer be claimed. */
            @Volatile var submitting = false

            // Told at once, before the turn waits its place in line: the Watch keeps its wake claim until this.
            override fun onAccepted(turnId: String, origin: VoiceOrigin) {
                navigationAtAccept = navigation()
                states.trySend(TurnStateMessage(turnId, "accepted", "", false))
            }
            override fun onStage(turnId: String, stage: VoiceTurnStage) {
                if (stage == VoiceTurnStage.DELIVERING || stage == VoiceTurnStage.RESPONDING || stage == VoiceTurnStage.AUDIO_RECOVERING) submitting = true
                states.trySend(TurnStateMessage(turnId, stage.name.lowercase(), "", false))
            }
            override fun onRouted(route: AssembledRoute) {
                states.trySend(TurnStateMessage(route.turnId, "routed", route.destination.alias, false))
            }

            // Only the destination's acceptance of the transcript moves the Watch (not the router's decision, the acknowledgement
            // or a failure), only for a routed request (routing off already used the Watch's own selection), and only while the
            // choice held when the request was accepted and still holds now. An upload without a generation (an older Watch) is not told.
            override fun onDelivered(route: AssembledRoute) {
                submitting = true
                val generation = upload.selectionGeneration ?: return
                if (route.direct || !navigationAtAccept || !navigation()) return
                val target = route.destination.storedSessionId
                if (!DestinationAllowlist.isValidSessionId(target)) return
                navigations.trySend(WatchNavigation(route.turnId, target, generation, route.created))
            }
        }
        origins[upload.turnId] = transport.nodeId
        return try {
            relay(upload, transport, states, navigations, listener)
        } catch (cancelled: CancellationException) {
            // The turn was stopped on the Phone (its background relay was stopped): the Watch is told, so it does not wait on.
            withContext(NonCancellable) {
                if (listener.submitting) {
                    runCatching {
                        transport.sendMessage(WatchLinkPaths.STATE, TurnStateMessage(upload.turnId, "done", STOPPED_ON_PHONE, true).encode())
                    }
                } else retainFailure(upload.turnId, transport, NOT_SENT_STOPPED)
            }
            throw cancelled
        } catch (error: Exception) {
            // The request ended on an unexpected error. Before the submission started it was not sent, and the Watch is told so.
            withContext(NonCancellable) { if (!listener.submitting) retainFailure(upload.turnId, transport, NOT_SENT_FAILED) }
            throw error
        } finally {
            origins.remove(upload.turnId)
        }
    }

    private suspend fun relay(upload: WatchTurnUpload, transport: WatchTransport, states: Channel<TurnStateMessage>,
                              navigations: Channel<WatchNavigation>, listener: VoiceTurnListener): VoiceTurnOutcome =
        coroutineScope {
            val forwarder = launch {
                for (state in states) runCatching { transport.sendMessage(WatchLinkPaths.STATE, state.encode()) }
            }
            // Before the terminal state: the Watch's own refresh on "done" then already sees the selected conversation.
            val navigator = launch {
                for (navigation in navigations) runCatching { transport.sendMessage(WatchLinkPaths.NAVIGATE, navigation.encode()) }
            }
            val outcome = try {
                orchestrator.run(VoiceTurnRequest(upload.turnId, VoiceOrigin.WATCH, upload.audio, upload.mimeType,
                    WatchPlaybackSink(transport, acks, diag = diag), listener, recognizedText = upload.recognizedText,
                    wakeTurn = upload.trigger == TurnTrigger.WAKE_PHRASE, wakeClaimId = upload.wakeClaimId,
                    originNodeId = transport.nodeId, routing = routing(upload)))
            } finally {
                states.close()
                navigations.close()
            }
            forwarder.join()
            navigator.join()
            val text = VoiceOutcomeText.describe(outcome)
            if (outcome is VoiceTurnOutcome.NotDelivered || outcome is VoiceTurnOutcome.NotAdmitted || outcome is VoiceTurnOutcome.RoutingRejected) {
                retainFailure(upload.turnId, transport, text)
            } else runCatching {
                transport.sendMessage(WatchLinkPaths.STATE,
                    TurnStateMessage(upload.turnId, "done", text, true,
                        replay = outcome is VoiceTurnOutcome.DeliveredResponseFailed).encode())
            }
            outcome
        }

    companion object {
        const val STOPPED_ON_PHONE = "Stopped on the phone"
        const val NOT_SENT_STOPPED = "Not sent: stopped on the phone before it was sent"
        const val NOT_SENT_FAILED = "Not sent: the phone could not finish the request. Say it again"
        const val REPLAYED = "Reply played"
        const val REPLAY_UNAVAILABLE = "That reply can't be played again now. Read it on the phone"
    }
}

/** Short user-facing text for a turn outcome (Phone status line and Watch terminal state). */
object VoiceOutcomeText {
    /** The start of every status that says the request never left the Phone. */
    const val NOT_SENT = "Not sent"

    fun describe(outcome: VoiceTurnOutcome): String = when (outcome) {
        is VoiceTurnOutcome.Completed ->
            "Delivered to ${outcome.route.destination.alias}" + if (outcome.route.created) " (new conversation)" else ""
        VoiceTurnOutcome.Stopped -> "Stopped"
        is VoiceTurnOutcome.DeliveredUnattributed ->
            "Delivered to ${outcome.route.destination.alias}; reply not spoken (destination busy)"
        is VoiceTurnOutcome.DeliveredResponseFailed ->
            "Delivered to ${outcome.route.destination.alias}; reply playback failed" +
                (outcome.note?.let { ": ${it.removePrefix("not played: ")}" }
                    ?: com.rumi.hermesvoice.core.diag.DiagLabels.detail(outcome.diagStage, outcome.diagCause)?.let { " ($it)" } ?: "")
        is VoiceTurnOutcome.RoutingRejected -> "Not delivered: routing rejected (${outcome.reason})"
        is VoiceTurnOutcome.NotDelivered -> {
            // Before the submission started nothing reached Hermes: that is "not sent". From the submission on, whether it arrived is not known here.
            val head = if (outcome.stage == VoiceTurnStage.DELIVERING) "Not delivered" else NOT_SENT
            when {
                outcome.possiblySent -> "Acceptance uncertain: check the conversation. Do not retry this request"
                outcome.authRequired -> "$head: sign in on the phone"
                outcome.reason == TurnRouting.TARGET_UNAVAILABLE ->
                    "Not sent: routing is off and the selected conversation isn't available. Select another"
                outcome.diagCause == com.rumi.hermesvoice.core.diag.DiagCause.STT_UNAVAILABLE ->
                    "$head: the speech server stayed unavailable; the recording was discarded. Say it again"
                else -> com.rumi.hermesvoice.core.diag.DiagLabels.detail(outcome.diagStage, outcome.diagCause)
                    ?.let { "$head: $it" } ?: "$head: ${outcome.reason.take(120)}"
            }
        }
        VoiceTurnOutcome.NoSpeech -> "No speech detected"
        is VoiceTurnOutcome.Duplicate -> "Duplicate turn ignored"
        is VoiceTurnOutcome.NotAdmitted ->
            if (outcome.reason == TurnRouting.NO_TARGET) "Not sent: routing is off. Open a conversation first"
            else if (outcome.reason == "wake_claim_missing") "Not sent: the wake settings changed while you spoke. Say it again"
            else if (outcome.reason.startsWith("wake_claim")) "Not sent: the wake phrase was answered elsewhere or timed out. Say it again"
            else "Not sent: ${outcome.reason.take(120)}"
    }
}
