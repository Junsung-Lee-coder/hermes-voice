package com.rumi.hermesvoice.core.voice

import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.HermesException
import com.rumi.hermesvoice.core.HermesPlaybackBusyException
import com.rumi.hermesvoice.core.net.LaterEnd
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything a turn needs, snapshotted once when the turn starts. */
data class VoiceTurnConfig(
    /** The router session; null for a routing-off turn, which never uses it. */
    val routingStoredSessionId: String?,
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
    /** A later reply (after the turn was answered): a recording device refuses it rather than play over the recording. */
    val later: Boolean = false,
)

/**
 * Plays on one device. Returns only after that device confirmed playback finished, throws
 * [HermesPlaybackException] on failure, and stops playback when cancelled.
 */
fun interface PlaybackSink {
    suspend fun play(audio: SpokenAudio, cue: PlaybackCue)

    /**
     * As [play], and calls [finished] at the device's own "played to the end" signal (the Phone
     * player's completion, the Watch's accepted PLAYED confirmation): from inside the sink, BEFORE it
     * resumes its caller (whose resumption is cancellable), at most once, and never for a failure,
     * refusal, stop or cancellation. A sink without such a signal (the default) never calls it: its
     * normal return is its only confirmation, which a cancellation landing just before that return
     * can still turn into "not played".
     */
    suspend fun playConfirmed(audio: SpokenAudio, cue: PlaybackCue, finished: () -> Unit) {
        play(audio, cue)
    }
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
    /** The turn came from the wake phrase (hands-free), not push-to-talk. */
    val wakeTurn: Boolean = false,
    /** The wake claim the turn was made under when both devices listen (see WakeAdmission). */
    val wakeClaimId: String? = null,
    /** The Watch node the turn came from; empty for the Phone. */
    val originNodeId: String = "",
    /** The Phone's routing switch for this turn, frozen when the request was made (see [TurnRouting]). */
    val routing: TurnRouting = TurnRouting.Model,
    /**
     * The microphone claim of the recording this request was made from ([AudioOwnership]). The
     * orchestrator takes it over the moment it starts the request, under the same lock, so later
     * replies keep waiting, without a gap, until this request has been answered (or refused).
     */
    val microphone: MicrophoneClaim? = null,
)

enum class VoiceTurnStage { TRANSCRIBING, ROUTING, CREATING, ACKNOWLEDGING, DELIVERING, RESPONDING }

/** What is durably known about a turn that asked for a new conversation (see [RecipientCreator]). */
data class PriorCreate(val intent: CreateIntent, val submitted: Boolean)

/** A conversation the Phone created and registered for a turn: the destination and its actual title. */
data class CreatedRecipient(val destination: DestinationEntry, val title: String)

/**
 * Creates the conversation a router asked for. The Phone implements it over its session
 * repository (existing authenticated API, registry and source checks); the orchestrator never
 * creates sessions itself and never takes a session id from the model.
 */
interface RecipientCreator {
    /** Non-null when [turnId] already asked for a new conversation, even before a restart. */
    fun previous(turnId: String): PriorCreate?

    /**
     * The created (or, for the same [turnId], previously created) destination, verified on the
     * dashboard and saved durably in the registry; throws on failure, a failed save, or ambiguity.
     */
    suspend fun create(turnId: String, intent: CreateIntent): CreatedRecipient

    /**
     * Called after the acknowledgement played and right before any transcript is submitted: throws
     * unless [storedSessionId] is still this app's unarchived conversation.
     */
    suspend fun requireDeliverable(storedSessionId: String)

    /**
     * Called right before a created turn's transcript is submitted. It saves "submitted" durably
     * first: false when it already was (never submit twice); throws when it could not be saved
     * (then nothing may be submitted).
     */
    suspend fun markSubmitted(turnId: String): Boolean
}

interface VoiceTurnListener {
    /** A recording was refused before acceptance because it had no usable audio ([verdict]); nothing else happens. */
    fun onInputRejected(turnId: String, origin: VoiceOrigin, verdict: AudioInputVerdict) {}

    /** A turn was refused before acceptance ([reason]); nothing else happens. */
    fun onNotAdmitted(turnId: String, origin: VoiceOrigin, reason: String) {}

