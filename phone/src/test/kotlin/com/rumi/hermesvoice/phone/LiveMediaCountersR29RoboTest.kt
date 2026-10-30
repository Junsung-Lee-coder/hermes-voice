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
 * R29: an ended call's own media numbers ([LiveMediaCounters]) become its fixed-number observations, read once before its media
 * closes; numbers the media does not expose are absent, never zero. The real controller; a synthetic media boundary reports the
 * numbers (the WebRTC adapter's own counting runs only on a device).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveMediaCountersR29RoboTest : PhoneRoboBase() {
    private var counters: LiveMediaCounters? = null
    private val events = java.util.Collections.synchronizedList(ArrayList<String>())
    private inner class Media : LiveTransport {
        var mic: ((Boolean) -> Unit)? = null
        override fun observeMicrophone(listener: (Boolean) -> Unit) { mic = listener }
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) { mic?.invoke(true); event("{\"type\":\"session.started\",\"session\":{\"id\":\"counters\"}}") }
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

    @Test fun anEndedCallsMediaNumbersAreObservedOnceBeforeItsMediaCloses() {
        counters = LiveMediaCounters(inputChunks = 1234, inputSpeechMs = 2_340, playoutChunks = 567, playoutSpeechMs = 4_560, rtpSent = 1300, rtpReceived = 650)
        call()
        assertEquals("silenced first, numbers read, then closed", listOf("silence", "counters", "close"), events.toList())
        live("live_input_audio").single().let { assertEquals(1234, it.n); assertEquals(2_340L, it.ms); assertEquals("phone", it.origin?.wire); assertNull(it.ref) }
        live("live_playback").single().let { assertEquals(567, it.n); assertEquals(4_560L, it.ms) }
        assertEquals(1300, live("live_rtp_sent").single().n); assertEquals(650, live("live_rtp_received").single().n)
    }

    @Test fun rtpNumbersTheMediaDoesNotExposeAreAbsentNotZeroAndHugeCountsAreBounded() {
        counters = LiveMediaCounters(inputChunks = 5_000_000_000L, inputSpeechMs = 0, playoutChunks = 0, playoutSpeechMs = 0, rtpSent = null, rtpReceived = null)
        call()
        assertEquals(1_000_000, live("live_input_audio").single().n); assertEquals(0L, live("live_input_audio").single().ms)
        assertEquals(0, live("live_playback").single().n)
        assertTrue(live("live_rtp_sent").isEmpty()); assertTrue(live("live_rtp_received").isEmpty())
    }

    @Test fun mediaWithoutNumbersAddsNoMediaObservation() {
        counters = null
        call()
        assertTrue(live("live_input_audio").isEmpty() && live("live_playback").isEmpty())
        assertEquals(1, live("live_ended").size)
    }
}
