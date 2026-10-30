package com.rumi.hermesvoice.phone.live

import android.content.*
import android.media.*
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.rumi.hermesvoice.core.live.LivePcmFrame
import com.rumi.hermesvoice.phone.PhoneApp

internal interface LivePcmOutput:AutoCloseable { fun write(frame:LivePcmFrame):Boolean }

/** Streaming decoded Live audio, never STT/TTS or Phone capture. Own zero-level route probing and no public fallback. */
internal class PhonePrivateLivePcm(private val app:PhoneApp,private val deviceId:Int,private val current:()->Boolean,
    private val lost:()->Unit,private val ownsAudioLease:Boolean=false,private val selectedPolicy:(()->Boolean)?=null):LivePcmOutput {
    private val audio=app.getSystemService(AudioManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    private var track:AudioTrack?=null;private var format:Pair<Int,Int>?=null
    private var focus:AudioFocusRequest?=null;private var lease:AutoCloseable?=null
    private var closed=false;private var probes=0;private var confirmed=false
    private val routing=AudioRouting.OnRoutingChangedListener{ synchronized(this){val actual=track?.routedDevice?.id;if((actual!=null&&actual!=deviceId)||(confirmed&&actual==null))fail()} }
    private val devices=object:AudioDeviceCallback(){override fun onAudioDevicesRemoved(removed:Array<out AudioDeviceInfo>){if(removed.any{it.id==deviceId})synchronized(this@PhonePrivateLivePcm){fail()}}}
    private val noisy=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){synchronized(this@PhonePrivateLivePcm){fail()}}}
    init {
        try {
            audio.registerAudioDeviceCallback(devices,handler)
            ContextCompat.registerReceiver(app,noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch(error:Exception){
            runCatching{audio.unregisterAudioDeviceCallback(devices)};runCatching{app.unregisterReceiver(noisy)}
            throw error
        }
    }
    private fun allowed()=!closed&&current()&&(selectedPolicy?.invoke()?: (app.headset.output()?.id==deviceId))
    @Synchronized override fun write(frame:LivePcmFrame):Boolean {
        if(!frame.valid()||!allowed()){fail();return false}
        try {
            var t=track
            if(t==null){
                val target=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull{it.id==deviceId}?:return fail()
                if(!ownsAudioLease)lease=app.liveAudioGate.live()?:return fail()
                val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                val f=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener({if(it<=AudioManager.AUDIOFOCUS_LOSS)synchronized(this){fail()}},handler).build()
                if(audio.requestAudioFocus(f)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return fail()
                focus=f
                val mask=if(frame.channels==1)AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val minimum=AudioTrack.getMinBufferSize(frame.sampleRate,mask,AudioFormat.ENCODING_PCM_16BIT)
                if(minimum<=0)return fail()
                t=AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(frame.sampleRate).setChannelMask(mask).build()).setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minimum,frame.sampleRate*frame.channels*2/10)).build()
                track=t;format=frame.sampleRate to frame.channels
                t.setVolume(0f) // before play, preference, or ANY PCM write
                if(!t.setPreferredDevice(target))return fail()
                t.addOnRoutingChangedListener(routing,handler);t.play()
            }
            if(format!=(frame.sampleRate to frame.channels))return fail()
            if(!allowed())return fail()
            if(t.routedDevice?.id!=deviceId){
                t.setVolume(0f)
                if(++probes>25)return fail()
                val silence=ByteArray(frame.pcm.size)
                if(t.write(silence,0,silence.size,AudioTrack.WRITE_NON_BLOCKING)!=silence.size)return fail()
                return true // zero-only route probe, NOT playback confirmation
            }
            if(!allowed())return fail()
            confirmed=true;t.setVolume(1f)
            if(t.write(frame.pcm,0,frame.pcm.size,AudioTrack.WRITE_NON_BLOCKING)!=frame.pcm.size)return fail()
            return true
        }catch(_:Exception){return fail()}
    }
    private fun fail():Boolean {if(closed)return false;close();handler.post{lost()};return false}
    @Synchronized override fun close(){
        if(closed)return;closed=true
        val t=track;track=null
        runCatching{t?.setVolume(0f)};runCatching{t?.pause()};runCatching{t?.flush()};runCatching{t?.stop()}
        runCatching{t?.removeOnRoutingChangedListener(routing)};runCatching{t?.release()}
        runCatching{audio.unregisterAudioDeviceCallback(devices)};runCatching{app.unregisterReceiver(noisy)}
        focus?.let{audio.abandonAudioFocusRequest(it)};focus=null;lease?.close();lease=null
    }
}
