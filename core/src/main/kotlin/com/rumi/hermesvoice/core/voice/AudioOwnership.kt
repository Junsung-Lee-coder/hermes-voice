package com.rumi.hermesvoice.core.voice

import com.rumi.hermesvoice.core.VoiceOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Who uses each device's microphone, and which later reply holds a speaker: both decided under ONE
 * lock, which the [VoiceTurnOrchestrator] also uses for its speaker floor, so a recording's claim and
 * a later reply's admission can never both succeed.
 *
 * Everything that records on a device (push-to-talk, a hands-free capture, a wake phrase heard and
 * about to record or send) [claimMicrophone]s it BEFORE the microphone is opened: from then on no
 * later reply is admitted there, and one already admitted there is stopped. The microphone is opened
 * only once that reply has really stopped ([MicrophoneClaim.whenSpeakerStopped]). A claim ends when it
 * is released, or when the voice request it became is taken over by the orchestrator
 * ([VoiceTurnRequest.microphone]), which keeps later replies waiting until that request is answered.
 * One instance per process, shared by every orchestrator of that process.
 */
class AudioOwnership {
    internal val lock = Any()
    private var nextClaim = 0L
    private val claims = HashMap<VoiceOrigin, MutableSet<Long>>()

    /** The later reply admitted to a speaker now (at most one at a time), or null. Guarded by [lock]. */
    internal var later: LaterSlot? = null

    /**
     * Claims [device]'s microphone for a recording that is about to open it. A later reply admitted
     * there is stopped now (its job cancelled; the Phone's player stops in its cancellation handler)
     * and played again afterwards; the returned claim tells when it has stopped.
     */
    fun claimMicrophone(device: VoiceOrigin): MicrophoneClaim {
        val (claim, stopping) = synchronized(lock) {
            nextClaim += 1
            claims.getOrPut(device) { HashSet() }.add(nextClaim)
            val playing = later?.takeIf { it.device == device }
            // A reply its sink confirmed played to the end is not stopped (completion wins), but the
            // microphone still waits for its teardown; any other is stopped and played again afterwards.
            val stop = playing?.takeIf { !it.completed }
            stop?.yielded = true
            MicrophoneClaim(this, device, nextClaim, playing?.stopped) to stop
        }
        stopping?.job?.cancel(LaterYielded())
        return claim
    }

    /**
     * Stops the reply admitted to [device]'s speaker now (not one its sink already confirmed played to the end) and marks it to be
     * played again from the first chunk not confirmed, like a recording's claim does: the private-output switch moves a reply that
     * plays on the Watch to the headset. True when one was stopped.
     */
    internal fun withdrawLater(device: VoiceOrigin): Boolean {
        val stopping = synchronized(lock) {
            later?.takeIf { it.device == device && !it.completed }?.also { it.yielded = true }
        }
        stopping?.job?.cancel(LaterYielded())
        return stopping != null
    }

    /** Whether something records (or is about to) on [device]. */
    fun microphoneClaimed(device: VoiceOrigin): Boolean = synchronized(lock) { claimedLocked(device) }

    internal fun claimedLocked(device: VoiceOrigin): Boolean = claims[device]?.isNotEmpty() == true

    internal fun release(device: VoiceOrigin, id: Long): Boolean = synchronized(lock) { claims[device]?.remove(id) == true }

    internal fun held(device: VoiceOrigin, id: Long): Boolean = synchronized(lock) { claims[device]?.contains(id) == true }

    companion object {
        /** How long opening a microphone waits for a later reply it stopped to finish stopping. */
        const val SPEAKER_STOP_MS = 2_000L
    }
}

/**
 * A reply admitted to [device]'s speaker: a later reply, or ([own]) the original reply of a turn,
 * which a newer request's acknowledgement waits for instead of stopping. Its flags are written under [AudioOwnership.lock].
 */
internal class LaterSlot(val device: VoiceOrigin, val job: Job, val own: Boolean = false) {
    /** Completed once its job ended AND the speaker was let go (what a recording waits for). */
    val stopped = CompletableDeferred<Unit>()

    /** Its sink confirmed it played to the end: nothing can stop it, play it again or report it as not played. */
    var completed = false

    /** A recording claimed the microphone before it finished: it is played again afterwards. */
    var yielded = false

    /** A newer request took the speaker before it finished. */
    var superseded = false
}

/** A later reply stopped because a recording claimed its device's microphone. */
internal class LaterYielded : CancellationException("a recording claimed the microphone")

/**
 * A recording's hold on one device's microphone ([AudioOwnership.claimMicrophone]); release it on
 * every path that does not hand it to a voice request. Releasing twice is harmless.
 */
class MicrophoneClaim internal constructor(
    private val owner: AudioOwnership,
    val device: VoiceOrigin,
    private val id: Long,
    /** The teardown of the later reply that held this device's speaker (stopped by this claim, or finishing), or null. */
    private val stopping: Job?,
) {
    /** Still holds the microphone (not released, not taken over by its voice request). */
    val held: Boolean get() = owner.held(device, id)

    fun release() {
        owner.release(device, id)
    }

    /**
     * Runs [open] once the later reply this claim stopped has really stopped, and never before:
     * right here, returning its result, when nothing was playing (or it already stopped); otherwise
     * it returns null and runs [open] from [scope] after the reply stopped. [open] gets false, and the
     * claim is released, when the reply did not stop within [timeoutMs] or the claim was released
     * meanwhile (the recording was cancelled); a [scope] cancelled before or while waiting releases
     * the claim without calling [open], and so does [open] throwing. It never blocks the calling thread.
     */
    fun <T> whenSpeakerStopped(scope: CoroutineScope, timeoutMs: Long = AudioOwnership.SPEAKER_STOP_MS, open: (stopped: Boolean) -> T): T? {
        val job = stopping
        if (job == null || job.isCompleted) {
            val ok = held
            return try {
                open(ok)
            } catch (error: Throwable) {
                release()
                throw error
            } finally {
                if (!ok) release()
            }
        }
        // Kept only once [open] returned for a free microphone; every other end (a timeout, a released claim, a
        // scope cancelled before or while waiting, the waiter never started, [open] throwing) gives it back, once.
        val kept = java.util.concurrent.atomic.AtomicBoolean(false)
        scope.launch {
            val ok = withTimeoutOrNull(timeoutMs) { job.join() } != null && held
            if (!ok) release()
            open(ok)
            if (ok) kept.set(true)
        }.invokeOnCompletion { if (!kept.get()) release() }
        return null
    }
}

/**
 * A wake episode's hold on [device]'s microphone ([com.rumi.hermesvoice.core.wake.WakeDevicePort.holdMicrophone]):
 * claimed when the phrase is heard, then handed to the recording or the request that follows
 * ([take]), or given back when the episode ends without one. Used from one thread (the wake flow's).
 */
class EpisodeMicrophone(private val ownership: AudioOwnership, private val device: VoiceOrigin) {
    private var claim: MicrophoneClaim? = null

    fun hold(held: Boolean) {
        if (held) {
            if (claim == null) claim = ownership.claimMicrophone(device)
        } else {
            claim?.release()
            claim = null
        }
    }

    /** Hands the hold over (to a recording or a request), or null when none is held. */
    fun take(): MicrophoneClaim? = claim.also { claim = null }
}
