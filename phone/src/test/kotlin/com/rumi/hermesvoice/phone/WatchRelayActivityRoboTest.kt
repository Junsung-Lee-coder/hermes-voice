package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.live.WatchLiveWire
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Phone transport/provider-activity seam, not a keepalive or backend progress event. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WatchRelayActivityRoboTest {
    @Test fun actualPhoneProviderOutputActivityReachesWatchButMicActivityIsNotEchoed(){
        val witness=WatchLiveRelayRoboTest();witness.setup()
        try{
            witness.watchOfferUsesSupportedAuthenticatedSessionCreationOnceAndNeverPhoneAudio()
            val f=WatchLiveRelayRoboTest::class.java.getDeclaredField("mediaFixtures").apply{isAccessible=true}
            @Suppress("UNCHECKED_CAST")val fixtures=f.get(witness) as List<PhoneRelayFixture>
            val output=fixtures.single().activity;assertNotNull("actual production provider activity observer installed",output)
            val pulse=output!!;pulse(false);pulse(false)
            val p=WatchLiveRelayRoboTest::class.java.getDeclaredField("packets").apply{isAccessible=true}
            @Suppress("UNCHECKED_CAST")val packets=p.get(witness) as java.util.concurrent.ConcurrentLinkedQueue<WatchLiveWire.Packet>
            assertTrue("Watch must learn genuine provider output activity even when Phone private output owns the sound",witness.await{packets.count{it.op=="dialogue"&&it.body=="output"}==2})
            pulse(true);witness.settle();assertEquals("Watch already owns microphone activity; it is not echoed as provider output",2,packets.count{it.op=="dialogue"})
        }finally{witness.cleanup()}
    }
}
