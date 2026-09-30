package com.rumi.hermesvoice.core.wake

import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.WakeLocation
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
)

enum class ClaimVerdict {
    GRANTED,

    /** Another device owns this wake episode (or just did): do not record or send. */
    HELD_BY_OTHER,

    /** The device listened under settings the Phone no longer has. */
    STALE_SETTINGS,

    /** The current wake location does not include this device. */
    NOT_LISTENING,

    /** A renewal for a claim the Phone no longer holds (expired, replaced, or the Phone restarted). */
    EXPIRED,
}

/**
 * The Phone's arbitration of "Both": both devices may hear the wake phrase, but one spoken wake
 * episode is admitted from ONE device. The Phone is the only coordinator; the Watch asks over the
 * paired Data Layer and the Phone takes the Watch's identity from the transport.
 *
 * - **Claim.** When a device's recognizer hears a leading wake phrase, it asks for the claim before
 *   it records or sends anything. The first claim wins. A claim from the other device is refused
 *   while the winner holds it and for [settleMs] after the winner's request was admitted, which
 *   covers the other recognizer finishing the same spoken phrase later. A claim released without
 *   a request frees the episode at once.
 *   The same device may claim again at once (its next request is a new episode).
 * - **Lease.** A claim lasts [ttlMs] and is renewed by its holder while it listens, hands off and
 *   records. Renewal has no limit, so it is not a recording cap; a holder that disappears stops
 *   renewing and its claim expires. Releases and renewals name their claim id, so a late message
 *   about an old claim cannot affect a newer one.
 * - **Admission.** In Both, a wake-phrase turn is accepted only with the currently held claim of
 *   its device, once. Anything else (no claim, another device's, expired, already used, or the
 *   Phone restarted meanwhile) is refused before the turn is accepted: nothing is transcribed,
 *   routed or delivered, and where replies play does not change. Push-to-talk and text are never
 *   arbitrated. It is not a merge by content or time: two deliberate requests are two claims.
 */
class WakeAdmission(
    private val clock: () -> Long,
    private val settings: () -> WatchSettings,
    private val ttlMs: Long = WakeContract.CLAIM_TTL_MS,
    private val settleMs: Long = WakeContract.CLAIM_SETTLE_MS,
) {
    private class Lease(val claim: WakeClaim, var expiresAtMs: Long)

    private var lease: Lease? = null
    private var settleUntilMs = 0L
    private var settleOwner: Pair<VoiceOrigin, String>? = null

    /** Whether wake-phrase turns need a claim now: only when both devices may listen. */
    fun required(): Boolean = settings().wakeLocation == WakeLocation.BOTH

    @Synchronized
    fun claim(claim: WakeClaim): ClaimVerdict {
        val current = settings()
        if (!current.wakeLocation.listensOn(claim.origin)) return ClaimVerdict.NOT_LISTENING
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
        } else if (now < settleUntilMs && settleOwner != (claim.origin to claim.nodeId)) {
            return ClaimVerdict.HELD_BY_OTHER
        }
        lease = Lease(claim, now + ttlMs)
        return ClaimVerdict.GRANTED
    }

    @Synchronized
    fun renew(claimId: String, origin: VoiceOrigin, nodeId: String): ClaimVerdict {
        val now = clock()
        expire(now)
        val held = lease?.takeIf { it.claim.claimId == claimId && sameDevice(it.claim, origin, nodeId) } ?: return ClaimVerdict.EXPIRED
        held.expiresAtMs = now + ttlMs
        return ClaimVerdict.GRANTED
    }

    /**
     * Gives the claim up without a request (nothing was sent, so the other device may still answer).
     * A release for any other claim id is ignored.
     */
    @Synchronized
    fun release(claimId: String, origin: VoiceOrigin, nodeId: String) {
        if (lease?.claim?.let { it.claimId == claimId && sameDevice(it, origin, nodeId) } == true) lease = null
    }

    /**
     * Admits a turn: null when it may proceed, else the refusal reason. A wake-phrase turn in Both
     * uses up its device's claim; every other turn is admitted untouched.
     */
    @Synchronized
    fun admitTurn(wakeTurn: Boolean, origin: VoiceOrigin, nodeId: String, claimId: String?): String? {
        if (!wakeTurn || !required()) return null
        if (claimId == null) return "wake_claim_missing"
        val now = clock()
        expire(now)
        val held = lease?.takeIf { it.claim.claimId == claimId && sameDevice(it.claim, origin, nodeId) } ?: return "wake_claim_invalid"
        finish(held, now)
        return null
    }

    /** For status and logs: which device holds the claim now, if any. */
    @Synchronized
    fun holder(): VoiceOrigin? {
        expire(clock())
        return lease?.claim?.origin
    }

    private fun finish(held: Lease, now: Long) {
        lease = null
        settleUntilMs = now + settleMs
        settleOwner = held.claim.origin to held.claim.nodeId
    }

    private fun expire(now: Long) {
        val held = lease ?: return
        if (now >= held.expiresAtMs) lease = null
    }

    private fun sameDevice(claim: WakeClaim, origin: VoiceOrigin, nodeId: String) = claim.origin == origin && claim.nodeId == nodeId
}

