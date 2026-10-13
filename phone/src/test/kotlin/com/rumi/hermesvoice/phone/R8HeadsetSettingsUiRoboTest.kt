package com.rumi.hermesvoice.phone

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundStatus
import com.rumi.hermesvoice.core.headset.HeadsetMediaText
import com.rumi.hermesvoice.core.settings.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Render the production SettingsTab, not its string helpers. The relay/media matrices are presentation fixtures;
 * R5PhoneBackgroundAnswersTest retains real relay tap/admission/refusal coverage. Help assertions use literal
 * user-facing caveats, actual scroll/click/back actions and the real preferences store. Android audio, physical
 * buttons and TalkBack are not simulated successes. These native cases are prepared, NOT_RUN until executed.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class R8HeadsetSettingsUiRoboTest : PhoneRoboBase() {
    val compose = createComposeRule()
    @get:Rule val cleanupThenCompose: org.junit.rules.RuleChain =
        org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(compose)

    private lateinit var model: PhoneViewModel
    private val off = BackgroundStatus(wanted = false, running = false, microphone = false, notice = BackgroundNotice.OFF)
    private val asked = BackgroundStatus(wanted = true, running = true, microphone = false, notice = BackgroundNotice.RUNNING)

    override fun pump() { idle(50); compose.waitForIdle() }

    @Before fun setUp() {
        baseSetUp()
        model = PhoneViewModel(app)
        model.setUseHeadset(true)
    }

    @After fun tearDown() = baseTearDown()

    private fun show(initial: PhoneUiState) = mutableStateOf(initial).also { shown ->
        compose.setContent { MaterialTheme { SettingsTab(shown.value, model, false) } }
        compose.waitForIdle()
    }

    private fun initial() = model.state.value.copy(androidSdk = 35, useHeadset = true, relay = off, relayForeground = false)

    private fun savedPreferences(): Map<String, Any?> =
        app.getSharedPreferences(AppSettings.PREFERENCES_NAME, Context.MODE_PRIVATE).all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }

    private fun assertHelpHadNoEffects(before: Map<String, Any?>, relayBefore: BackgroundStatus, admittedBefore: Boolean) {
        assertEquals("every stored setting, including wake and Watch options, is unchanged", before, savedPreferences())
        assertTrue("help never toggles Use headset", app.settings.useHeadset)
        assertEquals("help never asks for or stops the relay", relayBefore, app.relay.status)
        assertEquals("help cannot claim admission", admittedBefore, app.relayForeground.value)
        assertNull("no service request from help", shadowOf(app).nextStartedService)
        assertTrue("no request sent by help", fake.rawPrompts.isEmpty())
    }

    private fun assertNoInlineCaveats() {
        for (needle in listOf("not a guarantee of audible playback", "Android decides which app receives", "stored unencrypted")) {
            compose.onNodeWithText(needle, substring = true, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag("help_dialog_use_headset").assertDoesNotExist()
    }

    @Test fun R807_renderedBackgroundStatusesDistinguishSetupRequestedAdmissionRefusalAndStop() {
        val shown = show(initial())
        val before = savedPreferences()
        fun expect(text: String, action: String?) {
            compose.waitForIdle()
            compose.onNodeWithTag("headset_background_status", useUnmergedTree = true)
                .performScrollTo().assertTextEquals(text)
            if (action == null) compose.onNodeWithTag("headset_background_action").assertDoesNotExist()
            else compose.onNodeWithTag("headset_background_action").assertTextEquals(action)
            assertNoInlineCaveats()
            assertEquals("rendering statuses does not write preferences", before, savedPreferences())
        }
        expect("Background answers: off · Turn on below", "Turn on background answers")
        shown.value = initial().copy(relay = asked)
        expect("Background answers: waiting for Android", null)
        shown.value = initial().copy(relay = asked, relayForeground = true)
        expect("Background answers: relay running", null)
        for (notice in listOf(BackgroundNotice.REFUSED, BackgroundNotice.NEEDS_VISIBLE)) {
            shown.value = initial().copy(relay = off.copy(wanted = true, notice = notice))
            expect("Background answers: blocked · Try again with this app open", "Try again")
        }
        shown.value = initial().copy(relay = off.copy(wanted = true, notice = BackgroundNotice.PAUSED))
        expect("Background answers: relay stopped · Try again", "Try again")
        for (hidden in listOf(initial().copy(useHeadset = false), initial().copy(androidSdk = 34))) {
            shown.value = hidden
            compose.waitForIdle()
            compose.onNodeWithTag("headset_background_status").assertDoesNotExist()
            compose.onNodeWithTag("headset_background_action").assertDoesNotExist()
        }
        assertFalse("presentation fixtures never request the relay", app.relay.status.wanted)
        assertFalse("nor a microphone", app.relay.status.microphone)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun R808_renderedControlStatusesAreCompactAndDoNotPromiseButtonDelivery() {
        val shown = show(initial().copy(headsetControl = HeadsetMediaText.WAITING_FOR_HEADSET))
        val before = savedPreferences()
        val cases = listOf(
            HeadsetMediaText.WAITING_FOR_HEADSET to "Connect a headset to use play/pause",
            HeadsetMediaText.APP_CLOSED to "Open this app to start a recording",
            HeadsetMediaText.UNAVAILABLE to "Session unavailable · Try play/pause here or use the mic",
            HeadsetMediaText.REGISTERED to "Headset control ready · Try play/pause",
        )
        for ((reported, expected) in cases) {
            shown.value = initial().copy(headsetControl = reported)
            compose.waitForIdle()
            compose.onNodeWithTag("headset_control_status", useUnmergedTree = true).performScrollTo().assertTextEquals(expected)
            assertNoInlineCaveats()
        }
        shown.value = initial().copy(headsetControl = null)
        compose.waitForIdle()
        compose.onNodeWithTag("headset_control_status").assertDoesNotExist()
        assertEquals(before, savedPreferences())
        assertFalse(app.relay.status.microphone)
    }

    @Test fun R809_useHeadsetHelpRetainsRealCaveatsScrollsAndClosesWithoutChangingSettings() {
        show(initial().copy(headsetControl = HeadsetMediaText.REGISTERED))
        val before = savedPreferences()
        val relayBefore = app.relay.status
        val admittedBefore = app.relayForeground.value
        assertNoInlineCaveats()
        compose.onNodeWithTag("help_use_headset").performScrollTo()
            .assertContentDescriptionEquals("About Use headset").assertHasClickAction()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("help_dialog_use_headset").assert(hasScrollAction())
        for (caveat in listOf(
            "Off by default", "Watch confirms it", "does not start again when the headset returns",
            "falls back to this phone's microphone and says so", "speech recognizer",
            "even with HOME or the screen locked", "never a speaker fallback",
            "microphone foreground service", "one brief connection sound", "not a guarantee of button ownership", "force-stop",
            "Android decides which app receives", "Use the on-screen mic if buttons go elsewhere",
            "not a guarantee of audible playback", "It never changes the volume, Do Not Disturb",
            "audio focus, headset routing, volume and Do Not Disturb still apply.", "stored unencrypted",
        )) {
            compose.onNodeWithText(caveat, substring = true, useUnmergedTree = true).assertExists()
        }
        // The last paragraph is below the initial viewport. Exercise scroll-to through the actual dialog container.
        compose.onNodeWithTag("help_dialog_use_headset").performScrollToNode(hasText("stored unencrypted", substring = true))
        compose.onNodeWithText("stored unencrypted", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("help_dialog_use_headset").performScrollToNode(hasText("not a guarantee of audible playback", substring = true))
        compose.onNodeWithText("not a guarantee of audible playback", substring = true, useUnmergedTree = true).assertIsDisplayed()
        for (workflow in listOf("JUnit", "Robolectric", "test count", "agent workflow", "review gate")) {
            compose.onNodeWithText(workflow, substring = true, ignoreCase = true, useUnmergedTree = true).assertDoesNotExist()
        }
        assertHelpHadNoEffects(before, relayBefore, admittedBefore)
        compose.onNodeWithTag("help_close").assertHasClickAction()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        assertNoInlineCaveats()
        compose.onNodeWithTag("use_headset").assertIsOn()
        assertHelpHadNoEffects(before, relayBefore, admittedBefore)
    }

    @Test fun R810_useHeadsetHelpBackDismissAndReopenAreReadOnly() {
        show(initial())
        val before = savedPreferences()
        val relayBefore = app.relay.status
        val admittedBefore = app.relayForeground.value
        compose.onNodeWithTag("help_use_headset").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("help_dialog_use_headset").assertExists()
        Espresso.pressBack()
        compose.waitForIdle()
        assertNoInlineCaveats()
        assertHelpHadNoEffects(before, relayBefore, admittedBefore)
        compose.onNodeWithTag("help_use_headset").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("help_dialog_use_headset").assertExists()
        compose.onNodeWithTag("help_close").performClick()
        compose.waitForIdle()
        assertNoInlineCaveats()
        compose.onNodeWithTag("use_headset").assertIsOn()
        assertHelpHadNoEffects(before, relayBefore, admittedBefore)
    }
}
