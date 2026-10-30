package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.Context
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.wake.*
import io.mockk.*
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLiveWakeAdmission604RoboTest {
    private lateinit var app:WatchApp
    private val sent=java.util.concurrent.ConcurrentLinkedQueue<Pair<String,JSONObject>>()
    private fun idle(){repeat(8){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));Thread.sleep(20)}}
    @Before fun setup(){
        app=RuntimeEnvironment.getApplication() as WatchApp
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        mockkStatic(Wearable::class)
        val phone=mockk<Node>();every{phone.id}returns "phone-604";every{phone.isNearby}returns true
        val info=mockk<CapabilityInfo>();every{info.nodes}returns setOf(phone)
        val caps=mockk<CapabilityClient>();every{caps.getCapability(any(),any())}returns Tasks.forResult(info)
        every{Wearable.getCapabilityClient(any<Context>())}returns caps
        val messages=mockk<MessageClient>();every{messages.sendMessage(any(),any(),any())}answers{
            sent+=secondArg<String>() to JSONObject(thirdArg<ByteArray>().toString(Charsets.UTF_8));Tasks.forResult(1)
        }
        every{Wearable.getMessageClient(any<Context>())}returns messages
        app.applySettings(WatchSettings(wakeLocation=WakeLocation.BOTH,watchLiveEnabled=true,revision=604).toJson());idle();sent.clear()
    }
    @After fun teardown(){app.live.end();idle();app.scope.cancel();unmockkAll()}
    private fun port():WakeDevicePort=WatchVoiceRuntime::class.java.getDeclaredField("wakePort").apply{isAccessible=true}.get(app.voice) as WakeDevicePort
    private fun verifyTransfer(phrase:Boolean){
        val claim="actual-watch-claim-604"
        if(phrase)assertTrue(port().startRequestCapture(1000,claim))else port().sendRecognized("watch spoken episode",claim)
        idle()
        val starts=sent.filter{it.first==WatchLiveVoiceRuntime.COMMAND&&it.second.optString("op")=="start"}
        assertEquals(1,starts.size)
        val wire=starts.single().second
        assertTrue("actual wake caller must distinguish wake from manual Talk",wire.optBoolean("wake",false))
        assertEquals(claim,wire.optString("wake_claim_id"))
        assertFalse("a transferred unconsumed claim must not be released before Phone Live admission",sent.any{it.second.optString("op")=="release"&&it.second.optString("claim_id")==claim})
        assertFalse(app.voice.capturing)
    }
    @Test fun recognizedWakeTransfersExactClaimInsteadOfReleasingIt()=verifyTransfer(false)
    @Test fun phraseOnlyWakeTransfersExactClaimInsteadOfLosingIt()=verifyTransfer(true)
    @Test fun manualTalkRemainsClaimless(){
        app.live.toggle();idle();val start=sent.first{it.first==WatchLiveVoiceRuntime.COMMAND&&it.second.optString("op")=="start"}.second
        assertFalse(start.optBoolean("wake",false));assertFalse(start.has("wake_claim_id"));assertFalse(app.voice.capturing)
    }
}
