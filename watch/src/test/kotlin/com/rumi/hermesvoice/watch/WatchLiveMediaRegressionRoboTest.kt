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
class WatchLiveMediaRegressionRoboTest {
    @Test fun actualWatchTalkCannotClaimDuplexFromPhoneAudioOnlyStatus(){
        val app=RuntimeEnvironment.getApplication() as WatchApp
        mockkStatic(Wearable::class)
        val sent=ArrayList<JSONObject>();val messages=mockk<MessageClient>()
        every{messages.sendMessage(any(),any(),any())}answers{sent+=JSONObject(thirdArg<ByteArray>().toString(Charsets.UTF_8));Tasks.forResult(1)}
        every{Wearable.getMessageClient(any<android.content.Context>())}returns messages
        try {
            app.applySettings(WatchSettings(watchLiveEnabled=true,revision=100).toJson())
            val live=WatchLiveVoiceRuntime(app){"phone"}
            live.toggle();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            val request=sent.single{it.optString("op")=="start"}
            val reply=JSONObject().put("v",1).put("id",request.getString("id")).put("epoch","phone-only")
                .put("seq",1).put("probe",request.getString("probe")).put("active",true).put("muted",false).put("phone_audio",true).put("status","Phone microphone/speaker")
            live.onStatus("phone",reply.toString().toByteArray())
            assertFalse("Phone-only control is not actual Watch-origin media",live.state.value.active)
            assertFalse(app.voice.capturing)
        } finally { app.scope.coroutineContext[Job]?.cancel();unmockkAll() }
    }
}
