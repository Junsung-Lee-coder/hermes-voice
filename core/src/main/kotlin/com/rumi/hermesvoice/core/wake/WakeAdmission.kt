package com.rumi.hermesvoice.core.wake

import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.WatchSettings
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/** A device's request to own the current wake episode. [nodeId] is filled in by the Phone from the transport, never by the claimant. */
data class WakeClaim(
    val claimId: String,
    val origin: VoiceOrigin,
    val nodeId: String,
    /** The settings revision the device was listening under; a claim under other settings is refused. */
    val settingsRevision: Long,
    /** The device's visibility generation, for its own bookkeeping and logs. */
    val generation: Long,
    /** How many wake requests had been answered ([WakeAdmission.epoch]) when the device's recognizer window opened. */
    val epoch: Long = 0,
)

enum class ClaimVerdict {
    GRANTED,

    /** Another device owns this wake episode, or answered it while this device's window was already listening: do not record or send. */
    HELD_BY_OTHER,

    /** The device listened under settings the Phone no longer has. */
    STALE_SETTINGS,

    /** The current wake location does not include this device. */
    NOT_LISTENING,

    /** A renewal for a claim the Phone no longer holds (expired, replaced, or the Phone restarted). */
    EXPIRED,

    /** The device's window opened before a request that has since been answered (or under an episode count the Phone does not have). */
    STALE_WINDOW,

    /** A renewal for the claim whose request the Phone has just admitted: it was used up, the request is being answered. */
    USED,
}

/** A wake request the Phone admitted: [epoch] is the number of answered requests, this one included. */
data class WakeEpisode(val epoch: Long, val claimId: String, val origin: VoiceOrigin, val nodeId: String)

/** Where the Phone keeps the number of answered wake requests, so it survives the process. */
interface WakeEpochStore {
    fun load(): Long
    fun save(epoch: Long)
}

/**
 * The Phone's arbitration of "Both": both devices may hear the wake phrase, but one spoken wake
 * episode is admitted from ONE device. The Phone is the only coordinator; the Watch asks over the
 * paired Data Layer and the Phone takes the Watch's identity from the transport.
 *
 * - **Claim.** When a device's recognizer hears a leading wake phrase, it asks for the claim before
 *   it records or sends anything. The first claim wins; a claim from the other device is refused
 *   while the winner holds it. A claim released (or expired) without a request frees the episode
 *   at once, so the other device's recognizer may still answer it.
 * - **Lease.** A claim lasts [ttlMs] and is renewed by its holder while it listens, hands off,
 *   records and, on the Watch, until the Phone has answered the recording it sent. Renewal has no
 *   limit, so it is not a recording cap; a holder that disappears stops renewing and its claim
 *   expires. Releases and renewals name their claim id, so a late message about an old claim
 *   cannot affect a newer one.
 * - **Admission.** In Both, a wake-phrase turn is accepted only with the currently held claim of
 *   its device, once. Anything else (no claim, another device's, expired, already used, or the
 *   Phone restarted meanwhile) is refused before the turn is accepted: nothing is transcribed,
 *   routed or delivered, and where replies play does not change. Push-to-talk and text are never
 *   arbitrated. It is not a merge by content or time: two deliberate requests are two claims.
 * - **Answered episodes.** Every admitted wake request raises [epoch]. A claim carries the epoch
 *   its device knew when its recognizer window opened; a claim from a window that was already
 *   listening when a request was admitted is refused, however late its recognizer finishes the
 *   same spoken phrase. A window opened afterwards is a new episode. No time window is involved.
 */
