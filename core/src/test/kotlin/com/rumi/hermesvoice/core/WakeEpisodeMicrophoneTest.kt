package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.voice.AudioOwnership
import com.rumi.hermesvoice.core.voice.EpisodeMicrophone
import com.rumi.hermesvoice.core.voice.MicrophoneClaim
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeClaimPort
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B31-N4: a wake phrase that is heard owns the Phone's microphone ([WakeDevicePort.holdMicrophone])
 * before its accepted cue, through the microphone-handoff pause and a held "Both" claim, until its
 * recording or request takes the hold over, and gives it back on every other end. A later reply
 * holding the speaker closes an open window but never ends that episode ([WakeDeviceController.onPlaybackBusy]).
 */
class WakeEpisodeMicrophoneTest {
    private class Device(mode: WakeLocation, arbitrated: Boolean = false) :
        WakeRecognizerPort, WakeTimerPort, WakeDevicePort, WakeClaimPort {
        val calls = mutableListOf<String>()
        val ownership = AudioOwnership()
        private val episode = EpisodeMicrophone(ownership, VoiceOrigin.PHONE)
        var captureResult = true
        var captured: MicrophoneClaim? = null
        var sent: MicrophoneClaim? = null
        private var ids = 0
        val controller = WakeDeviceController(VoiceOrigin.PHONE, this, this, this, { 0L },
            WatchSettings(mode, "루미", revision = 1), claims = if (arbitrated) this else null)

        val claimed: Boolean get() = ownership.microphoneClaimed(VoiceOrigin.PHONE)

        override fun available() = true
        override fun start(generation: Long): Boolean { calls += "listen"; return true }
        override fun release() { calls += "release" }
        override fun schedule(delayMs: Long) {}
        override fun cancel() {}
        override fun windowChanged(open: Boolean) { calls += "window:$open" }
        override fun scheduleHandoff(delayMs: Long) { calls += "handoff_in" }
        override fun cancelHandoff() {}
        override fun startRequestCapture(silenceMs: Long): Boolean = startRequestCapture(silenceMs, null)
        override fun startRequestCapture(silenceMs: Long, claimId: String?): Boolean {
            calls += "capture:held=$claimed"
            captured = episode.take()
            if (!captureResult) captured?.release()
            return captureResult
        }
        override fun cancelRequestCapture(reason: String) { calls += "cancel_capture:$reason" }
        override fun sendRecognized(request: String) = sendRecognized(request, null)
        override fun sendRecognized(request: String, claimId: String?) {
            calls += "send:held=$claimed"
            sent = episode.take()
        }
        override fun closed(reason: String) { calls += "closed:$reason" }
        override fun wakeAccepted() { calls += "accepted:held=$claimed" }
        override fun holdMicrophone(held: Boolean) {
            calls += "hold:$held"
            episode.hold(held)
        }
        override fun armInputs() = WakeArmInputs(enabled = true, resumed = true, interactive = true, ambient = false, permission = true,
            microphoneMuted = false, talkIdle = true, phoneReachable = true, nowMs = 0, cooldownUntilMs = 0, generation = 0,
            lastArmedGeneration = null)

        override fun newClaimId(): String = "c-${++ids}"
        override fun epoch(): Long = 0
        override fun request(claimId: String, settingsRevision: Long, generation: Long, epoch: Long) { calls += "claim:held=$claimed" }
        override fun renew(claimId: String) {}
        override fun release(claimId: String) { calls += "claim_release" }
        override fun scheduleTimer(delayMs: Long) {}
        override fun cancelTimer() {}

        fun phrase(text: String, final: Boolean = true) = controller.onResults(controller.generation, listOf(text), final)
        fun holds() = calls.filter { it.startsWith("hold:") }
    }

    @Test
    fun `a phrase-only wake owns the microphone before its cue, through the handoff pause, and hands it to the recording`() {
        val phone = Device(WakeLocation.PHONE)
        phone.controller.onResume()
        phone.phrase("루미")
        assertTrue("held before the accepted cue", phone.calls.indexOf("hold:true") in 0 until phone.calls.indexOf("accepted:held=true"))
        assertTrue(phone.calls.contains("handoff_in"))
        assertTrue("held during the 300 ms handoff pause", phone.claimed)
        assertTrue(phone.controller.onHandoffDue(captureIdle = true))
        assertTrue("the recording got the hold while it was still held", phone.calls.contains("capture:held=true"))
        assertNotNull(phone.captured)
        assertEquals(listOf("hold:true", "hold:false"), phone.holds())
        assertTrue("the recording keeps it", phone.claimed)
        phone.captured!!.release()
        assertFalse(phone.claimed)
    }

