package com.rumi.hermesvoice.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.rumi.hermesvoice.core.headset.AudioEndpoint
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.headset.RecordingCueTone
import java.util.concurrent.atomic.AtomicBoolean

/** One ding marks an accepted recording start, two an accepted stop. */
enum class CueKind(val dings: Int) { START(1), STOP(2) }

enum class CueResult { PLAYED, NO_HEADSET, FOCUS_DENIED, FOCUS_LOST, UNROUTED, LOST, FAILED, TIMEOUT, CANCELLED }

fun interface CueOutput {
    /**
     * Plays [pcm] only to [target]; [done] is called once with how it ended. The returned handle cancels it silently.
     * Playing to anything else, or when [target] cannot be confirmed, is a failure, never a fallback.
     */
    fun play(pcm: ByteArray, sampleRate: Int, target: AudioEndpoint, done: (CueResult) -> Unit): AutoCloseable
}

/**
 * The private recording cue: plays only to the connected personal headset output while the setting is on, one at a time, and ends
 * exactly once - played, failed, cancelled by a newer cue, lost with the headset or timed out. With no private output nothing plays
 * and [play] returns null: there is no speaker or default-output path.
 */
class RecordingCues(private val policy: HeadsetPolicy, private val output: CueOutput, private val timeoutMs: Long = CUE_TIMEOUT_MS) {
    private class Active(val target: AudioEndpoint, val done: (CueResult) -> Unit) {
        val finished = AtomicBoolean(false)
        var watch: AutoCloseable? = null
        var handle: AutoCloseable? = null
        var timeout: Runnable? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var active: Active? = null

    fun available(): Boolean = policy.output() != null

    fun play(kind: CueKind, done: (CueResult) -> Unit): AutoCloseable? {
        val target = policy.output() ?: return null
        val previous = synchronized(lock) { active }
        previous?.let { end(it, CueResult.CANCELLED, notify = true) }
        val cue = Active(target, done)
        synchronized(lock) { active = cue }
        val watch = policy.watch { check(cue) }
        cue.watch = watch
        if (cue.finished.get()) runCatching { watch.close() }
        val timeout = Runnable { end(cue, CueResult.TIMEOUT, notify = true) }
        cue.timeout = timeout
        main.postDelayed(timeout, timeoutMs)
        val handle = runCatching { output.play(RecordingCueTone.pcm(kind.dings), RecordingCueTone.SAMPLE_RATE, target) { result -> end(cue, result, notify = true) } }
        handle.onSuccess { made ->
            synchronized(lock) { cue.handle = made }
            if (cue.finished.get()) runCatching { made.close() }
        }
        handle.onFailure { end(cue, CueResult.FAILED, notify = true) }
        check(cue)
        return AutoCloseable { end(cue, CueResult.CANCELLED, notify = false) }
    }

    /** The setting or the devices may have changed since a cue began: a cue whose headset is gone is cancelled as lost. */
    fun recheck() {
        val cue = synchronized(lock) { active } ?: return
        check(cue)
    }

    private fun check(cue: Active) {
        if (cue.finished.get()) return
        if (!policy.enabled || !policy.connected(cue.target)) end(cue, CueResult.LOST, notify = true)
    }

    private fun end(cue: Active, result: CueResult, notify: Boolean) {
        if (!cue.finished.compareAndSet(false, true)) return
        synchronized(lock) { if (active === cue) active = null }
        cue.timeout?.let { main.removeCallbacks(it) }
        cue.watch?.let { runCatching { it.close() } }
        cue.watch = null
        val handle = synchronized(lock) { cue.handle }
        handle?.let { runCatching { it.close() } }
        if (notify) cue.done(result)
    }

    companion object {
        const val CUE_TIMEOUT_MS = 1_500L
    }
}

/** What the cue output needs of an `AudioTrack`; the production one is [AndroidCueTrack], a fake in tests. Calls on the main thread. */
interface CueTrackPort {
    /** Asks Android to play on the output with [deviceId]; false when there is no such output or Android does not take it. */
    fun setPreferredDevice(deviceId: Int): Boolean

    /** Loads the whole clip (static track); false when it was not all accepted. */
    fun write(pcm: ByteArray): Boolean

    /** Silences (or restores) the track's level. */
    fun setMuted(muted: Boolean)

    /** The id of the output Android reports the track is on, or null when it names none (yet). */
    fun routedDeviceId(): Int?

    /** [onChange] on every route change Android reports; close the handle to stop. */
    fun watchRoute(onChange: () -> Unit, handler: Handler): AutoCloseable

    /** [reached] once, when playback passed [frames] frames. */
    fun onMarker(frames: Int, handler: Handler, reached: () -> Unit)

    fun play()

    fun stop()

    fun release()
}

fun interface CueTrackFactory {
    /** Throws when no track can be built. */
    fun create(attributes: AudioAttributes, sampleRate: Int, bytes: Int): CueTrackPort
}

class AndroidCueTrack(private val audio: AudioManager, attributes: AudioAttributes, sampleRate: Int, bytes: Int) : CueTrackPort {
    private val track = AudioTrack.Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(bytes)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()

    override fun setPreferredDevice(deviceId: Int): Boolean {
        val device = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId } ?: return false
        return track.setPreferredDevice(device) && track.preferredDevice != null
    }