class WakeAdmission(
    private val clock: () -> Long,
    private val settings: () -> WatchSettings,
    private val ttlMs: Long = WakeContract.CLAIM_TTL_MS,
    private val epochs: WakeEpochStore? = null,
    /** Told of each admitted wake request, outside the lock: e.g. to let the other device close a window that can no longer answer. */
    private val onAnswered: (WakeEpisode) -> Unit = {},
) {
    private class Lease(val claim: WakeClaim, var expiresAtMs: Long)

    private val lock = Any()
    private var lease: Lease? = null
    private var answered = epochs?.load() ?: 0L
    private var lastAnswered: WakeEpisode? = null

    /** The number of wake requests answered so far: what a device must know when its window opens. */
    val epoch: Long get() = synchronized(lock) { answered }

    /** Whether wake-phrase turns need a claim now: only when both devices may listen. */
    fun required(): Boolean = settings().arbitrationRequired

    fun claim(claim: WakeClaim): ClaimVerdict = synchronized(lock) { decide(claim) }

    /** As [claim], with the epoch to report back, read under the same lock. */
    fun answer(claim: WakeClaim): Pair<ClaimVerdict, Long> = synchronized(lock) { decide(claim) to answered }

    private fun decide(claim: WakeClaim): ClaimVerdict {
        val current = settings()
        if (!current.mayListen(claim.origin)) return ClaimVerdict.NOT_LISTENING
        if (current.revision != claim.settingsRevision) return ClaimVerdict.STALE_SETTINGS
        val now = clock()
        expire(now)
        val held = lease
        if (held != null) {
            if (!sameDevice(held.claim, claim.origin, claim.nodeId)) return ClaimVerdict.HELD_BY_OTHER
            if (held.claim.claimId == claim.claimId) {
                held.expiresAtMs = now + ttlMs
                return ClaimVerdict.GRANTED
            }
            // The same device started a new episode: its own earlier claim is revoked.
        }
        if (claim.epoch != answered) {
            // Its window was listening when a request was admitted: the phrase it heard has been answered.
            val last = lastAnswered
            val byOther = last != null && claim.epoch == last.epoch - 1 && !(last.origin == claim.origin && last.nodeId == claim.nodeId)
            return if (byOther) ClaimVerdict.HELD_BY_OTHER else ClaimVerdict.STALE_WINDOW
        }
        lease = Lease(claim, now + ttlMs)
        return ClaimVerdict.GRANTED
    }

    fun renew(claimId: String, origin: VoiceOrigin, nodeId: String): ClaimVerdict = synchronized(lock) {
        val now = clock()
        expire(now)
        val held = lease?.takeIf { it.claim.claimId == claimId && sameDevice(it.claim, origin, nodeId) }
            ?: return if (lastAnswered?.let { it.claimId == claimId && it.origin == origin && it.nodeId == nodeId } == true) ClaimVerdict.USED else ClaimVerdict.EXPIRED
        held.expiresAtMs = now + ttlMs
        ClaimVerdict.GRANTED
    }

    /**
     * Gives the claim up without a request (nothing was sent, so the other device may still answer).
     * A release for any other claim id is ignored.
     */
    fun release(claimId: String, origin: VoiceOrigin, nodeId: String) = synchronized(lock) {
        if (lease?.claim?.let { it.claimId == claimId && sameDevice(it, origin, nodeId) } == true) lease = null
    }

    /**
     * Admits a turn: null when it may proceed, else the refusal reason. A wake-phrase turn in Both
     * uses up its device's claim and counts as an answered episode; every other turn is admitted untouched.
     */
    fun admitTurn(wakeTurn: Boolean, origin: VoiceOrigin, nodeId: String, claimId: String?): String? {
        val episode = synchronized(lock) {
            if (!wakeTurn || !required()) return null
            if (claimId == null) return "wake_claim_missing"
            expire(clock())
            val held = lease?.takeIf { it.claim.claimId == claimId && sameDevice(it.claim, origin, nodeId) } ?: return "wake_claim_invalid"
            lease = null
            answered += 1
            epochs?.save(answered)
            WakeEpisode(answered, held.claim.claimId, origin, nodeId).also { lastAnswered = it }
        }
        onAnswered(episode)
        return null
    }

    /** For status and logs: which device holds the claim now, if any. */
    fun holder(): VoiceOrigin? = synchronized(lock) {
        expire(clock())
        lease?.claim?.origin
    }

    private fun expire(now: Long) {
        val held = lease ?: return
        if (now >= held.expiresAtMs) lease = null
    }

    private fun sameDevice(claim: WakeClaim, origin: VoiceOrigin, nodeId: String) = claim.origin == origin && claim.nodeId == nodeId
}

