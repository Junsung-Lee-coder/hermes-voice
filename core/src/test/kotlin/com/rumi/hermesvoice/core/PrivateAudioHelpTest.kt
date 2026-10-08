package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.SettingsHelp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Use headset" help says what private output does to the Watch and states its limits, instead of the earlier "the Watch is not changed". */
class PrivateAudioHelpTest {
    private val text = SettingsHelp.useHeadset().text

    @Test
    fun `it says the Watch and the phone speaker stay silent while the headset is connected`() {
        for (needle in listOf("Watch", "phone's speaker", "only")) assertTrue("mentions '$needle': $text", text.contains(needle))
        assertFalse("the earlier wording is gone", text.contains("the Watch is not changed"))
        assertTrue(text.contains("every answer", ignoreCase = true) || text.contains("all answers", ignoreCase = true))
    }

    @Test
    fun `it states what is not promised - confirmation from the Watch  a Watch that is away  and a stopped answer being replayed`() {
        for (needle in listOf("confirm", "out of reach", "start again")) assertTrue("mentions '$needle': $text", text.contains(needle, ignoreCase = true))
        assertTrue(text.contains("sound", ignoreCase = true) && text.contains("notification", ignoreCase = true))
        assertTrue("cars and speakers stay outside", text.contains("car", ignoreCase = true) && text.contains("speaker", ignoreCase = true))
    }

    @Test
    fun `it keeps the microphone and wake recognizer statements`() {
        assertTrue(text.contains("wake phrase itself is heard by Android's speech recognizer"))
        assertTrue(text.contains("only the recording that follows it uses the headset microphone"))
    }
}
