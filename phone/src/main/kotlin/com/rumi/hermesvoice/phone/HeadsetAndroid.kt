package com.rumi.hermesvoice.phone

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.rumi.hermesvoice.core.headset.AudioEndpoint
import com.rumi.hermesvoice.core.headset.CommsLink
import com.rumi.hermesvoice.core.headset.HeadsetDevices
import com.rumi.hermesvoice.core.headset.LinkResult

/** The live audio endpoints of this phone (`AudioManager.getDevices`) and their changes (an `AudioDeviceCallback`, main thread). */
class AndroidHeadsetDevices(context: Context) : HeadsetDevices {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun outputs(): List<AudioEndpoint> = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(::endpoint)

    override fun inputs(): List<AudioEndpoint> = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map(::endpoint)

    /** Registered only for the length of one playback or capture; nothing polls and no service keeps it. */
    override fun watch(onChange: () -> Unit): AutoCloseable {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onChange()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onChange()
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        return AutoCloseable { audio.unregisterAudioDeviceCallback(callback) }
    }

    private fun endpoint(info: AudioDeviceInfo) = AudioEndpoint(info.id, info.type, info.address.orEmpty(), info.productName?.toString().orEmpty())
}

/** What the speaker sink needs of a player: a `MediaPlayer` on the phone, a fake in tests. All calls on the main thread. */
interface PlayerPort {
    /** Asks Android to play on the output with [deviceId]; false when there is no such output. Never evidence of routing. */
    fun setPreferredDevice(deviceId: Int): Boolean

    /** The id of the output Android reports the running player is on, or null when it names none (yet). */
    fun routedDeviceId(): Int?

    /** Silences (or restores) the player's own output level; set before [play] it keeps the clip inaudible wherever Android routes it. */
    fun setMuted(muted: Boolean)

    /**
     * Calls [onChange] (main thread) on every output-route change Android reports for this player, and [onNoisy] - a distinct,
     * unconditional loss signal - on a "becoming noisy" broadcast (the personal output was pulled), whatever the readback still
     * says; close the handle to stop. Throws when the subscription cannot be made.
     */
    fun watchRoute(onChange: () -> Unit, onNoisy: () -> Unit): AutoCloseable

    /** Plays [path]; the callbacks end it: exactly one of completed / error. Throws when the source can't be set. */
    fun play(path: String, onStarted: () -> Unit, onCompleted: () -> Unit, onError: (what: Int, extra: Int) -> Unit)

    fun stop()

    fun release()
}

fun interface PlayerFactory {
    fun create(attributes: AudioAttributes): PlayerPort
}

class AndroidPlayer(private val context: Context, attributes: AudioAttributes) : PlayerPort {
    private val player = MediaPlayer()
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    init {
        player.setAudioAttributes(attributes)
        // The player keeps the CPU awake while it plays, screen on or off.
        player.setWakeMode(context.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
    }

    override fun setPreferredDevice(deviceId: Int): Boolean {
        val device = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId } ?: return false
        return player.setPreferredDevice(device)
    }

    override fun routedDeviceId(): Int? = runCatching { player.routedDevice?.id }.getOrNull()

    override fun setMuted(muted: Boolean) {
        val level = if (muted) 0f else 1f
        player.setVolume(level, level)
    }

    override fun watchRoute(onChange: () -> Unit, onNoisy: () -> Unit): AutoCloseable {
        val app = context.applicationContext
        val routing = AudioRouting.OnRoutingChangedListener { onChange() }
        player.addOnRoutingChangedListener(routing, Handler(Looper.getMainLooper()))
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = onNoisy()
        }
        try {
            ContextCompat.registerReceiver(app, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (error: RuntimeException) {
            runCatching { player.removeOnRoutingChangedListener(routing) }
            throw error
        }
        return AutoCloseable {
            runCatching { player.removeOnRoutingChangedListener(routing) }
            runCatching { app.unregisterReceiver(noisy) }
        }
    }

    override fun play(path: String, onStarted: () -> Unit, onCompleted: () -> Unit, onError: (what: Int, extra: Int) -> Unit) {
        player.setOnCompletionListener { onCompleted() }
        player.setOnErrorListener { _, what, extra -> onError(what, extra); true }
        player.setDataSource(path)
        player.setOnPreparedListener { it.start(); onStarted() }
        player.prepareAsync()
    }

    override fun stop() {
        runCatching { player.stop() }
    }

    override fun release() {
        runCatching { player.release() }
    }
}

object AndroidPlayers {
    fun factory(context: Context) = PlayerFactory { attributes -> AndroidPlayer(context, attributes) }
}

/** What a recorder needs of the microphone: an `AudioRecord` on the phone, a fake in tests. */
interface RecordPort {
    val initialized: Boolean

    /** Asks Android to record from the input with [deviceId]; false when there is no such input. Never evidence of routing. */
    fun setPreferredDevice(deviceId: Int): Boolean

