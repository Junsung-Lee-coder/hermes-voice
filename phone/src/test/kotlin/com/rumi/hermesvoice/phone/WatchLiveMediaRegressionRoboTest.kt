package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.settings.WatchSettings
import io.mockk.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Behavioral witness on the actual paired-Watch command entry, not a source-string witness. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WatchLiveMediaRegressionRoboTest:PhoneRoboBase(){
    private val replies=java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
    @Before fun setUp(){
        baseSetUp(); shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true))
        val node=mockk<Node>();every{node.id}returns "watch-node"
        val info=mockk<CapabilityInfo>();every{info.nodes}returns setOf(node)
        val caps=mockk<CapabilityClient>();every{caps.getCapability(any(),any())}returns Tasks.forResult(info)
        every{Wearable.getCapabilityClient(any<Context>())}returns caps
        val messages=mockk<MessageClient>();every{messages.sendMessage(any(),any(),any())}answers{
            replies+=JSONObject(thirdArg<ByteArray>().toString(Charsets.UTF_8));Tasks.forResult(1)
        }
        every{Wearable.getMessageClient(any<Context>())}returns messages
        app.live.admission={} // no Android service/native device: inspect ownership at admission.
    }
    @After fun tearDown(){app.live.end();settle();baseTearDown()}
    private fun start(){
        app.liveWatch.receive("watch-node",JSONObject().put("v",1).put("id","watch-media-witness").put("op","start").put("probe","probe").toString().toByteArray())
        assertTrue("paired command reached its status boundary",await{replies.isNotEmpty()})
    }
    @Test fun pairedWatchTalkNeverClaimsThePhoneMicrophone(){
        start()
        assertFalse("Watch-origin duplex must capture Watch, never reserve Phone microphone",app.phoneMic.occupied)
        assertFalse(app.manualCapture.busy);assertTrue(fake.prompts.isEmpty())
    }
    @Test fun phoneMicrophoneDenialDoesNotDenyWatchMicrophoneAdmission(){
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        start()
        assertFalse("Phone permission is not Watch permission",app.live.state.value.status.contains("Phone microphone permission"))
        assertFalse(app.phoneMic.occupied);assertTrue(fake.prompts.isEmpty())
    }
}
