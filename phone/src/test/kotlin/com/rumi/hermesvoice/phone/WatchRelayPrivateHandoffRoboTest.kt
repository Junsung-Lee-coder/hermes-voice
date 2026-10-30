package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.live.*
import com.rumi.hermesvoice.phone.live.WatchLiveTransport
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An OS private-output lease is not a remote Watch-speaker stop receipt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WatchRelayPrivateHandoffRoboTest {
    @Test fun actualPhoneDecoderDropsAudioUntilExactWatchQuiescenceAndNeverReplaysTheGap(){
        val w=WatchPrivateLivePcmRoboTest();w.setup()
        try{
            val start=w.javaClass.getDeclaredMethod("start").apply{isAccessible=true}
            @Suppress("UNCHECKED_CAST")val pair=start.invoke(w) as Pair<WatchLiveWire.Packet,WatchLiveTransport>
            val (open,t)=pair;t.outputAdmitted(true)
            val f=w.javaClass.getDeclaredField("fixture").apply{isAccessible=true};val fx=f.get(w) as PhoneRelayFixture
            fx.bind();w.app.liveWatch.receive("watch-node",WatchLiveWire.packet(open.id,open.media,open.epoch,1,"offer",WatchRelayAudio.OFFER))
            assertTrue("real relay/provider fixture ready",w.await{fx.output!=null&&w.app.live.state.value.ready})
            val field=w.javaClass.getDeclaredField("writes").apply{isAccessible=true}
            @Suppress("UNCHECKED_CAST")val writes=field.get(w) as java.util.concurrent.ConcurrentLinkedQueue<LivePcmFrame>
            fx.output!!(LivePcmFrame(1,48000,1,byteArrayOf(1,2)));w.settle()
            assertTrue("Phone must not play privately before the actual Watch speaker is confirmed stopped",writes.isEmpty())
            w.app.liveWatch.receive("watch-node",WatchLiveWire.packet(open.id,open.media,open.epoch,2,"private_ready","wrong-token"))
            fx.output!!(LivePcmFrame(2,48000,1,byteArrayOf(3,4)));w.settle();assertTrue(writes.isEmpty())
            w.app.liveWatch.receive("watch-node",WatchLiveWire.packet(open.id,open.media,open.epoch,3,"private_ready",JSONObject(open.body).getString("output")))
            fx.output!!(LivePcmFrame(3,48000,1,byteArrayOf(5,6)));assertTrue(w.await{writes.size==1})
            assertArrayEquals(byteArrayOf(5,6),writes.single().pcm);assertFalse(w.app.phoneMic.occupied)
        }finally{w.cleanup()}
    }
}
