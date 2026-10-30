package com.rumi.hermesvoice.phone.live

import android.content.*
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.rumi.hermesvoice.phone.PhoneApp

/** Owns only this call's focus/mode/device. Private admission never falls back to a public output. */
class LiveAudioRoute(private val app: PhoneApp, private val lost: () -> Unit) : LiveRoute {
    private val audio = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private var previousMode = audio.mode
    private var previousSpeaker = audio.isSpeakerphoneOn
    private var chosen: Int? = null
    private var previousDevice: AudioDeviceInfo? = null
    private var privateId: Int? = null
    private var privateAddress: String = ""
    private var active = false
    private var modeOwned = false
    private var communicationListener: Any? = null
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (active && !valid()) lost()
        }
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { if (active && !valid()) lost() }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (active && intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) lost() }
    }
    override fun open(): Boolean {
        val personal = app.headset.output()
        privateId = personal?.id
        privateAddress=personal?.address.orEmpty()
        // PhoneDeviceLiveTransport uses zero-level per-AudioTrack confirmation for private output,
        // independently of whether that media sink has a communication input (including A2DP).
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change -> if (change <= AudioManager.AUDIOFOCUS_LOSS && active) lost() }, main).build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = request; previousMode = audio.mode; previousSpeaker = audio.isSpeakerphoneOn
        if(personal==null){audio.mode = AudioManager.MODE_IN_COMMUNICATION;modeOwned=true}
        if (personal==null && Build.VERSION.SDK_INT >= 31) {
            previousDevice = audio.communicationDevice
            val target = if (personal == null) audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                else audio.availableCommunicationDevices.firstOrNull { it.id == personal.id ||
                    (it.type == personal.type && it.address == personal.address && personal.address.isNotBlank()) }
            if (target == null || !audio.setCommunicationDevice(target)) { close(); return false }
            chosen = target.id
            val listener = AudioManager.OnCommunicationDeviceChangedListener { if (active && !valid()) lost() }
            audio.addOnCommunicationDeviceChangedListener(app.mainExecutor, listener); communicationListener = listener
        } else if(personal==null) audio.isSpeakerphoneOn = true
        active = true
        audio.registerAudioDeviceCallback(devices, main)
        androidx.core.content.ContextCompat.registerReceiver(app, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        return true
    }
    override fun valid(): Boolean {
        if (!active) return false
        if (privateId != null && app.headset.output()?.id != privateId) {
            val current=app.headset.output()?:return false
            if(Build.VERSION.SDK_INT<31||privateAddress.isBlank()||current.address!=privateAddress||audio.communicationDevice?.id!=current.id)return false
        }
        if (privateId == null && app.headset.output() != null) return false // a new private policy requires a new explicit call.
        if(privateId!=null)return true // actual private AudioTrack readback is mandatory before nonzero PCM
        return if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.id == chosen else privateId == null && audio.isSpeakerphoneOn
    }
    override fun close() {
        active = false
        runCatching { audio.unregisterAudioDeviceCallback(devices) }
        runCatching { app.unregisterReceiver(noisy) }
        if (Build.VERSION.SDK_INT >= 31) {
            (communicationListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let { audio.removeOnCommunicationDeviceChangedListener(it) }
            communicationListener = null
            if (chosen!=null) runCatching { audio.clearCommunicationDevice() } // OUR accepted request, including a not-yet-active one
            chosen=null
        } else if (audio.isSpeakerphoneOn) audio.isSpeakerphoneOn = previousSpeaker
        if (modeOwned && audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = previousMode
        modeOwned=false
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
    }
}
