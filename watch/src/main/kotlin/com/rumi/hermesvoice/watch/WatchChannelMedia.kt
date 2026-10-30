package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.live.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Default Watch peripheral: real local capture/playback and Wear bytes only. No provider peer, IP check, credentials or Phone mic. */
internal class WatchChannelMedia(private val app:WatchApp,private val node:String,private val address:WatchRelayAudio.Address,
    private val current:()->Boolean,private val outputCurrent:()->Boolean,private val failed:()->Unit):WatchLiveMedia {
    private val closed=AtomicBoolean()
    private val resourceGate=Any()
    private val jobs=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val queue=Channel<LivePcmFrame>(16) // R40: 16 packets of 20 ms, the same 320 ms bound as 32 frames of 10 ms before
    @Volatile private var channel:ChannelClient.Channel?=null
    @Volatile private var input:DataInputStream?=null
    @Volatile private var output:DataOutputStream?=null
    @Volatile private var connected=false
    @Volatile private var silenced=false
    @Volatile private var muted=false
    @Volatile private var inputHeld=false
    private var inputRoute:((String)->Unit)?=null
    @Volatile private var outputEnabled=false
    @Volatile private var privateOwned=false
    @Volatile private var recorder:AudioRecord?=null
    @Volatile private var captureVersion=0L
    private var captureEpoch=0L
    private var capture:Job?=null
    private var player:AudioTrack?=null
    private var microphone:((Boolean)->Unit)?=null
    private var activity:((Boolean)->Unit)?=null
    private var lastInputActivity=0L;private var lastOutputActivity=0L
    private val captureGate=Any();private val outputGate=Any()
    private fun allowed()=!closed.get()&&!silenced&&current()
    @Volatile private var connectStep:WatchLiveSetupStep?=null
    override val setupStep:WatchLiveSetupStep? get()=connectStep
    /** R32: the step and error of the first loss this media reported through [failed] (the setup observation only). */
    @Volatile var lossStep:WatchLiveSetupStep?=null;private set
    @Volatile var lossError:Throwable?=null;private set
    /** R41: this media's relay counts (see [WatchRelayCounters]), updated per packet in constant time. */
    private val captured=java.util.concurrent.atomic.AtomicLong();private val written=java.util.concurrent.atomic.AtomicLong()
    private val capturedAudio=LivePcmMeter();@Volatile private var readWaitMaxNs=0L;@Volatile private var writeMaxNs=0L
    override val relay:WatchRelayCounters get()=WatchRelayCounters(captured.get(),readWaitMaxNs/1_000_000,capturedAudio.chunks,capturedAudio.speechMs,written.get(),writeMaxNs/1_000_000)
    private fun lose(step:WatchLiveSetupStep,error:Throwable?=null){if(allowed()){lossStep=step;lossError=error;silence();app.scope.launch{if(current()&&!closed.get())failed()}}}
    override suspend fun connect(signal:suspend(String)->String,event:(String)->Unit)=withTimeout(60_000){
        try {
        connectStep=WatchLiveSetupStep.CHANNEL_OPEN
        check(allowed())
        val client=Wearable.getChannelClient(app)
        withTimeout(5000){own(client.openChannel(node,address.path()),{channel=it},{client.close(it)})}
        connectStep=WatchLiveSetupStep.CHANNEL_IDENTITY
        val c=synchronized(resourceGate){check(allowed());checkNotNull(channel)}
        check(c.nodeId==node&&c.path==address.path())
        connectStep=WatchLiveSetupStep.CHANNEL_STREAMS
        withTimeout(5000){own(synchronized(resourceGate){check(allowed());client.getInputStream(c)},{input=DataInputStream(it)},{it.close()})}
        withTimeout(5000){own(synchronized(resourceGate){check(allowed());client.getOutputStream(c)},{output=DataOutputStream(it)},{it.close()})}
        val (incoming,outgoing)=synchronized(resourceGate){check(allowed());checkNotNull(input) to checkNotNull(output)}
        jobs.launch{
            var step=WatchLiveSetupStep.RELAY_DOWNLINK // R34: the Watch speaker is its own boundary, apart from the downlink read
            try{var previous=0L;while(allowed()){
                val frame=WatchRelayAudio.read(incoming,WatchRelayAudio.DOWNLINK)
                check(frame.sequence==previous+1);previous=frame.sequence
                if(allowed()&&outputEnabled&&!privateOwned){step=WatchLiveSetupStep.OUTPUT_PLAYBACK;play(frame);step=WatchLiveSetupStep.RELAY_DOWNLINK}
            }}catch(c:CancellationException){throw c}catch(error:Exception){lose(step,error)}
        }
        jobs.launch{
            // R39: the codec writes a frame field by field; encode it into one reused buffer first so each frame reaches the Wear
            // stream as ONE write of the same bytes (fragmented writes cut the stream's frame rate by the per-write cost).
            val encoded=ByteArrayOutputStream(WatchRelayAudio.FRAME_BYTES+64);val encoder=DataOutputStream(encoded)
            try{for(frame in queue){if(!allowed())break
                val valid=synchronized(captureGate){!muted&&WatchRelayAudio.captureEpoch(frame.sequence)==captureEpoch}
                if(valid&&allowed()){
                    encoded.reset();WatchRelayAudio.write(encoder,WatchRelayAudio.UPLINK,frame)
                    val began=System.nanoTime();encoded.writeTo(outgoing);outgoing.flush();val took=System.nanoTime()-began
                    written.incrementAndGet();if(took>writeMaxNs)writeMaxNs=took
                }
            }}catch(c:CancellationException){throw c}catch(error:Exception){lose(WatchLiveSetupStep.RELAY_UPLINK,error)}
        }
        check(signal(WatchRelayAudio.OFFER)=="phone-relay-ready")
        check(allowed());connected=true
        if(!muted)startCapture()
        } catch(error:Throwable){close();throw error}
    }
    /** Install ownership BEFORE resuming Unit, never return an unowned resource across prompt
     * cancellation. The Task listener survives cancellation and disposes an exact late result.
     * The same gate arbitrates installation vs close; no callback can clear a successor owner. */
    private suspend fun <T:Any> own(task:Task<T>,install:(T)->Unit,discard:(T)->Unit):Unit = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { close() }
        task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { completed ->
            if(completed.isCanceled)continuation.cancel()
            else {
                val error=completed.exception
                if(error!=null)continuation.resumeWithException(error)
                else {
                    val value=completed.result
                    val admitted=synchronized(resourceGate){
                        if(continuation.isActive && allowed()){install(value);true}else false
                    }
                    if(admitted)continuation.resume(Unit)
                    else {runCatching{discard(value)};continuation.cancel()}
                }
            }
        }
    }
    private fun dialogue(bytes:ByteArray,input:Boolean){
        var energy=0L;for(i in 0 until bytes.size-1 step 2)energy+=kotlin.math.abs(((bytes[i].toInt()and 255)or(bytes[i+1].toInt()shl 8)).toShort().toInt())
        if(bytes.size<2||energy/(bytes.size/2)<120)return
        val now=android.os.SystemClock.elapsedRealtime();val last=if(input)lastInputActivity else lastOutputActivity
        if(now-last>=250){if(input)lastInputActivity=now else lastOutputActivity=now;activity?.invoke(input)}
    }
    private fun startCapture()=synchronized(captureGate){
        if(!connected||muted||inputHeld||!allowed()||capture?.isActive==true)return@synchronized
        val version=++captureVersion;val epoch=captureEpoch
        capture=jobs.launch{
            var local:AudioRecord?=null;var echo:AcousticEchoCanceler?=null;var noise:NoiseSuppressor?=null
            var step=WatchLiveSetupStep.CAPTURE_RECORDER
            try{
                check(allowed()&&!muted&&captureVersion==version)
                // R40: the speech-grade uplink is recorded at 16 kHz itself (no resampling); an unsupported rate stays a fail-closed loss.
                val format=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(WatchRelayAudio.UPLINK_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
                val minimum=AudioRecord.getMinBufferSize(WatchRelayAudio.UPLINK_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);check(minimum>0)
                if(ContextCompat.checkSelfPermission(app,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
                    lose(WatchLiveSetupStep.CAPTURE_PERMISSION);return@launch
                }
                val record=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION).setAudioFormat(format).setBufferSizeInBytes(maxOf(minimum*2,WatchRelayAudio.FRAME_BYTES*2)).build();local=record // 40 ms, as before
                if(!allowed()||muted||captureVersion!=version)return@launch
                check(record.state==AudioRecord.STATE_INITIALIZED)
                val audio=app.getSystemService(android.media.AudioManager::class.java)
                fun endpoint(d:android.media.AudioDeviceInfo)=com.rumi.hermesvoice.core.headset.AudioEndpoint(d.id,d.type,d.address.orEmpty())
                val outputs=audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).filter{it.isSink}.map(::endpoint)
                val personal=outputs.firstOrNull{com.rumi.hermesvoice.core.headset.HeadsetClassifier.isPersonalOutput(it)}
                val inputs=audio.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).filter{it.isSource}
                val preferred=if(privateOwned) null else personal?.let{LiveInputPolicy.headset(it,inputs.map(::endpoint),outputs)}
                var target=if(personal!=null||privateOwned)inputs.singleOrNull{if(preferred!=null)it.id==preferred.id else it.type==android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC}else null
                var headsetActive=preferred!=null
                if(personal!=null||privateOwned){
                    if(target?.let{record.setPreferredDevice(it)}!=true){
                        check(headsetActive);headsetActive=false
                        target=inputs.singleOrNull{it.type==android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC}
                        check(target?.let{record.setPreferredDevice(it)}==true){"Original Watch input unavailable"}
                    }
                }
                // Supported Watch-local session-bound effects. Availability is not a physical AEC/latency guarantee.
                if(AcousticEchoCanceler.isAvailable())echo=AcousticEchoCanceler.create(record.audioSessionId)?.also{it.enabled=true}
                if(NoiseSuppressor.isAvailable())noise=NoiseSuppressor.create(record.audioSessionId)?.also{it.enabled=true}
                synchronized(captureGate){if(!allowed()||muted||captureVersion!=version)return@launch;recorder=record;record.startRecording();check(record.recordingState==AudioRecord.RECORDSTATE_RECORDING)}
                step=WatchLiveSetupStep.CAPTURE_ROUTE
                fun routed():Boolean {
                    val expected=target?:return true
                    return record.routedDevice?.let{it.isSource&&it.id==expected.id&&it.type==expected.type&&it.address==expected.address}==true &&
                        audio.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).any{it.id==expected.id&&it.isSource}
                }
                suspend fun confirmed()=withTimeoutOrNull(600){while(allowed()&&captureVersion==version&&record.routedDevice==null)delay(20);routed()}==true
                if(target!=null&&!confirmed()){
                    // Initial-only fallback, before any read/PCM forwarding. Never splice an active utterance.
                    check(headsetActive&&allowed()&&!muted&&captureVersion==version)
                    record.stop();target=inputs.singleOrNull{it.type==android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC}
                    check(target?.let{record.setPreferredDevice(it)}==true)
                    synchronized(captureGate){
                        if(!allowed()||muted||captureVersion!=version||recorder!==record)return@launch
                        record.startRecording();check(record.recordingState==AudioRecord.RECORDSTATE_RECORDING)
                    }
                    check(confirmed())
                    headsetActive=false
                }
                if(!allowed()||captureVersion!=version)return@launch
                step=WatchLiveSetupStep.CAPTURE_READ
                inputRoute?.invoke(if(headsetActive)"Watch headset microphone · OS capture route verified" else if(personal!=null)"Headset input unavailable or not confirmed; original Watch microphone" else "Watch microphone")
                microphone?.invoke(true)
                var sequence=0L
                while(allowed()&&!muted&&captureVersion==version){
                    val bytes=ByteArray(WatchRelayAudio.FRAME_BYTES);var filled=0;val began=System.nanoTime()
                    while(filled<bytes.size&&allowed()&&!muted&&captureVersion==version){val n=record.read(bytes,filled,bytes.size-filled,AudioRecord.READ_BLOCKING);check(n>0&&n<=bytes.size-filled);filled+=n}
                    if(filled!=bytes.size||!allowed()||muted||captureVersion!=version)break
                    val waited=System.nanoTime()-began;if(waited>readWaitMaxNs)readWaitMaxNs=waited
                    step=WatchLiveSetupStep.CAPTURE_ROUTE;check(routed()) {"Watch capture route changed; no microphone splice"};step=WatchLiveSetupStep.CAPTURE_READ
                    dialogue(bytes,true)
                    val frame=LivePcmFrame(WatchRelayAudio.sequence(epoch,++sequence),WatchRelayAudio.UPLINK_RATE,1,bytes)
                    if(!queue.trySend(frame).isSuccess){lose(WatchLiveSetupStep.CAPTURE_OVERFLOW);break}
                    captured.incrementAndGet();capturedAudio.count(bytes,1,WatchRelayAudio.UPLINK_RATE)
                }
            }catch(c:CancellationException){throw c}
            catch(error:SecurityException){if(captureVersion==version)lose(step,error)}
            catch(error:Exception){if(captureVersion==version)lose(step,error)}
            finally{
                synchronized(captureGate){if(recorder===local)recorder=null}
                runCatching{local?.stop()};runCatching{echo?.release()};runCatching{noise?.release()};runCatching{local?.release()}
                synchronized(captureGate){if(captureVersion==version)microphone?.invoke(false)}
            }
        }
    }
    private fun stopCapture()=synchronized(captureGate){
        ++captureVersion;val old=recorder;recorder=null
        runCatching{old?.stop()};capture?.cancel();capture=null
        while(queue.tryReceive().isSuccess){}
        microphone?.invoke(false)
    }
    private fun play(frame:LivePcmFrame)=synchronized(outputGate){
        if(!allowed()||privateOwned||!outputEnabled||!outputCurrent())return@synchronized
        var track=player
        if(track==null){
            val minimum=AudioTrack.getMinBufferSize(WatchRelayAudio.DOWNLINK_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);check(minimum>0)
            val prepared=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(WatchRelayAudio.DOWNLINK_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum*2,WatchRelayAudio.DOWNLINK_FRAME_BYTES*2)).build() // a 40 ms floor at 16 kHz, as before
            if(!allowed()||privateOwned||!outputEnabled||!outputCurrent()){prepared.release();return@synchronized}
            player=prepared;check(prepared.state==AudioTrack.STATE_INITIALIZED);prepared.play();track=prepared
        }
        if(!allowed()||privateOwned||!outputEnabled||!outputCurrent())return@synchronized
        val admitted=track?:return@synchronized
        check(admitted.write(frame.pcm,0,frame.pcm.size,AudioTrack.WRITE_NON_BLOCKING)==frame.pcm.size)
        dialogue(frame.pcm,false)
    }
    private fun stopOutput():Boolean=synchronized(outputGate){
        val old=player;player=null
        runCatching{old?.pause()};runCatching{old?.flush()}
        val stopped=runCatching{old?.stop()}.isSuccess
        val released=runCatching{old?.release()}.isSuccess
        stopped&&released
    }
    override fun send(json:String)=false // provider commands are Phone-local, not a Watch DataChannel or a fallback peer
    override fun observeActivity(listener:(Boolean)->Unit){activity=listener}
    override fun observeMicrophone(listener:(Boolean)->Unit){microphone=listener}
    override fun observeInputRoute(listener:(String)->Unit){inputRoute=listener}
    override fun holdInput(held:Boolean):Boolean=synchronized(captureGate){
        if(held&&(connected||capture!=null||recorder!=null))return@synchronized false
        inputHeld=held
        if(!held)startCapture()
        true
    }
    override fun mute(muted:Boolean){
        synchronized(captureGate){if(this.muted==muted)return;this.muted=muted;captureEpoch++}
        if(muted)stopCapture() else startCapture()
    }
    override fun phoneOutput(){privateOwned=true;check(stopOutput()){ "Watch native speaker stop/release unconfirmed" }}
    override fun privateOutput(listener:((LivePcmFrame)->Unit)?){if(listener!=null)phoneOutput()}
    override fun output(enabled:Boolean){outputEnabled=enabled;if(!enabled)stopOutput()}
    override fun silence(){silenced=true;outputEnabled=false;stopCapture();stopOutput()}
    override fun close(){
        val owned=synchronized(resourceGate){
            if(!closed.compareAndSet(false,true))return
            Triple(input,output,channel).also{input=null;output=null;channel=null}
        }
        silence();queue.close();jobs.cancel()
        runCatching{owned.first?.close()};runCatching{owned.second?.close()}
        owned.third?.let{runCatching{Wearable.getChannelClient(app).close(it)}}
        activity=null;microphone=null
    }
}
