package com.rumi.hermesvoice.watch

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wearable.WearableStatusCodes

/**
 * Whether a failed Data Layer call of a reader request may be made again. Decided by the failure's TYPE only, never its wording: only a
 * transport status that names a lost or interrupted connection is transient. Sign-in, access, developer/protocol, security, size, target,
 * cancellation and anything not recognised are final, so they are shown at once instead of being sent again as a "refused" message.
 */
internal object ReaderSendAdmission {
    private const val MAX_DEPTH = 6

    private val TRANSIENT_STATUSES = setOf(
        CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT, CommonStatusCodes.INTERRUPTED, CommonStatusCodes.INTERNAL_ERROR,
        CommonStatusCodes.CONNECTION_SUSPENDED_DURING_CALL, CommonStatusCodes.RECONNECTION_TIMED_OUT,
        WearableStatusCodes.TARGET_NODE_NOT_CONNECTED,
    )

    fun transient(error: Throwable): Boolean {
        var node: Throwable? = error
        var depth = 0
        while (node != null && depth < MAX_DEPTH) {
            if (node is ApiException) return node.statusCode in TRANSIENT_STATUSES
            val next = node.cause
            if (next === node) break
            node = next
            depth += 1
        }
        return false
    }
}
