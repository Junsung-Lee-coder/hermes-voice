package com.rumi.hermesvoice.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.voice.UnplayedReplyStore
import com.rumi.hermesvoice.core.watchlink.WatchTurnIntake
import com.rumi.hermesvoice.core.watchlink.ReplayRequest
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import java.security.MessageDigest
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The Watch's "Play again" request through the PRODUCTION Phone boundaries this module can run:
 * the merged Phone manifest, the real [PhoneWatchListenerService.onMessageReceived], the real application wiring, the real durable
 * [UnplayedReplyStore] on the real SharedPreferences, the real intake and the real [DataLayerWatchTransport]. The Wear message
 * bridge itself (Google Play services delivering or accepting a real message) is a mock that records what the Phone sent.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneWatchReplayPathTest : PhoneRoboBase() {
    private class Sent(val node: String, val path: String, val data: ByteArray)

    private val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())

    @Before fun setUp() {
        baseSetUp()
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            sent += Sent(firstArg(), secondArg(), thirdArg())
            Tasks.forResult(1)
        }
        every { Wearable.getMessageClient(any<Context>()) } returns messages
    }

    @After fun tearDown() = baseTearDown()

    private fun resolving(path: String): List<String> {
        val intent = Intent("com.google.android.gms.wearable.MESSAGE_RECEIVED").setPackage(app.packageName).setData(Uri.parse("wear://watch-node$path"))
        return app.packageManager.queryIntentServices(intent, 0).map { it.serviceInfo.name }
    }

    private fun deliver(path: String, data: ByteArray, node: String = "watch-node") {
        val service = Robolectric.setupService(PhoneWatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns path
        every { event.sourceNodeId } returns node
        every { event.data } returns data
        service.onMessageReceived(event)
    }

    private fun storeKey(): String {
        val endpoint = HermesDashboardEndpoint.parse(app.settings.dashboardUrl)
        val digest = MessageDigest.getInstance("SHA-256").digest("$endpoint|${app.settings.profile}".toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        return UnplayedReplyStore.KEY + "_" + digest
    }

    private fun seed(turnId: String, node: String?, session: String = "s-1"): String {
        val identity = ReplyAlert.identityOf(turnId, "final")
        val store = UnplayedReplyStore(SharedPreferencesKeyValueStore(app.getSharedPreferences(UnplayedReplyStore.PREFERENCES_NAME, Context.MODE_PRIVATE)), key = storeKey())
        assertTrue(store.put(UnplayedReplyStore.Entry(identity, turnId, VoiceOrigin.WATCH, node, session, false, "An answer the user already has.", System.currentTimeMillis())))
        return identity
    }

    private fun stateFor(turnId: String): List<TurnStateMessage> =
        sent.filter { it.path == WatchLinkPaths.STATE }.mapNotNull { TurnStateMessage.decode(it.data) }.filter { it.turnId == turnId }

    private fun awaitState(turnId: String): TurnStateMessage {
        assertTrue("the Phone told the Watch", await { stateFor(turnId).isNotEmpty() })
        return stateFor(turnId).first()
    }

    @Test fun R01_theMergedPhoneManifestDeliversTheReplayPathToTheListenerService() {
        val service = PhoneWatchListenerService::class.java.name
        assertTrue(service in resolving(WatchLinkPaths.REPLAY))
        assertFalse(service in resolving(WatchLinkPaths.REPLAY + "x"))
    }

    @Test fun R02_aReplayRequestForAnUnknownTurnIsAnsweredToTheAskingNodeAsUnavailableAndNothingElseIsSent() {
        deliver(WatchLinkPaths.REPLAY, ReplayRequest("never-seen").encode())
        val state = awaitState("never-seen")
        assertEquals("done", state.stage)
        assertEquals(WatchTurnIntake.REPLAY_UNAVAILABLE, state.detail)
        assertEquals(listOf("watch-node"), sent.map { it.node }.distinct())
        assertTrue("no audio or channel for a refused replay", sent.all { it.path == WatchLinkPaths.STATE })
    }

    @Test fun R03_aGarbledReplayRequestIsIgnoredWithoutAnyReply() {
        deliver(WatchLinkPaths.REPLAY, "not json".toByteArray())
        deliver(WatchLinkPaths.REPLAY, ByteArray(0))
        app.wiring()
        settle()
        assertTrue(sent.isEmpty())
    }

    @Test fun R04_aReplyKeptAcrossProcessCreationIsRefusedForAnotherNodeAndStaysAvailableForItsOwn() {
        val identity = seed("turn-durable", "watch-node")
        val reread = app.wiring().core.orchestrator.unplayedReplies.value
        assertNotNull("the durable reply was rehydrated by the real wiring", reread.firstOrNull { it.identity == identity })

        deliver(WatchLinkPaths.REPLAY, ReplayRequest("turn-durable").encode(), node = "other-watch")
        val state = awaitState("turn-durable")
        assertEquals(WatchTurnIntake.REPLAY_UNAVAILABLE, state.detail)
        assertEquals("other-watch", sent.first { it.path == WatchLinkPaths.STATE }.node)
        assertTrue("a refused request does not consume the reply", app.wiring().core.orchestrator.unplayedReplies.value.any { it.identity == identity })
    }
}
