package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeBlock
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeHandoff
import com.rumi.hermesvoice.core.wake.WakeHandoffGate
import com.rumi.hermesvoice.core.wake.WakeHostPort
import com.rumi.hermesvoice.core.wake.WakeOutcome
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import com.rumi.hermesvoice.core.wake.WakeWindowCoordinator
import com.rumi.hermesvoice.core.settings.WakePhrasePatterns
import com.rumi.hermesvoice.core.watchlink.CaptureCoordinator
import com.rumi.hermesvoice.core.watchlink.CapturePort
import com.rumi.hermesvoice.core.watchlink.CaptureStop
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.RotaryScrollDriver
import com.rumi.hermesvoice.core.watchlink.ScrollHapticGate
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour of the Watch adapters' pure cores: capture lifecycle, wake window, mic handoff and bezel scrolling. */
class WatchAdaptersTest {
    private class FakeCapturePort(var wav: ByteArray? = ByteArray(1_000)) : CapturePort {
        val calls = mutableListOf<String>()
        override fun stopRecorder(captureId: String, reason: CaptureStop): ByteArray? { calls += "stop:$captureId:$reason"; return wav }
        override fun haptic(event: HapticEvent) { calls += "haptic:$event" }
        override fun cue(line: String) { calls += "cue:$line" }
        override fun upload(captureId: String, trigger: TurnTrigger, wav: ByteArray) { calls += "upload:$captureId:$trigger" }
        override fun uploadRecognized(turnId: String, text: String) { calls += "recognized:$turnId:$text" }
        override fun discard(message: String) { calls += "discard:$message" }
    }

    @Test
    fun `push-to-talk starts the haptic on real audio and ends exactly once, whoever stops it first`() {
        val port = FakeCapturePort()
        val capture = CaptureCoordinator(port)
        assertTrue(capture.begin("c1", TurnTrigger.PUSH_TO_TALK))
        assertFalse("one capture at a time", capture.begin("c2", TurnTrigger.PUSH_TO_TALK))
        assertEquals(emptyList<String>(), port.calls)
        capture.onLive("c1")
        capture.onLive("c1")
        assertTrue(capture.tap())
        assertFalse("a late auto-end after the tap does nothing", capture.stop("c1", CaptureStop.SILENCE))
        assertFalse(capture.lifecycle())
        capture.onLive("c1")
        assertEquals(listOf("haptic:RECORDING_START", "stop:c1:TAP_SEND", "haptic:RECORDING_END", "upload:c1:PUSH_TO_TALK"), port.calls)
        assertNull(capture.activeId)
    }

    @Test
    fun `stale callbacks from an earlier capture cannot touch the current one`() {
        val port = FakeCapturePort()
        val capture = CaptureCoordinator(port)
        capture.begin("old", TurnTrigger.PUSH_TO_TALK)
        capture.lifecycle()
        capture.begin("new", TurnTrigger.PUSH_TO_TALK)
        port.calls.clear()
        capture.onLive("old")
        assertFalse(capture.stop("old", CaptureStop.MIC_ERROR))
        assertEquals(emptyList<String>(), port.calls)
        assertEquals("new", capture.activeId)
        // The next recording's stop is reported with its own reason, not the stale callback's.
        capture.onLive("new")
        assertTrue(capture.tap())
        assertEquals(listOf("haptic:RECORDING_START", "stop:new:TAP_SEND", "haptic:RECORDING_END", "upload:new:PUSH_TO_TALK"), port.calls)
    }

    @Test
    fun `each end reason sends or explains, and never buzzes before a real start`() {
        val cases = mapOf(
            CaptureStop.TAP_SEND to "upload:c:WAKE_PHRASE",
            CaptureStop.SILENCE to "upload:c:WAKE_PHRASE",
            CaptureStop.NO_SPEECH to "discard:Didn't hear a request",
            CaptureStop.SIZE_LIMIT to "discard:Recording too long for the watch link; nothing was sent",
            CaptureStop.MIC_ERROR to "discard:Microphone unavailable",
            CaptureStop.LIFECYCLE to "discard:Cancelled",
        )
        for ((reason, outcome) in cases) {
            val port = FakeCapturePort()
            val capture = CaptureCoordinator(port)
            capture.begin("c", TurnTrigger.WAKE_PHRASE)
            capture.onLive("c")
            assertEquals("wake cues only after calibration", emptyList<String>(), port.calls)
            capture.onCalibrated("c")
            assertTrue(capture.stop("c", reason))
            assertEquals("$reason", listOf("cue:Speak now…", "haptic:RECORDING_START", "stop:c:$reason", "haptic:RECORDING_END", outcome), port.calls)
        }
        val failed = FakeCapturePort()
        CaptureCoordinator(failed).apply { begin("f", TurnTrigger.PUSH_TO_TALK); stop("f", CaptureStop.START_FAILED) }
        assertEquals(listOf("stop:f:START_FAILED", "discard:Microphone unavailable"), failed.calls)
        val empty = FakeCapturePort(wav = null)
        CaptureCoordinator(empty).apply { begin("e", TurnTrigger.PUSH_TO_TALK); onLive("e"); tap() }
        assertEquals(listOf("haptic:RECORDING_START", "stop:e:TAP_SEND", "haptic:RECORDING_END", "discard:Too short"), empty.calls)
    }