/** Watch → Phone on `/hv/v1/wake/claim`: ask for, renew or release a wake claim. */
data class WakeClaimMessage(val op: Op, val claimId: String, val settingsRevision: Long = 0, val generation: Long = 0, val epoch: Long = 0) {
    enum class Op { CLAIM, RENEW, RELEASE }

    fun encode(): ByteArray = JSONObject().put("v", 1).put("op", op.name).put("claim_id", claimId).put("revision", settingsRevision)
        .put("generation", generation).put("epoch", epoch).toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): WakeClaimMessage? = runCatching {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            if (json.optInt("v") != 1) return null
            // A claim without an epoch (an older Watch app) can never match: it is refused, not guessed.
            WakeClaimMessage(Op.valueOf(json.getString("op")), json.getString("claim_id"), json.optLong("revision"), json.optLong("generation"),
                json.optLong("epoch", -1))
        }.getOrNull()?.takeIf { isValidClaimId(it.claimId) }

        private val CLAIM_ID = Regex("^[A-Za-z0-9-]{8,64}$")

        fun isValidClaimId(id: String): Boolean = CLAIM_ID.matches(id)
    }
}

/** Phone → the asking Watch node on `/hv/v1/wake/verdict`. [epoch] is the Phone's count of answered wake requests, or -1 when not given. */
data class WakeVerdictMessage(val claimId: String, val verdict: ClaimVerdict, val epoch: Long = -1) {
    fun encode(): ByteArray = JSONObject().put("v", 1).put("claim_id", claimId).put("verdict", verdict.name).put("epoch", epoch)
        .toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): WakeVerdictMessage? = runCatching {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            if (json.optInt("v") != 1) return null
            WakeVerdictMessage(json.getString("claim_id"), ClaimVerdict.valueOf(json.getString("verdict")), json.optLong("epoch", -1))
        }.getOrNull()?.takeIf { WakeClaimMessage.isValidClaimId(it.claimId) }
    }
}

/** The Phone's handling of a Watch claim message: the verdict to send back, or null when none is due. */
object WakeClaimService {
    fun handle(admission: WakeAdmission, sourceNodeId: String, bytes: ByteArray): WakeVerdictMessage? {
        val message = WakeClaimMessage.decode(bytes) ?: return null
        return when (message.op) {
            WakeClaimMessage.Op.CLAIM -> admission.answer(
                WakeClaim(message.claimId, VoiceOrigin.WATCH, sourceNodeId, message.settingsRevision, message.generation, message.epoch),
            ).let { (verdict, epoch) -> WakeVerdictMessage(message.claimId, verdict, epoch) }
            WakeClaimMessage.Op.RENEW -> WakeVerdictMessage(message.claimId, admission.renew(message.claimId, VoiceOrigin.WATCH, sourceNodeId), admission.epoch)
            WakeClaimMessage.Op.RELEASE -> {
                admission.release(message.claimId, VoiceOrigin.WATCH, sourceNodeId)
                null
            }
        }
    }
}

/**
 * Phone → Watch data item on `/hv/v1/wake/epoch`: how many wake requests have been answered, and
 * the claim of the latest. The Watch reads it before it listens, so its next window knows the
 * count, and closes a window that was already listening when another device's request was admitted.
 */
data class WakeEpochItem(val epoch: Long, val claimId: String = "") {
    fun toJson(): String = JSONObject().put("v", 1).put("epoch", epoch).put("claim_id", claimId).toString()

    companion object {
        fun parse(json: String): WakeEpochItem? = runCatching {
            val parsed = JSONObject(json)
            if (parsed.optInt("v") != 1) return null
            WakeEpochItem(parsed.getLong("epoch"), parsed.optString("claim_id"))
        }.getOrNull()?.takeIf { it.epoch >= 0 }
    }
}
