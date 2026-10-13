package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Amendment L: sending a voice request from the Watch is completely independent of the Phone's headset mode. Whatever the setting,
 * the connection state, the Watch's confirmation or the Phone's ability to play to the headset, the request is transcribed and
 * delivered exactly once; only the reply AUDIO is steered. Through the production core wiring.
 */
class WatchInputIndependenceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rig: PrivateAudioRig? = null

    @After fun tearDown() {
        rig?.close()
        scope.cancel()
    }

    private fun transcribes(r: PrivateAudioRig) = r.fake.timeline.count { it.startsWith("transcribe:") }

    private fun check(on: Boolean, outputs: List<com.rumi.hermesvoice.core.headset.AudioEndpoint>, withPhoneSink: Boolean = true, name: String) {
        val r = PrivateAudioRig(scope, FakeDevices(outputs), headsetOn = on, withPhoneSink = withPhoneSink).also { rig?.close(); rig = it }
        val stored = r.existing("work", "Done.")
        val outcome = r.watchTurn("w-$name", stored)
        assertEquals("$name: the request was transcribed and delivered once", 1, transcribes(r))
        assertTrue("$name: delivered, not refused: $outcome", outcome !is VoiceTurnOutcome.NotAdmitted && outcome !is VoiceTurnOutcome.RoutingRejected &&
            outcome !is VoiceTurnOutcome.NoSpeech && outcome !is VoiceTurnOutcome.Stopped)
        assertEquals("$name: one prompt reached Hermes for the session", 1, r.fake.rawPrompts.count { it.first == stored })
        rig?.close()
        rig = null
    }

    @Test
    fun `the Watch request is delivered with the setting on and a headset connected`() =
        check(true, listOf(Gear.speaker, Gear.a2dp), name = "on-connected")

    @Test
    fun `the Watch request is delivered with the setting on and no headset`() =
        check(true, listOf(Gear.speaker), name = "on-none")

    @Test
    fun `the Watch request is delivered with the setting off and a headset connected`() =
        check(false, listOf(Gear.speaker, Gear.a2dp), name = "off-connected")

    @Test
    fun `the Watch request is delivered even when the Phone cannot play to the headset - only the audio is withheld`() {
        val r = PrivateAudioRig(scope, FakeDevices(listOf(Gear.speaker, Gear.a2dp)), headsetOn = true, withPhoneSink = false).also { rig = it }
        val stored = r.existing("work", "Done.")
        r.watchTurn("w-nosink", stored)
        assertEquals(1, transcribes(r))
        assertEquals(1, r.fake.rawPrompts.count { it.first == stored })
        assertEquals("the audio never reached the Watch speaker", 0, r.watch.offered.size)
    }

    @Test
    fun `the Watch request is delivered while the Watch has not confirmed the private state - nothing waits for the receipt`() {
        val r = PrivateAudioRig(scope, FakeDevices(listOf(Gear.speaker, Gear.a2dp)), headsetOn = true).also { rig = it }
        assertTrue("no receipt has been given", r.watchPrivate.isNotEmpty())
        val stored = r.existing("work", "Done.")
        r.watchTurn("w-unconfirmed", stored)
        assertEquals(1, transcribes(r))
        assertEquals(listOf("FINAL:Done."), r.phoneFinals())
    }

    @Test
    fun `the reply audio is the only thing intercepted - text and session identity stay on the Watch request`() {
        val r = PrivateAudioRig(scope, FakeDevices(listOf(Gear.speaker, Gear.a2dp)), headsetOn = true).also { rig = it }
        val stored = r.existing("work", "Done.")
        r.watchTurn("w-identity", stored)
        assertEquals(0, r.watch.offered.size)
        assertEquals(1, transcribes(r))
        assertTrue("the request kept its Watch origin and its conversation", r.fake.rawPrompts.any { it.first == stored })
    }
}
