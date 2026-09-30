package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.CaptureEnd
import com.rumi.hermesvoice.core.audio.PcmCaptureLoop
import com.rumi.hermesvoice.core.audio.PcmSource
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import com.rumi.hermesvoice.core.settings.VadSilence
import com.rumi.hermesvoice.core.watchlink.CaptureCoordinator
import com.rumi.hermesvoice.core.watchlink.CapturePort
import com.rumi.hermesvoice.core.watchlink.CaptureStop
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recording loop both devices' recorders run (Watch reads 100 ms, Phone 160 ms), driven by a
 * scripted microphone, through the capture lifecycle to upload or discard: the configured trailing
 * silence ends a hands-free request, nothing is sent without speech or at the storage bound, and
 * push-to-talk never ends by itself.
 */
class CaptureLoopTest {
    /** A microphone that plays [script] and then the room's background forever. */
    private class Mic(script: ShortArray, private val room: Signals, private val roomRms: Double, private val failAfter: Int = -1) : PcmSource {
        private val bytes = Signals.bytes(script)
        private var offset = 0
        var reads = 0
        var onRead: (() -> Unit)? = null

        override fun read(buffer: ByteArray): Int {
            reads++
            onRead?.invoke()
            if (failAfter in 0 until reads) return 0
            if (offset >= bytes.size) {
                val noise = Signals.bytes(room.noise(buffer.size / 32L, roomRms))
                System.arraycopy(noise, 0, buffer, 0, minOf(noise.size, buffer.size))
                return minOf(noise.size, buffer.size)
            }
            val n = minOf(buffer.size, bytes.size - offset)
            System.arraycopy(bytes, offset, buffer, 0, n)
            offset += n
            return n
        }
    }

    private class Events : PcmCaptureLoop.Listener {
        val calls = mutableListOf<String>()
        override fun onLive() { calls += "live" }
        override fun onCalibrated() { calls += "calibrated" }
        override fun onEnd(reason: CaptureEnd) { calls += "end:$reason" }
    }

    private class Port(private val loop: () -> PcmCaptureLoop) : CapturePort {
        val calls = mutableListOf<String>()
        override fun stopRecorder(captureId: String, reason: CaptureStop): ByteArray? {
            calls += "stop:$reason"
            return loop().run { stop(); PcmCaptureLoop.wav(pcm()) }
        }
        override fun haptic(event: HapticEvent) { calls += "haptic:$event" }
        override fun cue(line: String) { calls += "cue:$line" }
        override fun upload(captureId: String, trigger: TurnTrigger, wav: ByteArray) { calls += "upload:$trigger" }
        override fun uploadRecognized(turnId: String, text: String) { calls += "recognized" }
        override fun discard(message: String) { calls += "discard:$message" }
    }

    /** One hands-free capture as the adapters wire it: loop events drive the coordinator. */
    private fun handsFree(mic: Mic, silenceSeconds: Double, chunkBytes: Int, limitBytes: Long = Long.MAX_VALUE): Triple<PcmCaptureLoop, Port, Events> {
        lateinit var loop: PcmCaptureLoop
        val port = Port { loop }
        val capture = CaptureCoordinator(port)
        val events = Events()
        capture.begin("w1", TurnTrigger.WAKE_PHRASE)
        loop = PcmCaptureLoop(mic, limitBytes, SilenceEndpoint.forSilenceSeconds(silenceSeconds), object : PcmCaptureLoop.Listener {
            override fun onLive() { events.onLive(); capture.onLive("w1") }
            override fun onCalibrated() { events.onCalibrated(); capture.onCalibrated("w1") }
            override fun onEnd(reason: CaptureEnd) { events.onEnd(reason); capture.stop("w1", CaptureStop.of(reason)) }
        }, chunkBytes = chunkBytes, retryDelay = {})
        loop.run()
        return Triple(loop, port, events)
    }

    @Test
    fun `phone and watch recorders end a hands-free request after the configured silence and send it`() {
        for ((device, chunkBytes) in listOf("watch" to 3_200, "phone" to 5_120)) {
            for (seconds in listOf(0.5, 2.0, 5.0, 10.0)) {
                val room = Signals(16_000, seed = 29)
                val mic = Mic(room.over(room.concat(room.silence(400), room.speech(2_500, 2_000.0)), 50.0), room, 50.0)
                val (loop, port, events) = handsFree(mic, seconds, chunkBytes)
                val stats = loop.stats()
                assertEquals("$device $seconds s", listOf("live", "calibrated", "end:SILENCE"), events.calls)
                assertEquals("$device $seconds s", VadSilence.millis(seconds), stats.silenceMs)
                assertEquals("$device $seconds s", VadSilence.millis(seconds), stats.endMs - stats.speechEndMs)
                val chunkMs = chunkBytes / 32L
                assertTrue("$device $seconds s: stops within one read", stats.pcmBytes / 32L - stats.endMs in 0 until chunkMs)
                assertEquals("$device $seconds s", listOf("cue:Speak now…", "haptic:RECORDING_START", "stop:SILENCE",
                    "haptic:RECORDING_END", "upload:WAKE_PHRASE"), port.calls)
            }
        }
    }

    @Test
    fun `a request that never starts is not sent, and neither is one that hits the storage bound`() {
        for (chunkBytes in listOf(3_200, 5_120)) {
            val room = Signals(16_000, seed = 31)
            val (_, quietPort, quietEvents) = handsFree(Mic(room.noise(400, 300.0), room, 300.0), 2.0, chunkBytes)
            assertEquals(listOf("live", "calibrated", "end:NO_SPEECH"), quietEvents.calls)
            assertFalse(quietPort.calls.any { it.startsWith("upload") })
            assertTrue(quietPort.calls.contains("discard:Didn't hear a request"))

            val talker = Mic(room.over(room.concat(room.silence(400), room.speech(30_000, 2_000.0)), 50.0), room, 50.0)
            val (_, boundPort, boundEvents) = handsFree(talker, 2.0, chunkBytes, limitBytes = 16_000L * 2 * 10)
            assertEquals("the bound is storage, reported once", "end:LIMIT", boundEvents.calls.last())
            assertFalse("never a truncated request", boundPort.calls.any { it.startsWith("upload") })
            assertTrue(boundPort.calls.contains("discard:Recording too long; nothing was sent"))
        }
    }

    @Test
    fun `a microphone that stops delivering audio ends the capture as a failure`() {
        val room = Signals(16_000, seed = 37)
        val (_, port, events) = handsFree(Mic(room.silence(0), room, 50.0, failAfter = 3), 2.0, 3_200)
        assertEquals("end:MIC_ERROR", events.calls.last())
        assertTrue(port.calls.contains("discard:Microphone unavailable"))
        assertFalse(port.calls.any { it.startsWith("upload") })
    }

    @Test
    fun `push-to-talk has no endpoint, so pauses and long silence never end or send it`() {
        val room = Signals(16_000, seed = 41)
        val mic = Mic(room.over(room.concat(room.speech(1_000, 2_000.0), room.silence(12_000), room.speech(1_000, 2_000.0)), 50.0), room, 50.0)
        val events = Events()
        val loop = PcmCaptureLoop(mic, Long.MAX_VALUE, null, events, chunkBytes = 5_120, retryDelay = {})
        mic.onRead = { if (mic.reads > 200) loop.stop() }
        loop.run()
        assertEquals("only the user's tap ends it", listOf("live"), events.calls)
        assertTrue("everything read until the tap is kept", loop.stats().pcmBytes >= 199 * 5_120)
    }
}
