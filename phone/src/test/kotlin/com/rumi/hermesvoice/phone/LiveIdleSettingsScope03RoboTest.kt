package com.rumi.hermesvoice.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.settings.AppSettings
import io.mockk.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34])
class LiveIdleSettingsScope03RoboTest:PhoneRoboBase(){
    private val compose=createComposeRule()
    @get:Rule val cleanupThenCompose:org.junit.rules.RuleChain=org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(compose)
    @Before fun setUp(){baseSetUp();val data=mockk<DataClient>();every{data.putDataItem(any())}answers{Tasks.forResult(mockk<DataItem>(relaxed=true))};every{Wearable.getDataClient(any<android.content.Context>())}returns data}
    @After fun tearDown()=baseTearDown()
    @Test fun actualIdleSettingPersistsDefaultAndInclusiveBoundsWithoutOpeningLive(){
        val model=PhoneViewModel(app)
        compose.setContent{val state by model.state.collectAsState();MaterialTheme{SettingsTab(state,model,false)}}
        compose.onNodeWithTag("live_idle_seconds").performScrollTo().assertExists()
        fun stored():Int{
            val again=AppSettings(SharedPreferencesKeyValueStore(app.getSharedPreferences(AppSettings.PREFERENCES_NAME,android.content.Context.MODE_PRIVATE)))
            return JSONObject(again.watchSettings().toJson()).getInt("live_idle_seconds")
        }
        fun set(value:String){compose.onNodeWithTag("live_idle_seconds").performScrollTo().performTextReplacement(value);compose.onNodeWithTag("live_idle_apply").performScrollTo().performClick();compose.waitForIdle();settle()}
        assertEquals(10,stored())
        set("1");assertEquals(1,stored())
        set("0");assertEquals(1,stored())
        set("60");assertEquals(60,stored())
        set("61");assertEquals(60,stored())
        assertFalse(app.live.active);assertFalse(app.manualCapture.busy)
        assertFalse(fake.restLog.any{it.contains("voice-live/session")})
    }
}
