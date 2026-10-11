package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.headset.HeadsetMediaText
import com.rumi.hermesvoice.core.settings.SettingsHelp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Amendment K: the "Use headset" help states what the headset play/pause control does, its limits, and that Watch recording is separate. */
class HeadsetMediaHelpTest {
    private val text = SettingsHelp.useHeadset().text

    @Test
    fun `it says play-pause starts and stops a Phone recording with one tone for start and two for stop`() {
        for (needle in listOf("play/pause", "starts", "stops", "one short tone", "two")) assertTrue("mentions '$needle': $text", text.contains(needle, ignoreCase = true))
    }

    @Test
    fun `it states the limits - only while the app is open  Android decides which app gets the buttons  and nothing is claimed beyond that`() {
        for (needle in listOf("while this app is open", "Android decides", "cannot confirm")) assertTrue("mentions '$needle': $text", text.contains(needle, ignoreCase = true))
        assertTrue("a missing tone is told, not replaced by a speaker", text.contains("no tone", ignoreCase = true) || text.contains("without a tone", ignoreCase = true))
    }

    @Test
    fun `it says the Watch microphone and sending from the Watch are not changed`() {
        assertTrue(text.contains("Watch microphone", ignoreCase = true))
        assertTrue(text.contains("Sending a request from the Watch works the same", ignoreCase = true))
    }

    @Test
    fun `the status lines are distinct and honest`() {
        val lines = listOf(HeadsetMediaText.WAITING_FOR_HEADSET, HeadsetMediaText.APP_CLOSED, HeadsetMediaText.UNAVAILABLE, HeadsetMediaText.REGISTERED)
        assertTrue(lines.toSet().size == lines.size)
        assertTrue(HeadsetMediaText.REGISTERED.contains("Android decides", ignoreCase = true))
        assertFalse(HeadsetMediaText.REGISTERED.contains("works", ignoreCase = true) && !HeadsetMediaText.REGISTERED.contains("Android decides"))
        assertNotEquals(HeadsetMediaText.CUE_SKIPPED, HeadsetMediaText.UNAVAILABLE)
        assertTrue(HeadsetMediaText.PERMISSION.contains("microphone", ignoreCase = true))
    }
}