    override fun write(pcm: ByteArray): Boolean = track.write(pcm, 0, pcm.size) == pcm.size

    override fun setMuted(muted: Boolean) {
        track.setVolume(if (muted) 0f else 1f)
    }

    override fun routedDeviceId(): Int? = runCatching { track.routedDevice?.id }.getOrNull()

    override fun watchRoute(onChange: () -> Unit, handler: Handler): AutoCloseable {
        val listener = AudioRouting.OnRoutingChangedListener { onChange() }
        track.addOnRoutingChangedListener(listener, handler)
        return AutoCloseable { runCatching { track.removeOnRoutingChangedListener(listener) } }
    }

    override fun onMarker(frames: Int, handler: Handler, reached: () -> Unit) {
        track.setNotificationMarkerPosition(frames)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) = reached()

            override fun onPeriodicNotification(track: AudioTrack) = Unit
        }, handler)
    }

    override fun play() = track.play()

    override fun stop() {
        track.stop()
    }

    override fun release() {
        track.release()
    }

    companion object {
        fun factory(context: Context) = CueTrackFactory { attributes, sampleRate, bytes ->
            AndroidCueTrack(context.applicationContext.getSystemService(AudioManager::class.java), attributes, sampleRate, bytes)
        }
    }
}

/**
 * The production cue output: a static track built from the generated tone, asked for the headset as its preferred device and
 * kept muted until Android reports that exact device as its route. Audio focus is requested for the cue and given back; losing it
 * (LOSS or LOSS_TRANSIENT) ends the cue at once, muted and stopped, as [CueResult.FOCUS_LOST]. A cue that is never confirmed on the
 * headset stays silent and is reported as unrouted; it is never moved to another output. Every ending happens once.
 *
 * After the exact headset was confirmed, Android naming no route at all (or another one) is lost authority and ends the cue
 * muted as [CueResult.UNROUTED]. A "becoming noisy" broadcast is an explicit loss signal that ends it at once, muted, as
 * [CueResult.LOST], whatever the readback still says; its receiver is registered only for a live cue (before the track is
 * built), given back exactly once by whichever ending comes first, and a receiver that cannot be registered fails the cue
 * before any track exists.
 */
class AndroidCueOutput(
    private val context: Context,
    private val tracks: CueTrackFactory = AndroidCueTrack.factory(context),
) : CueOutput {
    private val main = Handler(Looper.getMainLooper())

    override fun play(pcm: ByteArray, sampleRate: Int, target: AudioEndpoint, done: (CueResult) -> Unit): AutoCloseable {
        val audio = context.applicationContext.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val finished = AtomicBoolean(false)
        var track: CueTrackPort? = null
        var routeWatch: AutoCloseable? = null
        var verified = false
        var noisy: BroadcastReceiver? = null

        var focus: AudioFocusRequest? = null

        fun cleanup() {
            noisy?.let { receiver ->
                noisy = null
                runCatching { context.applicationContext.unregisterReceiver(receiver) }
            }
            val made = track
            track = null
            if (made != null) {
                runCatching { routeWatch?.close() }
                routeWatch = null
                runCatching { made.setMuted(true) }
                runCatching { made.stop() }
                runCatching { made.release() }
            }
            focus?.let { request -> runCatching { audio.abandonAudioFocusRequest(request) } }
        }

        fun end(result: CueResult) {
            if (!finished.compareAndSet(false, true)) return
            cleanup()
            main.post { done(result) }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) end(CueResult.FOCUS_LOST)
            }, main)
            .build()
        focus = request
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            finished.set(true)
            main.post { done(CueResult.FOCUS_DENIED) }
            return AutoCloseable { }
        }
        val becomingNoisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = end(CueResult.LOST)
        }
        try {
            ContextCompat.registerReceiver(
                context.applicationContext, becomingNoisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisy = becomingNoisy
        } catch (error: RuntimeException) {
            end(CueResult.FAILED)
            return AutoCloseable { }
        }
        try {
            val made = tracks.create(attributes, sampleRate, pcm.size)
            track = made
            // A focus loss while the track was being built already ended the cue: nothing may start after it.
            if (finished.get()) { cleanup(); return AutoCloseable { } }
            made.setMuted(true)
            if (!made.setPreferredDevice(target.id)) {
                end(CueResult.UNROUTED)
                return AutoCloseable { }
            }
            if (!made.write(pcm)) {
                end(CueResult.FAILED)
                return AutoCloseable { }
            }
            val check = {
                if (!finished.get()) {
                    val routed = made.routedDeviceId()
                    if (routed != null && routed == target.id) {
                        if (!verified) { verified = true; made.setMuted(false) }
                    } else if (routed != null || verified) {
                        end(CueResult.UNROUTED)
                    }
                }
            }
            routeWatch = made.watchRoute({ check() }, main)
            made.onMarker(pcm.size / 2, main) { end(if (verified) CueResult.PLAYED else CueResult.UNROUTED) }
            if (finished.get()) { cleanup(); return AutoCloseable { } }
            made.play()
            check()
        } catch (error: Exception) {
            end(CueResult.FAILED)
        }
        return AutoCloseable {
            if (finished.compareAndSet(false, true)) cleanup()
        }
    }
}
