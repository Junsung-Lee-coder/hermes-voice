package com.rumi.hermesvoice.phone

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.rumi.hermesvoice.core.background.DeviceLocalFlags
import com.rumi.hermesvoice.core.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The Phone-local text size control in Settings, through the real MainActivity, SettingsTab,
 * PhoneViewModel and the Phone's device-local preferences file: default 100%, bounded 80-140% in 10% steps, applied at once to the
 * list, chat, composer and Settings text, multiplied with Android's own font scale, kept across recreation and relaunch, and
 * safe against bad stored values. Nothing else in the preference files or the secure token store is written.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneFontScaleRoboTest : PhoneNavRoboBase() {
    private val key = "phone_font_scale_percent"

    private fun local() = app.getSharedPreferences(DeviceLocalFlags.PHONE_PREFERENCES, Context.MODE_PRIVATE)

    private fun settingsFile() = app.getSharedPreferences(AppSettings.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun openSettings() {
        rule.onNodeWithTag("settings_button").performClick()
        waitFor("settings are shown") { exists("font_scale_value") }
        rule.onNodeWithTag("font_scale_value").performScrollTo()
    }

    private fun value() = rule.onNodeWithTag("font_scale_value").fetchSemanticsNode().let { textsUnder(it).joinToString("") }

    private fun step(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        settle()
    }

    private fun scaleOf(tag: String) = textLayout(tag).layoutInput.density.fontScale

    private fun densityOf(tag: String) = textLayout(tag).layoutInput.density.density

    @Test fun F01_theDefaultIsOneHundredPercentAndResetIsDisabledThere() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        openSettings()
        assertEquals("100%", value())
        rule.onNodeWithTag("font_scale_reset").assertIsNotEnabled()
        rule.onNodeWithTag("font_scale_up").assertIsEnabled()
        rule.onNodeWithTag("font_scale_down").assertIsEnabled()
        assertEquals(1.0f, scaleOf("font_scale_value"), 0.001f)
    }

    @Test fun F02_stepsOfTenPercentAreBoundedAtEightyAndOneHundredForty() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        openSettings()
        repeat(2) { step("font_scale_down") }
        assertEquals("80%", value())
        rule.onNodeWithTag("font_scale_down").assertIsNotEnabled()
        repeat(6) { step("font_scale_up") }
        assertEquals("140%", value())
        rule.onNodeWithTag("font_scale_up").assertIsNotEnabled()
        step("font_scale_down")
        assertEquals("130%", value())
        step("font_scale_reset")
        assertEquals("100%", value())
        rule.onNodeWithTag("font_scale_reset").assertIsNotEnabled()
    }

    @Test fun F03_aStepAppliesAtOnceToTheSettingsTextTheListTheChatAndTheComposer() {
        val alpha = titled("Synthetic alpha", "alpha")
        seed(alpha, 4, "size")
        val model = showWith(alpha)
        val density = densityOf("conversation_title_$alpha")
        assertEquals(1.0f, scaleOf("conversation_title_$alpha"), 0.001f)
        openSettings()
        repeat(4) { step("font_scale_up") }
        assertEquals("140%", value())
        assertEquals("settings labels follow at once", 1.4f, scaleOf("font_scale_value"), 0.001f)
        rule.onNodeWithTag("back_button").performClick()
        waitFor("the list is back") { exists("conversation_row_$alpha") }
        assertEquals("list titles", 1.4f, scaleOf("conversation_title_$alpha"), 0.001f)
        assertEquals("dp density is untouched", density, densityOf("conversation_title_$alpha"), 0.0001f)
        openCard(model, alpha)
        waitFor("history is shown") { exists("message_text_4") }
        assertEquals("chat text", 1.4f, scaleOf("message_text_4"), 0.001f)
        assertEquals("composer", 1.4f, scaleOf("composer"), 0.001f)
        assertEquals("dp density is untouched in chat", density, densityOf("message_text_4"), 0.0001f)
    }

    @Test fun F04_theStoredValueIsPhoneLocalAndNothingElseIsWritten() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        val settingsBefore = settingsFile().all.toMap()
        val localBefore = local().all.toMap()
        openSettings()
        step("font_scale_up")
        step("font_scale_up")
        assertEquals("120%", value())
        assertEquals("120", local().all[key]?.toString())
        assertEquals("the shared settings file is untouched", settingsBefore, settingsFile().all.toMap())
        assertEquals("only the one key was added to the device-local file", localBefore + (key to local().all[key]), local().all.toMap())
        assertTrue("signed in still", app.tokens.load() != null)
    }

    @Test fun F05_theValueSurvivesRecreationAndARelaunch() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        openSettings()
        repeat(3) { step("font_scale_down") }.also { }
        assertEquals("80%", value())
        controller!!.recreate()
        idle(300); settle()
        waitFor("settings are still open after recreation") { exists("font_scale_value") }
        assertEquals("recreated value", "80%", value())
        assertEquals("recreated settings text", 0.8f, scaleOf("font_scale_value"), 0.001f)
        controller!!.destroy()
        showing()
        waitFor("relaunched") { exists("conversation_row_$alpha") }
        assertEquals("relaunched list titles", 0.8f, scaleOf("conversation_title_$alpha"), 0.001f)
        openSettings()
        assertEquals("80%", value())
    }

    @Test fun F06_theChosenPercentMultipliesAndroidsOwnFontScale() {
        RuntimeEnvironment.setFontScale(1.5f)
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        assertEquals("system scale alone", 1.5f, scaleOf("conversation_title_$alpha"), 0.001f)
        openSettings()
        repeat(2) { step("font_scale_up") }
        assertEquals("120%", value())
        assertEquals("1.5 x 1.2", 1.8f, scaleOf("font_scale_value"), 0.001f)
        rule.onNodeWithTag("font_scale_reset").performScrollTo().assertIsEnabled()
        step("font_scale_reset")
        assertEquals("back to the system scale", 1.5f, scaleOf("font_scale_value"), 0.001f)
    }

    private fun relaunchWith(raw: Any?) {
        controller?.destroy()
        val edit = local().edit()
        when (raw) {
            null -> edit.remove(key)
            is String -> edit.putString(key, raw)
            is Int -> edit.putInt(key, raw)
            is Boolean -> edit.putBoolean(key, raw)
            else -> error("unsupported")
        }
        edit.commit()
        showing()
    }

    @Test fun F07_aBadStoredValueFallsBackToTheDefaultAndOutOfRangeOnesToTheBound() {
        val alpha = titled("Synthetic alpha", "alpha")
        val expectations = listOf<Pair<Any?, Int>>(
            "NaN" to 100, "abc" to 100, "" to 100, "Infinity" to 100, "-Infinity" to 100, null to 100,
            "250" to 140, "20" to 80, "140" to 140, "80" to 80, "110" to 110, "90.0" to 90,
        )
        for ((raw, percent) in expectations) {
            relaunchWith(raw)
            waitFor("listed for $raw") { exists("conversation_row_$alpha") }
            assertEquals("stored $raw", percent / 100f, scaleOf("conversation_title_$alpha"), 0.001f)
        }
        for (raw in listOf<Any>("-5", "95", "0", "1e9")) {
            relaunchWith(raw)
            waitFor("listed for $raw") { exists("conversation_row_$alpha") }
            val scale = scaleOf("conversation_title_$alpha")
            assertTrue("stored $raw gives a safe scale: $scale", scale in 0.8f..1.4f)
            assertEquals("and a whole step: $scale", 0f, (scale * 10f) - Math.round(scale * 10f), 0.001f)
        }
        for (raw in listOf<Any>(120, true)) {
            relaunchWith(raw)
            waitFor("listed for $raw") { exists("conversation_row_$alpha") }
            val scale = scaleOf("conversation_title_$alpha")
            assertTrue("a value of the wrong type is safe: $scale", scale in 0.8f..1.4f)
        }
    }

    @Test fun F08_aBadStoredValueIsShownAsASafeStepInSettings() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        controller!!.destroy()
        local().edit().putString(key, "NaN").commit()
        showing()
        waitFor("listed") { exists("conversation_row_$alpha") }
        openSettings()
        assertEquals("100%", value())
        assertNotNull(value())
        rule.onNodeWithTag("font_scale_value").assertTextEquals("100%")
    }
}