    @Test
    fun `a same-breath request is sent with the hold`() {
        val phone = Device(WakeLocation.PHONE)
        phone.controller.onResume()
        phone.phrase("루미 불 꺼")
        assertTrue(phone.calls.indexOf("hold:true") < phone.calls.indexOf("accepted:held=true"))
        assertTrue(phone.calls.contains("send:held=true"))
        assertNotNull(phone.sent)
        assertTrue("the request keeps it until the orchestrator takes it over", phone.claimed)
        phone.sent!!.release()
        assertFalse(phone.claimed)
    }

    @Test
    fun `in Both the hold starts with the claim, lasts while it is held, and is given back when the claim fails`() {
        val phone = Device(WakeLocation.BOTH, arbitrated = true)
        phone.controller.onResume()
        phone.phrase("루미 내일", final = false)
        assertTrue("held before the claim is asked", phone.calls.indexOf("hold:true") < phone.calls.indexOf("claim:held=true"))
        phone.phrase("루미")
        assertTrue("held while waiting for the claim", phone.claimed)
        assertFalse(phone.calls.any { it.startsWith("accepted") })
        phone.controller.onClaimVerdict("c-1", ClaimVerdict.GRANTED)
        assertTrue(phone.calls.contains("accepted:held=true") && phone.calls.contains("handoff_in"))
        assertTrue(phone.controller.onHandoffDue(captureIdle = true))
        phone.captured!!.release()
        // Next episode: the claim is refused -> everything of it ends, and the hold goes back.
        phone.controller.onRequestCaptureEnded(sent = true)
        phone.controller.onResume()
        phone.phrase("루미")
        assertTrue(phone.claimed)
        phone.controller.onClaimVerdict("c-2", ClaimVerdict.HELD_BY_OTHER)
        assertFalse(phone.claimed)
        assertEquals("hold:false", phone.holds().last())
    }

    @Test
    fun `a later reply on the speaker closes an open window but never an accepted episode`() {
        val phone = Device(WakeLocation.PHONE)
        phone.controller.onResume()
        assertTrue(phone.controller.listening)
        phone.controller.onPlaybackBusy()
        assertFalse("the window stops listening", phone.controller.listening)
        assertFalse(phone.claimed)
        phone.controller.onResume()
        phone.phrase("루미")
        phone.controller.onPlaybackBusy()
        assertTrue("the episode goes on", phone.claimed)
        assertTrue(phone.controller.onHandoffDue(captureIdle = true))
        assertTrue(phone.calls.contains("capture:held=true"))
        phone.captured!!.release()
    }

    @Test
    fun `every other end of an episode gives the hold back - busy, pause, screen off, a refused or failed capture`() {
        val ends = mapOf<String, (Device) -> Unit>(
            "busy" to { it.controller.onBusy() },
            "pause" to { it.controller.onPause() },
            "screen_off" to { it.controller.onScreenOff() },
            "not_idle" to { it.controller.onHandoffDue(captureIdle = false) },
            "capture_failed" to { it.captureResult = false; it.controller.onHandoffDue(captureIdle = true) },
        )
        for ((name, end) in ends) {
            val phone = Device(WakeLocation.PHONE)
            phone.controller.onResume()
            phone.phrase("루미")
            assertTrue(name, phone.claimed)
            end(phone)
            assertFalse("$name: given back", phone.claimed)
            assertEquals(name, "hold:false", phone.holds().last())
        }
    }

    @Test
    fun `a mode change gives the hold back from a phrase only heard, and leaves an accepted episode its hold until it ends`() {
        // Only heard: a claim asked in Both, not granted. Another mode ends it and the hold goes back.
        val heard = Device(WakeLocation.BOTH, arbitrated = true)
        heard.controller.onResume()
        heard.phrase("루미")
        assertTrue(heard.claimed)
        heard.controller.onSettings(WatchSettings(WakeLocation.PHONE, "루미", revision = 2))
        assertFalse("mode: given back", heard.claimed)
        assertEquals("hold:false", heard.holds().last())

        // Accepted: the handoff pause and the recording keep the hold under their original settings.
        val accepted = Device(WakeLocation.PHONE)
        accepted.controller.onResume()
        accepted.phrase("루미")
        accepted.controller.onSettings(WatchSettings(WakeLocation.BOTH, "루미", revision = 2))
        assertTrue("held through the mode change", accepted.claimed)
        assertTrue(accepted.controller.onHandoffDue(captureIdle = true))
        assertNotNull(accepted.captured)
        accepted.captured!!.release()
        assertFalse(accepted.claimed)
    }
}