    @Test
    fun `a recognized request ends the wake capture with one end pulse and is sent as text`() {
        val port = FakeCapturePort()
        val capture = CaptureCoordinator(port)
        capture.sendRecognized("t1", "불 꺼")
        assertEquals(listOf("haptic:RECORDING_END", "recognized:t1:불 꺼"), port.calls)
    }

    @Test
    fun `the watch has no recording duration cap, only the frame-size storage bound`() {
        assertEquals(CaptureStop.SIZE_LIMIT, CaptureStop.valueOf("SIZE_LIMIT"))
        assertFalse(CaptureStop.values().any { it.name.contains("DURATION") || it.name.contains("MAX") })
    }

    private class FakeWake : WakeRecognizerPort, WakeTimerPort, WakeHostPort {
        val calls = mutableListOf<String>()
        var available = true
        var startOk = true
        override fun available() = available
        override fun start(generation: Long): Boolean { calls += "start:$generation"; return startOk }
        override fun release() { calls += "release" }
        override fun schedule(delayMs: Long) { calls += "timer:$delayMs" }
        override fun cancel() { calls += "timer-cancel" }
        override fun windowChanged(open: Boolean) { calls += "window:$open" }
        override fun handoff(generation: Long, handoff: WakeOutcome.Handoff) { calls += "handoff:$generation:${handoff.contract}:${handoff.request}" }
        override fun closed(reason: String) { calls += "closed:$reason" }
    }

    private val inputs = WakeArmInputs(enabled = true, resumed = true, interactive = true, ambient = false, permission = true,
        microphoneMuted = false, talkIdle = true, phoneReachable = true, nowMs = 0, cooldownUntilMs = 0, generation = 0,
        lastArmedGeneration = null)

    @Test
    fun `the recognizer is released before a handoff and a window opens once per generation`() {
        var now = 0L
        val fake = FakeWake()
        val wake = WakeWindowCoordinator(fake, fake, fake) { now }
        wake.newGeneration("resume")
        assertNull(wake.requestArm(inputs))
        assertEquals(WakeBlock.ALREADY_ARMED, wake.requestArm(inputs))
        now = 700
        wake.onResults(1, listOf("루미야"), final = true, patterns = WakePhrasePatterns.DEFAULT_PATTERNS)
        assertEquals(listOf("window:true", "timer:${WakeContract.WINDOW_MS}", "start:1", "timer-cancel", "release", "window:false",
            "handoff:1:SECOND_UTTERANCE:"), fake.calls)
        fake.calls.clear()
        wake.onResults(1, listOf("루미야"), final = true, patterns = WakePhrasePatterns.DEFAULT_PATTERNS)
        assertEquals("stale after resolution", emptyList<String>(), fake.calls)
        assertEquals(WakeBlock.ALREADY_ARMED, wake.requestArm(inputs))
        wake.newGeneration("screen_on")
        assertNull(wake.requestArm(inputs))
    }

    @Test
    fun `closing reasons are reported and an unavailable recognizer fails closed`() {
        var now = 0L
        val fake = FakeWake()
        val wake = WakeWindowCoordinator(fake, fake, fake) { now }
        wake.newGeneration("resume")
        wake.requestArm(inputs)
        now = 900
        wake.onResults(1, listOf("루미야 불 꺼"), final = false, patterns = WakePhrasePatterns.DEFAULT_PATTERNS)
        assertTrue(fake.calls.last().startsWith("timer:"))
        wake.onError(1, 2)
        assertEquals(listOf("timer-cancel", "release", "window:false", "closed:unfinished_request"), fake.calls.takeLast(4))
        fake.calls.clear()
        wake.close("pause")
        assertEquals("nothing open", emptyList<String>(), fake.calls)
        fake.available = false
        wake.newGeneration("resume")
        assertEquals(WakeBlock.UNAVAILABLE, wake.requestArm(inputs))
        assertEquals(listOf("closed:unavailable"), fake.calls)
        fake.available = true
        fake.startOk = false
        fake.calls.clear()
        wake.newGeneration("resume")
        wake.requestArm(inputs)
        assertEquals(listOf("window:true", "timer:${WakeContract.WINDOW_MS}", "start:3", "timer-cancel", "release", "window:false",
            "closed:start_failed"), fake.calls)
        assertEquals(WakeBlock.DISABLED, wake.requestArm(inputs.copy(enabled = false)))
    }

