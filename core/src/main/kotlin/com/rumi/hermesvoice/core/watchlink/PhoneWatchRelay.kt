package com.rumi.hermesvoice.core.watchlink

import com.rumi.hermesvoice.core.HermesPlaybackException
import com.rumi.hermesvoice.core.SpokenAudio
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.voice.AssembledRoute
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.voice.VoiceTurnStage
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
 */
class WatchAckRegistry {
    private class Waiter(val nodeId: String, val ack: CompletableDeferred<PlayedAck>)

    private val waiting = ConcurrentHashMap<String, Waiter>()

    fun expect(turnId: String, sequence: Int, nodeId: String): CompletableDeferred<PlayedAck> =
        CompletableDeferred<PlayedAck>().also { waiting[key(turnId, sequence)] = Waiter(nodeId, it) }

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
        return waiting.remove(key, waiter) && waiter.ack.complete(ack)
    }

    private fun key(turnId: String, sequence: Int) = "$turnId#$sequence"
}

/**
 * Plays an utterance on one Watch node and returns only after that Watch confirms playback
 * finished. Cancellation (a newer turn took the speaker) tells the Watch to stop.
 */
class WatchPlaybackSink(
    private val transport: WatchTransport,
    private val acks: WatchAckRegistry,
    private val ackTimeoutMs: (SpokenAudio) -> Long = { audio -> 30_000L + audio.bytes.size / 4 },
) : PlaybackSink {
    override suspend fun play(audio: SpokenAudio, cue: PlaybackCue) {
        val waiter = acks.expect(cue.turnId, cue.sequence, transport.nodeId)
        try {
            try {
                transport.sendChannel(WatchLinkPaths.playPath(cue.turnId, cue.sequence),
                    PlayRequest(cue.turnId, cue.sequence, cue.role.name, audio.mimeType, audio.bytes).toFrame().encode())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Data Layer failures (e.g. ApiException) must surface as a playback failure, not escape the turn.
                throw HermesPlaybackException("watch unreachable: ${error.javaClass.simpleName}")
            }
            val ack = try {
                withTimeout(ackTimeoutMs(audio)) { waiter.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw HermesPlaybackException("watch did not confirm playback of ${cue.role.name.lowercase()}")
            }
            if (!ack.ok) throw HermesPlaybackException("watch playback failed: ${ack.error.ifBlank { "unknown" }}")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { transport.sendMessage(WatchLinkPaths.STOP, TurnStateMessage(cue.turnId, "stopped", "", false).encode()) }
            }
            throw cancelled
        } finally {
            acks.forget(cue.turnId, cue.sequence)
        }
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
) {
    /**
     * [bytes] is the channel content, or null when it could not be read or exceeded the frame
     * bound. Anything that is not a valid upload is rejected to the Watch before the orchestrator
     * sees it, so it is never accepted as a voice request and never moves the playback route.
     */
    suspend fun onTurnChannel(path: String, bytes: ByteArray?, transport: WatchTransport): VoiceTurnOutcome? {
        val upload = try {
            if (bytes == null) throw LinkProtocolException("recording transfer failed or too large")
            WatchTurnUpload.fromFrame(path, LinkFrame.decode(bytes))
        } catch (error: LinkProtocolException) {
            WatchLinkPaths.turnIdFromPath(path)?.let { turnId ->
                val detail = if (bytes == null) "Recording transfer failed" else "Bad recording"
                runCatching { transport.sendMessage(WatchLinkPaths.STATE, TurnStateMessage(turnId, "rejected", detail, true).encode()) }
            }
            return null
        }
        val states = Channel<TurnStateMessage>(Channel.UNLIMITED)
        val listener = object : VoiceTurnListener {
            // Told at once, before the turn waits its place in line: the Watch keeps its wake claim until this.
            override fun onAccepted(turnId: String, origin: VoiceOrigin) {
                states.trySend(TurnStateMessage(turnId, "accepted", "", false))
            }
            override fun onStage(turnId: String, stage: VoiceTurnStage) {
                states.trySend(TurnStateMessage(turnId, stage.name.lowercase(), "", false))
            }
            override fun onRouted(route: AssembledRoute) {
                states.trySend(TurnStateMessage(route.turnId, "routed", route.destination.alias, false))
            }
        }
        return try {
            relay(upload, transport, states, listener)
        } catch (cancelled: CancellationException) {
            // The turn was stopped on the Phone (its background relay was stopped): the Watch is told, so it does not wait on.
            withContext(NonCancellable) {
                runCatching {
                    transport.sendMessage(WatchLinkPaths.STATE, TurnStateMessage(upload.turnId, "done", STOPPED_ON_PHONE, true).encode())
                }
            }
            throw cancelled
        }
    }

    private suspend fun relay(upload: WatchTurnUpload, transport: WatchTransport, states: Channel<TurnStateMessage>,
                              listener: VoiceTurnListener): VoiceTurnOutcome =
        coroutineScope {
            val forwarder = launch {
                for (state in states) runCatching { transport.sendMessage(WatchLinkPaths.STATE, state.encode()) }
            }
            val outcome = try {
                orchestrator.run(VoiceTurnRequest(upload.turnId, VoiceOrigin.WATCH, upload.audio, upload.mimeType,
                    WatchPlaybackSink(transport, acks), listener, recognizedText = upload.recognizedText,
                    wakeTurn = upload.trigger == TurnTrigger.WAKE_PHRASE, wakeClaimId = upload.wakeClaimId,
                    originNodeId = transport.nodeId))
            } finally {
                states.close()
            }
            forwarder.join()
            runCatching {
                transport.sendMessage(WatchLinkPaths.STATE,
                    TurnStateMessage(upload.turnId, "done", VoiceOutcomeText.describe(outcome), true).encode())
            }
            outcome
        }

    companion object {
        const val STOPPED_ON_PHONE = "Stopped on the phone"
    }
}

/** Short user-facing text for a turn outcome (Phone status line and Watch terminal state). */
object VoiceOutcomeText {
    fun describe(outcome: VoiceTurnOutcome): String = when (outcome) {
        is VoiceTurnOutcome.Completed ->
            "Delivered to ${outcome.route.destination.alias}" + if (outcome.route.created) " (new conversation)" else ""
        is VoiceTurnOutcome.Interrupted -> "Delivered to ${outcome.route.destination.alias}; playback interrupted"
        is VoiceTurnOutcome.DeliveredUnattributed ->
            "Delivered to ${outcome.route.destination.alias}; reply not spoken (destination busy)"
        is VoiceTurnOutcome.DeliveredResponseFailed ->
            "Delivered to ${outcome.route.destination.alias}; reply playback failed"
        is VoiceTurnOutcome.RoutingRejected -> "Not delivered: routing rejected (${outcome.reason})"
        is VoiceTurnOutcome.NotDelivered ->
            if (outcome.authRequired) "Not delivered: sign in on the phone" else "Not delivered: ${outcome.reason.take(120)}"
        VoiceTurnOutcome.NoSpeech -> "No speech detected"
        is VoiceTurnOutcome.Duplicate -> "Duplicate turn ignored"
        is VoiceTurnOutcome.NotAdmitted ->
            if (outcome.reason == "wake_claim_missing") "Not sent: the wake settings changed while you spoke. Say it again"
            else if (outcome.reason.startsWith("wake_claim")) "Not sent: the wake phrase was answered elsewhere or timed out. Say it again"
            else "Not sent: ${outcome.reason.take(120)}"
    }
}
