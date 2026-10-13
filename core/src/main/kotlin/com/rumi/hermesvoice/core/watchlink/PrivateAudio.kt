package com.rumi.hermesvoice.core.watchlink

import org.json.JSONObject

/**
 * The Watch's report of the private-output flag it applied: the settings snapshot [revision] it now holds and whether it
 * [suppressed] (refuses to play) replies. Strict: anything else is not a receipt.
 */
data class PrivateAudioReceipt(val revision: Long, val suppressed: Boolean) {
    fun toJson(): String = JSONObject().put("revision", revision).put("suppressed", suppressed).toString()

    companion object {
        fun parse(bytes: ByteArray): PrivateAudioReceipt? {
            val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
            val revision = when (val value = json.opt("revision")) {
                is Int, is Long -> (value as Number).toLong().takeIf { it >= 0 } ?: return null
                else -> return null
            }
            val suppressed = json.opt("suppressed") as? Boolean ?: return null
            return PrivateAudioReceipt(revision, suppressed)
        }
    }
}

/** What the Phone can truthfully say about the Watch while the headset is the private output. */
enum class PrivateWatchStatus {
    /** The headset is not the private output: the Watch is not asked to suppress anything. */
    NOT_ACTIVE,

    /** The Watch was asked (the snapshot was sent) but has not reported it applied it: nothing is claimed about the Watch. */
    PENDING,

    /** The Watch reported, for the CURRENT snapshot, that it refuses to play. */
    CONFIRMED,
}

/**
 * Remembers only the last snapshot this run published and whether the Watch confirmed it. A fresh ledger (a restarted Phone) is
 * NOT_ACTIVE until it publishes, and an active snapshot is PENDING until its own receipt arrives: a send is not a confirmation,
 * and an older, replayed or "not suppressed" receipt confirms nothing.
 */
class PrivateAudioLedger {
    private var revision = 0L
    private var active = false
    private var confirmed = false

    @Synchronized
    fun published(revision: Long, active: Boolean) {
        this.revision = revision
        this.active = active
        confirmed = false
    }

    /** True only when [receipt] confirms the current active snapshot. */
    @Synchronized
    fun received(receipt: PrivateAudioReceipt): Boolean {
        if (!active || receipt.revision != revision || !receipt.suppressed) return false
        confirmed = true
        return true
    }

    @Synchronized
    fun status(): PrivateWatchStatus = when {
        !active -> PrivateWatchStatus.NOT_ACTIVE
        confirmed -> PrivateWatchStatus.CONFIRMED
        else -> PrivateWatchStatus.PENDING
    }
}
