package com.rumi.hermesvoice.core.headset

/** How an attempt to bring up a two-way (communication) link to a Bluetooth headset ended. */
enum class LinkResult { READY, PERMISSION, TIMEOUT, UNAVAILABLE, IN_USE }

/**
 * Brings up the bidirectional link a Bluetooth headset needs for its microphone (a communication device on newer Android, SCO
 * on older), and only for a capture: playback alone never starts it. The Phone implements it over `AudioManager`.
 */
interface CommsLink {
    /**
     * [onResult] is called exactly once, within the port's own time bound, unless the returned handle is closed first (then never).
     * Closing the handle releases only what this call changed (the link, and the audio mode if this call took it) and restores
     * the prior mode; it is safe to close at any time, and more than once.
     */
    fun acquire(input: AudioEndpoint, onResult: (LinkResult) -> Unit): AutoCloseable
}

/** What a capture reports to the user about its microphone: [headset] true only when Android's own readback confirmed it. */
data class MicReport(val headset: Boolean, val text: String?)

/**
 * The microphone a NEW capture will ask Android for, decided once when the capture is prepared (the setting later toggling,
 * or the headset coming and going, never changes an accepted capture). [preferred] is the headset input to request as the
 * recorder's preferred device, or null for the Phone microphone; [fallback] says why it is null while a headset is connected.
 */
class MicPlan internal constructor(
    val preferred: AudioEndpoint?,
    private val fallback: String?,
    private var lease: AutoCloseable?,
) {
    private var released = false

    /**
     * The truthful source once the recorder runs: [routedId] is the id Android's readback (`getRoutedDevice`) names for the
     * running recorder, null when it names none. A setter returning true is never evidence. A headset that was asked for but not
     * confirmed releases the link at once, since nothing uses it.
     */
    fun report(routedId: Int?): MicReport {
        val wanted = preferred ?: return MicReport(false, fallback)
        return when {
            routedId == wanted.id -> MicReport(true, HeadsetText.HEADSET_MIC)
            routedId == null -> MicReport(false, HeadsetText.NOT_CONFIRMED).also { release() }
            else -> MicReport(false, HeadsetText.NOT_ROUTED).also { release() }
        }
    }

    /** Releases the link and mode this plan took, at the capture's end on every path; idempotent. */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        runCatching { lease?.close() }
        lease = null
    }
}

/**
 * Chooses the microphone for each new capture from the one "Use headset" setting and the live endpoints
 * ([HeadsetPolicy]). Off, no headset, or the Watch: the Phone microphone exactly as before. A wired or USB headset microphone
 * needs no link; a Bluetooth one is requested only after its communication link is up, within the link's own time bound.
 */
class HeadsetMicRoute(private val policy: HeadsetPolicy, private val comms: CommsLink) {
    /**
     * Calls [onPlan] exactly once (at once for off / no headset / wired / USB, later for Bluetooth), unless the returned handle
     * is closed first, which cancels a pending link and calls nothing.
     */
    fun prepare(onPlan: (MicPlan) -> Unit): AutoCloseable {
        val input = when (val choice = policy.micChoice()) {
            MicChoice.Normal -> return done(onPlan, MicPlan(null, null, null))
            is MicChoice.NoHeadsetMic -> return done(onPlan, MicPlan(null, HeadsetText.NO_HEADSET_MIC, null))
            is MicChoice.Headset -> choice.input
        }
        if (!HeadsetClassifier.needsCommunicationLink(input)) return done(onPlan, MicPlan(input, null, null))
        val lock = Any()
        var finished = false
        var cancelled = false
        var lease: AutoCloseable? = null
        var assigned = false
        var early: LinkResult? = null
        fun deliver(result: LinkResult) {
            val go = synchronized(lock) { (!finished && !cancelled).also { if (it) finished = true } }
            if (!go) return
            val held = synchronized(lock) { lease }
            val plan = when {
                result != LinkResult.READY -> MicPlan(null, textFor(result), null).also { runCatching { held?.close() } }
                // The headset may be gone again by the time the link is up.
                !policy.inputConnected(input) -> MicPlan(null, HeadsetText.ROUTE_UNAVAILABLE, null).also { runCatching { held?.close() } }
                else -> MicPlan(input, null, held)
            }
            onPlan(plan)
        }
        val acquired = comms.acquire(input) { result ->
            val deferred = synchronized(lock) { if (assigned) false else { early = result; true } }
            if (!deferred) deliver(result)
        }
        val ready = synchronized(lock) { lease = acquired; assigned = true; early }
        ready?.let(::deliver)
        return AutoCloseable {
            val held = synchronized(lock) { cancelled = true; lease }
            if (!synchronized(lock) { finished }) runCatching { held?.close() }
        }
    }

    private fun done(onPlan: (MicPlan) -> Unit, plan: MicPlan): AutoCloseable {
        onPlan(plan)
        return AutoCloseable { }
    }

    private fun textFor(result: LinkResult): String = when (result) {
        LinkResult.PERMISSION -> HeadsetText.PERMISSION
        LinkResult.TIMEOUT -> HeadsetText.LINK_TIMEOUT
        LinkResult.IN_USE -> HeadsetText.CALL_ACTIVE
        else -> HeadsetText.ROUTE_UNAVAILABLE
    }

    /**
     * Calls [onLost] once if the headset input of a running capture disappears (checked at each endpoint change, never by polling).
     * Close the handle when the capture ends.
     */
    fun watchLoss(plan: MicPlan, onLost: () -> Unit): AutoCloseable {
        val input = plan.preferred ?: return AutoCloseable { }
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        return policy.watch { if (!policy.inputConnected(input) && fired.compareAndSet(false, true)) onLost() }
    }
}
