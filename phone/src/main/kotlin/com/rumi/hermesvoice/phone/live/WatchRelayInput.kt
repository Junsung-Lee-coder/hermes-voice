package com.rumi.hermesvoice.phone.live

import android.media.AudioFormat
import com.rumi.hermesvoice.core.live.LivePcmFrame
import com.rumi.hermesvoice.core.live.WatchRelayAudio
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** The supported pinned ADM buffer callback fills its native-cached input buffer. Never creates an AudioRecord. */
interface ExternalLiveInput:AutoCloseable {
    /** R40: the pinned ADM input rate this source fills (setInputSampleRate): native 10 ms buffers of rate/100 mono PCM16 frames. */
    val inputRate:Int get()=48000
    fun fill(buffer:ByteBuffer,format:Int,channels:Int,rate:Int):Long
    fun mute(muted:Boolean)
}

internal class WatchRelayInput(private val current:()->Boolean,private val failed:()->Unit):ExternalLiveInput {
    /** R40: the Watch's 16 kHz uplink at the ADM's own native input rate, never resampled; each 20 ms packet fills two 10 ms buffers. */
    override val inputRate=WatchRelayAudio.UPLINK_RATE
    private val bufferBytes=inputRate/100*2
    private val frames=ArrayBlockingQueue<LivePcmFrame>(16) // 16 packets of 20 ms: the same 320 ms bound as 32 frames of 10 ms before
    private val gate=Any()
    private var closed=false;private var muted=false;private var captureEpoch=0L;private var received=0L;private var armed=false
    /** The packet whose second 10 ms half the next callback hands: only ever of the current capture epoch, dropped by mute and close. */
    private var half:LivePcmFrame?=null
    private var nextFrameNs=0L;private var lastFrameNs=System.nanoTime()
    private val silence=ByteArray(bufferBytes)
    init{check(WatchRelayAudio.FRAME_BYTES==2*bufferBytes)}
    fun offer(frame:LivePcmFrame):Boolean=synchronized(gate){
        if(closed||!current())return@synchronized false
        if(!WatchRelayAudio.valid(frame,WatchRelayAudio.UPLINK))return@synchronized false
        val epoch=WatchRelayAudio.captureEpoch(frame.sequence)
        if(epoch<captureEpoch)return@synchronized true // an old mute epoch can only be discarded
        if(epoch!=captureEpoch)return@synchronized false
        val counter=WatchRelayAudio.counter(frame.sequence)
        if(counter!=received+1)return@synchronized false
        received=counter
        if(muted)return@synchronized true
        frames.offer(frame)
    }
    override fun mute(muted:Boolean)=synchronized(gate){
        if(this.muted!=muted){this.muted=muted;captureEpoch++;received=0;frames.clear();half=null;lastFrameNs=System.nanoTime()}
    }
    fun arm(active:Boolean)=synchronized(gate){armed=active;lastFrameNs=System.nanoTime()}
    override fun fill(buffer:ByteBuffer,format:Int,channels:Int,rate:Int):Long {
        if(format!=AudioFormat.ENCODING_PCM_16BIT||channels!=1||rate!=inputRate||buffer.capacity()!=bufferBytes){failed();return 0}
        // The vendor's external-input thread has no AudioRecord.read to pace it. Bound it to real 10 ms media time.
        val now=System.nanoTime();val wait=nextFrameNs-now
        if(wait>0)LockSupport.parkNanos(minOf(wait,10_000_000L))
        nextFrameNs=maxOf(nextFrameNs+10_000_000L,System.nanoTime())
        val held=synchronized(gate){half.also{half=null}}
        val admitted=synchronized(gate){!closed&&!muted&&current()}
        val frame=held?:if(admitted)frames.poll(100,TimeUnit.MILLISECONDS) else null
        var starved=false
        synchronized(gate){
            val audio=!closed&&!muted&&current()&&frame!=null&&WatchRelayAudio.captureEpoch(frame.sequence)==captureEpoch
            buffer.clear()
            if(audio){buffer.put(frame!!.pcm,if(held!=null)bufferBytes else 0,bufferBytes);if(held==null)half=frame;lastFrameNs=System.nanoTime()}
            else buffer.put(silence)
            buffer.rewind()
            // Silence is only mute/jitter padding, never substituted capture or evidence of Watch audio.
            starved=armed&&!closed&&!muted&&current()&&System.nanoTime()-lastFrameNs>1_000_000_000L
        }
        if(starved)failed()
        return System.nanoTime() // Phone arrival/consumption time, not a claimed remote hardware capture timestamp
    }
    override fun close()=synchronized(gate){closed=true;frames.clear();half=null}
}
