package com.rumi.hermesvoice.phone

import android.content.Context
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * R35: the Watch's v2 media packets reach the current call's WatchLiveTransport in the order the real Wear listener received them,
 * whatever order or outcome the Phone's reachable-capability lookups have. The real PhoneWatchListenerService.onMessageReceived runs
 * on its own background thread, as on a device, through the actual PhoneLiveWatchControl into the actual transport of a started
 * Watch-origin call (the existing WatchPhoneRelayDefaultRoboTest composition). Only Play services Tasks, the provider peer and Android
 * leaves are synthetic. The guards stay: another node, a stale media tuple, a real sequence gap, a v1 control's own capability wait,
 * sign-out and Live OFF.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchRelayDeliveryOrderR35RoboTest {
    private lateinit var w: WatchPhoneRelayDefaultRoboTest
    private lateinit var listener: PhoneWatchListenerService
    private lateinit var offer: WatchLiveWire.Packet
    private lateinit var capable: CapabilityInfo
    private val lookups = ConcurrentLinkedQueue<TaskCompletionSource<CapabilityInfo>>()
    private val wear = Executors.newSingleThreadExecutor() // the Wear listener's own thread
    private var seq = 1L // the harness start already delivered the real offer as sequence one
    @Before fun setup() {
        w = WatchPhoneRelayDefaultRoboTest(); w.setup()
        w.javaClass.getDeclaredMethod("start", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(w, true)
        offer = w.javaClass.getDeclaredField("offer").apply { isAccessible = true }.get(w) as WatchLiveWire.Packet
        assertTrue("the Watch-origin call started", w.await { w.app.live.state.value.started })
        // From here every capability lookup is a Task this test completes, in the order and with the outcome it chooses.
        val node = mockk<Node>(); every { node.id } returns "watch-node"; capable = mockk(); every { capable.nodes } returns setOf(node)
        val caps = mockk<CapabilityClient>(); every { caps.getCapability(any(), any()) } answers { TaskCompletionSource<CapabilityInfo>().also { lookups += it }.task }
        every { Wearable.getCapabilityClient(any<Context>()) } returns caps
        listener = Robolectric.buildService(PhoneWatchListenerService::class.java).create().get()
    }
    @After fun cleanup() { wear.shutdownNow(); w.cleanup() }
    /** One Wear message through the real listener, on the listener's thread. */
    private fun deliver(bytes: ByteArray, node: String = "watch-node") {
        val event = mockk<MessageEvent>(); every { event.path } returns WatchLinkPaths.LIVE_CONTROL
        every { event.sourceNodeId } returns node; every { event.data } returns bytes
        wear.submit(java.util.concurrent.Callable { listener.onMessageReceived(event) }).get()
    }
    private fun media(op: String, body: String = "", sequence: Long = ++seq, mediaId: String = offer.media) = WatchLiveWire.packet(offer.id, mediaId, offer.epoch, sequence, op, body)
    private fun control(op: String) = JSONObject().put("v", 1).put("id", offer.id).put("op", op).put("probe", "r35").toString().toByteArray()
    private fun ends() = w.app.diagnostics.log.snapshot().filter { it.code.wire == "live_ended" }
    /** The two ordinary owner packets after the offer: connected, then the Watch microphone becoming active. */
    private fun connectedThenMicrophone() { deliver(media("connected")); deliver(media("microphone", "true")); w.settle() }
    private fun callGoesOnWithBothPackets() {
        w.settle()
        val s = w.app.live.state.value
        assertTrue("no self-induced 'Watch media sequence lost' end (status '${s.status}', end ${s.endReason})", w.app.live.active)
        assertFalse(w.app.live.state.value.endFailed); assertTrue(ends().isEmpty())
        assertTrue("the later packet (microphone) reached the transport after the earlier one", w.await { w.app.live.state.value.inputActive })
    }

    @Test fun D01_reversedCapabilityCompletionsDoNotReorderTheOwnersPackets() {
        connectedThenMicrophone()
        lookups.reversed().forEach { it.setResult(capable) } // the later packet's lookup completes first
        callGoesOnWithBothPackets()
    }

    @Test fun D02_aFailedLookupForAnOwnerPacketLeavesNoSequenceHole() {
        connectedThenMicrophone()
        lookups.forEachIndexed { i, t -> if (i == 0) t.setException(IllegalStateException("synthetic Play services failure")) else t.setResult(capable) }
        callGoesOnWithBothPackets()
    }

    @Test fun D03_anUnreachableAnswerForAnOwnerPacketLeavesNoSequenceHole() {
        connectedThenMicrophone()
        val none = mockk<CapabilityInfo>(); every { none.nodes } returns emptySet()
        lookups.forEachIndexed { i, t -> t.setResult(if (i == 0) none else capable) }
        callGoesOnWithBothPackets()
    }

    @Test fun D04_lookupsCompletingInOrderDeliverEveryPacketInOrder() {
        connectedThenMicrophone()
        lookups.forEach { it.setResult(capable) }
        callGoesOnWithBothPackets()
    }

    @Test fun G01_aPacketFromAnotherNodeWithTheOwnersExactTupleIsNeverDelivered() {
        deliver(media("refused", "stranger words", sequence = seq + 1), node = "stranger-node")
        connectedThenMicrophone()
        lookups.forEach { it.setResult(capable) }
        callGoesOnWithBothPackets()
    }

    @Test fun G02_aStaleMediaTupleIsIgnoredAndTheOwnersNextPacketStaysInOrder() {
        deliver(media("refused", "stale words", sequence = seq + 1, mediaId = "older-media"))
        connectedThenMicrophone()
        lookups.forEach { it.setResult(capable) }
        callGoesOnWithBothPackets()
    }

    @Test fun G03_aRealGapInTheOwnersSequenceStillEndsTheCallAsAFailure() {
        deliver(media("microphone", "true", sequence = seq + 2)); w.settle()
        lookups.forEach { it.setResult(capable) }
        assertTrue("the owner's own missing packet still ends the call", w.await { !w.app.live.active })
        assertTrue(w.app.live.state.value.endFailed); assertEquals("failure", w.app.live.state.value.endReason)
        assertEquals(3, ends().single().n)
    }

    @Test fun G04_aV1WatchControlStillWaitsForItsOwnCapabilityAndAFailedLookupActsOnNothing() {
        deliver(control("end")); w.settle()
        assertEquals("a v1 control still awaits its capability", 1, lookups.size); assertTrue(w.app.live.active)
        lookups.single().setException(IllegalStateException("synthetic Play services failure")); w.settle()
        assertTrue("a failed lookup admits no control", w.app.live.active)
        deliver(control("end")); w.settle(); lookups.last().setResult(capable)
        assertTrue(w.await { !w.app.live.active }); assertEquals("explicit", w.app.live.state.value.endReason)
        assertEquals("the Watch's End is an explicit end", 0, ends().single().n)
    }

    @Test fun G05_afterSignOutTheOldOwnersPacketsActOnNothing() {
        PhoneViewModel(w.app).signOut(); w.settle()
        assertFalse(w.app.live.active); val reason = w.app.live.state.value.endReason; val recorded = ends().size
        deliver(media("suspend")); deliver(media("microphone", "true")); w.settle()
        lookups.forEach { it.setResult(capable) }; w.settle()
        assertEquals("a late owner packet cannot turn the sign-out into an idle (resumable) end", reason, w.app.live.state.value.endReason)
        assertEquals(recorded, ends().size); assertFalse(w.app.live.state.value.inputActive)
    }

    @Test fun G06_afterLiveOffTheOldOwnersPacketsActOnNothing() {
        w.app.settings.saveWatchSettings(w.app.settings.watchSettings().copy(watchLiveEnabled = false)); w.app.live.settingsChanged(); w.settle()
        assertFalse(w.app.live.active); val recorded = ends().size
        deliver(media("refused", "late words")); deliver(media("suspend")); w.settle()
        lookups.forEach { it.setResult(capable) }; w.settle()
        assertEquals("explicit", w.app.live.state.value.endReason); assertFalse(w.app.live.state.value.endFailed)
        assertEquals(recorded, ends().size)
    }
}
