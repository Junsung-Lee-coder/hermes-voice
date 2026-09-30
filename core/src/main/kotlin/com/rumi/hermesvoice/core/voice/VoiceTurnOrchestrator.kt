package com.rumi.hermesvoice.core.voice

import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.HermesException
import com.rumi.hermesvoice.core.ResponsePlaybackSettings
import com.rumi.hermesvoice.core.SpokenAudio
import com.rumi.hermesvoice.core.SpokenRole
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.audio.AudioInputGate
import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.HermesConversationPort
import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import com.rumi.hermesvoice.core.net.SubmittedTurn
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything a turn needs, snapshotted once when the turn starts. */
data class VoiceTurnConfig(
    val routingStoredSessionId: String,
    val allowlist: DestinationAllowlist,
    val playback: ResponsePlaybackSettings,
)

data class PlaybackCue(
    val turnId: String,
    /** The device that submitted the turn. */
    val origin: VoiceOrigin,
    val role: SpokenRole,
    /** 0 is the acknowledgement; recipient responses count up from 1 in play order. */
    val sequence: Int,
    val text: String,
    /** The device it plays on: the [PlaybackRoute] target at handoff, which may differ from [origin]. */
    val device: VoiceOrigin = origin,
)

/**
 * Plays on one device. Returns only after that device confirmed playback finished, throws
 * [HermesPlaybackException] on failure, and stops playback when cancelled.
 */
fun interface PlaybackSink {
    suspend fun play(audio: SpokenAudio, cue: PlaybackCue)
}

class VoiceTurnRequest(
    val turnId: String,
    val origin: VoiceOrigin,
    val audio: ByteArray,
    val mimeType: String,
    /** Plays on the [origin] device; it becomes the [PlaybackRoute] target when this request is accepted. */
    val sink: PlaybackSink,
    /** Per-turn observer (e.g. the Watch talk-state projection), called after the orchestrator-wide one. */
    val listener: VoiceTurnListener? = null,
    /**
     * A request the Watch's speech recognizer already heard with the wake phrase. It replaces
     * transcription and is treated exactly like a transcript (untrusted data for the router).
     */
    val recognizedText: String? = null,
)

enum class VoiceTurnStage { TRANSCRIBING, ROUTING, CREATING, ACKNOWLEDGING, DELIVERING, RESPONDING }

/** What is durably known about a turn that asked for a new conversation (see [RecipientCreator]). */
data class PriorCreate(val intent: CreateIntent, val ackText: String, val submitted: Boolean)

/**
 * Creates the conversation a router asked for. The Phone implements it over its session
 * repository (existing authenticated API, registry and source checks); the orchestrator never
 * creates sessions itself and never takes a session id from the model.
 */
interface RecipientCreator {
    /** Non-null when [turnId] already asked for a new conversation, even before a restart. */
    fun previous(turnId: String): PriorCreate?

    /** The created (or, for the same [turnId], previously created) destination; throws on failure or ambiguity. */
    suspend fun create(turnId: String, intent: CreateIntent, ackText: String): DestinationEntry

    /** Called right before the transcript is submitted; false when it already was (never submit twice). */
    fun markSubmitted(turnId: String): Boolean
}

interface VoiceTurnListener {
    /** A recording was refused before acceptance because it had no usable audio ([verdict]); nothing else happens. */
    fun onInputRejected(turnId: String, origin: VoiceOrigin, verdict: AudioInputVerdict) {}

    /** A new voice request was accepted; its [origin] device is now the playback target. */
    fun onAccepted(turnId: String, origin: VoiceOrigin) {}
    fun onStage(turnId: String, stage: VoiceTurnStage) {}
    fun onRouted(route: AssembledRoute) {}
    fun onResponse(turnId: String, decision: ResponseDecision) {}

    /** [cue] finished playing on [PlaybackCue.device], as confirmed by that device's sink. */
    fun onPlayed(cue: PlaybackCue) {}
}

sealed class VoiceTurnOutcome {
    data class Duplicate(val turnId: String) : VoiceTurnOutcome()
    object NoSpeech : VoiceTurnOutcome()
    data class RoutingRejected(val transcript: String, val reason: String) : VoiceTurnOutcome()

