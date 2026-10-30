package com.rumi.hermesvoice.watch

import android.content.*
import android.media.*
import android.os.*

internal interface WatchLiveRoute:AutoCloseable { fun open():Boolean; fun valid():Boolean
    /** R32: which of its checks made the last [open] return false (the setup observation's step); null when it did not refuse. */
    val refusal:WatchLiveSetupStep? get()=null
}
/** Explicitly bound communication output; removal/focus loss silences before teardown. No volume changes. */
internal class WatchNativeLiveRoute(private val app:WatchApp,private val lost:()->Unit):WatchLiveRoute {
    private val audio=app.getSystemService(AudioManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    private var active=false;private var chosen:Int?=null
    private var oldMode=audio.mode;private var oldSpeaker=audio.isSpeakerphoneOn
    private var oldDevice:AudioDeviceInfo?=null
    private var listener:Any?=null;private var focus:AudioFocusRequest?=null
    private val privateTypes=setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_USB_HEADSET)
    private fun privateOutput()=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull{it.type in privateTypes}
    private val devices=object:AudioDeviceCallback(){
        override fun onAudioDevicesAdded(added:Array<out AudioDeviceInfo>){if(active&&!valid())lost()}
        override fun onAudioDevicesRemoved(removed:Array<out AudioDeviceInfo>){if(active&&!valid())lost()}
    }
    private val noisy=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){if(active)lost()}}
    @Volatile private var refusedAt:WatchLiveSetupStep?=null
    override val refusal:WatchLiveSetupStep? get()=refusedAt
    private fun refused(step:WatchLiveSetupStep):Boolean{refusedAt=step;return false}
    override fun open():Boolean {
        refusedAt=null
        if(app.settings.value.privateAudio)return refused(WatchLiveSetupStep.ROUTE_PRIVATE_SELECTED)
        val personal=privateOutput()
        if(Build.VERSION.SDK_INT<31 && personal!=null)return refused(WatchLiveSetupStep.ROUTE_PERSONAL_OUTPUT)
        val f=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({if(it<=AudioManager.AUDIOFOCUS_LOSS&&active)lost()},handler).build()
        if(audio.requestAudioFocus(f)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return refused(WatchLiveSetupStep.ROUTE_FOCUS_DENIED)
        focus=f;oldMode=audio.mode;oldSpeaker=audio.isSpeakerphoneOn;audio.mode=AudioManager.MODE_IN_COMMUNICATION
        if(Build.VERSION.SDK_INT>=31){
            oldDevice=audio.communicationDevice
            val target=audio.availableCommunicationDevices.firstOrNull{if(personal==null)it.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else it.id==personal.id || (it.type==personal.type && it.address.isNotBlank() && it.address==personal.address)}
            if(target==null){close();return refused(WatchLiveSetupStep.ROUTE_NO_DEVICE)}
            if(!audio.setCommunicationDevice(target)){close();return refused(WatchLiveSetupStep.ROUTE_DEVICE_REFUSED)}
            chosen=target.id
            val l=AudioManager.OnCommunicationDeviceChangedListener{if(active&&!valid())lost()}
            listener=l;audio.addOnCommunicationDeviceChangedListener(app.mainExecutor,l)
        } else audio.isSpeakerphoneOn=true
        active=true;audio.registerAudioDeviceCallback(devices,handler)
        androidx.core.content.ContextCompat.registerReceiver(app,noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        return valid()||refused(WatchLiveSetupStep.ROUTE_NOT_VALID)
    }
    override fun valid():Boolean {
        if(!active || app.settings.value.privateAudio)return false
        val personal=privateOutput()
        return if(Build.VERSION.SDK_INT>=31)audio.communicationDevice?.id==chosen && (personal==null || personal.id==chosen)
        else personal==null && audio.isSpeakerphoneOn
    }
    override fun close(){
        active=false;runCatching{audio.unregisterAudioDeviceCallback(devices)};runCatching{app.unregisterReceiver(noisy)}
        if(Build.VERSION.SDK_INT>=31){
            (listener as? AudioManager.OnCommunicationDeviceChangedListener)?.let{audio.removeOnCommunicationDeviceChangedListener(it)};listener=null
            if(chosen!=null)runCatching{audio.clearCommunicationDevice()};chosen=null
        }else if(audio.isSpeakerphoneOn)audio.isSpeakerphoneOn=oldSpeaker
        if(audio.mode==AudioManager.MODE_IN_COMMUNICATION)audio.mode=oldMode
        focus?.let{audio.abandonAudioFocusRequest(it)};focus=null
    }
}
