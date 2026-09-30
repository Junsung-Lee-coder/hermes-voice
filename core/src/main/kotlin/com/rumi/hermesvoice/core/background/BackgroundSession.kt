package com.rumi.hermesvoice.core.background

import com.rumi.hermesvoice.core.KeyValueStore

/** The foreground service of one device's background session, as the session needs it. */
interface BackgroundPort {
    /**
     * Starts the foreground service, typed for the microphone too when [microphone]. False when the
     * platform refused it; nothing is left running then.
     */
    fun startService(microphone: Boolean): Boolean

    /** Changes whether the running service is typed for the microphone. False when the platform refused; the old type stays. */
    fun retypeService(microphone: Boolean): Boolean
    fun stopService()
}

/** Why a running session's microphone is not armed; null when nothing stands in the way but visibility. */
enum class MicBlock {
    /** This device's microphone isn't wanted: a relay, or the Phone's wake settings exclude the device. */
    NOT_WANTED,

    /** No microphone permission. */
    PERMISSION,

    /** The device has no speech recognizer: nothing could listen. */
    NO_RECOGNIZER,

    /**
     * The session's notification can't be shown (not allowed, the app's notifications or the
     * session's channel switched off): a hidden microphone would have no visible indicator and no
     * Stop outside the app, so it is not armed.
     */
    NOTIFICATIONS;

    companion object {
        fun of(wanted: Boolean, permission: Boolean): MicBlock? = when {
            !wanted -> NOT_WANTED
            !permission -> PERMISSION
            else -> null
        }
    }
}

/** What the user is told about the background session. */
enum class BackgroundNotice {
    /** Not opted in. */
    OFF,

    /** Running; this device's microphone is not wanted (a relay, or the wake settings exclude the device). */
    RUNNING,

    /** Running with the microphone armed. */
    LISTENING,

    /** Running, but listening needs the microphone permission. */
    NEEDS_PERMISSION,

    /** Running, but the microphone can only be armed while the app is on screen. */
    NEEDS_VISIBLE_TO_LISTEN,

    /** Running for replies only: its notification can't be shown, so it doesn't listen. */
    NEEDS_NOTIFICATIONS,

    /** Running for replies only: the device has no speech recognizer. */
    NO_RECOGNIZER,

    /** Opted in, not running (the system ended it, or the device restarted): the user has to start it again. */
    PAUSED,

    /** A start was asked for while the app was not on screen: refused. */
    NEEDS_VISIBLE,

    /** The platform refused to start the service. */
    REFUSED,
}

data class BackgroundStatus(val wanted: Boolean, val running: Boolean, val microphone: Boolean, val notice: BackgroundNotice)

/**
 * One device's opt-in background session, independent of Android. Off unless the user started it
 * (the choice is stored under [key]; nothing else ever writes it). It starts only from a visible
 * app, and its microphone is armed only from a visible app and only when nothing blocks it
 * ([MicBlock]): a change that arrives while the app is hidden can disarm it but never arm it.
 * Stopping is the user's and is final; a session the system ended stays "wanted" and reads as
 * paused until the user starts it again ([resumeWhenVisible]: a session without a microphone may
 * also resume when its app is shown). Every started session has a [generation]; whatever belongs
 * to an older one [isCurrent] rejects.
 */
