package com.rumi.hermesvoice.watch

import com.rumi.hermesvoice.core.settings.SettingsHelp
import org.junit.Assert.*
import org.junit.Test

/**
 * Since the owner's UI contract of 2026-10-27 the Watch shows only its conversation list and the history: the Watch Live controls and
 * their help are gone from the Watch (WatchMinimalUiRoboTest checks the real screens). Watch Live is set, and explained, on the Phone:
 * this checks the relay guarantees the Phone's "Watch Live" help states. Text contract, not a media behavioral witness.
 */
class CallerClosureWatchHelpRoboTest {
    @Test fun shippedWatchHelpExplainsIndependentInputPrivateOutputAndRecovery(){
        val help=SettingsHelp.live("Watch")
        assertEquals("watch_live_enabled",help.id)
        val text=help.text
        assertFalse("obsolete blanket refusal is shipped to recipients",text.contains("Watch Live refuses"))
        val guarantees=listOf("real Watch microphone/speaker audio","Phone Wi-Fi or cellular","Watch does not need its own internet",
            "never uses the Phone microphone or input permission","No backend key is stored on Watch","Watch microphone and work continue",
            "never enables a public speaker","without direct-Watch, Phone-mic or TTS fallback")
        fun relayGuide(value:String)=guarantees.all{value.contains(it)}
        assertTrue("the relay's input, private output and loss behaviour must be explained",relayGuide(text))
        for(term in guarantees)assertFalse("help loses a required relay guarantee: $term",relayGuide(text.replace(term,"")))
        assertTrue("changing the setting never starts the microphone",text.contains("Changing the setting never starts the microphone"))
    }
}
