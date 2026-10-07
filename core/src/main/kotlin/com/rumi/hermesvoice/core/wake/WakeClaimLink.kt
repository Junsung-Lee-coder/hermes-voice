package com.rumi.hermesvoice.core.wake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Watch's one ordered sender of wake claim messages. Messages are handed to the transport
 * strictly in the order they were offered, one at a time, each only after the one before was
 * handed over or failed: a RELEASE can never reach the Phone before the CLAIM it releases, however
 * slow the Phone lookup or the first send is.
 *
 * - A CLAIM looks up the Phone ([resolveNode]) and remembers it; renewals and the release of that
 *   claim go to the same node, and only a verdict from that node is accepted ([accepts]).
 * - A message that cannot be handed over within [timeoutMs] is given up, and with it its claim:
 *   nothing more is sent for that claim id, so a CLAIM that the transport delivers late is never
 *   followed by an earlier-looking message, and the Phone's lease for it simply runs out. The
 *   device's own claim timer fails the episode closed meanwhile.
 * - Messages name their claim id, so none can affect another claim.
 */
class WakeClaimSender(
    scope: CoroutineScope,
    private val resolveNode: suspend () -> String?,
    private val send: suspend (nodeId: String, bytes: ByteArray) -> Unit,
    private val timeoutMs: Long = WakeContract.CLAIM_SEND_TIMEOUT_MS,
    private val log: (String) -> Unit = {},
) {
    private val queue = Channel<WakeClaimMessage>(Channel.UNLIMITED)

    /** The Phone node each live claim was sent to, oldest first; bounded, the oldest is dropped. */
    private val targets = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > MAX_TARGETS
    }

    init {
        scope.launch { for (message in queue) deliver(message) }
    }

    /** Queues [message] behind everything offered before it. */
    fun offer(message: WakeClaimMessage) {
        queue.trySend(message)
    }

    /** Whether a verdict for [claimId] may come from [nodeId]: only from the node that claim was sent to. */
    fun accepts(claimId: String, nodeId: String): Boolean = synchronized(targets) { targets[claimId] } == nodeId

    /** Stops accepting verdicts and sending anything for [claimId] (the Phone refused it, or its request was answered). */
    fun forget(claimId: String) {
        synchronized(targets) { targets.remove(claimId) }
    }

    private suspend fun deliver(message: WakeClaimMessage) {
        val claiming = message.op == WakeClaimMessage.Op.CLAIM
        val node = if (claiming) withTimeoutOrNull(timeoutMs) { resolveNode() } else synchronized(targets) { targets[message.claimId] }
        if (node == null) {
            log("wake claim ${message.op} ${message.claimId.take(10)} not sent (${if (claiming) "phone not reachable" else "claim given up"})")
            return
        }
        if (claiming) synchronized(targets) { targets[message.claimId] = node }
        val sent = withTimeoutOrNull(timeoutMs) { runCatching { send(node, message.encode()) }.isSuccess } ?: false
        // Not handed over: the claim is given up, so nothing later is sent for it.
        if (!sent || message.op == WakeClaimMessage.Op.RELEASE) forget(message.claimId)
        log("wake claim ${message.op} ${message.claimId.take(10)} sent=$sent")
    }

    private companion object {
        const val MAX_TARGETS = 16
    }
}

/**
 * Keeps the Watch's wake claim alive for a request it has handed to the Phone: recording ended,
 * but the claim is the Watch's until the Phone has answered that turn. Without this a recording
 * whose transfer takes longer than the claim's lifetime is refused after the user finished talking.
 *
 * The owner calls [tick] every [WakeContract.CLAIM_RENEW_MS] while [active]. The claim is renewed
 * until the Phone's first state for the turn ([onPhoneState]: admitted, which used the claim up, or
 * refused, after which it is released), until the transfer fails ([onTransferFailed]: released),
 * until the Phone no longer holds it ([onVerdict], e.g. it restarted), or until [limitMs] passed
 * without any of these. Every call names the turn or claim it is about; anything else is ignored.
 * [onLost] reports a claim that was lost while its request was still on the way.
 */
class WakeClaimTransit(
    private val clock: () -> Long,
    private val renew: (claimId: String) -> Unit,
    private val release: (claimId: String) -> Unit,
    private val onLost: (turnId: String, reason: String) -> Unit,
) {
    private class Transit(val claimId: String, val turnId: String, val deadlineMs: Long)

    private var transit: Transit? = null

    val active: Boolean @Synchronized get() = transit != null

    /** The request [turnId] was handed to the transport with [claimId]; renews at once. */
    @Synchronized
    fun begin(claimId: String, turnId: String, limitMs: Long) {
        transit = Transit(claimId, turnId, clock() + limitMs)
        renew(claimId)
    }

    fun tick() {
        val lost = synchronized(this) {
            val current = transit ?: return
            if (clock() < current.deadlineMs) {
                renew(current.claimId)
                return
            }
            transit = null
            release(current.claimId)
            current
        }
        onLost(lost.turnId, "wake_transfer_timeout")
    }

    /**
     * The Phone's first state for [turnId]; returns the claim that was kept for it, or null when none was.
     * [terminal]: the turn ended there (refused or failed), so the claim may still be held and is released.
     */
    @Synchronized
    fun onPhoneState(turnId: String, terminal: Boolean): String? {
        val current = transit?.takeIf { it.turnId == turnId } ?: return null
        transit = null
        if (terminal) release(current.claimId)
        return current.claimId
    }

    @Synchronized
    fun onTransferFailed(turnId: String) {
        val current = transit?.takeIf { it.turnId == turnId } ?: return
        transit = null
        release(current.claimId)
    }

    /** Stop withdraws the current in-transit authority, including a sent turn still awaiting admission. */
    @Synchronized
    fun cancel() {
        val current = transit ?: return
        transit = null
        release(current.claimId)
    }

    /**
     * The Phone's answer to a renewal. USED: the request was admitted (the same as its first state).
     * Anything else but GRANTED: the claim is gone while the request was still on its way.
     */
    fun onVerdict(claimId: String, verdict: ClaimVerdict) {
        val lost = synchronized(this) {
            val current = transit?.takeIf { it.claimId == claimId } ?: return
            if (verdict == ClaimVerdict.GRANTED) return
            transit = null
            if (verdict == ClaimVerdict.USED) return
            current
        }
        onLost(lost.turnId, "wake_claim_failed")
    }
}
