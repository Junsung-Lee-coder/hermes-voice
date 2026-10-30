package com.rumi.hermesvoice.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.WatchSettings
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LiveModeScope01RoboTest : PhoneRoboBase() {
    private val compose = createComposeRule()
    @get:Rule val cleanupThenCompose: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(compose)
    @Before fun setUp() {
        baseSetUp()
        app.settings.saveWatchSettings(WatchSettings())
        val data = mockk<DataClient>()
        every { data.putDataItem(any()) } answers { Tasks.forResult(mockk<DataItem>(relaxed = true)) }
        every { Wearable.getDataClient(any<android.content.Context>()) } returns data
    }
    @After fun tearDown() = baseTearDown()

    @Test fun oneTalkIndependentPersistentLiveModesThroughRealSettingsEntry() {
        val model = PhoneViewModel(app)
        compose.setContent {
            val state by model.state.collectAsState()
            MaterialTheme { SettingsTab(state, model, false) }
        }
        fun tap(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick(); compose.waitForIdle(); settle() }
        fun matrix(phone: Boolean, watch: Boolean) {
            val again = AppSettings(SharedPreferencesKeyValueStore(app.getSharedPreferences(AppSettings.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)))
            val json = JSONObject(again.watchSettings().toJson())
            assertEquals(phone, json.optBoolean("phone_live_enabled", false))
            assertEquals(watch, json.optBoolean("watch_live_enabled", false))
            assertFalse("settings must not start a recorder", app.manualCapture.busy)
            assertFalse(again.useHeadset)
        }
        matrix(false, false)
        tap("phone_live_enabled"); matrix(true, false)
        tap("watch_live_enabled"); matrix(true, true)
        tap("phone_live_enabled"); matrix(false, true)
        tap("watch_live_enabled"); matrix(false, false)
    }
}
