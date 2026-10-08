package com.rumi.hermesvoice.core.voice

import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.HermesException
import com.rumi.hermesvoice.core.HermesPlaybackBusyException
import com.rumi.hermesvoice.core.HermesPlaybackException
import com.rumi.hermesvoice.core.HermesProtocolException
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
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
    /**
     * The turn's own reply, which may arrive while another request is being recorded: like a later
     * reply it is refused by a recording device rather than played over the recording (the Watch
     * receives the same wire flag as for [later]); it is still not a later reply.
     */
    val deferrable: Boolean = false,
    /** Which chunk of a long reply this is (0 for the first or only one; see [TtsBatcher]); [text] is then that chunk's text. */
    val part: Int = 0,
    /**
     * The headset output this answer was admitted to (the Phone's "Use headset"): the sink plays on THAT device only, and fails
     * rather than fall back to the speaker when it is gone. Null: the ordinary route, whatever Android picks.
     */
    val headset: com.rumi.hermesvoice.core.headset.AudioEndpoint? = null,
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

    /**
     * Hands a reply-arrival alert to the device this sink plays on, for that device to show itself. True only when the link took
     * it. The Phone's own sink (and any sink without a separate device) returns false: the Phone shows its own alerts.
     */
    suspend fun deliverReplyAlert(alert: com.rumi.hermesvoice.core.notify.ReplyAlert): Boolean = false
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
    /**
     * This request was made by voice (every request here is), so its final recipient gets the leading voice marker ([VoiceTurnPrompt]).
     * Frozen with the request: never read from the selected conversation, the playback device or a global flag.
     */
    val sentByVoice: Boolean = true,
)

enum class VoiceTurnStage {
    TRANSCRIBING, ROUTING, CREATING, ACKNOWLEDGING,

    /** No longer entered (a request to a busy conversation is sent at once); kept for the Phone/Watch wire format. */
    QUEUED,
    DELIVERING, RESPONDING,
}

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
     * Turn [turnId] left the transmitting phase (transcribe, route, acknowledge, send): it was delivered and now only waits for
     * its reply, or it ended or was refused. From here nothing is held on the device for it. Called at most once per turn.
     */
    fun onTransmitted(turnId: String) {}

    /**
     * A later reply of delivered turn [turnId] (see [VoiceTurnOrchestrator]) was [played] on the
     * device named in [detail], or not, with the reason in [detail]. Every later reply that arrived
     * while the turn was followed is reported here exactly once.
     */
    fun onLaterReply(turnId: String, played: Boolean, detail: String) {}

    /**
     * Following turn [turnId]'s conversation for later replies ended (no more arrivals): [reason] is
     * "window" (the configured follow window ran out), "superseded" (the app sent that conversation something new),
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

    /** Turn [turnId] ended with [outcome] (not called when [run] itself is cancelled by its caller). */
    fun onOutcome(turnId: String, outcome: VoiceTurnOutcome) {}

    /** The user's Stop for exactly this turn was accepted ([VoiceTurnOrchestrator.stopTurn]). */
    fun onStopRequested(turnId: String) {}

    /** One speech-synthesis chunk of turn [turnId] finished ([failure] is the exception's class name, never its message). */
    fun onSpeechChunk(turnId: String, index: Int, count: Int, ms: Long, failure: String?) {}
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

    /** The user stopped this turn ([VoiceTurnOrchestrator.stopTurn]): nothing more is sent, waited for or played for it. */
    object Stopped : VoiceTurnOutcome()

    data class Completed(val route: AssembledRoute, val spoken: List<SpokenRole>) : VoiceTurnOutcome()
}

private class TurnSupersededException : CancellationException("superseded by a newer voice turn")

/** The user stopped one turn. */
private class TurnStopped : CancellationException("turn stopped")

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
 *
 * Waiting: a delivered turn only WAITS for its reply; nothing is held for it meanwhile, so new
 * requests are accepted from either device and every turn awaits its own reply independently. A
 * newer turn never cancels or supersedes an older one: replies are spoken one at a time, in
 * arrival order, by the speaker arbiter below (a reply that is ready while another request is
 * recorded, or another reply plays, waits, and is never played over a recording nor dropped
 * silently); an acknowledgement waits for the reply that is playing and stops only a LATER reply.
 * Requests to the same destination session are NOT held back: each is sent at once and Hermes' own busy-input
 * policy decides whether it steers, queues or interrupts the running turn (the app never picks one). A finite
 * per-session count ([SESSION_QUEUE_MAX]) only refuses overload visibly. Only a user's Stop
 * ([stopTurn], or cancelling [run]) ends a pending turn early.
 * Deduplication: a replayed turn id is ignored; duplicate responses are filtered by
 * [RecipientResponseTracker].
 */
