package com.rumi.hermesvoice.phone.live

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import androidx.core.content.ContextCompat
import com.rumi.hermesvoice.core.headset.*
import com.rumi.hermesvoice.core.live.*
import com.rumi.hermesvoice.phone.PhoneApp
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/** App-owned 48 kHz capture feeding the pinned external ADM API. No native ADM Phone-mic fallback.
 * Every byte is checked against AudioRecord's actual SOURCE, including after read.
 * [admitted] is one exact source (a headset or a sole built-in), or every built-in source Android exposes for the original
 * Phone microphone (bottom and back both map to TYPE_BUILTIN_MIC): then the actually routed member is pinned once, before PCM. */
internal class LiveCaptureInput(private val app:PhoneApp,private val admitted:List<AudioEndpoint>,
    private val current:()->Boolean,private val microphone:(Boolean)->Unit,private val lost:()->Unit,private val activity:()->Unit={}):ExternalLiveInput {
    constructor(app:PhoneApp,target:AudioEndpoint,current:()->Boolean,microphone:(Boolean)->Unit,lost:()->Unit,activity:()->Unit={}):this(app,listOf(target),current,microphone,lost,activity)
    init{require(admitted.isNotEmpty()&&(admitted.size==1||admitted.all{it.type==AudioDeviceInfo.TYPE_BUILTIN_MIC}))}
    private val linked=HeadsetClassifier.needsCommunicationLink(admitted.first())
    /** The exact source. Never replaced once set: a later unmute must reach this same attachment. */
    @Volatile private var target:AudioEndpoint?=admitted.singleOrNull()
    private val audio=app.getSystemService(AudioManager::class.java)
    private val gate=Any()
    @Volatile private var closed=false
    @Volatile private var muted=false
    @Volatile private var record:AudioRecord?=null
    @Volatile private var verified=false

    @Volatile private var version=0L
    private var lastActivity=0L
    private var nextFrameNs=0L
    private var preparing:Job?=null
    @Volatile private var preparingToken=0L
    private var oldMode:Int?=null;private var oldDevice:AudioDeviceInfo?=null;private var chosen:Int?=null
    @Volatile private var clean=true
    private var unreleased:AudioRecord?=null
    internal fun settled()=clean&&unreleased==null&&record==null
    internal fun playbackDeviceId():Int?=chosen
    internal fun active()=runCatching{verified&&!muted&&allowed()&&record?.let{same(it.routedDevice)}==true}.getOrDefault(false)
    private fun allowed()=!closed&&current()&&ContextCompat.checkSelfPermission(app,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED&&
        (!linked||Build.VERSION.SDK_INT<31||ContextCompat.checkSelfPermission(app,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)
    private fun matches(d:AudioDeviceInfo?,e:AudioEndpoint)=d!=null&&d.isSource&&d.id==e.id&&d.type==e.type&&d.address.orEmpty()==e.address
    private fun same(d:AudioDeviceInfo?)=target?.let{matches(d,it)}==true
    private fun connected()=audio.getDevices(AudioManager.GET_DEVICES_INPUTS).any{d->target?.let{matches(d,it)}?:admitted.any{matches(d,it)}}
    /** The admitted built-in the recorder was actually routed to, if exactly one admitted member is it and it is attached exactly once now. */
    private fun routedBuiltIn(routed:AudioDeviceInfo?):AudioEndpoint?{
        if(routed==null||routed.type!=AudioDeviceInfo.TYPE_BUILTIN_MIC)return null
        val member=admitted.filter{matches(routed,it)}.singleOrNull()?:return null
        return member.takeIf{audio.getDevices(AudioManager.GET_DEVICES_INPUTS).count{d->matches(d,member)}==1}
    }
    private suspend fun communication(){
        if(!linked)return
        if(Build.VERSION.SDK_INT<31)throw IllegalStateException("Bluetooth Live input unavailable on this Android version")
        if(chosen!=null){check(audio.communicationDevice?.id==chosen);return}
        check(ContextCompat.checkSelfPermission(app,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED){"Bluetooth input permission unavailable"}
        val link=admitted.single()
        val candidates=audio.availableCommunicationDevices.filter{it.isSink&&it.type==link.type}
        val sink=if(link.address.isNotBlank())candidates.singleOrNull{it.address==link.address}
            else candidates.singleOrNull()
        check(sink!=null){"Headset communication input unavailable"}
        synchronized(gate){
            check(allowed())
            val existing=audio.communicationDevice
            check(audio.mode==AudioManager.MODE_NORMAL){"Another communication route is active"}
            if(audio.mode==AudioManager.MODE_NORMAL){oldMode=audio.mode;audio.mode=AudioManager.MODE_IN_COMMUNICATION}
            oldDevice=existing
            check(audio.setCommunicationDevice(sink)){"Communication route request refused"};chosen=sink.id
        }
        withTimeout(1000){while(allowed()&&audio.communicationDevice?.id!=sink.id)delay(20)}
        check(allowed()&&audio.communicationDevice?.id==sink.id)
    }
    suspend fun prepare() {
        if(muted)return
        val generation=version
        try {
            communication()
            withContext(Dispatchers.IO){
                val v=generation
                synchronized(gate){check(allowed()&&connected()&&!muted&&version==v);if(record!=null)return@withContext}
                val min=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);check(min>0)
                // A possibly slow factory never holds the Stop gate. The IO continuation owns an
                // exact late handle until it is installed; cancellation never returns that handle.
                if(ContextCompat.checkSelfPermission(app,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw SecurityException("Live input permission revoked")
                val r=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(maxOf(min*2,3840)).build()
                var owned=false
                try{
                    synchronized(gate){check(allowed()&&!muted&&version==v&&record==null);record=r;owned=true}
                    check(r.state==AudioRecord.STATE_INITIALIZED)
                    // A known exact source is requested; several unpinned built-ins get no arbitrary preference: the OS route decides.
                    if(target!=null){
                        val source=audio.getDevices(AudioManager.GET_DEVICES_INPUTS).singleOrNull(::same)
                        check(source!=null&&r.setPreferredDevice(source))
                    }
                    synchronized(gate){check(allowed()&&!muted&&version==v&&record===r);r.startRecording();check(r.recordingState==AudioRecord.RECORDSTATE_RECORDING)}
                    withTimeout(600){while(allowed()&&!muted&&version==v&&r.routedDevice==null)delay(20)}
                    synchronized(gate){
                        check(allowed()&&!muted&&version==v&&record===r)
                        if(target==null)target=routedBuiltIn(r.routedDevice) // pinned once, before any PCM; never re-pinned
                        check(same(r.routedDevice)){"Headset input route not confirmed"};verified=true
                    }
                    microphone(true)
                }finally{if(!owned)releaseNative(r)}
            }
        }catch(error:Throwable){stop(generation);throw error}
    }
    override fun fill(buffer:ByteBuffer,format:Int,channels:Int,rate:Int):Long {
        // External ADM input has no platform read to pace mute/preparation silence.
        val wait=nextFrameNs-System.nanoTime()
        if(wait>0)java.util.concurrent.locks.LockSupport.parkNanos(minOf(wait,10_000_000L))
        nextFrameNs=maxOf(nextFrameNs+10_000_000L,System.nanoTime())
        val zeros=ByteArray(buffer.capacity());var bytes=zeros
        if(format!=AudioFormat.ENCODING_PCM_16BIT||channels!=1||rate!=48000||buffer.capacity()!=960){lost();buffer.clear();buffer.put(zeros);buffer.rewind();return 0}
        val r=record;val v=version
        try {
            if(allowed()&&!muted&&verified&&r!=null){
                check(connected()&&same(r.routedDevice))
                val captured=ByteArray(960);var n=0
                while(n<captured.size&&allowed()&&!muted&&version==v){val count=r.read(captured,n,captured.size-n,AudioRecord.READ_BLOCKING);check(count>0&&count<=captured.size-n);n+=count}
                if(allowed()&&!muted&&version==v){check(n==960&&same(r.routedDevice)&&connected());bytes=captured}
            }else if(!muted&&!closed&&!allowed()){stop();lost()}
        }catch(_:Exception){if(!closed&&!muted&&version==v){stop();lost()}}
        synchronized(gate){
            val value=if(allowed()&&!muted&&version==v)bytes else zeros
            buffer.clear();buffer.put(value);buffer.rewind()
            if(value!==zeros){
                var energy=0L;for(i in 0 until value.size-1 step 2)energy+=kotlin.math.abs(((value[i].toInt()and 255)or(value[i+1].toInt()shl 8)).toShort().toInt())
                val now=android.os.SystemClock.elapsedRealtime()
                if(energy/(value.size/2)>=120&&now-lastActivity>=250){lastActivity=now;activity()}
            }
        }
        return System.nanoTime()
    }
    override fun mute(muted:Boolean){
        val request=synchronized(gate){if(this.muted==muted)return;this.muted=muted;version++;++preparingToken}
        if(muted)stop()else if(!closed)preparing=app.mainScope.launch{try{prepare()}catch(c:CancellationException){throw c}catch(_:Exception){if(request==preparingToken&&!closed&&!this@LiveCaptureInput.muted)lost()}}
    }
    private fun releaseNative(r:AudioRecord){
        runCatching{if(r.recordingState==AudioRecord.RECORDSTATE_RECORDING)r.stop()}
        if(runCatching{r.release()}.isFailure)synchronized(gate){clean=false;unreleased=r}
    }
    private fun stop(expected:Long?=null){
        val r=synchronized(gate){if(expected!=null&&version!=expected)return;version++;verified=false;record.also{record=null}}
        if(r!=null)releaseNative(r)
        microphone(false)
    }
    override fun close(){
        if(closed)return;closed=true;preparingToken++;preparing?.cancel();stop()
        if(Build.VERSION.SDK_INT>=31&&chosen!=null)runCatching{audio.clearCommunicationDevice()} // clear OUR accepted request even if it never became active
        chosen=null;oldMode?.let{runCatching{if(clean&&audio.mode==AudioManager.MODE_IN_COMMUNICATION)audio.mode=it}};oldMode=null
    }
    companion object {
        fun headset(app:PhoneApp):AudioEndpoint? {
            val output=app.headset.output()?:return null
            val audio=app.getSystemService(AudioManager::class.java)
            val inputs=audio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter{it.isSource}.map{AudioEndpoint(it.id,it.type,it.address.orEmpty())}
            val outputs=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter{it.isSink}.map{AudioEndpoint(it.id,it.type,it.address.orEmpty())}
            return LiveInputPolicy.headset(output,inputs,outputs)
        }
        /** Every built-in source Android currently exposes, none chosen here; null when there is none. */
        fun original(app:PhoneApp):List<AudioEndpoint>?=app.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter{it.isSource&&it.type==AudioDeviceInfo.TYPE_BUILTIN_MIC}.map{AudioEndpoint(it.id,it.type,it.address.orEmpty())}.takeIf{it.isNotEmpty()}
    }
}