class BackgroundSession(
    private val store: KeyValueStore,
    private val key: String,
    private val port: BackgroundPort,
    private val resumeWhenVisible: Boolean,
    private val onChanged: (BackgroundStatus) -> Unit = {},
) {
    private var running = false
    private var microphone = false
    private var block: MicBlock? = MicBlock.NOT_WANTED
    private var refused: BackgroundNotice? = null
    private var last: BackgroundStatus? = null

    /** Counts started and ended sessions; a service or callback carries the value it was started under. */
    var generation = 0L
        private set

    private val wanted: Boolean get() = store.getBoolean(key, false)

    val status: BackgroundStatus
        @Synchronized get() = BackgroundStatus(wanted, running, microphone, when {
            !wanted -> BackgroundNotice.OFF
            !running -> refused ?: BackgroundNotice.PAUSED
            microphone -> BackgroundNotice.LISTENING
            else -> when (block) {
                MicBlock.NOT_WANTED -> BackgroundNotice.RUNNING
                MicBlock.PERMISSION -> BackgroundNotice.NEEDS_PERMISSION
                MicBlock.NO_RECOGNIZER -> BackgroundNotice.NO_RECOGNIZER
                MicBlock.NOTIFICATIONS -> BackgroundNotice.NEEDS_NOTIFICATIONS
                null -> BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN
            }
        })

    init {
        last = status
    }

    @Synchronized
    fun isCurrent(generation: Long): Boolean = running && generation == this.generation

    /** The user asked for background operation. Only a [visible] app may start it. */
    fun start(visible: Boolean, microphoneWanted: Boolean, microphonePermission: Boolean): BackgroundStatus =
        start(visible, MicBlock.of(microphoneWanted, microphonePermission))

    /** The user asked for background operation. Only a [visible] app may start it; the microphone only when [block] is null. */
    @Synchronized
    fun start(visible: Boolean, block: MicBlock?): BackgroundStatus {
        if (!visible) return status.copy(notice = BackgroundNotice.NEEDS_VISIBLE)
        store.putBoolean(key, true)
        this.block = block
        if (running) return arm()
        val arming = block == null
        val started = port.startService(arming)
        // The microphone type alone may be refused: run for playback and say so.
        val fallback = !started && arming && port.startService(false)
        running = started || fallback
        microphone = started && arming
        refused = if (running) null else BackgroundNotice.REFUSED
        if (running) generation += 1
        return changed()
    }

    /** The user stopped it: off for good, until they start it again. Safe to repeat. */
    @Synchronized
    fun stop(): BackgroundStatus {
        store.putBoolean(key, false)
        refused = null
        if (running) {
            running = false
            microphone = false
            generation += 1
            port.stopService()
        }
        return changed()
    }

    /** The service of session [generation] is gone without the user's stop (the system ended it). */
    @Synchronized
    fun onServiceGone(generation: Long): BackgroundStatus {
        if (!isCurrent(generation)) return status
        running = false
        microphone = false
        this.generation += 1
        return changed()
    }

    /**
     * The platform refused the microphone for the service of session [generation] after all (the
     * refusal arrives when the service really starts): it runs on without it, and says listening
     * needs the app on screen.
     */
    @Synchronized
    fun onMicrophoneRefused(generation: Long): BackgroundStatus {
        if (!isCurrent(generation) || !microphone) return status
        microphone = false
        return changed()
    }

    /**
     * The microphone of session [generation] was granted but nothing can use it (the app wasn't
     * visible when it came, or the loop can't open a window): it is given back, and the session
     * says listening needs the app on screen.
     */
    @Synchronized
    fun onMicrophoneStalled(generation: Long): BackgroundStatus {
        if (!isCurrent(generation) || !microphone) return status
        microphone = false
        port.retypeService(false)
        return changed()
    }

    /** The app is on screen: the only moment a running session's microphone may be armed. */
    fun onVisible(microphoneWanted: Boolean, microphonePermission: Boolean): BackgroundStatus =
        onVisible(MicBlock.of(microphoneWanted, microphonePermission))

    /** The app is on screen: the only moment a running session's microphone may be armed (when [block] is null). */
    @Synchronized
    fun onVisible(block: MicBlock?): BackgroundStatus {
        this.block = block
        if (!running) return if (wanted && resumeWhenVisible) start(true, block) else status
        return arm()
    }

    /**
     * Whether this device's microphone is wanted changed (the Phone's wake settings, or the
     * permission). Not wanted or not permitted disarms at once; wanted arms only while [visible].
     */
    fun onMicrophoneWanted(wanted: Boolean, visible: Boolean, permission: Boolean): BackgroundStatus =
        onMicrophoneBlock(MicBlock.of(wanted, permission), visible)

    /**
     * What stands in the way of the microphone changed. Any [block] disarms at once, wherever it
     * comes from; none arms only while [visible].
     */
    @Synchronized
    fun onMicrophoneBlock(block: MicBlock?, visible: Boolean): BackgroundStatus {
        this.block = block
        if (!running) return status
        if (microphone && block != null) {
            microphone = false
            port.retypeService(false)
            return changed()
        }
        return if (visible) arm() else changed()
    }

    private fun arm(): BackgroundStatus {
        if (!microphone && block == null) microphone = port.retypeService(true)
        if (microphone && block != null) {
            microphone = false
            port.retypeService(false)
        }
        return changed()
    }

    private fun changed(): BackgroundStatus {
        val now = status
        if (now != last) {
            last = now
            onChanged(now)
        }
        return now
    }
}