    /** A new voice request was accepted; its [origin] device is now the playback target. */
    fun onAccepted(turnId: String, origin: VoiceOrigin) {}
    fun onStage(turnId: String, stage: VoiceTurnStage) {}
    fun onRouted(route: AssembledRoute) {}
    fun onResponse(turnId: String, decision: ResponseDecision) {}

    /** [cue] finished playing on [PlaybackCue.device], as confirmed by that device's sink. */
    fun onPlayed(cue: PlaybackCue) {}

    /** The destination accepted [route]'s original transcript (after the acknowledgement played). */
    fun onDelivered(route: AssembledRoute) {}

    /**
     * A later reply of delivered turn [turnId] (see [VoiceTurnOrchestrator]) was [played] on the
     * device named in [detail], or not, with the reason in [detail]. Every later reply that arrived
     * while the turn was followed is reported here exactly once.
     */
    fun onLaterReply(turnId: String, played: Boolean, detail: String) {}

    /**
     * Following turn [turnId]'s conversation for later replies ended (no more arrivals): [reason] is
     * "window" (30 minutes passed), "superseded" (the app sent that conversation something new),
     * "disconnected" (the gateway connection dropped; nothing re-subscribes), "stopped" (a Stop),
     * "off" (the option was switched off) or "released". Replies that arrived before a window,
     * superseded or disconnected end are still spoken or reported; a Stop or "off" ends them too.
     */
    fun onLaterFollowEnded(turnId: String, reason: String) {}

    /**
     * Metadata-only stage diagnostics of turn [turnId] ("" when not about one turn): what a stage
     * did and how long it took, never any text, audio, address, session, device or node id.
     */
    fun onDiagnostic(turnId: String, stage: String, detail: String) {}
}

sealed class VoiceTurnOutcome {
    data class Duplicate(val turnId: String) : VoiceTurnOutcome()

