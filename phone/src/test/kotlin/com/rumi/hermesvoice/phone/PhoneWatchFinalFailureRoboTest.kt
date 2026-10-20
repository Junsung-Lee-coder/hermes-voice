package com.rumi.hermesvoice.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.TestAudio
import com.rumi.hermesvoice.core.net.SttCode
import com.rumi.hermesvoice.core.net.SttSafeCode
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). A Watch recording uploaded through the real PhoneWatchListenerService, transcribed by the real
 * core against the dashboard contract double, whose final failure must reach the originating Watch node, survive that node being
 * unreachable, and be sent again to that node only when it asks. Simulated: the Data Layer (mockk) and the keystore.
 * The new wire paths are given as literals so the same bytes also run on the tree that does not know them.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneWatchFinalFailureRoboTest : PhoneRoboBase() {
    private class Sent(val node: String, val path: String, val data: ByteArray) {
        val state: TurnStateMessage? get() = if (path == WatchLinkPaths.STATE) TurnStateMessage.decode(data) else null
    }

    private val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
    private var reachable = true
    private val origin = "watch-node-a"

    @Before fun setUp() {
        baseSetUp()
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            if (reachable) { sent += Sent(firstArg(), secondArg(), thirdArg()); Tasks.forResult(1) }
            else Tasks.forException(java.io.IOException("watch not reachable"))
        }
        every { Wearable.getMessageClient(any<Context>()) } returns messages
    }

    @After fun tearDown() = baseTearDown()

    private fun turnId(n: Int) = "watch-pf-%04d".format(n)

    private fun upload(turnId: String) = WatchTurnUpload(turnId, TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav())
        .toFrame().encode()

    private fun uploadFrom(node: String, turnId: String) {
        val channels = mockk<ChannelClient>()
        every { channels.getInputStream(any()) } returns Tasks.forResult(ByteArrayInputStream(upload(turnId)))
        every { channels.close(any()) } returns Tasks.forResult<Void>(null)
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        val channel = mockk<ChannelClient.Channel>()
        every { channel.path } returns WatchLinkPaths.turnPath(turnId)
        every { channel.nodeId } returns node
        Robolectric.setupService(PhoneWatchListenerService::class.java).onChannelOpened(channel)
    }

    private fun deliver(path: String, data: ByteArray, node: String = origin) {
        val event = mockk<MessageEvent>()
        every { event.path } returns path
        every { event.sourceNodeId } returns node
        every { event.data } returns data
        Robolectric.setupService(PhoneWatchListenerService::class.java).onMessageReceived(event)
    }

    private fun terminalsTo(node: String, turn: String) = sent.toList().filter { it.node == node && it.state?.let { s -> s.terminal && s.turnId == turn } == true }

    private fun failFinally(status: Int, code: SttCode) =
        fake.transcribeStatuses.add(status to "[hermes-stt:${code.wire}] ${SttSafeCode.message(code)}")

    private fun storedText(): String = java.io.File(app.dataDir, "shared_prefs").listFiles().orEmpty().joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }

    @Test fun F01_aFinalHttp400RejectionReachesTheOriginatingWatchAsNotSentForThatRequest() {
        failFinally(400, SttCode.INPUT_REJECTED)
        uploadFrom(origin, turnId(1))
        assertTrue("a terminal state arrived", await { terminalsTo(origin, turnId(1)).isNotEmpty() })
        val terminal = terminalsTo(origin, turnId(1)).single().state!!
        assertEquals("done", terminal.stage)
        assertTrue("says it was not sent: '${terminal.detail}'", terminal.detail.startsWith("Not sent"))
        assertTrue("nothing was submitted to Hermes", fake.rawPrompts.isEmpty())
        assertTrue("only the originating node was addressed", sent.all { it.node == origin })
    }

    @Test fun F02_aFinalProviderFailureReachesTheOriginatingWatchAsNotSent() {
        failFinally(400, SttCode.TRANSCRIPTION_FAILED)
        uploadFrom(origin, turnId(2))
        assertTrue(await { terminalsTo(origin, turnId(2)).isNotEmpty() })
        assertTrue(terminalsTo(origin, turnId(2)).single().state!!.detail.startsWith("Not sent"))
        assertTrue(fake.rawPrompts.isEmpty())
    }

    @Test fun F03_theFailureIsKeptInTheAppsPrivateStorageAndNoRecordingOrSpeechIsKeptWithIt() {
        reachable = false
        failFinally(400, SttCode.INPUT_REJECTED)
        uploadFrom(origin, turnId(3))
        assertTrue("the failure was recorded as owed to the Watch", await { storedText().contains(turnId(3)) })
        val stored = storedText()
        assertFalse("no recording bytes", stored.contains("UklGR"))
        assertFalse("no transcript", stored.contains("move my meeting"))
    }

    @Test fun F04_aWatchThatWasAwayGetsTheFailureWhenItAsksAndNotAgainOnceItAcknowledged() {
        reachable = false
        failFinally(400, SttCode.INPUT_REJECTED)
        uploadFrom(origin, turnId(4))
        assertTrue(await { storedText().contains(turnId(4)) })
        assertTrue("nothing could be sent while away", sent.isEmpty())

        reachable = true
        deliver("/hv/v1/state/sync", ByteArray(0))
        assertTrue("sent to the node that asked", await { terminalsTo(origin, turnId(4)).isNotEmpty() })
        val resent = terminalsTo(origin, turnId(4)).first().state!!
        assertTrue(resent.detail.startsWith("Not sent"))

        deliver("/hv/v1/state/ack", TurnStateMessage(turnId(4), "ack", "", true).encode())
        val before = sent.size
        deliver("/hv/v1/state/sync", ByteArray(0))
        settle()
        assertEquals("an acknowledged failure is not sent again", before, sent.size)
    }

    @Test fun F05_anotherNodeNeitherReceivesNorAcknowledgesTheFailure() {
        reachable = false
        failFinally(400, SttCode.INPUT_REJECTED)
        uploadFrom(origin, turnId(5))
        assertTrue("the failure was recorded as owed [sent=${sent.size}; prompts=${fake.rawPrompts.size}; files=${java.io.File(app.dataDir, "shared_prefs").list().orEmpty().toList()}; " +
            "rest=${fake.restLog.toList().takeLast(8)}]", await { storedText().contains(turnId(5)) })
        reachable = true
        deliver("/hv/v1/state/sync", ByteArray(0), node = "watch-node-b")
        settle()
        assertTrue("a different node was sent nothing", sent.none { it.node == "watch-node-b" })
        deliver("/hv/v1/state/ack", TurnStateMessage(turnId(5), "ack", "", true).encode(), node = "watch-node-b")
        deliver("/hv/v1/state/sync", ByteArray(0))
        assertTrue("the real origin still receives it", await { terminalsTo(origin, turnId(5)).isNotEmpty() })
    }

    @Test fun F06_theFailureOwedToAWatchSurvivesTheWiringBeingRebuilt() {
        reachable = false
        failFinally(400, SttCode.INPUT_REJECTED)
        uploadFrom(origin, turnId(6))
        assertTrue(await { storedText().contains(turnId(6)) })
        app.settings.profile = "elsewhere"
        app.wiring()
        app.settings.profile = ""
        app.wiring()
        reachable = true
        deliver("/hv/v1/state/sync", ByteArray(0))
        assertTrue(await { terminalsTo(origin, turnId(6)).isNotEmpty() })
    }

    @Test fun F07_theMergedManifestDeliversTheSyncAndAcknowledgementPathsToThePhoneListener() {
        val service = PhoneWatchListenerService::class.java.name
        for (path in listOf("/hv/v1/state/sync", "/hv/v1/state/ack")) {
            val intent = Intent("com.google.android.gms.wearable.MESSAGE_RECEIVED").setPackage(app.packageName).setData(Uri.parse("wear://watch-node$path"))
            assertTrue("$path is delivered to the Phone listener", app.packageManager.queryIntentServices(intent, 0).any { it.serviceInfo.name == service })
        }
    }
}
