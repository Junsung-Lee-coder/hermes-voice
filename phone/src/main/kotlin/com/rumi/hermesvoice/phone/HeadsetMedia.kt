package com.rumi.hermesvoice.phone

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.headset.MediaButtonDecoder
import com.rumi.hermesvoice.core.headset.RecordingCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the Phone's recording controller did with a headset media command. */
enum class HeadsetOutcome { STARTED, STOPPED, IGNORED, REFUSED }

/** The one recording controller a headset media command reaches (the Phone screen's view model). */
interface HeadsetRecordingTarget {
    fun onHeadsetCommand(command: RecordingCommand): HeadsetOutcome

    /**
     * The app is hidden, a recording this target started is still open, and the headset control for it can no longer be kept (the
     * setting went off or the personal output is gone): nothing could stop that recording any more, so the target ends and discards it.
     */
    fun onHeadsetControlLost() = Unit
}

/** Why the headset media control is or is not registered; [REGISTERED] says only that this app asked Android for the buttons. */
enum class MediaControlStatus { OFF, NO_HEADSET, APP_CLOSED, UNAVAILABLE, REGISTERED }

interface MediaSessionPort {
    /** Mirrors the recording in the session's control state; this is not audio and starts no playback. */
    fun setRecording(recording: Boolean)

    fun release()
}

fun interface MediaSessionFactory {
    /** Null when Android gave no session. */
    fun open(callback: MediaSession.Callback): MediaSessionPort?
}

/** The session's callback: media keys and the transport play / pause become at most one recording command per press. */
class HeadsetMediaCallback(private val decoder: MediaButtonDecoder, private val dispatch: (RecordingCommand) -> Unit) : MediaSession.Callback() {
    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        val event = keyEvent(mediaButtonIntent) ?: return false
        if (!decoder.handles(event.keyCode)) return false
        decoder.key(event.keyCode, event.action, event.repeatCount, event.downTime)?.let(dispatch)
        return true
    }

    override fun onPlay() {
        decoder.transport(RecordingCommand.START)?.let(dispatch)
    }

    override fun onPause() {
        decoder.transport(RecordingCommand.STOP)?.let(dispatch)
    }

    private fun keyEvent(intent: Intent): KeyEvent? {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return null
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }
}

/**
 * Registers a media session for the headset buttons ONLY while "Use headset" is on, a personal headset output is connected, the
 * Phone screen has a recording controller and the app is visible; releases it on every other state. The one exception is a Phone
 * recording that is already open ([recording]): its session is kept while the app is hidden or the screen locked, so the headset can
 * still finish that exact recording; a hidden app never gets a session for a new one. Every other release rule is unchanged (setting
 * off, no output, no screen target); when one of them ends the control of a hidden open recording the target is told
 * ([HeadsetRecordingTarget.onHeadsetControlLost]) so it can discard that orphan capture. It starts no service, plays no audio and watches the devices only while the setting is on. Whether Android hands the buttons to this app is Android's decision.
 */
class HeadsetMediaControl(
    private val policy: HeadsetPolicy,
    private val sessions: MediaSessionFactory,
    private val visible: () -> Boolean,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private class Registration(val port: MediaSessionPort) { @Volatile var live = true }

    private val lock = Any()
    private var target: HeadsetRecordingTarget? = null
    private var registration: Registration? = null
    private var deviceWatch: AutoCloseable? = null
    private var closed = false
    private var capturing = false
    private var refreshing = false
    private var again = false
    private val state = MutableStateFlow(MediaControlStatus.OFF)
    val status: StateFlow<MediaControlStatus> = state

    fun attach(target: HeadsetRecordingTarget) {
        synchronized(lock) { this.target = target }
        refresh()
    }

    fun detach(target: HeadsetRecordingTarget) {
        val changed = synchronized(lock) { (this.target === target).also { if (it) { this.target = null; capturing = false } } }
        if (changed) refresh()
    }

    fun refresh() {
        // Registering the device watch can call back at once; that re-entry only asks for another pass.
        synchronized(lock) {
            if (refreshing) { again = true; return }
            refreshing = true
        }
        try {
            do {
                synchronized(lock) { again = false }
                // Outside the lock: the target ends its recording, which calls back into [recording].
                refreshOnce()?.let { runCatching { it.onHeadsetControlLost() } }
            } while (synchronized(lock) { again })
        } finally {
            synchronized(lock) { refreshing = false }
        }
    }

    /** One pass; returns the target whose open recording lost its headset control while the app is hidden, if any. */
    private fun refreshOnce(): HeadsetRecordingTarget? {
        synchronized(lock) {
            if (closed) return null
            val hiddenCapture = if (capturing && !visible()) target else null
            if (!policy.enabled) {
                deviceWatch?.let { runCatching { it.close() } }
                deviceWatch = null
                release()
                state.value = MediaControlStatus.OFF
                return hiddenCapture
            }
            if (deviceWatch == null) deviceWatch = policy.watch { refresh() }
            when {
                policy.output() == null -> { release(); state.value = MediaControlStatus.NO_HEADSET; return hiddenCapture }
                target == null || (!visible() && !capturing) -> { release(); state.value = MediaControlStatus.APP_CLOSED }
                registration != null -> state.value = MediaControlStatus.REGISTERED
                else -> state.value = open()
            }
            return null
        }
    }

    /**
     * A Phone recording opened or ended. The session mirrors it (if one is registered); and while one is open the session survives the
     * app being hidden, and when it ends in a hidden app the session is released at once.
     */
    fun recording(on: Boolean) {
        val current = synchronized(lock) {
            capturing = on
            registration
        }
        current?.let { runCatching { it.port.setRecording(on) } }
        refresh()
    }

    fun close() {
        synchronized(lock) {
            closed = true
            target = null
            capturing = false
            deviceWatch?.let { runCatching { it.close() } }
            deviceWatch = null
            release()
            state.value = MediaControlStatus.OFF
        }
    }

    private fun open(): MediaControlStatus {
        val decoder = MediaButtonDecoder(clock)
        var made: Registration? = null
        val callback = HeadsetMediaCallback(decoder) { command ->
            if (made?.live == true) synchronized(lock) { target }?.onHeadsetCommand(command)
        }
        val port = runCatching { sessions.open(callback) }.getOrNull() ?: return MediaControlStatus.UNAVAILABLE
        registration = Registration(port).also { made = it }
        // A session registered while a recording is open (after a reconnect, say) shows the true state, not the idle one.
        if (capturing) runCatching { port.setRecording(true) }
        return MediaControlStatus.REGISTERED
    }

    private fun release() {
        val old = registration ?: return
        registration = null
        old.live = false
        runCatching { old.port.release() }
    }
}

/** The production sessions: a plain [MediaSession] on the main thread, active only while registered; no service, no manifest entry. */
class AndroidMediaSessions(private val context: Context) : MediaSessionFactory {
    override fun open(callback: MediaSession.Callback): MediaSessionPort? = runCatching {
        val session = MediaSession(context.applicationContext, SESSION_TAG)
        @Suppress("DEPRECATION")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setCallback(callback, Handler(Looper.getMainLooper()))
        session.setPlaybackState(playbackState(recording = false))
        session.isActive = true
        object : MediaSessionPort {
            override fun setRecording(recording: Boolean) = session.setPlaybackState(playbackState(recording))

            override fun release() {
                runCatching { session.isActive = false }
                session.setCallback(null)
                session.release()
            }
        }
    }.getOrNull()

    private fun playbackState(recording: Boolean): PlaybackState = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
        .setState(if (recording) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
        .build()

    private companion object {
        const val SESSION_TAG = "hermes-voice-headset"
    }
}
