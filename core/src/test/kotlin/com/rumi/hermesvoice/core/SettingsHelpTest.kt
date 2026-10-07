package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.SettingsHelp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ⓘ explanations behind the Phone settings: structure, accessibility names, and that the warnings were kept, not deleted. */
class SettingsHelpTest {
    private val topics = SettingsHelp.all(30)

    @Test
    fun `every settings topic exists once with stable unique tags and a name tied to its option`() {
        assertEquals(SettingsHelp.topicIds, topics.map { it.id })
        assertEquals(topics.size, topics.map { it.id }.toSet().size)
        assertEquals(topics.size, topics.map { it.buttonTag }.toSet().size)
        assertEquals(topics.size, topics.map { it.dialogTag }.toSet().size)
        for (topic in topics) {
            assertEquals("help_${topic.id}", topic.buttonTag)
            assertEquals("help_dialog_${topic.id}", topic.dialogTag)
            assertEquals("About ${topic.title}", topic.contentDescription)
            assertTrue(topic.title.isNotBlank())
            assertTrue("${topic.id} has an explanation", topic.paragraphs.isNotEmpty() && topic.paragraphs.all { it.isNotBlank() })
            assertEquals(topic.paragraphs.joinToString("\n\n"), topic.text)
        }
        assertEquals(topics.size, topics.map { it.contentDescription }.toSet().size)
    }

    @Test
    fun `the later-reply help names the configured duration and never claims a fixed 30 minutes`() {
        for ((minutes, words) in listOf(1 to "1 minute", 90 to "1 hour 30 minutes", 1440 to "1 day", 4320 to "3 days")) {
            val text = SettingsHelp.laterReplies(minutes).text
            assertTrue("$minutes: $words", text.contains("within $words are spoken") && text.contains("replies arriving after $words"))
            assertFalse("$minutes must not say 30 minutes as the window", text.contains("within 30 minutes") || text.contains("after 30 minutes"))
        }
        assertTrue(SettingsHelp.laterReplies(30).text.contains("within 30 minutes are spoken"))
        assertEquals("the title does not change with the duration", SettingsHelp.laterReplies(1).title, SettingsHelp.laterReplies(4320).title)
    }

    @Test
    fun `the later-reply help keeps every substantive warning`() {
        val text = SettingsHelp.laterReplies(4320).text
        for (needle in listOf("Off by default", "isn't restored from a backup", "never turns this on", "another Hermes app or the dashboard",
            "other conversations stay silent", "device of your latest voice request", "a recording started on this phone stops it first",
            "may play on the other device", "nothing keeps the two apart acoustically", "not a delivery guarantee",
            "Android closes the app", "can't be tied to the original request", "within 10 minutes", "no total time cuts a long reply", "3 seconds",
            "background relay's Stop", "changing it later doesn't shorten or extend", "1 minute to 3 days", "30 minutes by default")) {
            assertTrue("later-reply help keeps: $needle", text.contains(needle))
        }
    }

    @Test
    fun `routing and navigation help state defaults, device ownership and subordinate conditions`() {
        val routing = SettingsHelp.routing().text
        assertTrue(routing.contains("the default") && routing.contains("one setting on this phone for both devices") && routing.contains("nothing is sent"))
        val phone = SettingsHelp.phoneNavigation().text
        assertTrue(phone.contains("Off by default") && phone.contains("only while this app is open") && phone.contains("Only used while routing is on"))
        assertTrue(phone.contains("kept while routing is off"))
        val watch = SettingsHelp.watchNavigation().text
        for (needle in listOf("Off by default", "Saved on this phone", "has no switch of its own", "DELIVERED", "failed send doesn't count",
            "never move the Watch", "screen doesn't wake", "your choice stays", "after Stop", "Only used while routing is on",
            "kept while routing is off", "only while its app process runs")) {
            assertTrue("watch navigation help keeps: $needle", watch.contains(needle))
        }
        assertEquals("Open the routed conversation on Watch", SettingsHelp.watchNavigation().title)
        assertEquals("Open the routed conversation on this phone", SettingsHelp.phoneNavigation().title)
    }

    @Test
    fun `recipient help never reports how or whether it was tested, measured, reviewed or run`() {
        val banned = Regex("(?i)\\b(checked on|was checked|were checked|tested|untested|verified|unverified|measured|not yet on|emulator|unit test|test suite|self-verified|independent review|review pending|build \\d|agent|workflow)\\b")
        for (minutes in listOf(1, 30, 4320)) {
            for (topic in SettingsHelp.all(minutes)) {
                for (line in topic.paragraphs + topic.title) {
                    assertFalse("${topic.id}: '$line' reads as a verification or workflow note: ${banned.find(line)?.value}", banned.containsMatchIn(line))
                }
            }
        }
    }

    @Test
    fun `background, standby, wake and VAD help keep the mic and platform restrictions`() {
        val relay = SettingsHelp.backgroundRelay().text
        assertTrue(relay.contains("never listens or records") && relay.contains("force stop") && relay.contains("uses more battery"))
        val standby = SettingsHelp.standby().text
        for (needle in listOf("off by default", "not a Stop", "never cuts a recording", "ON-DEVICE", "non-exact", "not instant", "maker may still stop it",
            "Android lets a microphone start only from a visible app", "subordinate", "your choice is kept",
            "screen-off listening remains subject to Android restrictions", "may increase battery use")) {
            assertTrue("standby help keeps: $needle", standby.contains(needle, ignoreCase = true))
        }
        val wake = SettingsHelp.wakePhrase().text
        assertTrue(wake.contains("only one of them takes each phrase") && wake.contains("microphone permission") && wake.contains("speech recognizer"))
        assertTrue(SettingsHelp.wakePatterns().text.contains("* stands for any characters inside one word"))
        assertTrue(SettingsHelp.wakePatterns().text.contains("An empty list restores the default"))
        val vad = SettingsHelp.vad().text
        assertTrue(vad.contains("not by understanding speech") && vad.contains("loud changing sound") && vad.contains("very soft speech"))
        assertTrue(SettingsHelp.spokenReplies().text.contains("always play"))
        assertTrue(SettingsHelp.watch().text.contains("no time limit"))
    }
}
