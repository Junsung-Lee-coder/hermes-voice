package com.rumi.hermesvoice.phone

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.phone.live.PhoneLiveWatchControl
import io.mockk.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Existing control entry after a Phone process restart must settle a Watch's old lease, never strand it or open a mic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LiveWatchControlScope03RoboTest:PhoneRoboBase(){
    private val replies=java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
    @Before fun setUp(){
        baseSetUp()
        val watch=mockk<Node>();every{watch.id}returns "watch-node"
        val info=mockk<CapabilityInfo>();every{info.nodes}returns setOf(watch)
        val caps=mockk<CapabilityClient>();every{caps.getCapability(any(),any())}returns Tasks.forResult(info)
        every{Wearable.getCapabilityClient(any<Context>())}returns caps
        val messages=mockk<MessageClient>();every{messages.sendMessage(any(),any(),any())}answers{
            if(secondArg<String>()==PhoneLiveWatchControl.STATE)replies+=JSONObject(thirdArg<ByteArray>().toString(Charsets.UTF_8))
            Tasks.forResult(1)
        }
        every{Wearable.getMessageClient(any<Context>())}returns messages
    }
    @After fun tearDown()=baseTearDown()
    @Test fun currentPairedWatchGetsExactInactiveEpochReceiptForAnUnknownOldControlLease(){
        app.liveWatch.receive("watch-node",JSONObject().put("v",1).put("id","old-watch-call").put("op","status").put("probe","fresh-probe").toString().toByteArray())
        assertTrue("current paired Watch must settle an unknown control lease",await{replies.isNotEmpty()})
        val result=replies.single();assertEquals("old-watch-call",result.getString("id"));assertEquals("fresh-probe",result.getString("probe"))
        assertFalse(result.getBoolean("active"));assertTrue(result.getString("epoch").isNotBlank());assertFalse(result.getBoolean("phone_audio"));assertTrue(result.getBoolean("watch_audio"))
        assertFalse(app.live.active);assertFalse(app.phoneMic.occupied);assertTrue(fake.prompts.isEmpty())
    }
}