    /** Failed before the destination accepted the transcript: nothing was delivered. */
    data class NotDelivered(val stage: VoiceTurnStage, val reason: String, val authRequired: Boolean = false) :
        VoiceTurnOutcome()

    /** Delivered, but Hermes's reply could not be attributed to this turn, so nothing beyond the ack is spoken. */
    data class DeliveredUnattributed(val route: AssembledRoute, val submitStatus: String) : VoiceTurnOutcome()

    /** Delivered; listening for or playing the recipient's responses failed. */
    data class DeliveredResponseFailed(val route: AssembledRoute, val spoken: List<SpokenRole>, val reason: String) :
        VoiceTurnOutcome()

    /** Delivered; a newer turn took the speaker before this turn's responses finished. */
    data class Interrupted(val route: AssembledRoute, val spoken: List<SpokenRole>) : VoiceTurnOutcome()

    data class Completed(val route: AssembledRoute, val spoken: List<SpokenRole>) : VoiceTurnOutcome()
}

private class TurnSupersededException : CancellationException("superseded by a newer voice turn")

/**
 * One voice turn, identical for Phone and Watch origins:
 * transcribe (Phone-owned transcript) → routing session → validated alias + ack, or a validated
 * request for a NEW conversation that the [RecipientCreator] creates, verifies and registers →
 * ack spoken and finished → ORIGINAL transcript submitted to that destination → recipient
 * responses spoken per [ResponsePlaybackSettings] (FINAL always). A failed or ambiguous creation
 * ends the turn: nothing is acknowledged and nothing is delivered anywhere else.
 *
 * Ordering: capture→delivery is serialized, so destinations receive turns in capture order.
 * Playback device: every utterance plays on the [PlaybackRoute] target at its handoff, i.e. the
 * device that most recently submitted an accepted voice request, not necessarily the turn's origin.
 * Interruption: a newer turn takes the speaker when its acknowledgement is about to play; the older
 * turn's remaining response playback stops (its Hermes turn keeps running server-side).
 * Deduplication: a replayed turn id is ignored; duplicate responses are filtered by
 * [RecipientResponseTracker].
 */
