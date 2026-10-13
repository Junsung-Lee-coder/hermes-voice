package com.rumi.hermesvoice.phone

import android.media.AudioDeviceInfo
import com.rumi.hermesvoice.core.FakeDevices
import com.rumi.hermesvoice.core.Gear
import com.rumi.hermesvoice.core.audio.CaptureEnd
import com.rumi.hermesvoice.core.audio.PcmCaptureLoop
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import com.rumi.hermesvoice.core.headset.AudioEndpoint
import com.rumi.hermesvoice.core.headset.CommsLink
import com.rumi.hermesvoice.core.headset.EndpointType
import com.rumi.hermesvoice.core.headset.HeadsetMicRoute
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.headset.HeadsetText
import com.rumi.hermesvoice.core.headset.LinkResult
import com.rumi.hermesvoice.core.headset.MicPlan
import com.rumi.hermesvoice.core.headset.MicReport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Phone's two recorders with a fake [RecordPort] (no microphone is opened): the headset input is REQUESTED as the preferred
 * device, the source shown to the user comes only from Android's readback of the running recorder (a setter's result is never
 * evidence), and the headset link / audio mode a capture took is given back on every way it ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneHeadsetRecorderTest {
    private class FakeRecord(
        var routed: Int?,
        val accepts: Boolean = true,
        override val initialized: Boolean = true,
        val starts: Boolean = true,
    ) : RecordPort {
        var preferred: Int? = null
        var stopped = 0
        var released = 0
        override fun setPreferredDevice(deviceId: Int): Boolean { preferred = deviceId; return accepts }
        override fun routedDeviceId(): Int? = routed
        override fun start(): Boolean = starts
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(5)
            for (i in offset until offset + length step 2) { buffer[i] = 0x10; buffer[i + 1] = 0x10 }
            return length
        }
        override fun stop() { stopped++ }
        override fun release() { released++ }
    }

    private class Link(private val result: LinkResult = LinkResult.READY) : CommsLink {
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        override fun acquire(input: AudioEndpoint, onResult: (LinkResult) -> Unit): AutoCloseable {
            acquired.incrementAndGet()
            onResult(result)
            return AutoCloseable { released.incrementAndGet() }
        }
    }

    private fun plan(on: Boolean, devices: FakeDevices, link: Link = Link()): MicPlan {
        var made: MicPlan? = null
        HeadsetMicRoute(HeadsetPolicy({ on }, devices), link).prepare { made = it }
        return made!!
    }

    private val wired get() = FakeDevices(listOf(Gear.speaker, Gear.wiredHeadset), listOf(Gear.wiredHeadsetMic))
    private val bluetooth get() = FakeDevices(listOf(Gear.speaker, Gear.scoOut), listOf(Gear.scoIn))

    private fun record(plan: MicPlan?, recorder: FakeRecord, hold: () -> Unit = {}): Pair<MicReport?, ByteArray?> {
        val got = CountDownLatch(1)
        var report: MicReport? = null
        val wav = WavRecorder(records = RecordFactory { recorder })
        wav.start(plan) { report = it; got.countDown() }
        if (plan != null) assertTrue("a source report", got.await(5, TimeUnit.SECONDS))
        hold()
        Thread.sleep(700) // at least 0.2 s of audio for a usable WAV
        return report to wav.stop()
    }

    @Test
    fun `a wired headset microphone is asked for as the preferred input and reported only after Android's readback confirms it`() {
        val recorder = FakeRecord(routed = Gear.wiredHeadsetMic.id)
        val (report, wav) = record(plan(true, wired), recorder)
        assertEquals(Gear.wiredHeadsetMic.id, recorder.preferred)
        assertTrue(report!!.headset)
        assertEquals(HeadsetText.HEADSET_MIC, report.text)
        assertNotNull(wav)
        assertEquals(1, recorder.released)
    }

    @Test
    fun `the setter returning true is not a headset microphone - an absent readback says it was not confirmed`() {
        val recorder = FakeRecord(routed = null, accepts = true)
        val (report, _) = record(plan(true, wired), recorder)
        assertFalse(report!!.headset)
        assertEquals(HeadsetText.NOT_CONFIRMED, report.text)
    }

    @Test
    fun `a readback naming another input is reported as not selected`() {
        val recorder = FakeRecord(routed = 99)
        val (report, _) = record(plan(true, wired), recorder)
        assertFalse(report!!.headset)
        assertEquals(HeadsetText.NOT_ROUTED, report.text)
    }

    @Test
    fun `off  or no headset  never asks for a device and says nothing about one`() {
        for (devices in listOf(wired, FakeDevices())) {
            val recorder = FakeRecord(routed = 5)
            val off = plan(false, devices)
            val (report, wav) = record(off, recorder)
            assertNull(recorder.preferred)
            assertNull(report!!.text)
            assertFalse(report.headset)
            assertNotNull(wav)
        }
        val recorder = FakeRecord(routed = 5)
        record(null, recorder)
        assertNull("no plan at all: the existing recorder", recorder.preferred)
    }

    @Test
    fun `a Bluetooth microphone takes its link only for the capture and gives it back at the end`() {
        val link = Link()
        val recorder = FakeRecord(routed = Gear.scoIn.id)
        val p = plan(true, bluetooth, link)
        assertEquals(1, link.acquired.get())
        assertEquals(0, link.released.get())
        val (report, _) = record(p, recorder)
        assertEquals(Gear.scoIn.id, recorder.preferred)
        assertTrue(report!!.headset)
        assertEquals("released at the capture's end, exactly once", 1, link.released.get())
    }

    @Test
    fun `an unconfirmed Bluetooth microphone releases the link at once`() {
        val link = Link()
        val recorder = FakeRecord(routed = null)
        val (report, _) = record(plan(true, bluetooth, link), recorder)
        assertFalse(report!!.headset)
        assertEquals(1, link.released.get())
    }

    @Test
    fun `permission denied and a timed out link fall back to the Phone microphone with the reason and no stray link`() {
        for ((result, text) in listOf(LinkResult.PERMISSION to HeadsetText.PERMISSION, LinkResult.TIMEOUT to HeadsetText.LINK_TIMEOUT)) {
            val link = Link(result)
            val recorder = FakeRecord(routed = 5)
            val (report, _) = record(plan(true, bluetooth, link), recorder)
            assertNull(recorder.preferred)
            assertEquals(text, report!!.text)
            assertEquals(1, link.released.get())
        }
    }

    @Test
    fun `a recorder that cannot open or start releases the link and throws`() {
        for (broken in listOf(FakeRecord(null, initialized = false), FakeRecord(null, starts = false))) {
            val link = Link()
            val p = plan(true, bluetooth, link)
            val wav = WavRecorder(records = RecordFactory { broken })
            var thrown = false
            try {
                wav.start(p) {}
            } catch (_: IllegalStateException) {
                thrown = true
            }
            assertTrue(thrown)
            assertEquals(1, link.released.get())
            assertFalse(wav.isRecording)
        }
    }

    // ── the hands-free recorder ──────────────────────────────────────────────────────────────

    private fun capture(plan: MicPlan?, recorder: FakeRecord): Pair<MicReport?, ByteArray?> {
        val got = CountDownLatch(1)
        var report: MicReport? = null
        val c = PhoneCapture("c-1", SilenceEndpoint(), object : PcmCaptureLoop.Listener {
            override fun onLive() {}
            override fun onCalibrated() {}
            override fun onEnd(reason: CaptureEnd) {}
        }, RecordFactory { recorder })
        assertTrue(c.start(plan) { report = it; got.countDown() })
        if (plan != null) assertTrue(got.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        return report to c.stop()
    }

    @Test
    fun `hands-free capture requests the headset input and reports the readback`() {
        val recorder = FakeRecord(routed = Gear.wiredHeadsetMic.id)
        val (report, _) = capture(plan(true, wired), recorder)
        assertEquals(Gear.wiredHeadsetMic.id, recorder.preferred)
        assertTrue(report!!.headset)
        assertEquals(1, recorder.released)
    }

    @Test
    fun `hands-free capture whose request Android refuses says so and never claims the headset`() {
        val link = Link()
        val recorder = FakeRecord(routed = Gear.scoIn.id, accepts = false)
        val (report, _) = capture(plan(true, bluetooth, link), recorder)
        assertFalse(report!!.headset)
        assertEquals(HeadsetText.NOT_ROUTED, report.text)
        assertEquals(1, link.released.get())
    }

    @Test
    fun `hands-free capture releases its link at stop  and when it cannot start`() {
        val link = Link()
        capture(plan(true, bluetooth, link), FakeRecord(routed = Gear.scoIn.id))
        assertEquals(1, link.released.get())
        val failed = Link()
        val c = PhoneCapture("c-2", SilenceEndpoint(), object : PcmCaptureLoop.Listener {
            override fun onLive() {}
            override fun onCalibrated() {}
            override fun onEnd(reason: CaptureEnd) {}
        }, RecordFactory { FakeRecord(null, starts = false) })
        assertFalse(c.start(plan(true, bluetooth, failed)))
        assertEquals(1, failed.released.get())
    }

    @Test
    fun `without a plan the hands-free capture is the existing recorder`() {
        val recorder = FakeRecord(routed = 7)
        val (report, wav) = capture(null, recorder)
        assertNull(report)
        assertNull(recorder.preferred)
        assertNotNull(wav)
    }

    // ── endpoint constants ───────────────────────────────────────────────────────────────────

    @Test
    fun `the classifier's type numbers are the platform's`() {
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, EndpointType.BUILTIN_SPEAKER)
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, EndpointType.WIRED_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, EndpointType.WIRED_HEADPHONES)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, EndpointType.BLUETOOTH_SCO)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, EndpointType.BLUETOOTH_A2DP)
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, EndpointType.USB_HEADSET)
        assertEquals(26, EndpointType.BLE_HEADSET) // AudioDeviceInfo.TYPE_BLE_HEADSET is API 31
        assertEquals(27, Gear.bleSpeaker.type)
        assertEquals(23, Gear.hearingAid.type)
    }
}
