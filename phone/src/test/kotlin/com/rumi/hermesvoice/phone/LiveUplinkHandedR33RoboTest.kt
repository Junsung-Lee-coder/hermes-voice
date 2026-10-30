package com.rumi.hermesvoice.phone

import android.Manifest
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * R33: the input audio time an ended call's pinned ADM buffer callback handed to WebRTC's send path while started becomes the [ms] of
 * its live_rtp_sent observation, beside the packets sent; unknown numbers stay absent, a known zero stays zero. The real controller;
 * a synthetic media boundary reports the numbers (the WebRTC adapter's own counting runs only on a device), and the PCM16 duration
 * the adapter adds per handed frame is checked directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveUplinkHandedR33RoboTest : PhoneRoboBase() {
    private var counters: LiveMediaCounters? = null
    private val events = java.util.Collections.synchronizedList(ArrayList<String>())
    private var providerStarts = true
    private inner class Media : LiveTransport {
        var mic: ((Boolean) -> Unit)? = null
        override fun observeMicrophone(listener: (Boolean) -> Unit) { mic = listener }
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) {
            mic?.invoke(true); events += "connect"; if (providerStarts) event("{\"type\":\"session.started\",\"session\":{\"id\":\"uplink\"}}")
        }
        override fun send(json: String) = true
        override fun mute(muted: Boolean) {}
        override fun output(enabled: Boolean) {}
        override fun silence() { events += "silence" }
        override fun close() { events += "close" }
        override suspend fun counters(): LiveMediaCounters? { events += "counters"; return counters }
    }
    @Before fun setUp() {
        baseSetUp(); shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = true, watchLiveEnabled = true))
        app.live.routeFactory = { object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.transportFactory = { Media() }
        app.live.admission = { app.live.admitted(it) }
        conversation("alpha")
    }
    @After fun tearDown() { app.live.end(); settle(); baseTearDown() }
    private fun live(wire: String) = app.diagnostics.log.snapshot().filter { it.code.wire == wire }
    private fun call() { assertTrue(app.live.start()); assertTrue(await { app.live.state.value.ready }); app.live.end(); assertTrue(await { events.contains("close") }); settle() }

    @Test fun U01_theInputAudioHandedToTheSendPathIsObservedBesideThePacketsSent() {
        counters = LiveMediaCounters(inputChunks = 332, inputSpeechMs = 2_260, playoutChunks = 4_993, playoutSpeechMs = 2_360, rtpSent = 618, rtpReceived = 2_543, inputHandedMs = 13_090)
        call()
        live("live_rtp_sent").single().let { assertEquals(618, it.n); assertEquals(13_090L, it.ms); assertEquals("phone", it.origin?.wire); assertNull(it.ref) }
        live("live_rtp_received").single().let { assertEquals(2_543, it.n); assertNull("received keeps no duration", it.ms) }
        live("live_input_audio").single().let { assertEquals("the nonzero-chunk meter is unchanged", 332, it.n); assertEquals(2_260L, it.ms) }
    }

    @Test fun U02_handedAudioIsObservedEvenWhenTheStatsExposeNoPacketCountWhichStaysAbsent() {
        counters = LiveMediaCounters(inputChunks = 0, inputSpeechMs = 0, playoutChunks = 0, playoutSpeechMs = 0, rtpSent = null, rtpReceived = null, inputHandedMs = 5_000)
        call()
        live("live_rtp_sent").single().let { assertNull("an unexposed packet count is absent, not zero", it.n); assertEquals(5_000L, it.ms) }
        assertTrue(live("live_rtp_received").isEmpty())
    }

    @Test fun U03_aKnownZeroHandedTimeIsAZeroAndMediaWithoutTheCallbackKeepsTheEarlierShape() {
        counters = LiveMediaCounters(inputChunks = 0, inputSpeechMs = 0, playoutChunks = 0, playoutSpeechMs = 0, rtpSent = null, rtpReceived = null, inputHandedMs = 0)
        call()
        live("live_rtp_sent").single().let { assertNull(it.n); assertEquals("handed nothing while started is a true zero", 0L, it.ms) }
        app.diagnostics.log.clear(); events.clear()
        counters = LiveMediaCounters(inputChunks = 1, inputSpeechMs = 0, playoutChunks = 0, playoutSpeechMs = 0, rtpSent = 1_300, rtpReceived = 650)
        call()
        live("live_rtp_sent").single().let { assertEquals(1_300, it.n); assertNull("no callback: the handed time is unknown", it.ms) }
    }

    @Test fun U05_aCallThatNeverStartedKeepsTheEarlierShapeWithoutAHandedTime() {
        providerStarts = false
        fun unstarted() {
            assertTrue(app.live.start()); assertTrue(await { events.contains("connect") }); assertFalse(app.live.state.value.started)
            app.live.end(); assertTrue(await { events.contains("close") }); settle()
        }
        counters = LiveMediaCounters(inputChunks = 0, inputSpeechMs = 0, playoutChunks = 0, playoutSpeechMs = 0, rtpSent = null, rtpReceived = null, inputHandedMs = 0)
        unstarted()
        assertTrue("never started and no packet count: no live_rtp_sent, as before", live("live_rtp_sent").isEmpty())
        assertEquals(1, live("live_input_audio").size)
        app.diagnostics.log.clear(); events.clear()
        counters = LiveMediaCounters(inputChunks = 0, inputSpeechMs = 0, playoutChunks = 0, playoutSpeechMs = 0, rtpSent = 13, rtpReceived = null, inputHandedMs = 1_550)
        unstarted()
        live("live_rtp_sent").single().let { assertEquals(13, it.n); assertNull("before Started no handed time is observed", it.ms) }
    }

    @Test fun U04_eachHandedFrameAddsItsOwnPcm16Duration() {
        assertEquals("the pinned 10 ms 48 kHz mono callback buffer", 10_000L, pcm16Micros(960, 1, 48_000))
        assertEquals(10_000L, pcm16Micros(1_920, 2, 48_000))
        assertEquals(10_000L, pcm16Micros(320, 1, 16_000))
        assertEquals(0L, pcm16Micros(0, 1, 48_000)); assertEquals(0L, pcm16Micros(960, 0, 48_000)); assertEquals(0L, pcm16Micros(960, 1, 0))
    }
}