    /** The id of the input Android reports the running recorder is on, or null when it names none. */
    fun routedDeviceId(): Int?

    /** True when it is recording. */
    fun start(): Boolean

    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun stop()

    fun release()
}

fun interface RecordFactory {
    fun create(bufferBytes: Int): RecordPort?
}

@SuppressLint("MissingPermission") // Callers request RECORD_AUDIO first.
class AndroidRecord(context: Context, bufferBytes: Int) : RecordPort {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, bufferBytes)

    override val initialized: Boolean get() = record.state == AudioRecord.STATE_INITIALIZED

    override fun setPreferredDevice(deviceId: Int): Boolean {
        val device = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == deviceId } ?: return false
        return record.setPreferredDevice(device)
    }

    override fun routedDeviceId(): Int? = runCatching { record.routedDevice?.id }.getOrNull()

    override fun start(): Boolean = runCatching { record.startRecording() }.isSuccess && record.recordingState == AudioRecord.RECORDSTATE_RECORDING

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = record.read(buffer, offset, length)

    override fun stop() {
        runCatching { record.stop() }
    }

    override fun release() {
        runCatching { record.release() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

object AndroidRecords {
    fun factory(context: Context) = RecordFactory { bufferBytes -> runCatching { AndroidRecord(context, bufferBytes) }.getOrNull() }
}

/**
 * The two-way link a Bluetooth headset microphone needs, owned by the app only for one capture: a communication device on
 * Android 12+, legacy Bluetooth SCO before. It changes the audio mode only when it is MODE_NORMAL (a call or another app's
 * communication mode is never taken over), and on close puts back exactly what it changed. Every wait is bounded by [timeoutMs].
 * Playback never uses it.
 */
class AndroidCommsLink(private val context: Context, private val timeoutMs: Long = LINK_TIMEOUT_MS) : CommsLink {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("NewApi")
    override fun acquire(input: AudioEndpoint, onResult: (LinkResult) -> Unit): AutoCloseable {
        val link = Link(onResult)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            link.finish(LinkResult.PERMISSION)
            return link
        }
        if (audio.mode != AudioManager.MODE_NORMAL) {
            link.finish(LinkResult.IN_USE)
            return link
        }
        link.takeMode()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) link.startCommunicationDevice(input) else link.startLegacySco()
        return link
    }

    private inner class Link(private val onResult: (LinkResult) -> Unit) : AutoCloseable {
        private var done = false
        private var savedMode: Int? = null
        private var deviceSet = false
        private var scoStarted = false
        private var receiver: BroadcastReceiver? = null
        private var listener: Any? = null
        private val timeout = Runnable { finish(LinkResult.TIMEOUT) }

        fun takeMode() {
            savedMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
        }

        @Synchronized
        fun finish(result: LinkResult) {
            if (done) return
            done = true
            main.removeCallbacks(timeout)
            if (result != LinkResult.READY) undo()
            onResult(result)
        }

        @Suppress("NewApi")
        fun startCommunicationDevice(input: AudioEndpoint) {
            val device = audio.availableCommunicationDevices.firstOrNull { it.id == input.id }
            if (device == null) return finish(LinkResult.UNAVAILABLE)
            val changed = AudioManager.OnCommunicationDeviceChangedListener { current -> if (current?.id == device.id) main.post { finish(LinkResult.READY) } }
            listener = changed
            audio.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(app), changed)
            if (!audio.setCommunicationDevice(device)) return finish(LinkResult.UNAVAILABLE)
            deviceSet = true
            if (audio.communicationDevice?.id == device.id) return finish(LinkResult.READY)
            main.postDelayed(timeout, timeoutMs)
        }

        @Suppress("DEPRECATION")
        fun startLegacySco() {
            if (!audio.isBluetoothScoAvailableOffCall) return finish(LinkResult.UNAVAILABLE)
            val scoReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED) finish(LinkResult.READY)
                }
            }
            receiver = scoReceiver
            ContextCompat.registerReceiver(app, scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED)
            scoStarted = true
            audio.startBluetoothSco()
            main.postDelayed(timeout, timeoutMs)
        }

        @Synchronized
        override fun close() {
            done = true
            main.removeCallbacks(timeout)
            undo()
        }

        /** Puts back only what this link changed, in reverse order; safe to run twice. */
        @Suppress("NewApi", "DEPRECATION")
        private fun undo() {
            (listener as? AudioManager.OnCommunicationDeviceChangedListener)?.let { runCatching { audio.removeOnCommunicationDeviceChangedListener(it) } }
            listener = null
            if (deviceSet) runCatching { audio.clearCommunicationDevice() }
            deviceSet = false
            if (scoStarted) runCatching { audio.stopBluetoothSco() }
            scoStarted = false
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
            receiver = null
            savedMode?.let { saved -> if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = saved }
            savedMode = null
        }
    }

    companion object {
        const val LINK_TIMEOUT_MS = 4_000L
    }
}
