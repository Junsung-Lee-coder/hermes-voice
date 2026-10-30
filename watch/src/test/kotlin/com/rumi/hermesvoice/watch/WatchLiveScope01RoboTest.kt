package com.rumi.hermesvoice.watch

import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.settings.WatchSettings
import io.mockk.*
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLiveScope01RoboTest {
    private lateinit var app: WatchApp
    private lateinit var live: WatchLiveVoiceRuntime
    private val sent = ArrayList<JSONObject>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    @Before fun setUp() {
        app=RuntimeEnvironment.getApplication() as WatchApp
        mockkStatic(Wearable::class)
        val messages=mockk<MessageClient>()
        every { messages.sendMessage(any(),any(),any()) } answers { sent += JSONObject(thirdArg<ByteArray>().toString(Charsets.UTF_8)); Tasks.forResult(1) }
        every { Wearable.getMessageClient(any<android.content.Context>()) } returns messages
        live=WatchLiveVoiceRuntime(app) { "phone" };live.admission={} // status fencing never admits a native device in this fixture
    }
    @After fun tearDown() { app.scope.coroutineContext[Job]?.cancel(); unmockkAll() }
    @Test fun ownModeRequestsDoNotCopyPhonePreferenceOrOpenWatchMicrophone() {
        app.applySettings(WatchSettings(phoneLiveEnabled=true,watchLiveEnabled=false,revision=100).toJson()); idle()
        live.setMode(true); idle()
        assertTrue(app.settings.value.phoneLiveEnabled)
        assertFalse("only Phone's canonical reply commits the preference", app.settings.value.watchLiveEnabled)
        assertEquals("mode",sent.single().getString("op")); assertTrue(sent.single().getBoolean("enabled"))
        assertFalse(sent.single().has("phone_live_enabled"))
        assertFalse(app.voice.capturing)
        app.applySettings(WatchSettings(phoneLiveEnabled=true,watchLiveEnabled=true,revision=101).toJson()); idle()
        assertTrue(app.settings.value.watchLiveEnabled); assertTrue(app.settings.value.phoneLiveEnabled)
    }
    @Test fun editableIdleIsPhoneOwnedAndMirroredWithoutOpeningWatchAudio() {
        app.applySettings(WatchSettings(phoneLiveEnabled=true,watchLiveEnabled=false,liveIdleSeconds=10,revision=100).toJson());idle()
        live.setIdle(1);idle();assertEquals(10,app.settings.value.liveIdleSeconds)
        assertEquals("idle",sent.single().getString("op"));assertEquals(1,sent.single().getInt("seconds"))
        app.applySettings(WatchSettings(phoneLiveEnabled=true,watchLiveEnabled=false,liveIdleSeconds=1,revision=101).toJson());idle()
        assertEquals(1,app.settings.value.liveIdleSeconds);assertTrue(app.settings.value.phoneLiveEnabled);assertFalse(app.settings.value.watchLiveEnabled);assertFalse(app.voice.capturing)
        live.setIdle(60);idle();assertEquals(60,sent.last().getInt("seconds"))
        live.setIdle(61);idle();assertEquals(2,sent.size)
    }
    @Test fun restartStatusProbeResetsOnlyTheConfirmedPhoneEpochAndOldCallbacksCannotReviveIt() {
        app.applySettings(WatchSettings(watchLiveEnabled=true,revision=100).toJson());idle()
        live.toggle();idle();val start=sent.single { it.optString("op")=="start" };val oldId=start.getString("id")
        fun packet(epoch:String,seq:Long,active:Boolean,probe:String)=JSONObject().put("v",1).put("id",oldId).put("epoch",epoch).put("seq",seq).put("active",active).put("muted",false).put("phone_audio",false).put("watch_audio",true).put("probe",probe).put("status","Phone audio").toString().toByteArray()
        live.onStatus("phone",com.rumi.hermesvoice.core.live.WatchLiveWire.packet(oldId,"first-media","old-boot",1,"open"));live.onStatus("phone",packet("old-boot",100,true,start.getString("probe")));assertTrue(live.state.value.active)
        live.refresh();idle();val probe=sent.last().getString("probe")
        live.onStatus("phone",packet("new-boot",1,false,"wrong-probe"));assertTrue(live.state.value.active)
        live.onStatus("phone",packet("new-boot",1,false,probe));assertFalse(live.state.value.active)
        live.toggle();idle();assertNotEquals(oldId,sent.last().getString("id"))
        live.onStatus("phone",packet("old-boot",999,true,start.getString("probe")));assertFalse(live.state.value.active)
        assertFalse(app.voice.capturing)
    }
    @Test fun watchEnabledWithPhoneModeOffSendsControlsNotAudioAndFencesNodeAndId() {
        app.applySettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true,revision=100).toJson()); idle()
        live.toggle(); idle()
        val id=sent.single { it.optString("op")=="start" }.getString("id")
        val state=JSONObject().put("v",1).put("id",id).put("epoch","phone-boot").put("probe",sent.last().getString("probe")).put("seq",1).put("active",true).put("muted",false).put("phone_audio",false).put("watch_audio",true).put("status","Phone audio")
        live.onStatus("wrong",state.toString().toByteArray()); assertFalse(live.state.value.active)
        live.onStatus("phone",com.rumi.hermesvoice.core.live.WatchLiveWire.packet(id,"first-media","phone-boot",1,"open"));live.onStatus("phone",state.toString().toByteArray()); assertTrue(live.state.value.active)
        live.onStatus("phone",state.put("seq",0).put("active",false).toString().toByteArray()); assertTrue(live.state.value.active)
        live.mute(); idle(); live.toggle(); idle()
        assertEquals(listOf("start","mute","end"),sent.map { it.getString("op") })
        assertTrue(sent.all { it.getString("id")==id }); assertFalse(app.voice.capturing)
    }
}
