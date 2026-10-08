package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.headset.AudioEndpoint
import com.rumi.hermesvoice.core.headset.CommsLink
import com.rumi.hermesvoice.core.headset.HeadsetMicRoute
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.headset.HeadsetText
import com.rumi.hermesvoice.core.headset.LinkResult
import com.rumi.hermesvoice.core.headset.MicPlan
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The microphone choice of a NEW capture: the headset input only when "Use headset" is on and a connected headset supplies an
 * actual input, a Bluetooth one only after its communication link is up (bounded by the port), and the source reported only from
 * Android's readback. The Phone microphone otherwise, with the reason.
 */
class HeadsetMicRouteTest {
    private class FakeLink(var immediate: LinkResult? = null) : CommsLink {
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        var pending: ((LinkResult) -> Unit)? = null
        override fun acquire(input: AudioEndpoint, onResult: (LinkResult) -> Unit): AutoCloseable {
            acquired.incrementAndGet()
            val now = immediate
            if (now != null) onResult(now) else pending = onResult
            return AutoCloseable { released.incrementAndGet() }
        }
        fun finish(result: LinkResult) = pending!!.invoke(result)
    }

    private fun route(on: Boolean, devices: FakeDevices, link: FakeLink = FakeLink()) = HeadsetMicRoute(HeadsetPolicy({ on }, devices), link) to link

    private fun plan(route: HeadsetMicRoute): MicPlan {
        var got: MicPlan? = null
        route.prepare { got = it }
        return got!!
    }

    @Test
    fun `off uses the phone microphone with nothing to say  and never touches the link`() {
        val (r, link) = route(false, FakeDevices(listOf(Gear.speaker, Gear.scoOut), listOf(Gear.scoIn)))
        val p = plan(r)
        assertNull(p.preferred)
        assertNull(p.report(null).text)
        assertEquals(0, link.acquired.get())
    }

    @Test
    fun `on with no headset is the normal phone microphone`() {
        val (r, link) = route(true, FakeDevices())
        val p = plan(r)
        assertNull(p.preferred)
        assertNull(p.report(5).text)
        assertEquals(0, link.acquired.get())
    }

    @Test
    fun `a wired or USB headset microphone is requested without any communication link`() {
        for ((outs, ins, wanted) in listOf(
            Triple(listOf(Gear.speaker, Gear.wiredHeadset), listOf(Gear.wiredHeadsetMic), Gear.wiredHeadsetMic),
            Triple(listOf(Gear.speaker, Gear.usbOut), listOf(Gear.usbIn), Gear.usbIn),
        )) {
            val (r, link) = route(true, FakeDevices(outs, ins))
            val p = plan(r)
            assertEquals(wanted, p.preferred)
            assertEquals(0, link.acquired.get())
        }
    }

    @Test
    fun `the source is the readback  never the setter result`() {
        val (r, _) = route(true, FakeDevices(listOf(Gear.speaker, Gear.wiredHeadset), listOf(Gear.wiredHeadsetMic)))
        val confirmed = plan(r).report(Gear.wiredHeadsetMic.id)
        assertTrue(confirmed.headset)
        assertEquals(HeadsetText.HEADSET_MIC, confirmed.text)
        val none = plan(r).report(null)
        assertFalse(none.headset)
        assertEquals(HeadsetText.NOT_CONFIRMED, none.text)
        val elsewhere = plan(r).report(Gear.speaker.id)
        assertFalse(elsewhere.headset)
        assertEquals(HeadsetText.NOT_ROUTED, elsewhere.text)
    }

    @Test
    fun `headphones without a microphone  or A2DP only  are not microphones and say so`() {
        for (outs in listOf(listOf(Gear.speaker, Gear.wiredHeadphones), listOf(Gear.speaker, Gear.a2dp))) {
            val (r, link) = route(true, FakeDevices(outs))
            val p = plan(r)
            assertNull(p.preferred)
            assertEquals(HeadsetText.NO_HEADSET_MIC, p.report(null).text)
            assertEquals(0, link.acquired.get())
        }
    }

    @Test
    fun `a Bluetooth microphone waits for the link and releases it with the capture`() {
        val link = FakeLink()
        val r = HeadsetMicRoute(HeadsetPolicy({ true }, FakeDevices(listOf(Gear.speaker, Gear.a2dp), listOf(Gear.scoIn))), link)
        var got: MicPlan? = null
        r.prepare { got = it }
        assertNull("not before the link is up", got)
        assertEquals(1, link.acquired.get())
        link.finish(LinkResult.READY)
        val p = got!!
        assertEquals(Gear.scoIn, p.preferred)
        assertEquals(0, link.released.get())
        assertTrue(p.report(Gear.scoIn.id).headset)
        p.release()
        p.release()
        assertEquals("released once", 1, link.released.get())
    }

