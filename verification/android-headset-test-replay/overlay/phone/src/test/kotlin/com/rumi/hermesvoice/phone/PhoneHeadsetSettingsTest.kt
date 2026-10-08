package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The one "Use headset" preference as the real [PhoneApp] holds it: off by default, persistent, and read live by the policy and the microphone route. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneHeadsetSettingsTest {
    private lateinit var app: PhoneApp

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        app = RuntimeEnvironment.getApplication() as PhoneApp
    }

    @Test
    fun `a fresh install and a legacy store have it off  and the policy follows the saved value live`() {
        assertFalse(app.settings.useHeadset)
        assertFalse(app.headset.enabled)
        app.settings.useHeadset = true
        assertTrue("the same preference, read at each decision", app.headset.enabled)
        app.settings.useHeadset = false
        assertFalse(app.headset.enabled)
    }

    @Test
    fun `it is saved in this phone's settings and survives a new settings object over the same store`() {
        app.settings.useHeadset = true
        val again = AppSettings(SharedPreferencesKeyValueStore(app.getSharedPreferences(AppSettings.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)))
        assertTrue(again.useHeadset)
    }

    @Test
    fun `the view model setter is the toggle - it writes the preference that playback and capture read`() {
        // Not through AndroidViewModelFactory.getInstance: that factory is a process-wide singleton bound to the first test's Application.
        val model = PhoneViewModel(app)
        assertFalse(model.state.value.useHeadset)
        model.setUseHeadset(true)
        assertTrue(app.settings.useHeadset)
        assertTrue(app.headset.enabled)
        assertTrue(model.state.value.useHeadset)
        model.setUseHeadset(false)
        assertFalse(app.headset.enabled)
        assertEquals(false, model.state.value.useHeadset)
        assertNotNull(app.headsetMic)
    }
}
