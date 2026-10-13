package com.rumi.hermesvoice.phone

/** Main-thread resource mutex, separate from response AudioOwnership. Only recognizers are preemptible. */
class PhoneMicArbiter {
    private class Owner(val recognizer: Boolean, val releaseResource: () -> Boolean)
    private var owner: Owner? = null
    val occupied: Boolean get() = owner != null
    val recordingOwned: Boolean get() = owner?.recognizer == false

    fun acquire(recognizer: Boolean = false, releaseResource: () -> Boolean = { false }): AutoCloseable? {
        if (owner != null) return null
        val next = Owner(recognizer, releaseResource)
        owner = next
        return AutoCloseable { if (owner === next) owner = null }
    }

    /** Actual cancel/destroy must succeed and relinquish its token before manual recording can take over. */
    fun takeManual(): AutoCloseable? {
        val previous = owner
        if (previous != null) {
            if (!previous.recognizer || !runCatching(previous.releaseResource).getOrDefault(false) || owner != null) return null
        }
        return acquire()
    }
}