class VoiceTurnOrchestrator(
    private val speech: HermesSpeechGateway,
    private val conversations: HermesConversationPort,
    private val config: suspend (TurnRouting) -> VoiceTurnConfig,
    private val listener: VoiceTurnListener = object : VoiceTurnListener {},
    private val routingTimeoutMs: Long = 120_000,
    /**
     * How long the destination may stay SILENT (no gateway event at all) while a turn waits for its reply. It is an
     * inactivity bound: every event restarts it, and the synthesis and playback of replies are not counted in it.
     */
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
     * The window for a turn about to be followed, read once when its follow starts (a snapshot: later changes never touch a
     * follow already running). Null: every follow uses [laterWindowMs]. An out-of-range answer falls back to [LATER_WINDOW_MS].
     */
    private val laterWindowProvider: (() -> Long)? = null,
    /**
     * Wraps one unit of work of speaking a reply: the synthesis of one chunk, or the handoff and playback of one chunk. The
     * Phone keeps the CPU awake for that and no longer (the wait for a free speaker and microphone happens outside it), so the
     * hold is renewed per chunk of a long reply and is never held while a reply only waits.
     */
    private val laterWork: suspend (suspend () -> Unit) -> Unit = { it() },
    /** The user's opt-in to speak later replies, read when a turn would be followed and at every later reply. Off by default. */
    private val laterEnabled: () -> Boolean = { false },
    /**
     * Whether a wake-phrase window listens on [device] now. It closes for a reply admitted to
     * that device's speaker ([laterSpeaker]), so it is waited for only briefly, right before playback.
     */
    private val wakeListening: (VoiceOrigin) -> Boolean = { false },
    /** How long after its arrival an attempt to speak a reply may still BEGIN (it waits for a busy speaker or microphone). */
    private val laterDeferMaxMs: Long = LATER_DEFER_MAX_MS,
    /** The microphones and the reply speaker of this process ([AudioOwnership]); its lock is the speaker arbiter's. */
    val ownership: AudioOwnership = AudioOwnership(),
    /**
     * A reply's clip holds [device]'s speaker (true from its admission to the end of that clip, on every path):
     * the Phone closes its wake windows for it. Never told while one only waits, is synthesized (the next chunk
     * of a long reply included), or is handed to another device.
     */
    private val laterSpeaker: (device: VoiceOrigin, holding: Boolean) -> Unit = { _, _ -> },
    /** The longest the short acknowledgement's synthesis may take (the acknowledgement precedes delivery). */
    private val ackSynthesisMaxMs: Long = ACK_SYNTHESIS_MAX_MS,
    /** Most voice requests pending at once (transmitting, queued, awaiting or speaking); a request beyond it is refused visibly. */
    private val maxPendingTurns: Int = MAX_PENDING_TURNS,
    /** Most requests pending per destination session, the one awaiting its reply included; a request beyond it is refused visibly (overload guard only, nothing waits). */
    private val maxPerSession: Int = SESSION_QUEUE_MAX,
    /** Told of each actual final answer received (own or later), with what became of its audio: the arrival alert's only input. */
    private val finalReply: (com.rumi.hermesvoice.core.notify.FinalReply) -> Unit = {},
    /**
     * The Phone's "Use headset" (null: none, the behavior is exactly as without it). With the setting on and a personal headset
     * connected, a final answer that plays on the Phone is bound to that headset when it is admitted, and later answers and the
     * typed chat answers are spoken there too, whatever the later-reply option says. See [com.rumi.hermesvoice.core.headset.HeadsetPolicy].
     */
    private val headset: com.rumi.hermesvoice.core.headset.HeadsetPolicy? = null,
    /**
     * The Phone's speaker sink, which plays to the connected headset. With "Use headset" on and a headset output connected, EVERY
     * spoken reply (whoever asked, whichever device is the playback target) goes through this sink and nowhere else; null: while
     * that is the case nothing is spoken (the Watch and the loudspeaker are never used instead).
     */
    private val privateSink: PlaybackSink? = null,
) {
    private val deliveryLock = Mutex()
    private val floorLock = ownership.lock
    private val recentTurnIds = LinkedHashSet<String>()

    private val pendingTurns = PendingTurns()

    /** The accepted voice requests that are not finished yet, in acceptance order (the pending list of the Phone and the Watch). */
    val pending: StateFlow<List<PendingTurn>> get() = pendingTurns.state

    private val sessionGate = SessionGate(maxPerSession)
    private val stoppable = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val stopRequested = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Voice requests in their TRANSMITTING phase (from the moment [run] takes one, through its transcription, routing,
     * acknowledgement and sending; not while it only waits for its reply or for an earlier request to its session):
     * later replies wait for them. Guarded by [floorLock].
     */
    private var turnsInFlight = 0

    /** Acknowledgements waiting for, or holding, a speaker: replies give way to them. Guarded by [floorLock]. */
    private var acksWaiting = 0
    private var acksPlaying = 0

    /** Replies (own and later) that wait for a speaker, first come first served. Guarded by [floorLock]. */
    private val replyQueue = ArrayList<Utterance>()

    private val followers = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()

    /** Later replies that arrived and are not reported yet, over all follows (bounded by [LATER_PENDING_MAX]). */
    private val laterPending = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Stops following every delivered turn's later replies, and every later reply that arrived and
     * waits or plays (each reported as not played). A Stop, or the option switched off. Safe to repeat.
     */
    fun onPrivateOutputChanged(active: Boolean) {
        // A reply that plays on the Watch when the headset takes over is stopped (the link sends the Watch its STOP) and spoken again
        // on the headset from the first chunk not confirmed played; queued replies are resolved to the headset when they are admitted.
        if (active) ownership.withdrawLater(VoiceOrigin.WATCH)
    }

    fun stopFollowing() {
        followers.toList().forEach { it.cancel(LaterStopped()) }
    }

    /**
     * Stops ONE pending turn wherever it is (being transmitted, waiting for
     * its reply, or speaking it): [run] returns [VoiceTurnOutcome.Stopped], its queue place, pending entry and
     * playback are released and nothing more is sent or played for it. False when no such turn is running.
     */
    fun stopTurn(turnId: String): Boolean {
        val job = stoppable[turnId] ?: return false
        stopRequested += turnId
        job.cancel(TurnStopped())
        listener.onStopRequested(turnId)
        return true
    }

    private class Prepared(val route: AssembledRoute, val settings: ResponsePlaybackSettings)

    private class Delivered(val route: AssembledRoute, val turn: SubmittedTurn, val settings: ResponsePlaybackSettings)

    /** A request's time in its transmitting phase: ended once, on whichever path comes first. */
    private inner class Transmission(private val request: VoiceTurnRequest) {
        private var open = true

        fun end() {
            val ended = synchronized(floorLock) {
                if (open) {
                    open = false
                    turnsInFlight -= 1
                    true
                } else false
            }
            if (!ended) return
            listener.onTransmitted(request.turnId)
            request.listener?.onTransmitted(request.turnId)
        }
    }

    suspend fun run(request: VoiceTurnRequest): VoiceTurnOutcome {
        // From here until this request is delivered, answered or refused, later replies wait: the recording's
        // microphone claim becomes this count under the same lock, so there is no gap between them.
        val transmission = Transmission(request)
        synchronized(floorLock) {
            turnsInFlight += 1
            request.microphone?.release()
        }
        try {
            val outcome = try {
                coroutineScope { runCounted(request, transmission) }
            } catch (cancelled: CancellationException) {
                if (stopRequested.contains(request.turnId) && currentCoroutineContext().isActive) VoiceTurnOutcome.Stopped else throw cancelled
            }
            runCatching { listener.onOutcome(request.turnId, outcome) }
            return outcome
        } finally {
            transmission.end()
            pendingTurns.remove(request.turnId)
            if (!stoppable.containsKey(request.turnId)) stopRequested.remove(request.turnId)
        }
    }

    private suspend fun runCounted(request: VoiceTurnRequest, transmission: Transmission): VoiceTurnOutcome {
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
        // The pending bound is checked before acceptance, so a refused request never becomes the playback target.
        if (pendingTurns.size >= maxPendingTurns && !alreadyAccepted(request.turnId)) {
            listener.onNotAdmitted(request.turnId, request.origin, PENDING_LIMIT)
            return VoiceTurnOutcome.NotAdmitted(PENDING_LIMIT)
        }
        if (!accept(request)) return VoiceTurnOutcome.Duplicate(request.turnId)
        val self = currentCoroutineContext()[Job]!!
        stoppable[request.turnId] = self
        pendingTurns.add(PendingTurn(request.turnId, request.origin, PendingPhase.TRANSMITTING))
        // Held from the route's decision until this turn's own reply completes, fails or is stopped.
        val held = arrayOfNulls<SessionGate.Ticket>(1)
        var unreleased: SubmittedTurn? = null
        try {
            listener.onAccepted(request.turnId, request.origin)
            request.listener?.onAccepted(request.turnId, request.origin)
            val prepared = deliveryLock.withLock {
                when (val result = prepare(request, held)) {
                    is Prepared -> result
                    is VoiceTurnOutcome -> return result
                    else -> error("unexpected preparation result")
                }
            }
            val delivered = when (val result = send(request, prepared)) {
                is Delivered -> result
                is VoiceTurnOutcome -> return result
                else -> error("unexpected delivery result")
            }
            unreleased = delivered.turn
            listener.onDelivered(delivered.route)
            request.listener?.onDelivered(delivered.route)
            if (!delivered.turn.attributable) {
                unreleased = null
                delivered.turn.release()
                return VoiceTurnOutcome.DeliveredUnattributed(delivered.route, delivered.turn.submitStatus)
            }
            transmission.end()
            pendingTurns.update(request.turnId, PendingPhase.AWAITING, delivered.route.destination.alias)
            val (outcome, nextSequence) = respond(request, delivered)
            sessionGate.release(held[0]!!)
            unreleased = null
            follow(request, delivered.turn, nextSequence, delivered.route.destination.storedSessionId)
            return outcome
        } finally {
            // Stopped or failed before the reply was followed: the destination subscription is released here, once.
            unreleased?.release()
            held[0]?.let { sessionGate.release(it) }
            stoppable.remove(request.turnId, self)
        }
    }

    private fun alreadyAccepted(turnId: String): Boolean = synchronized(recentTurnIds) { turnId in recentTurnIds }

    /** Up to and including the acknowledgement: it runs under [deliveryLock], so acknowledgements keep the capture order. */
    private suspend fun prepare(request: VoiceTurnRequest, held: Array<SessionGate.Ticket?>): Any {
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
            pendingTurns.update(request.turnId, PendingPhase.TRANSMITTING, route.destination.alias)
            // Its place in the destination's line (before anything is spoken): a full line refuses visibly, never drops.
            held[0] = sessionGate.enter(route.destination.storedSessionId)
                ?: return VoiceTurnOutcome.NotDelivered(VoiceTurnStage.QUEUED, QUEUE_FULL)

            stage = VoiceTurnStage.ACKNOWLEDGING
            notifyStage(request, stage)
            val ackAudio = timedSpeak(request, SpokenRole.ACK, route.ackText)
            playAck(request, ackAudio, route.ackText)
            return Prepared(route, cfg.playback)
        } catch (auth: HermesAuthRequiredException) {
            return VoiceTurnOutcome.NotDelivered(stage, auth.message ?: "auth_required", authRequired = true)
        } catch (error: HermesException) {
            return VoiceTurnOutcome.NotDelivered(stage, error.message ?: error.javaClass.simpleName)
        } catch (error: IOException) {
            return VoiceTurnOutcome.NotDelivered(stage, "network: ${error.javaClass.simpleName}")
        }
    }

    /**
     * After the acknowledgement: submits the ORIGINAL transcript at once, even while an earlier request to the same
     * session is still being answered. Not under [deliveryLock].
     */
    private suspend fun send(request: VoiceTurnRequest, prepared: Prepared): Any {
        val stage = VoiceTurnStage.DELIVERING
        val route = prepared.route
        try {
            notifyStage(request, stage)
            // After the acknowledgement, which can take a while: the destination must still be deliverable.
            recipientCreator?.requireDeliverable(route.destination.storedSessionId)
            if (route.created && recipientCreator?.markSubmitted(request.turnId) == false) {
                return VoiceTurnOutcome.Duplicate(request.turnId)
            }
            val submitted = conversations.submit(route.destination.storedSessionId,
                if (request.sentByVoice) VoiceTurnPrompt.compose(route.originalTranscript) else route.originalTranscript)
            diagnostic(request, "submitted", "status=${submitted.submitStatus} attributable=${submitted.attributable} " +
                "created=${route.created} direct=${route.direct}")
            return Delivered(route, submitted, prepared.settings)
        } catch (auth: HermesAuthRequiredException) {
            return VoiceTurnOutcome.NotDelivered(stage, auth.message ?: "auth_required", authRequired = true)
        } catch (error: HermesException) {
            return VoiceTurnOutcome.NotDelivered(stage, error.message ?: error.javaClass.simpleName)
        } catch (error: IOException) {
            return VoiceTurnOutcome.NotDelivered(stage, "network: ${error.javaClass.simpleName}")
        }
    }

    /** The outcome, and the next playback sequence number of this turn (for its later replies). */
    private suspend fun respond(request: VoiceTurnRequest, delivered: Delivered): Pair<VoiceTurnOutcome, Int> {
        val route = delivered.route
        val spoken = mutableListOf<SpokenRole>()
        var failure: String? = null
        var sequence = 1
        notifyStage(request, VoiceTurnStage.RESPONDING)
        val tracker = RecipientResponseTracker(delivered.settings)
        var finalDecision: ResponseDecision? = null
        var finalComplete = false
        val privateSeen = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            // Waiting for the next event is bounded by the destination's SILENCE only ([responseTimeoutMs]); a reply being
            // synthesized or played is not waiting, and events that arrive meanwhile are kept in order.
            delivered.turn.collect(responseTimeoutMs) { event ->
                val decision = tracker.onEvent(event) ?: return@collect
                if (decision.role == SpokenRole.FINAL) {
                    finalDecision = decision
                    finalComplete = event is RecipientEvent.Complete && (event.status == null || event.status == "complete")
                }
                notifyResponse(request, decision)
                val text = decision.speakText ?: return@collect
                try {
                    speakOwn(request, decision.role, sequence++, text, privateSeen)
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
        // A real, non-empty final answer was received (never an interim, empty or errored completion): its audio decides the alert.
        finalDecision?.takeIf { finalComplete && it.text.isNotEmpty() }?.let { final ->
            val heard = SpokenRole.FINAL in spoken ||
                (final.reason == "final_already_spoken" && (SpokenRole.FIRST in spoken || SpokenRole.MIDDLE in spoken))
            emitFinalReply(request, ReplyAlert.identityOf(request.turnId, "final"), route.destination.storedSessionId, FinalReplySource.OWN, heard, false, final.text, quiet = privateSeen.get())
        }
        val outcome = when {
            failure != null && SpokenRole.FINAL !in spoken -> VoiceTurnOutcome.DeliveredResponseFailed(route, spoken.toList(), failure!!)
            else -> VoiceTurnOutcome.Completed(route, spoken.toList())
        }
        diagnostic(request, "responses", "${delivered.turn.diagnostics()} spoken=${spoken.joinToString(",").ifEmpty { "none" }} " +
            "outcome=${outcome.javaClass.simpleName}")
        return outcome to sequence
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
    private fun follow(request: VoiceTurnRequest, turn: SubmittedTurn, firstSequence: Int, storedSessionId: String) {
        val scope = laterScope
        if (scope == null || !(laterEnabled() || headset?.enabled == true)) return turn.release()
        val windowMs = laterWindowProvider?.let { provider -> provider().takeIf { it in LATER_WINDOW_MIN_MS..LATER_WINDOW_MAX_MS } ?: LATER_WINDOW_MS } ?: laterWindowMs
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // A reply dropped from the queue by a Stop (or the option switched off) is reported as such.
            val arrived = Channel<LaterReply>(LATER_QUEUE_MAX) { it.report(false, stoppedDetail(), cancelled = !it.admitting) }
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
                reason = when (turn.collectLater(windowMs) { event ->
                    // Switched off meanwhile: this reply and every later one are not spoken (a headset answer is the exception, below).
                    val optedIn = laterEnabled()
                    val forcedByHeadset = !optedIn && headset?.output() != null
                    if (!optedIn && headset?.enabled != true) throw LaterStopped()
                    val text = event.text.trim()
                    // Use headset on, but no headset connected now and no opt-in: exactly as if neither were on (nothing is spoken or alerted).
                    if (text.isNotEmpty() && (optedIn || forcedByHeadset)) {
                        admit(arrived, LaterReply(request, storedSessionId, text, sequence++, monotonicMs() + laterDeferMaxMs, headsetOnly = forcedByHeadset))
                    }
                }) {
                    LaterEnd.WINDOW -> "window"
                    LaterEnd.SUPERSEDED -> "superseded"
                    LaterEnd.DISCONNECTED -> "disconnected"
                    LaterEnd.RELEASED -> "released"
                }
            } catch (stopped: CancellationException) {
                reason = if (laterEnabled() || headset?.enabled == true) "stopped" else "off"
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

    /** A reply to be spoken: the turn's own response ([own]) or a later reply. */
    private open inner class Utterance(
        val request: VoiceTurnRequest,
        val role: SpokenRole,
        val sequence: Int,
        val text: String,
        deadlineMs: Long,
        val own: Boolean,
    ) {
        /** When an attempt to begin the next clip stops waiting for a free speaker: renewed once each clip is ready. */
        @Volatile var deadlineMs: Long = deadlineMs


        /** The device its sink confirmed it played to the end on ([markPlayed]); written under the floor lock. */
        @Volatile var playedOn: VoiceOrigin? = null

        /** Where this reply plays regardless of the playback route (the typed chat answer: always the Phone); null: the route's target. */
        open val fixedTarget: PlaybackTarget? get() = null

        /** Spoken only because "Use headset" forced it (the later-reply option is off): it plays on the headset or not at all. */
        open val headsetOnly: Boolean get() = false

        /** The headset this reply was bound to when its first clip was admitted; [bindingDecided] says the (possibly null) decision was made. */
        @Volatile var binding: com.rumi.hermesvoice.core.headset.AudioEndpoint? = null
        @Volatile var bindingDecided = false
    }

    /** One later reply that arrived: spoken (or not) and reported exactly once. */
    private inner class LaterReply(request: VoiceTurnRequest, val storedSessionId: String, text: String, sequence: Int, deadlineMs: Long, headsetOnly: Boolean = false) :
        Utterance(request, SpokenRole.FINAL, sequence, text, deadlineMs, own = false) {
        override val headsetOnly: Boolean = headsetOnly

        private val reported = java.util.concurrent.atomic.AtomicBoolean(false)

        /** True only while [admit] tries to queue it: a refusal then is not a Stop's drop. */
        @Volatile var admitting = false

        /** [cancelled]: the user's Stop (or switching later replies off) ended it, so it is no arrival to alert about. */
        fun report(played: Boolean, detail: String, cancelled: Boolean = false) {
            if (!reported.compareAndSet(false, true)) return
            laterPending.decrementAndGet()
            listener.onLaterReply(request.turnId, played, detail)
            request.listener?.onLaterReply(request.turnId, played, detail)
            emitFinalReply(request, ReplyAlert.identityOf(request.turnId, "later$sequence"), storedSessionId, FinalReplySource.LATER, played, cancelled && !played, text,
                quiet = binding != null || headsetOnly)
        }

        /** Not played for [detail], unless its sink already confirmed it played to the end: then that is the truth. */
        fun reportNotPlayed(detail: String, cancelled: Boolean = false) {
            val device = playedOn
            if (device != null) report(true, device.name.lowercase()) else report(false, detail, cancelled)
        }
    }

    /**
     * [quiet]: the answer was meant for the headset only (or the headset is the private output now): its arrival alert is visual only
     * here, never a sound on the Phone and never handed to the Watch to sound there.
     */
    private fun emitFinalReply(request: VoiceTurnRequest, identity: String, storedSessionId: String, source: FinalReplySource, heard: Boolean, cancelled: Boolean, text: String, quiet: Boolean = false) {
        val silent = quiet || privateOutputNow()
        val target = playbackRoute.current()
        runCatching {
            finalReply(FinalReply(identity, storedSessionId, target?.device ?: request.origin, heard, cancelled, source, if (silent) null else target?.sink, text, quiet = silent))
        }
    }

    /** Queues [reply] for this follow's speaker; too many waiting (here or over all follows) is reported at once. */
    private fun admit(arrived: Channel<LaterReply>, reply: LaterReply) {
        reply.admitting = true
        val queued = try {
            laterPending.incrementAndGet() <= LATER_PENDING_MAX && arrived.trySend(reply).isSuccess
        } finally {
            reply.admitting = false
        }
        if (!queued) reply.report(false, "not played: too many later replies waiting")
    }

    private fun stoppedDetail(): String = if (laterEnabled() || headset?.enabled == true) "not played: stopped" else "not played: switched off"

    private sealed class LaterAttempt {
        class Played(val device: VoiceOrigin) : LaterAttempt()

        /** One clip of a longer reply played to its end; the next chunk still has to be spoken. */
        object ClipPlayed : LaterAttempt()

        /** The speaker or the target's microphone turned busy (a recording; the Watch refused it as busy): try again. */
        object Busy : LaterAttempt()

        /** A newer request took the speaker while it played. */
        object Superseded : LaterAttempt()

        /** It can't be spoken: [detail] says why. */
        class Failed(val detail: String) : LaterAttempt()
    }

    /** A reply's chunks, synthesized with a diagnostic per chunk (metadata only). */
    private fun chunkedFor(request: VoiceTurnRequest, role: SpokenRole, text: String, scope: CoroutineScope): ChunkedSpeech =
        ChunkedSpeech(speech, text, scope) { index, count, chars, ms, bytes, failure ->
            listener.onSpeechChunk(request.turnId, index, count, ms, failure)
            val part = if (count > 1) " part=${index + 1}/$count" else ""
            diagnostic(request, "speech", "role=$role chars=$chars ms=$ms " + (if (failure == null) "bytes=$bytes" else "failed=$failure") + part)
        }

    /** One later reply: spoken (see [speakDeferred]) and reported exactly once: played on a device, or why not. */
    private suspend fun speakLater(reply: LaterReply) {
        try {
            coroutineScope {
                val chunked = chunkedFor(reply.request, reply.role, reply.text, this)
                try {
                    when (val result = speakDeferred(reply, chunked)) {
                        is LaterAttempt.Played -> reply.report(true, result.device.name.lowercase())
                        is LaterAttempt.Failed -> reply.reportNotPlayed(result.detail)
                        LaterAttempt.Superseded -> reply.reportNotPlayed("stopped: a newer request took the speaker")
                        LaterAttempt.Busy -> reply.reportNotPlayed("not played: the speaker or microphone stayed busy")
                        LaterAttempt.ClipPlayed -> error("a clip is not a reply result")
                    }
                } finally {
                    chunked.close()
                }
            }
        } catch (error: HermesException) {
            reply.reportNotPlayed((error.message ?: error.javaClass.simpleName).take(160))
        } catch (error: IOException) {
            reply.reportNotPlayed("network: ${error.javaClass.simpleName}")
        } catch (stopped: CancellationException) {
            // A Stop (or off) that lands after the sink confirmed the end reports what happened: played.
            reply.reportNotPlayed(stoppedDetail(), cancelled = true)
            throw stopped
        }
    }

    /**
     * The turn's own response [text]: synthesized in chunks ([TtsBatcher]) and spoken through the same speaker arbiter as
     * a later reply, so it waits (never plays over a recording or another reply, never cancels one, and is not dropped
     * silently) and an acknowledgement waits for it. Throws [HermesPlaybackException] when it can't be spoken.
     */
    private suspend fun speakOwn(request: VoiceTurnRequest, role: SpokenRole, sequence: Int, text: String, privateSeen: java.util.concurrent.atomic.AtomicBoolean) = coroutineScope {
        val chunked = chunkedFor(request, role, text, this)
        val utterance = Utterance(request, role, sequence, text, monotonicMs() + laterDeferMaxMs, own = true)
        try {
            when (val result = speakDeferred(utterance, chunked)) {
                is LaterAttempt.Played -> Unit
                is LaterAttempt.Failed -> throw HermesPlaybackException(result.detail)
                LaterAttempt.Superseded -> throw HermesPlaybackException("stopped: a newer request took the speaker")
                LaterAttempt.Busy -> throw HermesPlaybackException("not played: the speaker or microphone stayed busy")
                LaterAttempt.ClipPlayed -> error("a clip is not a reply result")
            }
        } catch (error: HermesException) {
            // Confirmed played to the end by its sink: that is the truth, whatever failed after.
            if (utterance.playedOn == null) throw error
        } finally {
            if (utterance.binding != null) privateSeen.set(true)
            chunked.close()
        }
    }

    /**
     * One reply, own or later, spoken clip by clip (a chunk of [TtsBatcher] is one clip). Each clip: its synthesis is awaited
     * with NO speaker, slot, wake gating or [laterWork] CPU hold, since nothing is audible while a request is generated;
     * then wait (no CPU hold) until the speaker is free for it: no acknowledgement waits or plays, no reply plays, the
     * target's microphone isn't claimed, it is first in line, and (a later reply only) no voice request is being
     * transmitted; then play that clip alone ([playClip]), which holds the speaker only while it is really handed off and
     * played. A busy outcome (a recording claimed the microphone, the Watch refused it as busy) goes back to waiting, with a
     * back-off, and resumes at the first chunk not confirmed played. An attempt may only begin within [laterDeferMaxMs] of
     * the reply's arrival, or (a later clip) of its synthesis completing. Synthesis and playback have no total time limit:
     * they end with their own result, an error or a disconnect, or a Stop. Never returns [LaterAttempt.Busy].
     */
    private suspend fun speakDeferred(u: Utterance, chunked: ChunkedSpeech): LaterAttempt {
        synchronized(floorLock) { replyQueue += u }
        try {
            var refusals = 0
            while (true) {
                if (chunked.next > 0) {
                    chunked.current()
                    u.deadlineMs = monotonicMs() + laterDeferMaxMs
                }
                val left = u.deadlineMs - monotonicMs()
                if (left <= 0 || withTimeoutOrNull(left) { awaitReplySpeaker(u) } == null) {
                    return LaterAttempt.Failed("not played: the speaker or microphone stayed busy")
                }
                if (chunked.next == 0) chunked.current()
                when (val result = playClip(u, chunked)) {
                    is LaterAttempt.Played, is LaterAttempt.Failed, LaterAttempt.Superseded -> return result
                    LaterAttempt.ClipPlayed -> refusals = 0
                    LaterAttempt.Busy -> {
                        delay((LATER_BUSY_RETRY_MS shl refusals.coerceAtMost(3)).coerceAtMost(LATER_BUSY_RETRY_MAX_MS))
                        refusals += 1
                    }
                }
            }
        } finally {
            synchronized(floorLock) { replyQueue -= u }
        }
    }

    /** Whether [u] may take the speaker now (under [floorLock]): see [speakDeferred]. */
    private fun replyFreeLocked(u: Utterance, device: VoiceOrigin): Boolean {
        if (!u.own && turnsInFlight != 0) return false
        if (acksWaiting != 0 || acksPlaying != 0 || ownership.later != null || ownership.claimedLocked(device)) return false
        // First come first served among the replies that could play now (a later reply still waits for transmissions).
        return replyQueue.firstOrNull { it.own || turnsInFlight == 0 } === u
    }

    private sealed class HeadsetBinding {
        object None : HeadsetBinding()
        class Bound(val endpoint: com.rumi.hermesvoice.core.headset.AudioEndpoint) : HeadsetBinding()
        class Refused(val detail: String) : HeadsetBinding()
    }

    /** "Use headset" is on and a personal headset OUTPUT is connected now: replies are private (the Phone's headset or nowhere). */
    private fun privateOutputNow(): Boolean = headset?.output() != null

    /** [u] is spoken privately: it is bound to a headset already, or the headset output is connected now. */
    private fun privateFor(u: Utterance): Boolean = u.binding != null || u.headsetOnly || privateOutputNow()

    /**
     * "Use headset" for one clip of [u] about to be admitted. Whatever the role, the device that asked and the playback target, a
     * reply is bound to the headset output connected when its clip is admitted (an unbound reply is evaluated again at each clip),
     * and from its first bound clip on it must find that same device still connected, else the rest is not played: never on the
     * Watch or the loudspeaker instead. Switching the setting off meanwhile does not unbind it. A reply that only the headset made
     * speakable ([Utterance.headsetOnly]) is not played at all without a connected headset.
     */
    private fun bindHeadset(u: Utterance): HeadsetBinding {
        val policy = headset
        if (policy == null) return if (u.headsetOnly) HeadsetBinding.Refused(com.rumi.hermesvoice.core.headset.HeadsetText.NO_HEADSET) else HeadsetBinding.None
        if (u.binding == null) {
            u.binding = policy.output()
            u.bindingDecided = true
        }
        val bound = u.binding
        return when {
            bound == null -> if (u.headsetOnly) HeadsetBinding.Refused(com.rumi.hermesvoice.core.headset.HeadsetText.NO_HEADSET) else HeadsetBinding.None
            policy.connected(bound) -> HeadsetBinding.Bound(bound)
            else -> HeadsetBinding.Refused(com.rumi.hermesvoice.core.headset.HeadsetText.PLAYBACK_LOST)
        }
    }

    private inner class TypedFinal(request: VoiceTurnRequest, text: String, deadlineMs: Long) :
        Utterance(request, SpokenRole.FINAL, 1, text, deadlineMs, own = false) {
        override val fixedTarget: PlaybackTarget? = PlaybackTarget(VoiceOrigin.PHONE, request.sink)
        override val headsetOnly: Boolean get() = true
    }

    /**
     * Speaks a typed chat answer ([text], Phone-owned, never moved to the Watch) through the connected headset: true when it was
     * taken (the headset setting is on, a headset output is connected now, the follow scope exists), after which [done] is called
     * exactly once with whether it was heard to the end and whether a Stop ended it. False: nothing is spoken and [done] is never
     * called (the answer is only shown, as before). The speech uses the same speaker arbiter and the same sink as a later reply,
     * so it never plays over a recording, is stopped by [stopFollowing], and is never replayed.
     */
    fun speakTypedFinal(turnId: String, sink: PlaybackSink, text: String, done: (heard: Boolean, cancelled: Boolean, detail: String) -> Unit): Boolean {
        val scope = laterScope ?: return false
        val policy = headset ?: return false
        if (text.isBlank() || policy.output() == null) return false
        val request = VoiceTurnRequest(turnId, VoiceOrigin.PHONE, ByteArray(0), "", sink)
        val reply = TypedFinal(request, text.trim(), monotonicMs() + laterDeferMaxMs)
        val reported = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(detail: String, cancelled: Boolean) {
            if (!reported.compareAndSet(false, true)) return
            val played = reply.playedOn
            done(played != null, cancelled && played == null, if (played != null) played.name.lowercase() else detail)
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                coroutineScope {
                    val chunked = chunkedFor(request, reply.role, reply.text, this)
                    try {
                        when (val result = speakDeferred(reply, chunked)) {
                            is LaterAttempt.Played -> finish("", false)
                            is LaterAttempt.Failed -> finish(result.detail, false)
                            LaterAttempt.Superseded -> finish("stopped: a newer request took the speaker", false)
                            LaterAttempt.Busy -> finish("not played: the speaker or microphone stayed busy", false)
                            LaterAttempt.ClipPlayed -> error("a clip is not a reply result")
                        }
                    } finally {
                        chunked.close()
                    }
                }
            } catch (error: HermesException) {
                finish((error.message ?: error.javaClass.simpleName).take(160), false)
            } catch (error: IOException) {
                finish("network: ${error.javaClass.simpleName}", false)
            } catch (stopped: CancellationException) {
                finish("not played: stopped", true)
                throw stopped
            }
        }
        followers += job
        job.invokeOnCompletion { followers -= job }
        job.start()
        return true
    }

    /** The device whose speaker [u] needs: the Phone (its headset) when private, else the fixed target or the playback route's. */
    private fun speakerDevice(u: Utterance): VoiceOrigin? =
        u.fixedTarget?.device ?: if (privateFor(u)) VoiceOrigin.PHONE else playbackRoute.current()?.device

    private fun replySpeakerFree(u: Utterance): Boolean {
        val target = speakerDevice(u) ?: return true
        return synchronized(floorLock) { replyFreeLocked(u, target) }
    }

    private suspend fun awaitReplySpeaker(u: Utterance) {
        while (!replySpeakerFree(u)) delay(if (u.own) OWN_POLL_MS else LATER_POLL_MS)
    }

    /**
     * Admits ONE clip of the reply (chunk [ChunkedSpeech.next], already synthesized) to the target's speaker, atomically with
     * the microphone claims and the arbiter's state ([AudioOwnership]), and plays it. The speaker slot and [laterSpeaker] are
     * held from that admission to the end of this clip only, never while the next chunk is being synthesized. Then
     * [laterSpeaker] is told, an open wake window there closes, and it plays. An acknowledgement stops a later reply's
     * clip (Superseded) but waits for an own reply's; a recording's claim stops either (Busy: it resumes at the first chunk not
     * confirmed played) unless the sink already confirmed the clip played to the end ([PlaybackSink.playConfirmed],
     * recorded by [markPlayed] / [ChunkedSpeech.played] under the lock before the sink resumes this job): completion wins, so
     * a confirmed clip is never played twice and a confirmed reply is never reported as not played. A recording still waits for
     * its teardown ([LaterSlot.stopped]). Returns [LaterAttempt.Played] when the LAST chunk was confirmed, and
     * [LaterAttempt.ClipPlayed] when an earlier one was.
     */
    private suspend fun playClip(u: Utterance, chunked: ChunkedSpeech): LaterAttempt = coroutineScope {
        val bound = bindHeadset(u)
        if (bound is HeadsetBinding.Refused) return@coroutineScope LaterAttempt.Failed(bound.detail)
        val viaHeadset = u.fixedTarget == null && bound is HeadsetBinding.Bound
        val target = if (viaHeadset) {
            PlaybackTarget(VoiceOrigin.PHONE, privateSink ?: return@coroutineScope LaterAttempt.Failed(com.rumi.hermesvoice.core.headset.HeadsetText.NO_PRIVATE_SINK))
        } else {
            u.fixedTarget ?: playbackRoute.current() ?: return@coroutineScope LaterAttempt.Busy
        }
        val device = target.device
        val index = chunked.next
        val last = index == chunked.size - 1
        val fullCue = PlaybackCue(u.request.turnId, u.request.origin, u.role, u.sequence, u.text, device,
            later = !u.own, deferrable = u.own, headset = (bound as? HeadsetBinding.Bound)?.endpoint)
        lateinit var slot: LaterSlot
        val play = launch(start = CoroutineStart.LAZY) {
            // An open wake window closes now that it was told a reply holds this speaker; it is never spoken over.
            withTimeoutOrNull(LATER_WINDOW_CLOSE_MS) { while (wakeListening(device)) delay(LATER_POLL_MS) } ?: return@launch
            try {
                // The hold is renewed per clip: a long reply never outlasts it, and none is held while it waits.
                laterWork {
                    val audio = chunked.current()
                    val cue = if (chunked.size == 1) fullCue else fullCue.copy(text = chunked.chunks[index], part = index)
                    // The sink confirms the end from its own completion signal, before it resumes this (cancellable) job.
                    target.sink.playConfirmed(audio, cue) {
                        if (last) markPlayed(slot, u, fullCue, returned = false) else chunked.played(index)
                    }
                    // The sink returned normally: by its contract it played to the end (even if a stop came too late to stop it).
                    if (last) markPlayed(slot, u, fullCue, returned = true)
                    chunked.played(index)
                }
            } catch (refused: HermesPlaybackBusyException) {
                return@launch
            }
        }
        slot = LaterSlot(device, play, own = u.own)
        val admitted = synchronized(floorLock) {
            ((u.fixedTarget != null || viaHeadset || playbackRoute.current() === target) && replyFreeLocked(u, device)).also { if (it) ownership.later = slot }
        }
        if (!admitted) {
            play.cancel()
            return@coroutineScope LaterAttempt.Busy
        }
        laterSpeaker(device, true)
        if (u.own) pendingTurns.update(u.request.turnId, PendingPhase.SPEAKING)
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
            if (u.own) pendingTurns.update(u.request.turnId, PendingPhase.AWAITING)
        }
        val (completed, yielded) = synchronized(floorLock) { slot.completed to slot.yielded }
        when {
            completed -> LaterAttempt.Played(device)
            chunked.next > index -> LaterAttempt.ClipPlayed
            yielded -> LaterAttempt.Busy
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
    private fun markPlayed(slot: LaterSlot, reply: Utterance, cue: PlaybackCue, returned: Boolean) {
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

    /** [speech] for the short acknowledgement [role], bounded by [ackSynthesisMaxMs], with its length, duration and size (or failure class) as a diagnostic. */
    private suspend fun timedSpeak(request: VoiceTurnRequest, role: SpokenRole, text: String): SpokenAudio {
        val started = System.nanoTime()
        try {
            val audio = withTimeoutOrNull(ackSynthesisMaxMs) { speech.speak(text) }
                ?: throw HermesProtocolException("speech synthesis of the acknowledgement took too long")
            diagnostic(request, "speech", "role=$role chars=${text.length} ms=${(System.nanoTime() - started) / 1_000_000} bytes=${audio.bytes.size}")
            return audio
        } catch (error: Exception) {
            diagnostic(request, "speech", "role=$role chars=${text.length} ms=${(System.nanoTime() - started) / 1_000_000} failed=${error.javaClass.simpleName}")
            throw error
        }
    }

    /**
     * Plays the acknowledgement on the playback target. It waits for the reply that is playing (an own reply is never
     * cut off by a newer request) and for other acknowledgements; a LATER reply that plays is stopped first. While it
     * waits or plays, replies that are ready give way to it.
     */
    private suspend fun playAck(request: VoiceTurnRequest, audio: SpokenAudio, text: String) {
        synchronized(floorLock) { acksWaiting += 1 }
        var waiting = true
        try {
            while (true) {
                var stopLater: LaterSlot? = null
                val admitted = synchronized(floorLock) {
                    val slot = ownership.later
                    when {
                        acksPlaying > 0 || (slot != null && slot.own && !slot.completed) -> false
                        slot != null && !slot.completed -> {
                            // A later reply confirmed played to the end is left alone (it only ends its job); any other is stopped.
                            slot.superseded = true
                            ownership.later = null
                            stopLater = slot
                            false
                        }
                        else -> {
                            acksPlaying += 1
                            acksWaiting -= 1
                            waiting = false
                            true
                        }
                    }
                }
                stopLater?.let {
                    it.job.cancel(TurnSupersededException())
                    it.job.join()
                }
                if (admitted) break
                if (stopLater == null) delay(ACK_POLL_MS)
            }
            try {
                timedHandOff(request, audio, SpokenRole.ACK, 0, text)
            } finally {
                synchronized(floorLock) { acksPlaying -= 1 }
            }
        } finally {
            if (waiting) synchronized(floorLock) { acksWaiting -= 1 }
        }
    }

    /** [handOff], with the device and how long it took to be confirmed (or how it failed) as a diagnostic. */
    private suspend fun timedHandOff(request: VoiceTurnRequest, audio: SpokenAudio, role: SpokenRole, sequence: Int, text: String) {
        val started = System.nanoTime()
        val device = if (privateOutputNow()) "phone" else playbackRoute.current()?.device?.name?.lowercase() ?: "none"
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
        val endpoint = headset?.output()
        if (endpoint != null) {
            // Private: the acknowledgement is heard on the headset or not at all (it never reaches the Watch or the loudspeaker, and
            // missing it never fails the request).
            val sink = privateSink ?: return
            val cue = PlaybackCue(request.turnId, request.origin, role, sequence, text, VoiceOrigin.PHONE, headset = endpoint)
            try {
                sink.play(audio, cue)
            } catch (skipped: HermesPlaybackException) {
                return
            }
            listener.onPlayed(cue)
            request.listener?.onPlayed(cue)
            return
        }
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

        /** [VoiceTurnOutcome.NotAdmitted] reason: too many voice requests are pending ([MAX_PENDING_TURNS]); nothing was sent. */
        const val PENDING_LIMIT = "pending_limit"

        /** [VoiceTurnOutcome.NotDelivered] reason: too many requests are pending for this conversation ([SESSION_QUEUE_MAX]); nothing was sent. */
        const val QUEUE_FULL = "queue_full"

        /** Voice requests pending at once (all conversations); more are refused visibly. */
        const val MAX_PENDING_TURNS = 16

        /** Requests pending per conversation, the one awaiting its reply included. More are refused visibly before anything is sent; none waits for another. */
        const val SESSION_QUEUE_MAX = 8

        /** How long a delivered turn's destination is followed for later replies unless the user picked another window (1 minute to 3 days). */
        const val LATER_WINDOW_MS = 30 * 60_000L
        const val LATER_WINDOW_MIN_MS = 60_000L
        const val LATER_WINDOW_MAX_MS = 72 * 60 * 60_000L

        /**
         * How long after its arrival an attempt to speak a reply may still begin (it waits for a busy speaker or
         * microphone); the wake window's close (at most [LATER_WINDOW_CLOSE_MS]) comes after that. Synthesis
         * and playback have no total limit (see [speakDeferred]).
         */
        const val LATER_DEFER_MAX_MS = 10 * 60_000L

        /** The short acknowledgement's synthesis; it precedes delivery, so it must not hang the turn. */
        const val ACK_SYNTHESIS_MAX_MS = 2 * 60_000L

        /**
         * Later replies that arrived and wait per follow (besides the one its speaker prepares or plays), and over all
         * follows (counting those being prepared or played); more are reported as not played at once.
         */
        const val LATER_QUEUE_MAX = 4
        const val LATER_PENDING_MAX = 8
        private const val LATER_POLL_MS = 250L
        private const val OWN_POLL_MS = 100L
        private const val ACK_POLL_MS = 50L
        private const val LATER_BUSY_RETRY_MS = 1_000L
        private const val LATER_BUSY_RETRY_MAX_MS = 8_000L
        private const val LATER_WINDOW_CLOSE_MS = 3_000L
    }
}