class VoiceTurnOrchestrator(
    private val speech: HermesSpeechGateway,
    private val conversations: HermesConversationPort,
    private val config: suspend () -> VoiceTurnConfig,
    private val listener: VoiceTurnListener = object : VoiceTurnListener {},
    private val routingTimeoutMs: Long = 120_000,
    private val responseTimeoutMs: Long = 15 * 60_000,
    val playbackRoute: PlaybackRoute = PlaybackRoute(),
    private val inputGate: (ByteArray, String) -> AudioInputVerdict = AudioInputGate::assess,
    private val recipientCreator: RecipientCreator? = null,
) {
    private val deliveryLock = Mutex()
    private val floorLock = Any()
    private var floorGeneration = 0L
    private var floorJob: Job? = null
    private val recentTurnIds = LinkedHashSet<String>()

    private class Delivered(val route: AssembledRoute, val turn: SubmittedTurn, val floor: Long, val settings: ResponsePlaybackSettings)

    suspend fun run(request: VoiceTurnRequest): VoiceTurnOutcome {
        // A recording with no usable audio stops here, before acceptance: it is never transcribed,
        // routed or delivered, and it cannot become the latest voice sender (see AudioInputGate).
        if (request.recognizedText == null) {
            val verdict = inputGate(request.audio, request.mimeType)
            if (verdict != AudioInputVerdict.USABLE) {
                listener.onInputRejected(request.turnId, request.origin, verdict)
                return VoiceTurnOutcome.NoSpeech
            }
        }
        // A turn that already created a conversation and submitted its transcript (even before a
        // restart) is a replay: it must not submit again, and it does not move the playback route.
        if (recipientCreator?.previous(request.turnId)?.submitted == true) return VoiceTurnOutcome.Duplicate(request.turnId)
        if (!accept(request)) return VoiceTurnOutcome.Duplicate(request.turnId)
        listener.onAccepted(request.turnId, request.origin)
        request.listener?.onAccepted(request.turnId, request.origin)
        val delivered = deliveryLock.withLock {
            when (val result = deliver(request)) {
                is Delivered -> result
                is VoiceTurnOutcome -> return result
                else -> error("unexpected delivery result")
            }
        }
        if (!delivered.turn.attributable) {
            delivered.turn.release()
            return VoiceTurnOutcome.DeliveredUnattributed(delivered.route, delivered.turn.submitStatus)
        }
        return respond(request, delivered)
    }

    private suspend fun deliver(request: VoiceTurnRequest): Any {
        var stage = VoiceTurnStage.TRANSCRIBING
        try {
            val cfg = try {
                config()
            } catch (error: IllegalArgumentException) {
                return VoiceTurnOutcome.NotDelivered(VoiceTurnStage.TRANSCRIBING, "config_invalid: ${error.message}")
            }
            notifyStage(request, stage)
            val transcript = (request.recognizedText ?: speech.transcribe(request.audio, request.mimeType)).trim()
            if (transcript.isEmpty()) return VoiceTurnOutcome.NoSpeech

            // A replayed turn that already asked for a new conversation resumes with that one
            // (or fails closed if its creation is unresolved); it is not routed a second time.
            val prior = recipientCreator?.previous(request.turnId)
            val decision: RoutingDecision = if (prior != null) RoutingDecision.Create(prior.intent, prior.ackText) else {
                stage = VoiceTurnStage.ROUTING
                notifyStage(request, stage)
                val routingTurn = conversations.submit(cfg.routingStoredSessionId,
                    RoutingContract.buildRoutingPrompt(transcript, cfg.allowlist))
                var routingReply: RecipientEvent.Complete? = null
                routingTurn.collect(routingTimeoutMs) { event -> if (event is RecipientEvent.Complete) routingReply = event }
                val reply = routingReply ?: return VoiceTurnOutcome.RoutingRejected(transcript, "routing_reply_missing")
                when (val parsed = RoutingContract.parse(reply.text, reply.status, cfg.allowlist)) {
                    is RoutingParseResult.Rejected -> return VoiceTurnOutcome.RoutingRejected(transcript, parsed.reason)
                    is RoutingParseResult.Accepted -> parsed.decision
                }
            }
            val route = when (decision) {
                is RoutingDecision.Route ->
                    AssembledRoute(request.turnId, request.origin, transcript, decision.destination, decision.ackText)
                is RoutingDecision.Create -> {
                    val creator = recipientCreator
                        ?: return VoiceTurnOutcome.RoutingRejected(transcript, "routing_create_unsupported")
                    stage = VoiceTurnStage.CREATING
                    notifyStage(request, stage)
                    val destination = creator.create(request.turnId, decision.intent, decision.ackText)
                    AssembledRoute(request.turnId, request.origin, transcript, destination, decision.ackText, created = true)
                }
            }
            notifyRouted(request, route)

            stage = VoiceTurnStage.ACKNOWLEDGING
            notifyStage(request, stage)
            val ackAudio = speech.speak(route.ackText)
            val floor = claimFloor()
            handOff(request, ackAudio, SpokenRole.ACK, 0, route.ackText)

            stage = VoiceTurnStage.DELIVERING
            notifyStage(request, stage)
            if (route.created && recipientCreator?.markSubmitted(request.turnId) == false) {
                return VoiceTurnOutcome.Duplicate(request.turnId)
            }
            val submitted = conversations.submit(route.destination.storedSessionId, route.originalTranscript)
            return Delivered(route, submitted, floor, cfg.playback)
        } catch (auth: HermesAuthRequiredException) {
            return VoiceTurnOutcome.NotDelivered(stage, auth.message ?: "auth_required", authRequired = true)
        } catch (error: HermesException) {
            return VoiceTurnOutcome.NotDelivered(stage, error.message ?: error.javaClass.simpleName)
        } catch (error: IOException) {
            return VoiceTurnOutcome.NotDelivered(stage, "network: ${error.javaClass.simpleName}")
        }
    }

    private suspend fun respond(request: VoiceTurnRequest, delivered: Delivered): VoiceTurnOutcome = coroutineScope {
        val route = delivered.route
        val spoken = mutableListOf<SpokenRole>()
        var failure: String? = null
        notifyStage(request, VoiceTurnStage.RESPONDING)
        val job = launch(start = CoroutineStart.LAZY) {
            val tracker = RecipientResponseTracker(delivered.settings)
            var sequence = 1
            try {
                delivered.turn.collect(responseTimeoutMs) { event ->
                    val decision = tracker.onEvent(event) ?: return@collect
                    notifyResponse(request, decision)
                    val text = decision.speakText ?: return@collect
                    try {
                        val audio = speech.speak(text)
                        handOff(request, audio, decision.role, sequence++, text)
                        spoken += decision.role
                    } catch (error: HermesException) {
                        // A lost FIRST/MIDDLE response must not prevent the FINAL from playing.
                        if (decision.role == SpokenRole.FINAL) throw error
                        failure = "${decision.role.name.lowercase()}_playback_failed: ${error.message}"
                    } catch (error: IOException) {
                        if (decision.role == SpokenRole.FINAL) throw error
                        failure = "${decision.role.name.lowercase()}_playback_failed: network"
                    }
                }
            } catch (error: HermesException) {
                failure = error.message ?: error.javaClass.simpleName
            } catch (error: IOException) {
                failure = "network: ${error.javaClass.simpleName}"
            }
        }
        if (!registerFloor(delivered.floor, job)) job.cancel(TurnSupersededException())
        job.start()
        job.join()
        releaseFloor(job)
        delivered.turn.release()
        when {
            job.isCancelled -> VoiceTurnOutcome.Interrupted(route, spoken.toList())
            failure != null && SpokenRole.FINAL !in spoken -> VoiceTurnOutcome.DeliveredResponseFailed(route, spoken.toList(), failure!!)
            else -> VoiceTurnOutcome.Completed(route, spoken.toList())
        }
    }

    /** Resolves the playback device now, not when the turn started, and plays there. */
    private suspend fun handOff(request: VoiceTurnRequest, audio: SpokenAudio, role: SpokenRole, sequence: Int, text: String) {
        val target = checkNotNull(playbackRoute.current()) { "no playback target after an accepted voice request" }
        val cue = PlaybackCue(request.turnId, request.origin, role, sequence, text, target.device)
        target.sink.play(audio, cue)
        listener.onPlayed(cue)
        request.listener?.onPlayed(cue)
    }

    private fun notifyStage(request: VoiceTurnRequest, stage: VoiceTurnStage) {
        listener.onStage(request.turnId, stage)
        request.listener?.onStage(request.turnId, stage)
    }

    private fun notifyRouted(request: VoiceTurnRequest, route: AssembledRoute) {
        listener.onRouted(route)
        request.listener?.onRouted(route)
    }

    private fun notifyResponse(request: VoiceTurnRequest, decision: ResponseDecision) {
        listener.onResponse(request.turnId, decision)
        request.listener?.onResponse(request.turnId, decision)
    }

    /** The newest turn owns the speaker; the previous owner's response playback is stopped first. */
    private suspend fun claimFloor(): Long {
        val (generation, previous) = synchronized(floorLock) {
            floorGeneration += 1
            val previous = floorJob
            floorJob = null
            floorGeneration to previous
        }
        previous?.let {
            it.cancel(TurnSupersededException())
            it.join()
        }
        return generation
    }

    private fun registerFloor(generation: Long, job: Job): Boolean = synchronized(floorLock) {
        if (generation != floorGeneration) return false
        floorJob = job
        true
    }

    private fun releaseFloor(job: Job) = synchronized(floorLock) {
        if (floorJob === job) floorJob = null
    }

    /** Admits a new turn id and makes its device the playback target, atomically, so acceptance order decides the route. */
    private fun accept(request: VoiceTurnRequest): Boolean = synchronized(recentTurnIds) {
        require(request.turnId.isNotBlank()) { "turn id is required" }
        if (!recentTurnIds.add(request.turnId)) return false
        while (recentTurnIds.size > MAX_REMEMBERED_TURNS) recentTurnIds.remove(recentTurnIds.first())
        playbackRoute.accept(PlaybackTarget(request.origin, request.sink))
        true
    }

    companion object {
        private const val MAX_REMEMBERED_TURNS = 128
    }
}