    @Test
    fun `a contradicted final closes with the retry notice and starts no recorder`() {
        var now = 0L
        val fake = FakeWake()
        val wake = WakeWindowCoordinator(fake, fake, fake) { now }
        wake.newGeneration("resume")
        wake.requestArm(inputs)
        now = 400
        wake.onResults(1, listOf("hermes turn on the"), final = false, patterns = "hermes")
        now = 900
        wake.onResults(1, listOf("her mess turn on the lights"), final = true, patterns = "hermes")
        assertEquals(listOf("timer-cancel", "release", "window:false", "closed:unfinished_request"), fake.calls.takeLast(4))
        assertFalse(fake.calls.any { it.startsWith("handoff") })
        fake.calls.clear()
        wake.newGeneration("resume")
        wake.requestArm(inputs)
        now = 1_500
        wake.onResults(2, listOf("I told hermes about it"), final = true, patterns = "hermes")
        assertEquals("ambient speech closes silently", "closed:not_matched", fake.calls.last())
    }

    @Test
    fun `the deadline timer resolves only its own window`() {
        var now = 0L
        val fake = FakeWake()
        val wake = WakeWindowCoordinator(fake, fake, fake) { now }
        wake.newGeneration("resume")
        wake.requestArm(inputs)
        now = 4_000
        wake.onTimer()
        assertFalse(fake.calls.contains("release"))
        now = WakeContract.WINDOW_MS
        wake.onTimer()
        assertEquals("closed:timeout", fake.calls.last())
    }

    @Test
    fun `the delayed microphone handoff is cancellable and tied to its generation`() {
        val gate = WakeHandoffGate()
        gate.schedule(4)
        assertTrue(gate.claim(4, currentGeneration = 4, resumed = true, captureIdle = true))
        assertFalse("claimed once", gate.claim(4, currentGeneration = 4, resumed = true, captureIdle = true))
        gate.schedule(5)
        gate.cancel()
        assertFalse("cancelled on pause, opt-out or busy", gate.claim(5, 5, resumed = true, captureIdle = true))
        gate.schedule(6)
        assertFalse("a newer generation started", gate.claim(6, currentGeneration = 7, resumed = true, captureIdle = true))
        gate.schedule(8)
        assertFalse(gate.claim(8, 8, resumed = false, captureIdle = true))
        gate.schedule(9)
        assertFalse(gate.claim(9, 9, resumed = true, captureIdle = false))
    }

    /** A list that can scroll between 0 and [max] pixels. */
    private class FakeList(val max: Float) {
        var position = 0f
        val consumed = mutableListOf<Float>()
        fun scrollBy(px: Float): Float {
            val next = (position + px).coerceIn(0f, max)
            val used = next - position
            position = next
            consumed += used
            return used
        }
    }

    @Test
    fun `bezel scrolling ticks only when the list moved and coalesces bursts without losing rotation`() = runBlocking {
        val list = FakeList(max = 300f)
        val driver = RotaryScrollDriver(ScrollHapticGate(minIntervalMs = 50))
        var now = 0L
        var ticks = 0
        assertTrue(driver.offer(100f))
        var burst = true
        driver.drain({ px ->
            if (burst) assertFalse("a burst arrives while draining", driver.offer(50f))
            burst = false
            now += 60
            list.scrollBy(px)
        }, { now }) { ticks++ }
        assertEquals(150f, list.position, 0.01f)
        assertEquals(2, ticks)
        assertTrue(driver.offer(500f))
        driver.drain({ px -> now += 60; list.scrollBy(px) }, { now }) { ticks++ }
        assertEquals(300f, list.position, 0.01f)
        assertEquals(3, ticks)
        assertTrue(driver.offer(40f))
        driver.drain({ px -> now += 60; list.scrollBy(px) }, { now }) { ticks++ }
        assertEquals("no tick at the end of the list", 3, ticks)
    }

    @Test
    fun `a cancelled scroll drops its pending rotation instead of jumping later`() = runBlocking {
        val list = FakeList(max = 1_000f)
        val driver = RotaryScrollDriver()
        driver.offer(100f)
        try {
            driver.drain({ px -> driver.offer(400f); throw CancellationException("touch drag took over") }, { 0L }) {}
        } catch (_: CancellationException) {
        }
        assertTrue(driver.offer(10f))
        driver.drain({ px -> list.scrollBy(px) }, { 1_000L }) {}
        assertEquals(10f, list.position, 0.01f)
    }
}
