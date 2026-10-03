package com.rumi.hermesvoice.phone

import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rumi.hermesvoice.core.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two routing switches through the real Settings screen, view model and SharedPreferences of
 * this (fixture) install. The phases run as separate instrumentation runs, i.e. separate processes:
 * what [RoutingSettingsWriteTest] switched must be what [RoutingSettingsRestartTest] reads after the
 * process was restarted. Nothing here signs in or sends anything.
 */
abstract class RoutingSettingsUi {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    protected lateinit var model: PhoneViewModel

    protected fun show() {
        model = PhoneViewModel(rule.activity.application)
        rule.setContent {
            HermesVoiceTheme(dark = true) {
                val state by model.state.collectAsStateWithLifecycle()
                SettingsTab(state, model, recognizerAvailable = true)
            }
        }
        rule.waitForIdle()
        Log.i(TAG, "${javaClass.simpleName} pid=${Process.myPid()}")
    }

    protected fun switch(tag: String) = rule.onNodeWithTag(tag).performScrollTo()

    protected fun saved() = AppSettings(SharedPreferencesKeyValueStore(
        rule.activity.getSharedPreferences(AppSettings.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)))

    companion object {
        const val TAG = "HermesVoiceUiTest"
    }
}

@RunWith(AndroidJUnit4::class)
class RoutingSettingsWriteTest : RoutingSettingsUi() {
    @Test
    fun defaultsThenSwitchOffRoutingKeepingTheNavigationChoice() {
        show()
        switch("routing_enabled").assertIsOn()
        switch("auto_navigate").assertIsOff().assertIsEnabled()
        assertTrue(model.state.value.routingEnabled)
        assertFalse(model.state.value.autoNavigate)

        switch("auto_navigate").performClick()
        switch("auto_navigate").assertIsOn()
        switch("routing_enabled").performClick()
        switch("routing_enabled").assertIsOff()
        // Irrelevant while routing is off: shown disabled, the saved choice kept.
        switch("auto_navigate").assertIsOn().assertIsNotEnabled()
        assertFalse(saved().routingEnabled)
        assertTrue(saved().autoNavigateToRouted)
        assertFalse(saved().autoNavigationApplies)
        Thread.sleep(1_500) // SharedPreferences.apply() writes to disk in the background
        Log.i(TAG, "written routing=false auto_navigate=true pid=${Process.myPid()}")
    }
}

@RunWith(AndroidJUnit4::class)
class RoutingSettingsRestartTest : RoutingSettingsUi() {
    @Test
    fun aNewProcessShowsWhatWasSwitchedAndCanRestoreTheDefaults() {
        show()
        switch("routing_enabled").assertIsOff()
        switch("auto_navigate").assertIsOn().assertIsNotEnabled()
        assertEquals(false, model.state.value.routingEnabled)
        assertEquals(true, model.state.value.autoNavigate)

        switch("routing_enabled").performClick()
        switch("auto_navigate").assertIsOn().assertIsEnabled()
        assertTrue(saved().autoNavigationApplies)
        switch("auto_navigate").performClick()
        switch("auto_navigate").assertIsOff()
        assertTrue(saved().routingEnabled)
        assertFalse(saved().autoNavigateToRouted)
        Thread.sleep(1_500)
        Log.i(TAG, "read after restart and restored defaults pid=${Process.myPid()}")
    }
}
