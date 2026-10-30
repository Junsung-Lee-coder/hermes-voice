package com.rumi.hermesvoice.watch

import android.media.*
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual default Channel adapter; latch is in the synchronous fallback preference platform boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WatchFallbackStopLifetimeRoboTest {
    private lateinit var w:WatchPhoneChannelDefaultRoboTest
    private lateinit var live:WatchLiveVoiceRuntime
    private lateinit var record:AudioRecord
    private lateinit var headset:AudioDeviceInfo
    private lateinit var original:AudioDeviceInfo
    private val fallback=CountDownLatch(1);private val releasePreference=CountDownLatch(1)
    private val released=CountDownLatch(1);private val readRelease=CountDownLatch(1)
    private val starts=AtomicInteger();private val reads=AtomicInteger();private val releases=AtomicInteger()
    private var hold=true
    private class Setup(message:String):RuntimeException(message)
    private fun setupCheck(ok:Boolean,message:String){if(!ok)throw Setup(message)}
    private fun field(n:String)=w.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(w)
    private fun endpoint(id:Int,type:Int,input:Boolean,address:String):AudioDeviceInfo {
        val d=mockk<AudioDeviceInfo>(relaxed=true);every{d.id}returns id;every{d.type}returns type
        every{d.isSource}returns input;every{d.isSink}returns !input;every{d.address}returns address;return d
    }
    @Before fun setup(){
        w=WatchPhoneChannelDefaultRoboTest();w.setup();live=field("live") as WatchLiveVoiceRuntime
        val app=field("app") as WatchApp;val audio=app.getSystemService(AudioManager::class.java)
        val output=endpoint(81,AudioDeviceInfo.TYPE_WIRED_HEADSET,false,"watch-headset")
        headset=endpoint(82,AudioDeviceInfo.TYPE_WIRED_HEADSET,true,"watch-headset")
        original=endpoint(83,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"")
        every{audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)}returns arrayOf(output)
        every{audio.getDevices(AudioManager.GET_DEVICES_INPUTS)}returns arrayOf(headset,original)
        record=AudioRecord.Builder().build()
        every{record.startRecording()}answers{starts.incrementAndGet();Unit}
        // Initial request succeeds but OS reports the wrong input. This must reach the real initial-only fallback.
        every{record.routedDevice}answers{original}
        every{record.setPreferredDevice(any())}answers{
            if(firstArg<AudioDeviceInfo?>()?.id==original.id){
                fallback.countDown()
                if(hold&&!releasePreference.await(8,TimeUnit.SECONDS))throw Setup("held fallback boundary timed out")
            }
            true
        }
        every{record.read(any<ByteArray>(),any(),any(),any())}answers{
            reads.incrementAndGet();readRelease.await(8,TimeUnit.SECONDS);-3
        }
        every{record.stop()}just Runs
        every{record.release()}answers{releases.incrementAndGet();released.countDown();Unit}
    }
    @After fun cleanup(){releasePreference.countDown();readRelease.countDown();w.cleanup()}
    private fun startHeld(){
        w.javaClass.getDeclaredMethod("start").apply{isAccessible=true}.invoke(w)
        setupCheck(fallback.await(5,TimeUnit.SECONDS),"must reach fallback preferred-device, not a wrong-route refusal")
        setupCheck(starts.get()==1,"exactly the first native start must precede held fallback")
        setupCheck(reads.get()==0,"fallback must precede all reads")
    }
    private fun mute(value:Boolean){w.javaClass.getDeclaredMethod("packet",String::class.java,String::class.java).apply{isAccessible=true}.invoke(w,"mute",value.toString())}
    private fun idle()=org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun finishOld(){releasePreference.countDown();setupCheck(released.await(5,TimeUnit.SECONDS),"exact old recorder cleanup must complete");idle()}
    @Test fun muteReturningBeforeFallbackPreferenceCannotBeFollowedByNativeRestart(){
        startHeld();mute(true);setupCheck(live.state.value.muted,"actual runtime Mute must return before preference resumes")
        finishOld()
        assertEquals("Mute must fence second native fallback start",1,starts.get())
        assertEquals(0,reads.get());assertEquals(1,releases.get());assertFalse(live.state.value.inputActive)
    }
    @Test fun endReturningBeforeFallbackPreferenceCannotBeFollowedByNativeRestart(){
        startHeld();live.end();setupCheck(!live.state.value.active,"real End must return before preference resumes")
        finishOld()
        assertEquals("End must fence second native fallback start",1,starts.get())
        assertEquals(0,reads.get());assertEquals(1,releases.get());assertFalse(live.state.value.inputActive)
    }
    @Test fun cancelledFallbackCannotRestartOrClearUnmutedSuccessorRecorder(){
        startHeld();mute(true)
        val successor=mockk<AudioRecord>(relaxed=true);val newStarts=CountDownLatch(1);val newRead=CountDownLatch(1)
        val successorStops=AtomicInteger();val successorRelease=CountDownLatch(1)
        every{successor.state}returns AudioRecord.STATE_INITIALIZED
        every{successor.recordingState}returns AudioRecord.RECORDSTATE_RECORDING
        every{successor.routedDevice}returns headset;every{successor.setPreferredDevice(any())}returns true
        every{successor.startRecording()}answers{newStarts.countDown();Unit}
        every{successor.read(any<ByteArray>(),any(),any(),any())}answers{newRead.countDown();successorRelease.await(8,TimeUnit.SECONDS);-3}
        every{successor.stop()}answers{successorStops.incrementAndGet();successorRelease.countDown();Unit}
        every{anyConstructed<AudioRecord.Builder>().build()}returns successor
        try{
            mute(false);setupCheck(newStarts.await(5,TimeUnit.SECONDS)&&newRead.await(5,TimeUnit.SECONDS),"unmute must admit and read its own successor recorder")
            finishOld()
            assertEquals("old cancelled fallback cannot restart beside successor",1,starts.get())
            assertEquals(0,reads.get());assertEquals(0,successorStops.get())
            assertTrue("old cleanup cannot clear successor active callback",live.state.value.inputActive)
        }finally{successorRelease.countDown()}
    }
    @Test fun eligibleInitialFallbackReallyRestartsAndReadsOriginalWatchInput(){
        hold=false
        w.javaClass.getDeclaredMethod("start").apply{isAccessible=true}.invoke(w)
        setupCheck(fallback.await(5,TimeUnit.SECONDS),"positive must reach actual fallback")
        val end=System.nanoTime()+5_000_000_000
        while(reads.get()==0&&System.nanoTime()<end)Thread.sleep(10)
        idle() // native read is IO; drain the real runtime's queued Main microphone projection before observing it
        assertEquals(2,starts.get());assertEquals(1,reads.get())
        assertTrue(live.state.value.inputActive)
        verify{record.setPreferredDevice(headset)};verify{record.setPreferredDevice(original)}
    }
}