/** Watch → Phone on `/hv/v1/wake/claim`: ask for, renew or release a wake claim. */
data class WakeClaimMessage(val op: Op, val claimId: String, val settingsRevision: Long = 0, val generation: Long = 0) {
    enum class Op { CLAIM, RENEW, RELEASE }

    fun encode(): ByteArray = JSONObject().put("v", 1).put("op", op.name).put("claim_id", claimId).put("revision", settingsRevision)
        .put("generation", generation).toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): WakeClaimMessage? = runCatching {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            if (json.optInt("v") != 1) return null
            WakeClaimMessage(Op.valueOf(json.getString("op")), json.getString("claim_id"), json.optLong("revision"), json.optLong("generation"))
        }.getOrNull()?.takeIf { isValidClaimId(it.claimId) }

        private val CLAIM_ID = Regex("^[A-Za-z0-9-]{8,64}$")

        fun isValidClaimId(id: String): Boolean = CLAIM_ID.matches(id)
    }
}

/** Phone → the asking Watch node on `/hv/v1/wake/verdict`. */
data class WakeVerdictMessage(val claimId: String, val verdict: ClaimVerdict) {
    fun encode(): ByteArray = JSONObject().put("v", 1).put("claim_id", claimId).put("verdict", verdict.name)
        .toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): WakeVerdictMessage? = runCatching {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            if (json.optInt("v") != 1) return null
            WakeVerdictMessage(json.getString("claim_id"), ClaimVerdict.valueOf(json.getString("verdict")))
        }.getOrNull()?.takeIf { WakeClaimMessage.isValidClaimId(it.claimId) }
    }
}

/** The Phone's handling of a Watch claim message: the verdict to send back, or null when none is due. */
object WakeClaimService {
    fun handle(admission: WakeAdmission, sourceNodeId: String, bytes: ByteArray): WakeVerdictMessage? {
        val message = WakeClaimMessage.decode(bytes) ?: return null
        return when (message.op) {
            WakeClaimMessage.Op.CLAIM -> WakeVerdictMessage(message.claimId, admission.claim(
                WakeClaim(message.claimId, VoiceOrigin.WATCH, sourceNodeId, message.settingsRevision, message.generation)))
            WakeClaimMessage.Op.RENEW -> WakeVerdictMessage(message.claimId, admission.renew(message.claimId, VoiceOrigin.WATCH, sourceNodeId))
            WakeClaimMessage.Op.RELEASE -> {
                admission.release(message.claimId, VoiceOrigin.WATCH, sourceNodeId)
                null
            }
        }
    }
}
