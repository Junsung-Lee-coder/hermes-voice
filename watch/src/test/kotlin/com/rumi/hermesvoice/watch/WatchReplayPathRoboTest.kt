package com.rumi.hermesvoice.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.watchlink.ReplayRequest
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTalkState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Duration
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The Watch's "Play again" through the REAL WatchListenerService (the Phone's STATE message),
 * the real WatchApp state and the real [WatchApp.replayReply] message. Simulated: the Data Layer (mockk) that records what the Watch sent;
 * no Phone, no audio.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchReplayPathRoboTest {
    private lateinit var app: WatchApp
    private class Sent(val node: String, val path: String, val data: ByteArray)

    private val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
    private var phoneReachable = true

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val node = mockk<Node>()
        every { node.id } returns "phone-node"
        every { node.isNearby } returns true
        every { node.displayName } returns "phone"
        val info = mockk<CapabilityInfo>()
        every { info.nodes } answers { if (phoneReachable) setOf(node) else emptySet() }
        every { info.name } returns WatchLinkPaths.CAPABILITY_PHONE
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } returns Tasks.forResult(info)
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            sent += Sent(firstArg(), secondArg(), thirdArg())
            Tasks.forResult(1)
        }
        val channels = mockk<ChannelClient>(relaxed = true)
        val data = mockk<DataClient>(relaxed = true)
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { Wearable.getDataClient(any<Context>()) } returns data
        every { Wearable.getDataClient(any<android.app.Activity>()) } returns data
    }

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun await(timeoutMs: Long = 8_000, done: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            idle(50)
            if (done()) return true
            if (System.nanoTime() > end) return false
            Thread.sleep(25)
        }
    }

    private fun phoneSays(state: TurnStateMessage) {
        val service = Robolectric.setupService(WatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns WatchLinkPaths.STATE
        every { event.sourceNodeId } returns "phone-node"
        every { event.data } returns state.encode()
        service.onMessageReceived(event)
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        stubWearable()
    }

    @After fun tearDown() = unmockkAll()

    private fun replayOffered(turnId: String = app.newTurn(TurnTrigger.PUSH_TO_TALK)!!): String {
        phoneSays(TurnStateMessage(turnId, "done", "Reply audio did not play: play it again, or read the text", true, replay = true))
        assertTrue("the Phone's replay-capable terminal state offers Play again", await { app.talk.value.replayTurnId == turnId })
        return turnId
    }

    @Test fun W01_thePhoneManifestIndependentWatchListenerAcceptsTheStatePathAndReplayIsOfferedOnlyForTheTerminalReplayState() {
        val intent = Intent("com.google.android.gms.wearable.MESSAGE_RECEIVED").setPackage(app.packageName).setData(Uri.parse("wear://phone-node${WatchLinkPaths.STATE}"))
        assertTrue(app.packageManager.queryIntentServices(intent, 0).any { it.serviceInfo.name == WatchListenerService::class.java.name })
        replayOffered()
    }

    @Test fun W02_playAgainSendsExactlyOneReplayRequestForThatTurnToThePhoneNodeAndWithdrawsTheControl() {
        val turnId = replayOffered()
        app.replayReply()
        assertEquals("the control is withdrawn at once", null, app.talk.value.replayTurnId)
        assertEquals(WatchTalkState.REPLAY_REQUESTED, app.talk.value.line)
        assertTrue("the request was sent", await { sent.any { it.path == WatchLinkPaths.REPLAY } })
        val request = sent.single { it.path == WatchLinkPaths.REPLAY }
        assertEquals("phone-node", request.node)
        assertEquals(turnId, ReplayRequest.decode(request.data)?.turnId)
        app.replayReply()
        idle(200)
        assertEquals("a second tap sends nothing more", 1, sent.count { it.path == WatchLinkPaths.REPLAY })
    }

    @Test fun W03_thePhonesPlayedResultClearsTheControlAndAFailedResultOffersItAgain() {
        val turnId = replayOffered()
        app.replayReply()
        assertTrue(await { sent.any { it.path == WatchLinkPaths.REPLAY } })
        phoneSays(TurnStateMessage(turnId, "done", "Reply audio did not play: play it again, or read the text", true, replay = true))
        assertTrue("failed again: the control returns", await { app.talk.value.replayTurnId == turnId })
        app.replayReply()
        assertTrue(await { sent.count { it.path == WatchLinkPaths.REPLAY } == 2 })
        phoneSays(TurnStateMessage(turnId, "done", "Reply played", true))
        assertTrue(await { app.talk.value.line == "Reply played" })
        assertNull(app.talk.value.replayTurnId)
    }

    @Test fun W04_anUnreachablePhoneKeepsTheSameReplyAndShowsAVisibleReason() {
        val turnId = replayOffered()
        phoneReachable = false
        app.replayReply()
        assertTrue("the control returns with a visible reason", await { app.talk.value.replayTurnId == turnId })
        assertEquals("Phone not reachable - try again", app.talk.value.line)
        assertTrue("nothing was sent", sent.none { it.path == WatchLinkPaths.REPLAY })
        assertNotNull(app.talk.value.replayTurnId)
    }
}
