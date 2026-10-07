package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.HelpTopic
import com.rumi.hermesvoice.core.settings.SettingsHelp
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import com.rumi.hermesvoice.core.watchlink.WatchPlaybackSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v19 review R2 (EP-F1): the in-app timeout wording is asserted on the real [HelpTopic] paragraphs, not on the README.
 * The behaviours the wording describes are pinned to the code's own constants and proven behaviourally elsewhere
 * (SynthesisNoCutoffTest: no synthesis cutoff, Stop cancels the call; WatchPlaybackProgressTest: the 30 s no-advance stall;
 * VoiceTurnAwarenessTest / LaterReplyCompletionTest: the 15 minute response silence).
 */
class HelpTimeoutSemanticsTest {
    private val later: HelpTopic = SettingsHelp.laterReplies(30)
    private val waiting: HelpTopic = SettingsHelp.waiting()

    private fun paragraphWith(topic: HelpTopic, needle: String): String =
        topic.paragraphs.single { it.contains(needle) }

    @Test
    fun `the constants the help describes are the code's`() {
        assertEquals(30_000L, WatchPlaybackSink.PROGRESS_STALL_MS)
        assertEquals(10 * 60_000L, VoiceTurnOrchestrator.LATER_DEFER_MAX_MS)
    }

    @Test
    fun `no help topic claims a 10 minute no-progress rule or any inactivity rule the product does not have`() {
        for (minutes in listOf(1, 30, 4320)) {
            for (topic in SettingsHelp.all(minutes)) {
                val text = topic.text
                assertFalse("${topic.id}: the removed 10 minute stall", Regex("(?i)no progress for 10 minutes|10 minutes without").containsMatchIn(text))
                assertFalse("${topic.id}: progress is not 'making progress' of synthesis", text.contains("finished speech pieces"))
            }
        }
    }

    @Test
    fun `the waiting help states the real bounds - 15 minutes of response silence, no speech preparation limit, 30 seconds of Watch playback not advancing`() {
        val p = paragraphWith(waiting, "total time limit")
        assertTrue(p, p.contains("15 minutes without any new response from Hermes"))
        assertTrue(p, p.contains("Preparing the speech has no time limit"))
        assertTrue(p, p.contains("Stop"))
        assertTrue(p, p.contains("about 30 seconds"))
        assertTrue(p, p.contains("Watch"))
        assertTrue(p, p.contains("doesn't advance"))
        assertFalse(p, p.contains("10 minutes"))
    }

    @Test
    fun `the later-reply help keeps the 10 minute admission and states the real playback rules after it`() {
        val p = paragraphWith(later, "free speaker within 10 minutes")
        assertTrue(p, p.contains("within 10 minutes of arriving"))
        assertTrue(p, p.contains("no total time cuts a long reply"))
        assertTrue(p, p.contains("Preparing its speech has no time limit"))
        assertTrue(p, p.contains("about 30 seconds"))
        assertTrue(p, p.contains("Stop"))
        assertEquals("10 minutes appears only as the admission window", 1, Regex("10 minutes").findAll(p).count())
    }

    @Test
    fun `the help never says the app gives up on a slow speech preparation`() {
        for (topic in listOf(later, waiting)) {
            assertFalse(topic.id, Regex("(?i)(speech|synthesis|preparing)[^.]*(gives? up|is cut|times? out|ends it)").containsMatchIn(topic.text.replace("Stop", "")))
        }
    }
}