    /** Refused before acceptance (e.g. the other device answered this wake phrase): nothing happened, the playback target is unchanged. */
    data class NotAdmitted(val reason: String) : VoiceTurnOutcome()
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

/** Following later replies was stopped (a Stop, or the option switched off). */
private class LaterStopped : CancellationException("later replies stopped")

private fun monotonicMs(): Long = System.nanoTime() / 1_000_000

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
    private val config: suspend (TurnRouting) -> VoiceTurnConfig,
    private val listener: VoiceTurnListener = object : VoiceTurnListener {},
    private val routingTimeoutMs: Long = 120_000,
    private val responseTimeoutMs: Long = 15 * 60_000,
    val playbackRoute: PlaybackRoute = PlaybackRoute(),
    private val inputGate: (ByteArray, String) -> AudioInputVerdict = AudioInputGate::assess,
    private val recipientCreator: RecipientCreator? = null,
    /** Asked once per turn after the input check and before acceptance; a non-null reason refuses the turn. */
    private val admission: (VoiceTurnRequest) -> String? = { null },
    /**
     * Where a delivered turn's LATER replies are followed (see [SubmittedTurn.collectLater]): a
     * background-process or async-delegation completion, or a follow-up, that Hermes delivers as a
     * new turn on the destination session after the submitted one completed. Null: not followed.
     */
    private val laterScope: CoroutineScope? = null,
    private val laterWindowMs: Long = LATER_WINDOW_MS,
    /**
     * Wraps one attempt to speak a later reply: its synthesis (the first attempt only) and handoff.
     * The Phone keeps the CPU awake for that and no longer (the wait for a free speaker and
     * microphone happens outside it), so the hold is per attempt, not per reply.
     */
    private val laterWork: suspend (suspend () -> Unit) -> Unit = { it() },
    /** The user's opt-in to speak later replies, read when a turn would be followed and at every later reply. Off by default. */
    private val laterEnabled: () -> Boolean = { false },
    /**
     * Whether a wake-phrase window listens on [device] now. It closes for a later reply admitted to
     * that device's speaker ([laterSpeaker]), so it is waited for only briefly, right before playback.
     */
    private val wakeListening: (VoiceOrigin) -> Boolean = { false },
    /** How long after its arrival an attempt to speak a later reply may still BEGIN (it waits for a busy speaker or microphone). */
    private val laterDeferMaxMs: Long = LATER_DEFER_MAX_MS,
    /** The microphones and the later-reply speaker of this process ([AudioOwnership]); its lock is the speaker floor's. */
    val ownership: AudioOwnership = AudioOwnership(),
    /**
     * A later reply holds [device]'s speaker (true from its admission to its end, on every path):
     * the Phone closes its wake windows for it. Never told while one only waits, is synthesized, or
     * is handed to another device.
     */
    private val laterSpeaker: (device: VoiceOrigin, holding: Boolean) -> Unit = { _, _ -> },
) {
    private val deliveryLock = Mutex()
    private val floorLock = ownership.lock
    private var floorGeneration = 0L
    private var floorJob: Job? = null
    private val recentTurnIds = LinkedHashSet<String>()

    /**
     * Voice requests started and not finished yet (from the moment [run] takes one, through its
     * transcription, routing, acknowledgement and responses): later replies wait for them.
     */
    private var turnsInFlight = 0

    private val followers = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()

    /** Later replies that arrived and are not reported yet, over all follows (bounded by [LATER_PENDING_MAX]). */
    private val laterPending = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Stops following every delivered turn's later replies, and every later reply that arrived and
     * waits or plays (each reported as not played). A Stop, or the option switched off. Safe to repeat.
     */
    fun stopFollowing() {
        followers.toList().forEach { it.cancel(LaterStopped()) }
    }

    private class Delivered(val route: AssembledRoute, val turn: SubmittedTurn, val floor: Long, val settings: ResponsePlaybackSettings)

    suspend fun run(request: VoiceTurnRequest): VoiceTurnOutcome {
        // From here until this request is answered or refused, later replies wait: the recording's
        // microphone claim becomes this count under the same lock, so there is no gap between them.
        synchronized(floorLock) {
            turnsInFlight += 1
            request.microphone?.release()
        }
        try {
            return runCounted(request)
        } finally {
            synchronized(floorLock) { turnsInFlight -= 1 }
        }
    }

    private suspend fun runCounted(request: VoiceTurnRequest): VoiceTurnOutcome {
        // A recording with no usable audio stops here, before acceptance: it is never transcribed,
        // routed or delivered, and it cannot become the latest voice sender (see AudioInputGate).
        if (request.recognizedText == null) {
            val verdict = inputGate(request.audio, request.mimeType)
            if (verdict != AudioInputVerdict.USABLE) {
                listener.onInputRejected(request.turnId, request.origin, verdict)
                return VoiceTurnOutcome.NoSpeech
            }
        }
        admission(request)?.let { reason ->
            listener.onNotAdmitted(request.turnId, request.origin, reason)
            return VoiceTurnOutcome.NotAdmitted(reason)
        }
        // Routing off needs a conversation selected on the sending device; there is no fallback to
        // the router or to any other conversation, and the playback target stays where it was.
        val routing = request.routing
        if (routing is TurnRouting.Direct && routing.storedSessionId == null) {
            listener.onNotAdmitted(request.turnId, request.origin, TurnRouting.NO_TARGET)
            return VoiceTurnOutcome.NotAdmitted(TurnRouting.NO_TARGET)
        }
        // A turn that already created a conversation and submitted its transcript (even before a
        // restart) is a replay: it must not submit again, and it does not move the playback route.
        val prior = try {
            recipientCreator?.previous(request.turnId)
        } catch (error: HermesException) {
            // The Phone's saved record of created conversations can't be trusted: fail closed.
            return VoiceTurnOutcome.NotDelivered(VoiceTurnStage.TRANSCRIBING, error.message ?: error.javaClass.simpleName)
        }
        if (prior?.submitted == true) return VoiceTurnOutcome.Duplicate(request.turnId)
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
        listener.onDelivered(delivered.route)
        request.listener?.onDelivered(delivered.route)
        if (!delivered.turn.attributable) {
            delivered.turn.release()
            return VoiceTurnOutcome.DeliveredUnattributed(delivered.route, delivered.turn.submitStatus)
        }
        val (outcome, nextSequence) = respond(request, delivered)
        follow(request, delivered.turn, nextSequence)
        return outcome
    }

    private suspend fun deliver(request: VoiceTurnRequest): Any {
        var stage = VoiceTurnStage.TRANSCRIBING
        try {
            val cfg = try {
                config(request.routing)
            } catch (error: IllegalArgumentException) {
                return VoiceTurnOutcome.NotDelivered(VoiceTurnStage.TRANSCRIBING, "config_invalid: ${error.message}")
            }
            // Routing off: the selected conversation must be one of this app's active ones (never
            // the router, an archived, deleted or foreign session); checked before anything is sent.
            val direct = (request.routing as? TurnRouting.Direct)?.let { routing ->
                cfg.allowlist.entries.firstOrNull { it.storedSessionId == routing.storedSessionId }
                    ?: return VoiceTurnOutcome.NotDelivered(VoiceTurnStage.ROUTING, TurnRouting.TARGET_UNAVAILABLE)
            }
            notifyStage(request, stage)
            val transcript = (request.recognizedText ?: speech.transcribe(request.audio, request.mimeType)).trim()
            if (transcript.isEmpty()) return VoiceTurnOutcome.NoSpeech

            // A replayed turn that already asked for a new conversation resumes with that one
            // (or fails closed if its creation is unresolved); it is not routed a second time.
            val prior = recipientCreator?.previous(request.turnId)
            val decision: RoutingDecision = if (prior != null) RoutingDecision.Create(prior.intent)
            else if (direct != null) RoutingDecision.Route(direct, DirectAck.compose(direct.alias, transcript))
            else {
                stage = VoiceTurnStage.ROUTING
                notifyStage(request, stage)
                val router = cfg.routingStoredSessionId
                    ?: return VoiceTurnOutcome.NotDelivered(VoiceTurnStage.ROUTING, "config_invalid: no routing session")
                val routingTurn = conversations.submit(router,
                    RoutingContract.buildRoutingPrompt(transcript, cfg.allowlist))
                var routingReply: RecipientEvent.Complete? = null
                try {
                    routingTurn.collect(routingTimeoutMs) { event -> if (event is RecipientEvent.Complete) routingReply = event }
                } finally {
                    routingTurn.release()
                    diagnostic(request, "routing", routingTurn.diagnostics())
                }
                val reply = routingReply ?: return VoiceTurnOutcome.RoutingRejected(transcript, "routing_reply_missing")
                when (val parsed = RoutingContract.parse(reply.text, reply.status, cfg.allowlist)) {
                    is RoutingParseResult.Rejected -> return VoiceTurnOutcome.RoutingRejected(transcript, parsed.reason)
                    is RoutingParseResult.Accepted -> parsed.decision
                }
            }
            val route = when (decision) {
                is RoutingDecision.Route ->
                    AssembledRoute(request.turnId, request.origin, transcript, decision.destination, decision.ackText, direct = direct != null)
                is RoutingDecision.Create -> {
                    val creator = recipientCreator
                        ?: return VoiceTurnOutcome.RoutingRejected(transcript, "routing_create_unsupported")
                    stage = VoiceTurnStage.CREATING
                    notifyStage(request, stage)
                    val created = creator.create(request.turnId, decision.intent)
                    // Only now, with the conversation verified and saved, is the acknowledgement worded, by the
                    // Phone, from what really exists: its title and the alias the Phone assigned.
                    AssembledRoute(request.turnId, request.origin, transcript, created.destination,
                        CreateAck.compose(created.title, created.destination.alias, transcript), created = true)
                }
            }
            notifyRouted(request, route)

            stage = VoiceTurnStage.ACKNOWLEDGING
            notifyStage(request, stage)
            val ackAudio = timedSpeak(request, SpokenRole.ACK, route.ackText)
            val floor = claimFloor()
            timedHandOff(request, ackAudio, SpokenRole.ACK, 0, route.ackText)

            stage = VoiceTurnStage.DELIVERING
            notifyStage(request, stage)
            // After the acknowledgement, which can take a while: the destination must still be deliverable.
            recipientCreator?.requireDeliverable(route.destination.storedSessionId)
            if (route.created && recipientCreator?.markSubmitted(request.turnId) == false) {
                return VoiceTurnOutcome.Duplicate(request.turnId)
            }
            val submitted = conversations.submit(route.destination.storedSessionId, route.originalTranscript)
            diagnostic(request, "submitted", "status=${submitted.submitStatus} attributable=${submitted.attributable} " +
                "created=${route.created} direct=${route.direct}")
            return Delivered(route, submitted, floor, cfg.playback)
        } catch (auth: HermesAuthRequiredException) {
            return VoiceTurnOutcome.NotDelivered(stage, auth.message ?: "auth_required", authRequired = true)
        } catch (error: HermesException) {
            return VoiceTurnOutcome.NotDelivered(stage, error.message ?: error.javaClass.simpleName)
        } catch (error: IOException) {
            return VoiceTurnOutcome.NotDelivered(stage, "network: ${error.javaClass.simpleName}")
        }
    }

    /** The outcome, and the next playback sequence number of this turn (for its later replies). */
    private suspend fun respond(request: VoiceTurnRequest, delivered: Delivered): Pair<VoiceTurnOutcome, Int> = coroutineScope {
        val route = delivered.route
        val spoken = mutableListOf<SpokenRole>()
        var failure: String? = null
        var sequence = 1
        notifyStage(request, VoiceTurnStage.RESPONDING)
        val job = launch(start = CoroutineStart.LAZY) {
            val tracker = RecipientResponseTracker(delivered.settings)
            try {
                delivered.turn.collect(responseTimeoutMs) { event ->
                    val decision = tracker.onEvent(event) ?: return@collect
                    notifyResponse(request, decision)
                    val text = decision.speakText ?: return@collect
                    try {
                        val audio = timedSpeak(request, decision.role, text)
                        timedHandOff(request, audio, decision.role, sequence++, text)
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
        val outcome = when {
            job.isCancelled -> VoiceTurnOutcome.Interrupted(route, spoken.toList())
            failure != null && SpokenRole.FINAL !in spoken -> VoiceTurnOutcome.DeliveredResponseFailed(route, spoken.toList(), failure!!)
            else -> VoiceTurnOutcome.Completed(route, spoken.toList())
        }
        diagnostic(request, "responses", "${delivered.turn.diagnostics()} spoken=${spoken.joinToString(",").ifEmpty { "none" }} " +
            "outcome=${outcome.javaClass.simpleName}")
        outcome to sequence
    }

    /**
     * Follows [turn]'s destination for its later replies in [laterScope], only while the user's
     * opt-in ([laterEnabled]) is on (released at once otherwise). The follow window bounds ARRIVALS
     * only: a reply that arrived in it is queued (bounded, in arrival order) and then spoken by this
     * follow's one speaker, serially, with its own deadline ([speakLater]), even after the window
     * ended. Each is spoken as a FINAL (the first/middle switches don't apply), on the device of the
     * latest accepted voice request at its handoff, never elsewhere instead, and reported exactly
     * once. Why arrivals ended is reported too ([VoiceTurnListener.onLaterFollowEnded]).
     */
    private fun follow(request: VoiceTurnRequest, turn: SubmittedTurn, firstSequence: Int) {
        val scope = laterScope
        if (scope == null || !laterEnabled()) return turn.release()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // A reply dropped from the queue by a Stop (or the option switched off) is reported as such.
            val arrived = Channel<LaterReply>(LATER_QUEUE_MAX) { it.report(false, stoppedDetail()) }
            val speaker = launch {
                try {
                    for (reply in arrived) speakLater(reply)
                } finally {
                    // Stopped: anything still queued is reported as not played (the channel's undelivered handler).
                    arrived.cancel()
                }
            }
            var sequence = firstSequence
            var reason = "released"
            try {
                reason = when (turn.collectLater(laterWindowMs) { event ->
                    // Switched off meanwhile: this reply and every later one are not spoken.
                    if (!laterEnabled()) throw LaterStopped()
                    val text = event.text.trim()
                    if (text.isNotEmpty()) admit(arrived, LaterReply(request, text, sequence++, monotonicMs() + laterDeferMaxMs))
                }) {
                    LaterEnd.WINDOW -> "window"
                    LaterEnd.SUPERSEDED -> "superseded"
                    LaterEnd.DISCONNECTED -> "disconnected"
                    LaterEnd.RELEASED -> "released"
                }
            } catch (stopped: CancellationException) {
                reason = if (laterEnabled()) "stopped" else "off"
                throw stopped
            } finally {
                arrived.close()
                turn.release()
                listener.onLaterFollowEnded(request.turnId, reason)
                request.listener?.onLaterFollowEnded(request.turnId, reason)
            }
            // Arrivals ended; replies that already arrived are still spoken, each within its own bounds.
            speaker.join()
        }
        followers += job
        job.invokeOnCompletion { followers -= job }
        job.start()
    }

    /** One later reply that arrived: spoken (or not) and reported exactly once. */
    private inner class LaterReply(val request: VoiceTurnRequest, val text: String, val sequence: Int, val deadlineMs: Long) {
        private val reported = java.util.concurrent.atomic.AtomicBoolean(false)

        /** The device its sink confirmed it played to the end on ([markPlayed]); written under the floor lock. */
        @Volatile var playedOn: VoiceOrigin? = null

        fun report(played: Boolean, detail: String) {
            if (!reported.compareAndSet(false, true)) return
            laterPending.decrementAndGet()
            listener.onLaterReply(request.turnId, played, detail)
            request.listener?.onLaterReply(request.turnId, played, detail)
        }

        /** Not played for [detail], unless its sink already confirmed it played to the end: then that is the truth. */
        fun reportNotPlayed(detail: String) {
            val device = playedOn
            if (device != null) report(true, device.name.lowercase()) else report(false, detail)
        }
    }

    /** Queues [reply] for this follow's speaker; too many waiting (here or over all follows) is reported at once. */
    private fun admit(arrived: Channel<LaterReply>, reply: LaterReply) {
        val queued = laterPending.incrementAndGet() <= LATER_PENDING_MAX && arrived.trySend(reply).isSuccess
        if (!queued) reply.report(false, "not played: too many later replies waiting")
    }

    private fun stoppedDetail(): String = if (laterEnabled()) "not played: stopped" else "not played: switched off"

    private sealed class LaterAttempt {
        class Played(val device: VoiceOrigin) : LaterAttempt()

        /** The speaker or the target's microphone turned busy (a recording; the Watch refused it as busy): try again. */
        object Busy : LaterAttempt()

        /** A newer request took the speaker while it played. */
        object Superseded : LaterAttempt()

        /** It can't be spoken: [detail] says why. */
        class Failed(val detail: String) : LaterAttempt()
    }

    /**
     * One later reply: wait (no CPU hold) until no voice request is in flight, no later reply plays
     * and the target's microphone isn't claimed; then, inside [laterWork], synthesize it (once) and
     * try to play it ([playLater]). A busy outcome (a recording claimed the microphone, the Watch
     * refused it as busy) goes back to waiting, with a back-off. It must START playing within
     * An attempt may only begin within [laterDeferMaxMs] of its arrival (a free speaker and
     * microphone); then come its synthesis (first attempt, at most [LATER_SYNTHESIS_MAX_MS]), the
     * wake window's close (at most [LATER_WINDOW_CLOSE_MS]) and playback (at most
     * [LATER_PLAYBACK_MAX_MS]). A Stop or the option switched off ends it. Reported exactly once:
     * played on a device, or why not.
     */
    private suspend fun speakLater(reply: LaterReply) {
        try {
            var audio: SpokenAudio? = null
            var refusals = 0
            while (true) {
                val left = reply.deadlineMs - monotonicMs()
                if (left <= 0 || withTimeoutOrNull(left) { awaitLaterSpeaker() } == null) {
                    return reply.report(false, "not played: the speaker or microphone stayed busy")
                }
                var attempt: LaterAttempt = LaterAttempt.Busy
                laterWork {
                    val spoken = audio ?: withTimeoutOrNull(LATER_SYNTHESIS_MAX_MS) { speech.speak(reply.text) }
                    audio = spoken
                    attempt = if (spoken == null) LaterAttempt.Failed("not played: speech synthesis took too long")
                        else playLater(reply, spoken)
                }
                when (val result = attempt) {
                    is LaterAttempt.Played -> return reply.report(true, result.device.name.lowercase())
                    is LaterAttempt.Failed -> return reply.reportNotPlayed(result.detail)
                    LaterAttempt.Superseded -> return reply.reportNotPlayed("stopped: a newer request took the speaker")
                    LaterAttempt.Busy -> {
                        delay((LATER_BUSY_RETRY_MS shl refusals.coerceAtMost(3)).coerceAtMost(LATER_BUSY_RETRY_MAX_MS))
                        refusals += 1
                    }
                }
            }
        } catch (error: HermesException) {
            reply.reportNotPlayed((error.message ?: error.javaClass.simpleName).take(160))
        } catch (error: IOException) {
            reply.reportNotPlayed("network: ${error.javaClass.simpleName}")
        } catch (stopped: CancellationException) {
            // A Stop (or off) that lands after the sink confirmed the end reports what happened: played.
            reply.reportNotPlayed(stoppedDetail())
            throw stopped
        }
    }

    /** No voice request is in flight, no turn holds the speaker, no later reply plays, and the target's microphone isn't claimed. */
    private fun laterSpeakerFree(): Boolean {
        val target = playbackRoute.current()?.device ?: return true
        return synchronized(floorLock) { laterFreeLocked(target) }
    }

    private fun laterFreeLocked(device: VoiceOrigin): Boolean =
        turnsInFlight == 0 && floorJob == null && ownership.later == null && !ownership.claimedLocked(device)

    private suspend fun awaitLaterSpeaker() {
        while (!laterSpeakerFree()) delay(LATER_POLL_MS)
    }

    /**
     * Admits [audio] to the target's speaker, atomically with the microphone claims and the floor
     * ([AudioOwnership]): only if nothing records there, no voice request is in flight and nothing
     * else plays. Then [laterSpeaker] is told, an open wake window there closes, and it plays (at
     * most [LATER_PLAYBACK_MAX_MS]). A turn's claim ([claimFloor]) stops it (Superseded); a
     * recording's claim stops it (Busy: played again afterwards) unless the sink already confirmed
     * it played to the end ([PlaybackSink.playConfirmed], recorded by [markPlayed] under the lock
     * before the sink resumes this job): completion wins, so a confirmed reply is never played twice
     * and never reported as not played. A recording still waits for its teardown ([LaterSlot.stopped]).
     */
    private suspend fun playLater(reply: LaterReply, audio: SpokenAudio): LaterAttempt = coroutineScope {
        val target = playbackRoute.current() ?: return@coroutineScope LaterAttempt.Busy
        val device = target.device
        val cue = PlaybackCue(reply.request.turnId, reply.request.origin, SpokenRole.FINAL, reply.sequence, reply.text, device, later = true)
        var failed: LaterAttempt.Failed? = null
        lateinit var slot: LaterSlot
        val play = launch(start = CoroutineStart.LAZY) {
            // An open wake window closes now that it was told a later reply holds this speaker; it is never spoken over.
            withTimeoutOrNull(LATER_WINDOW_CLOSE_MS) { while (wakeListening(device)) delay(LATER_POLL_MS) } ?: return@launch
            val returned = try {
                // The sink confirms the end from its own completion signal, before it resumes this (cancellable) job.
                withTimeoutOrNull(LATER_PLAYBACK_MAX_MS) {
                    target.sink.playConfirmed(audio, cue) { markPlayed(slot, reply, cue, returned = false) }
                    // The sink returned normally: by its contract it played to the end (even if a stop came too late to stop it).
                    markPlayed(slot, reply, cue, returned = true)
                    true
                } == true
            } catch (refused: HermesPlaybackBusyException) {
                return@launch
            }
            if (!returned) failed = LaterAttempt.Failed("not played: playback did not finish in time")
        }
        slot = LaterSlot(device, play)
        val admitted = synchronized(floorLock) {
            (playbackRoute.current() === target && laterFreeLocked(device)).also { if (it) ownership.later = slot }
        }
        if (!admitted) {
            play.cancel()
            return@coroutineScope LaterAttempt.Busy
        }
        laterSpeaker(device, true)
        // Let go the moment its job ends, on every path; only then may a recording waiting for it open the microphone.
        play.invokeOnCompletion {
            try {
                laterSpeaker(device, false)
            } finally {
                slot.stopped.complete(Unit)
            }
        }
        try {
            play.start()
            play.join()
        } finally {
            synchronized(floorLock) { if (ownership.later === slot) ownership.later = null }
        }
        val (completed, yielded) = synchronized(floorLock) { slot.completed to slot.yielded }
        when {
            completed -> LaterAttempt.Played(device)
            yielded -> LaterAttempt.Busy
            failed != null -> failed!!
            play.isCancelled -> LaterAttempt.Superseded
            // Refused as busy by the Watch, or the wake window did not close in time.
            else -> LaterAttempt.Busy
        }
    }

    /**
     * The sink confirmed [slot]'s reply played to the end: recorded once, under the floor lock, so a
     * recording's claim or a newer request that comes after it can neither stop it, replay it nor
     * report it as not played. A completion signal ([returned] false) for an attempt that was
     * already stopped (yielded, superseded, cancelled) is ignored: a stale or late callback can't
     * turn a stopped attempt into a played one. A normal return of the sink ([returned]) is its
     * contract's own confirmation and always counts.
     */
    private fun markPlayed(slot: LaterSlot, reply: LaterReply, cue: PlaybackCue, returned: Boolean) {
        val first = synchronized(floorLock) {
            (!slot.completed && (returned || (!slot.yielded && !slot.superseded && !slot.job.isCancelled))).also {
                if (it) {
                    slot.completed = true
                    reply.playedOn = slot.device
                }
            }
        }
        if (!first) return
        listener.onPlayed(cue)
        reply.request.listener?.onPlayed(cue)
    }

    private fun diagnostic(request: VoiceTurnRequest, stage: String, detail: String) {
        listener.onDiagnostic(request.turnId, stage, detail)
        request.listener?.onDiagnostic(request.turnId, stage, detail)
    }

    /** [speech] for [role], with its length, duration and size (or failure class) as a diagnostic. */
    private suspend fun timedSpeak(request: VoiceTurnRequest, role: SpokenRole, text: String): SpokenAudio {
        val started = System.nanoTime()
        try {
            return speech.speak(text).also {
                diagnostic(request, "speech", "role=$role chars=${text.length} ms=${(System.nanoTime() - started) / 1_000_000} bytes=${it.bytes.size}")
            }
        } catch (error: Exception) {
            diagnostic(request, "speech", "role=$role chars=${text.length} ms=${(System.nanoTime() - started) / 1_000_000} failed=${error.javaClass.simpleName}")
            throw error
        }
    }

    /** [handOff], with the device and how long it took to be confirmed (or how it failed) as a diagnostic. */
    private suspend fun timedHandOff(request: VoiceTurnRequest, audio: SpokenAudio, role: SpokenRole, sequence: Int, text: String) {
        val started = System.nanoTime()
        val device = playbackRoute.current()?.device?.name?.lowercase() ?: "none"
        try {
            handOff(request, audio, role, sequence, text)
            diagnostic(request, "handoff", "role=$role seq=$sequence device=$device ms=${(System.nanoTime() - started) / 1_000_000} confirmed=true")
        } catch (error: Throwable) {
            diagnostic(request, "handoff", "role=$role seq=$sequence device=$device ms=${(System.nanoTime() - started) / 1_000_000} " +
                "failed=${error.javaClass.simpleName}")
            throw error
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

    /**
     * The newest turn owns the speaker; the previous owner's response playback, or a later reply
     * being played, is stopped first.
     */
    private suspend fun claimFloor(): Long {
        val (generation, previous, later) = synchronized(floorLock) {
            floorGeneration += 1
            val previous = floorJob
            // A later reply confirmed played to the end is left alone (it only ends its job), and stays
            // registered so a recording still waits for its teardown.
            val later = ownership.later?.takeIf { !it.completed }
            later?.superseded = true
            floorJob = null
            if (later != null) ownership.later = null
            Triple(floorGeneration, previous, later?.job)
        }
        for (stopped in listOfNotNull(previous, later)) {
            stopped.cancel(TurnSupersededException())
            stopped.join()
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

        /** How long a delivered turn's destination is followed for later replies. */
        const val LATER_WINDOW_MS = 30 * 60_000L

        /**
         * How long after its arrival an attempt to speak a later reply may still begin (it waits for a busy speaker or
         * microphone); synthesis, the wake window's close and playback come after that, each with its own bound.
         */
        const val LATER_DEFER_MAX_MS = 10 * 60_000L

        /** Synthesizing one later reply (once per reply). */
        const val LATER_SYNTHESIS_MAX_MS = 2 * 60_000L

        /** Playing one later reply, once admitted to a speaker (per attempt). */
        const val LATER_PLAYBACK_MAX_MS = 5 * 60_000L

        /**
         * Later replies that arrived and wait per follow (besides the one its speaker prepares or plays), and over all
         * follows (counting those being prepared or played); more are reported as not played at once.
         */
        const val LATER_QUEUE_MAX = 4
        const val LATER_PENDING_MAX = 8
        private const val LATER_POLL_MS = 250L
        private const val LATER_BUSY_RETRY_MS = 1_000L
        private const val LATER_BUSY_RETRY_MAX_MS = 8_000L
        private const val LATER_WINDOW_CLOSE_MS = 3_000L
    }
}
