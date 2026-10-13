package com.rumi.hermesvoice.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Product control taps through SettingsTab -> PhoneViewModel -> owner store -> actual WatchSettingsSync publisher. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ChargingWakeR7SettingsUiRoboTest : PhoneRoboBase() {
    val compose = createComposeRule()
    @get:Rule val cleanupThenCompose: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(compose)
    private val sent = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Before fun setUp() {
        baseSetUp()
        app.settings.saveWatchSettings(WatchSettings())
        val data = mockk<DataClient>()
        every { data.putDataItem(any()) } answers {
            val request = firstArg<PutDataRequest>()
            if (request.uri.path == WatchLinkPaths.SETTINGS) DataMap.fromByteArray(request.data!!).getString("json")?.let(sent::add)
            Tasks.forResult(mockk<DataItem>(relaxed = true))
        }
        every { Wearable.getDataClient(any<android.content.Context>()) } returns data
    }
    @After fun tearDown() = baseTearDown()
    private fun show() {
        val model = PhoneViewModel(app)
        compose.setContent {
            val state by model.state.collectAsState()
            MaterialTheme { SettingsTab(state, model, false) }
        }
    }
    private fun value(key: String) = JSONObject(app.settings.watchSettings().toJson()).getBoolean(key)
    private fun tap(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick(); compose.waitForIdle(); settle() }

    @Test fun independentControlsPersistAndPublishWithoutChangingExistingPreferences() {
        show()
        tap("phone_wake_while_charging")
        assertTrue(value("phone_wake_while_charging")); assertFalse(value("watch_wake_while_charging"))
        tap("watch_wake_while_charging")
        assertTrue(value("phone_wake_while_charging")); assertTrue(value("watch_wake_while_charging"))
        tap("phone_wake_while_charging")
        assertFalse(value("phone_wake_while_charging")); assertTrue(value("watch_wake_while_charging"))
        val final = app.settings.watchSettings()
        assertEquals(WakeLocation.OFF, final.wakeLocation)
        assertFalse(final.phoneBackgroundWakeEnabled); assertFalse(final.watchBackgroundWakeEnabled)
        assertFalse(final.phoneBackgroundWakeScreenOffEnabled); assertFalse(final.watchBackgroundWakeScreenOffEnabled)
        assertTrue("actual publisher carries canonical revision", await { sent.any { WatchSettings.parse(it) == final } })
    }

    @Test fun chargingHelpIsOptionSpecificAndProductOnlyAndStatusIsNotAnAdmissionClaim() {
        show()
        val topic = com.rumi.hermesvoice.core.settings.SettingsHelp::class.java.methods
            .firstOrNull { it.name == "chargingWake" }
        assertNotNull("product help must exist", topic)
        val phone = topic!!.invoke(com.rumi.hermesvoice.core.settings.SettingsHelp, "Phone") as com.rumi.hermesvoice.core.settings.HelpTopic
        val watch = topic.invoke(com.rumi.hermesvoice.core.settings.SettingsHelp, "Watch") as com.rumi.hermesvoice.core.settings.HelpTopic
        assertEquals("help_phone_wake_while_charging", phone.buttonTag)
        assertEquals("help_watch_wake_while_charging", watch.buttonTag)
        for (help in listOf(phone, watch)) {
            assertTrue(help.contentDescription.contains(help.title)); assertTrue(help.text.contains("standby"))
            assertTrue(help.text.contains("Android")); assertTrue(help.text.contains("gaps"))
            for (workflow in listOf("JUnit", "Robolectric", "review gate", "test count")) assertFalse(help.text.contains(workflow))
        }
        compose.onNodeWithTag("phone_wake_while_charging_note").performScrollTo().assertExists()
        compose.onNodeWithTag("watch_wake_while_charging_note").performScrollTo().assertExists()
    }

    @Test fun helpOpenAndDismissHaveNoSettingsSideEffects() {
        show(); val before = app.settings.watchSettings()
        compose.onNodeWithTag("help_phone_wake_while_charging").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag("help_dialog_phone_wake_while_charging").assertExists()
        compose.onNodeWithTag("help_close").performClick(); compose.waitForIdle()
        assertEquals(before, app.settings.watchSettings()); assertTrue(sent.isEmpty())
    }
}
