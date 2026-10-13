package com.rumi.hermesvoice.watch

import android.content.Context
import android.net.Uri
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.diag.DiagCause
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagJournal
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.DiagModality
import com.rumi.hermesvoice.core.diag.DiagOrigin
import com.rumi.hermesvoice.core.diag.DiagPseudonyms
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.diag.DiagWire
import com.rumi.hermesvoice.core.diag.DiagWireV2
import com.rumi.hermesvoice.core.diag.FileDiagJournalStorage
import com.rumi.hermesvoice.core.diag.WatchDiagStatus
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.Collections
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * SCRATCH HARNESS (never packaged). The Watch's own diagnostics through the REAL WatchApp, WatchListenerService and journal:
 * hand-over failures to the Phone, the answer to the Phone's request in the schema the Phone asked in, persistence across a
 * recreated log, and the local clear. Simulated: the Data Layer (mockk). No watch, no Phone, no radio.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchNetworkDiagnosticsRoboTest {
    private lateinit var app: WatchApp
    private val sent: MutableList<Triple<String, String, ByteArray>> = Collections.synchronizedList(mutableListOf())
    private val phone = "phone-node"
    private val secret = "SECRET-token 10.0.0.7 content://media/private.jpg"

    private fun stubWearable(phoneNearby: Boolean, openChannel: Exception?) {
        mockkStatic(Wearable::class)
        val node = mockk<Node>()
        every { node.id } returns phone
        every { node.isNearby } returns true
        every { node.displayName } returns "phone"
        val info = mockk<CapabilityInfo>()
        every { info.nodes } returns if (phoneNearby) setOf(node) else emptySet()
        every { info.name } returns WatchLinkPaths.CAPABILITY_PHONE
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } returns Tasks.forResult(info)
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            sent += Triple(firstArg<String>(), secondArg<String>(), thirdArg<ByteArray>())
            Tasks.forResult(1)
        }
        val channels = mockk<ChannelClient>()
        if (openChannel != null) every { channels.openChannel(any(), any()) } returns Tasks.forException(openChannel)
        val data = mockk<DataClient>()
        every { data.getDataItems(any<Uri>()) } returns Tasks.forException<DataItemBuffer>(IllegalStateException("harness: no data item"))
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { Wearable.getDataClient(any<Context>()) } returns data
    }

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun await(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (condition()) return true; idle(50); Thread.sleep(10) }
        return condition()
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
    }

    @After fun tearDown() {
        unmockkAll()
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun journalDir() = File(app.noBackupFilesDir, "diag-journal")
    private fun journalFiles() = journalDir().listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty()

    private fun upload(): String {
        val turnId = app.newTurn(TurnTrigger.PUSH_TO_TALK)!!
        app.upload(turnId, TurnTrigger.PUSH_TO_TALK, ByteArray(64) { 1 })
        return turnId
    }

    @Test fun anUnreachablePhoneIsSaidAsSuchAndRecordedAsAWatchVoiceTransferFailure() {
        stubWearable(phoneNearby = false, openChannel = null)
        upload()
        assertTrue(await { app.diag.snapshot().any { it.code == DiagCode.REQUEST_FAILED } })
        val event = app.diag.snapshot().first { it.code == DiagCode.REQUEST_FAILED }
        assertEquals(DiagOrigin.WATCH, event.origin)
        assertEquals(DiagStage.TRANSFER, event.stage)
        assertEquals(DiagModality.VOICE, event.modality)
        assertEquals(DiagCause.DISCONNECT, event.cause)
        assertTrue(await { app.talk.value.line == "Couldn't reach the phone" })
    }

    @Test fun aFailedChannelOpenSaysWhatKindOfFailureWithoutTheErrorText_andARetryIsAnotherAttempt() {
        stubWearable(phoneNearby = true, openChannel = IOException(secret))
        val first = upload()
        assertTrue(await { app.diag.snapshot().count { it.code == DiagCode.REQUEST_FAILED } == 1 })
        assertTrue(await { app.talk.value.line.startsWith("Couldn't send to the phone: ") })
        assertFalse(app.talk.value.line.contains("SECRET"))
        val second = upload()
        assertTrue(await { app.diag.snapshot().count { it.code == DiagCode.REQUEST_FAILED } == 2 })
        assertTrue(first != second)
        val refs = app.diag.snapshot().filter { it.code == DiagCode.REQUEST_FAILED }.map { it.ref }
        assertEquals("two attempts are two correlations", 2, refs.toSet().size)
        assertFalse(app.diag.snapshot().joinToString { it.toString() }.contains("SECRET"))
        assertTrue("nothing was sent to the Phone over the message path", sent.none { it.second == WatchLinkPaths.DIAG_RESPONSE })
    }

    private fun ask(request: ByteArray, path: String = WatchLinkPaths.DIAG_REQUEST): ByteArray {
        val service = Robolectric.setupService(WatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns path
        every { event.sourceNodeId } returns phone
        every { event.data } returns request
        service.onMessageReceived(event)
        assertTrue("the Watch answers", await { sent.any { it.second == WatchLinkPaths.DIAG_RESPONSE } })
        return sent.first { it.second == WatchLinkPaths.DIAG_RESPONSE }.also { assertEquals(phone, it.first) }.third
    }

    @Test fun theWatchAnswersInTheSchemaThePhoneAsked_oldPhoneAndNewPhone() {
        stubWearable(phoneNearby = true, openChannel = IOException(secret))
        upload()
        assertTrue(await { app.diag.snapshot().any { it.code == DiagCode.REQUEST_FAILED } })

        val salt = DiagPseudonyms.newSalt()
        val old = ask(DiagWire.encodeRequest(salt))
        val oldPart = DiagWire.decodeResponse(old, salt)
        assertEquals(WatchDiagStatus.REACHABLE, oldPart.status)
        assertTrue(oldPart.events.isNotEmpty())
        assertNull(oldPart.v2)

        sent.clear()
        val newSalt = DiagPseudonyms.newSalt()
        val new = ask(DiagWire.encodeRequestV2(newSalt), WatchLinkPaths.DIAG_REQUEST_V2)
        val newPart = DiagWireV2.decodeResponse(new, newSalt)
        assertEquals(WatchDiagStatus.REACHABLE, newPart.status)
        assertNotNull(newPart.v2)
        assertTrue(newPart.v2!!.app.versionName.isNotEmpty())
        assertTrue(newPart.v2!!.app.build.status.wire in setOf("available", "unavailable", "unreadable"))
        for (bytes in listOf(old, new)) {
            val text = String(bytes, Charsets.UTF_8)
            assertFalse(text.contains("SECRET"))
            assertFalse(text.contains("10.0.0.7"))
            assertFalse(text.contains(app.packageName + ".apk"))
        }
    }

    @Test fun aRequestOnThePathOfTheOtherSchemaGetsNoAnswer() {
        stubWearable(phoneNearby = true, openChannel = null)
        val service = Robolectric.setupService(WatchListenerService::class.java)
        for ((path, bytes) in listOf(
            WatchLinkPaths.DIAG_REQUEST to DiagWire.encodeRequestV2(DiagPseudonyms.newSalt()),
            WatchLinkPaths.DIAG_REQUEST_V2 to DiagWire.encodeRequest(DiagPseudonyms.newSalt()),
        )) {
            val event = mockk<MessageEvent>()
            every { event.path } returns path
            every { event.sourceNodeId } returns phone
            every { event.data } returns bytes
            service.onMessageReceived(event)
        }
        Thread.sleep(200)
        idle(300)
        assertTrue(sent.none { it.second == WatchLinkPaths.DIAG_RESPONSE })
    }

    @Test fun aMalformedRequestGetsNoAnswerAndNothingBreaks() {
        stubWearable(phoneNearby = true, openChannel = null)
        val service = Robolectric.setupService(WatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns WatchLinkPaths.DIAG_REQUEST
        every { event.sourceNodeId } returns phone
        every { event.data } returns "{not json".toByteArray()
        service.onMessageReceived(event)
        Thread.sleep(200)
        idle(300)
        assertTrue(sent.none { it.second == WatchLinkPaths.DIAG_RESPONSE })
    }

    @Test fun aFlushedWatchLogSurvivesARecreatedProcess() {
        stubWearable(phoneNearby = false, openChannel = null)
        upload()
        assertTrue(await { app.diag.snapshot().any { it.code == DiagCode.REQUEST_FAILED } })
        app.diagJournal.flushNow()
        assertTrue(journalFiles().isNotEmpty())
        val text = journalFiles().joinToString { it.readText() }
        assertFalse(text.contains("SECRET"))
        val reborn = DiagLog(clock = { 10L })
        val journal = DiagJournal(reborn, FileDiagJournalStorage(journalDir()), Executor { it.run() })
        journal.attach()
        assertTrue(reborn.snapshot().any { it.code == DiagCode.REQUEST_FAILED && it.origin == DiagOrigin.WATCH && it.opaque })
        assertTrue(reborn.coverage().restored >= 1)
    }

    @Test fun clearingThisWatchsLogRemovesOnlyItsDiagnostics() {
        stubWearable(phoneNearby = false, openChannel = null)
        upload()
        assertTrue(await { app.diag.snapshot().any { it.code == DiagCode.REQUEST_FAILED } })
        app.diagJournal.flushNow()
        assertTrue(journalFiles().isNotEmpty())
        val line = app.talk.value.line
        assertTrue(runBlocking { app.clearLocalLog() })
        assertTrue(app.diag.snapshot().isEmpty())
        val reborn = DiagLog(clock = { 10L })
        DiagJournal(reborn, FileDiagJournalStorage(journalDir()), Executor { it.run() }).attach()
        assertTrue("nothing of the cleared log can be restored (a waiting flush may leave an empty journal, never the old events)", reborn.snapshot().isEmpty())
        assertEquals("the screen state of the conversation is untouched", line, app.talk.value.line)
    }
}
