package com.rumi.hermesvoice.watch

import android.media.AudioTrack
import android.os.Looper
import com.rumi.hermesvoice.core.live.WatchLiveWire
import io.mockk.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Real default Watch AudioTrack/Channel runtime; platform release is the only injected effect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchRelayPrivateHandoffRoboTest {
    private fun await(done:()->Boolean):Boolean{val end=System.nanoTime()+5_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));if(done())return true;Thread.sleep(10)};return done()}
    private fun run(failRelease:Boolean){
        val w=WatchPhoneChannelDefaultRoboTest();w.setup()
        try{
            w.noWatchInternetDefaultCapturesRealWatchPcmToPhoneAndReturnedFramesReachWatchSpeaker()
            val live=w.javaClass.getDeclaredField("live").apply{isAccessible=true}.get(w) as WatchLiveVoiceRuntime
            val time=live.now();live.now={time} // prevent a missing receipt being confused with the unrelated 5s control lease
            val adapter=live.javaClass.getDeclaredField("media").apply{isAccessible=true}.get(live) as WatchChannelMedia
            val player=adapter.javaClass.getDeclaredField("player").apply{isAccessible=true}.get(adapter) as AudioTrack
            var released=false
            every{player.release()}answers{if(failRelease)throw IllegalStateException("synthetic release refusal");released=true}
            val packet=w.javaClass.getDeclaredMethod("packet",String::class.java,String::class.java).apply{isAccessible=true}
            packet.invoke(w,"private","handoff-output")
            @Suppress("UNCHECKED_CAST")val sent=w.javaClass.getDeclaredField("sent").apply{isAccessible=true}.get(w) as Collection<ByteArray>
            fun receipts()=sent.mapNotNull{WatchLiveWire.parse(it)}.filter{it.op=="private_ready"}
            if(failRelease){
                assertTrue("a failed Watch native output stop must refuse, never acknowledge quiescence",await{!live.state.value.active})
                assertTrue(receipts().isEmpty())
            }else{
                assertTrue("actual Watch output stop must produce the exact token-bound private receipt",await{receipts().any{it.body=="handoff-output"}})
                assertTrue(released);assertTrue(live.state.value.active);assertTrue(live.state.value.inputActive)
            }
        }finally{w.cleanup()}
    }
    @Test fun actualDefaultWatchAcknowledgesOnlyAfterNativeSpeakerRelease(){run(false)}
    @Test fun failedNativeSpeakerReleaseNeverAuthorizesPhonePrivatePlayback(){run(true)}
}