    @Test
    fun `a link result that arrives before acquire returns is still delivered once`() {
        val link = FakeLink(immediate = LinkResult.READY)
        val r = HeadsetMicRoute(HeadsetPolicy({ true }, FakeDevices(listOf(Gear.speaker, Gear.scoOut), listOf(Gear.scoIn))), link)
        val plans = mutableListOf<MicPlan>()
        r.prepare { plans += it }
        assertEquals(1, plans.size)
        assertEquals(Gear.scoIn, plans[0].preferred)
        plans[0].release()
        assertEquals(1, link.released.get())
    }

    @Test
    fun `permission denied  timeout  a call in progress and an unavailable device each fall back with the reason and give the link back`() {
        for ((result, text) in listOf(
            LinkResult.PERMISSION to HeadsetText.PERMISSION,
            LinkResult.TIMEOUT to HeadsetText.LINK_TIMEOUT,
            LinkResult.IN_USE to HeadsetText.CALL_ACTIVE,
            LinkResult.UNAVAILABLE to HeadsetText.ROUTE_UNAVAILABLE,
        )) {
            val link = FakeLink(immediate = result)
            val r = HeadsetMicRoute(HeadsetPolicy({ true }, FakeDevices(listOf(Gear.speaker, Gear.scoOut), listOf(Gear.scoIn))), link)
            val plans = mutableListOf<MicPlan>()
            r.prepare { plans += it }
            assertEquals(result.name, 1, plans.size)
            assertNull(result.name, plans[0].preferred)
            assertEquals(result.name, text, plans[0].report(null).text)
            assertEquals("${result.name}: the link is released", 1, link.released.get())
        }
    }

    @Test
    fun `an input that disappears while the link comes up falls back instead of recording from nothing`() {
        val devices = FakeDevices(listOf(Gear.speaker, Gear.scoOut), listOf(Gear.scoIn))
        val link = FakeLink()
        var got: MicPlan? = null
        HeadsetMicRoute(HeadsetPolicy({ true }, devices), link).prepare { got = it }
        devices.disconnectAll()
        link.finish(LinkResult.READY)
        assertNull(got!!.preferred)
        assertEquals(HeadsetText.ROUTE_UNAVAILABLE, got!!.report(null).text)
        assertEquals(1, link.released.get())
    }

    @Test
    fun `cancelling a pending preparation gives the link back and delivers nothing`() {
        val link = FakeLink()
        var got: MicPlan? = null
        val handle = HeadsetMicRoute(HeadsetPolicy({ true }, FakeDevices(listOf(Gear.speaker, Gear.scoOut), listOf(Gear.scoIn))), link).prepare { got = it }
        handle.close()
        assertEquals(1, link.released.get())
        link.finish(LinkResult.READY)
        assertNull(got)
    }

    @Test
    fun `a readback that is not the headset releases the link at once`() {
        val link = FakeLink(immediate = LinkResult.READY)
        val r = HeadsetMicRoute(HeadsetPolicy({ true }, FakeDevices(listOf(Gear.speaker, Gear.scoOut), listOf(Gear.scoIn))), link)
        val p = plan(r)
        assertEquals(0, link.released.get())
        assertEquals(HeadsetText.NOT_ROUTED, p.report(Gear.speaker.id).text)
        assertEquals(1, link.released.get())
    }

    @Test
    fun `losing the input of a running capture is reported once  and only the input counts`() {
        val devices = FakeDevices(listOf(Gear.speaker, Gear.usbOut), listOf(Gear.usbIn))
        val (r, _) = route(true, devices)
        val p = plan(r)
        val lost = AtomicInteger()
        val watch = r.watchLoss(p) { lost.incrementAndGet() }
        assertEquals(1, devices.watching)
        devices.set(listOf(Gear.speaker, Gear.usbOut, Gear.a2dp), listOf(Gear.usbIn))
        assertEquals("an unrelated device change is not a loss", 0, lost.get())
        devices.set(listOf(Gear.speaker, Gear.usbOut), emptyList())
        devices.set(listOf(Gear.speaker), emptyList())
        assertEquals(1, lost.get())
        watch.close()
        assertEquals("the watch is gone with the capture", 0, devices.watching)
    }

    @Test
    fun `a capture on the phone microphone watches nothing`() {
        val devices = FakeDevices()
        val (r, _) = route(true, devices)
        val watch = r.watchLoss(plan(r)) { throw AssertionError("no loss to report") }
        assertEquals(0, devices.watching)
        watch.close()
        assertNotNull(watch)
    }
}
