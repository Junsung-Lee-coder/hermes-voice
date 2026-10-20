package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import com.rumi.hermesvoice.core.HermesHttpException
import com.rumi.hermesvoice.core.HermesPromptUncertainException
import com.rumi.hermesvoice.core.InMemoryKeyValueStore
import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.ResponsePlaybackSettings
import com.rumi.hermesvoice.core.SpokenAudio
import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.HermesConversationPort
import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.net.SubmittedTurn
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.DestinationEntry
import com.rumi.hermesvoice.core.voice.RecipientEvent
import com.rumi.hermesvoice.core.voice.SttRecoveryPolicy
import com.rumi.hermesvoice.core.voice.VoiceTurnConfig
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchAckRegistry
import com.rumi.hermesvoice.core.watchlink.WatchFailureOutbox
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnIntake
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.WatchFailureInbox
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * R11: every terminal failure the Phone keeps owing (not sent, not delivered, acceptance uncertain) is ingested by the REAL
 * WatchListenerService and WatchApp, kept owed until the resumed screen showed it, and acknowledged to the Phone node it came from.
 * A stop and a playback failure are not such failures. Simulated: the Data Layer (mockk); no device. Synthetic content only.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchRetainedFailureR11RoboTest {
    private lateinit var app: WatchApp
    private lateinit var manager: NotificationManager
    private var controller: ActivityController<WatchActivity>? = null
    private val sent: MutableList<Triple<String, String, ByteArray>> = Collections.synchronizedList(mutableListOf())
    private val requests = ConcurrentLinkedQueue<ReaderRequest>()
    private var answerReads = true
    private var failReaderSend = false
    private var newestRole = "assistant"
    private var n = 0

    private val phone = "phone-node"
    private val work = "20260101_120000_workwork"
    private val notSent = "Not sent: the recording was rejected. Say it again"

    private class RefusableStore(val inner: KeyValueStore) : KeyValueStore by inner {
        @Volatile var failing = false
        override fun putString(key: String, value: String) { if (!failing) inner.putString(key, value) }
        override fun commitString(key: String, value: String): Boolean = if (failing) false else inner.commitString(key, value)
    }

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val node = mockk<Node>()
        every { node.id } returns phone
        every { node.isNearby } returns true
        every { node.displayName } returns "phone"
        val info = mockk<CapabilityInfo>()
        every { info.nodes } returns setOf(node)
        every { info.name } returns WatchLinkPaths.CAPABILITY_PHONE
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } returns Tasks.forResult(info)
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            val path = secondArg<String>()
            if (path == WatchLinkPaths.READER_REQUEST) {
                ReaderRequest.decode(thirdArg<ByteArray>())?.let(requests::add)
                if (failReaderSend) return@answers Tasks.forException<Int>(java.io.IOException("harness: phone unreachable"))
            }
            sent += Triple(firstArg<String>(), path, thirdArg<ByteArray>())
            Tasks.forResult(1)
        }
        val channels = mockk<ChannelClient>()
        val data = mockk<DataClient>()
        every { data.getDataItems(any<Uri>()) } returns Tasks.forException<DataItemBuffer>(IllegalStateException("harness: no data item"))
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { Wearable.getDataClient(any<Context>()) } returns data
        every { Wearable.getDataClient(any<Activity>()) } returns data
    }

    private fun pump() {
        while (true) {
            val request = requests.poll() ?: break
            if (!answerReads) continue
            val rows = listOf(ReaderMessageRow(1, "user", "question", false), ReaderMessageRow(2, newestRole, "answer", false))
            app.onReaderResponse(phone, ReaderResponse(request.reqId, request.kind, true, sessionId = request.sessionId, messages = rows, nextOffset = 2).encode())
        }
    }

    private fun idle(ms: Long = 50) {
        pump()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        pump()
    }

    private fun await(timeoutMs: Long = 6000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (condition()) return true; idle(50); Thread.sleep(10) }
        return condition()
    }

    private fun holdsFor(ms: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (!condition()) return false; idle(50); Thread.sleep(10) }
        return condition()
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)
        stubWearable()
    }

    @After fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        unmockkAll()
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
        val companion = Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher").getDeclaredField("Companion").also { it.isAccessible = true }.get(null)
        val context = companion.javaClass.getMethod("getMain").invoke(companion) as kotlin.coroutines.CoroutineContext
        val dispatcher = context[kotlin.coroutines.ContinuationInterceptor]!!
        for (name in listOf("scheduledTrampolineDispatch", "scheduledFrameDispatch")) {
            dispatcher.javaClass.getDeclaredField(name).also { it.isAccessible = true }.setBoolean(dispatcher, false)
        }
    }

    private fun useStore(store: KeyValueStore) {
        fun field(name: String) = WatchApp::class.java.getDeclaredField(name).also { it.isAccessible = true }
        field("localStore\$delegate").set(app, lazyOf(store))
        recreateInbox()
    }

    /** The process ends and a new one starts: the inbox is rebuilt from the file alone and the screen asks it what is owed. */
    private fun recreateInbox() {
        val delegate = WatchApp::class.java.getDeclaredField("failureInbox\$delegate").also { it.isAccessible = true }
        delegate.set(app, lazy { WatchFailureInbox(app.localStore) })
        app.hydrateOwedFailure()
    }

    private fun phoneSays(turnId: String, detail: String = notSent) {
        val service = Robolectric.setupService(WatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns WatchLinkPaths.STATE
        every { event.sourceNodeId } returns phone
        every { event.data } returns TurnStateMessage(turnId, "done", detail, true).encode()
        service.onMessageReceived(event)
        idle(100)
    }

    private fun acks(turnId: String) = sent.toList().filter { it.second == WatchLinkPaths.STATE_ACK && TurnStateMessage.decode(it.third)?.turnId == turnId }

    private fun launch(intent: Intent? = null) {
        controller = Robolectric.buildActivity(WatchActivity::class.java, intent ?: Intent(app, WatchActivity::class.java)).setup()
        idle(300)
    }

    private val uncertain = "Acceptance uncertain: check the conversation. Do not retry this request"
    private val notDelivered = "Not delivered: rejected by Hermes while sending"

    @Test fun N1_aNotDeliveredEndArrivingWhileHiddenIsOwedThenShownAndAcknowledgedOnceToThePhone() {
        val id = "watch-nd-0001"
        phoneSays(id, notDelivered)
        assertEquals(id, app.owedFailure.value?.turnId)
        assertEquals(notDelivered, app.owedFailure.value?.detail)
        assertTrue(holdsFor(1_200) { acks(id).isEmpty() })
        launch()
        assertTrue(await { acks(id).isNotEmpty() })
        assertTrue(acks(id).all { it.first == phone })
        assertNull(app.owedFailure.value)
        assertTrue(holdsFor(600) { acks(id).size == 1 })
    }

    @Test fun N2_anAcceptanceUncertainEndIsOwedAcrossARestartAndNeverSendsAnythingToHermes() {
        val id = "watch-au-0002"
        phoneSays(id, uncertain)
        recreateInbox()
        assertEquals(uncertain, app.owedFailure.value?.detail)
        assertEquals(phone, app.owedFailure.value?.source)
        launch()
        assertTrue(await { acks(id).isNotEmpty() })
        assertTrue(holdsFor(600) { acks(id).size == 1 })
        assertEquals("only an acknowledgement went back; no request was made again", emptyList<String>(),
            sent.toList().map { it.second }.filter { it.startsWith(WatchLinkPaths.TURN_PREFIX) })
    }

    @Test fun N3_theOwedTextIsShownExactlyAsTheStatusLine() {
        phoneSays("watch-text-0003", notDelivered)
        assertEquals(notDelivered, talkHint(app.talk.value, false, app.owedFailure.value!!.detail))
    }

    @Test fun N4_aStopAndAPlaybackFailureAreNotOwedFailures() {
        phoneSays("watch-stop-0004", "Stopped on the phone")
        phoneSays("watch-play-0005", "Delivered to Work; reply playback failed")
        assertNull(app.owedFailure.value)
        launch()
        assertTrue(holdsFor(1_000) { acks("watch-stop-0004").isEmpty() && acks("watch-play-0005").isEmpty() })
    }

    /** The REAL Phone-side sender (intake, orchestrator, outbox) with a failing hand over; its STATE bytes go straight into the REAL listener. */
    private fun phoneEndsWith(turnId: String, failure: Throwable): WatchFailureOutbox {
        val acks = WatchAckRegistry()
        val outbox = WatchFailureOutbox(InMemoryKeyValueStore())
        val speech = object : HermesSpeechGateway {
            override suspend fun transcribe(audio: ByteArray, mimeType: String) = "synthetic request"
            override suspend fun speak(text: String) = SpokenAudio(text.toByteArray(), "audio/mpeg")
        }
        val conversations = object : HermesConversationPort {
            override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) = throw UnsupportedOperationException()
            override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
                if (storedSessionId == "router_session") return object : SubmittedTurn {
                    override val submitStatus = "streaming"
                    override val attributable = true
                    override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) =
                        onEvent(RecipientEvent.Complete("""{"destination":"work","ack":"Sending."}""", "complete"))
                    override fun release() {}
                }
                throw failure
            }
        }
        val orchestrator = VoiceTurnOrchestrator(speech, conversations,
            config = { VoiceTurnConfig("router_session", DestinationAllowlist.create(listOf(DestinationEntry("work", "work_session")), "router_session"), ResponsePlaybackSettings()) },
            inputGate = { _, _ -> AudioInputVerdict.USABLE }, sttPolicy = SttRecoveryPolicy(budgetMs = 60_000, maxAttempts = 2, backoffMs = listOf(1_000)))
        val transport = object : WatchTransport {
            override val nodeId = phone
            override suspend fun sendMessage(path: String, bytes: ByteArray) {
                if (path != WatchLinkPaths.STATE) return
                val service = Robolectric.setupService(WatchListenerService::class.java)
                val event = mockk<MessageEvent>()
                every { event.path } returns path
                every { event.sourceNodeId } returns phone
                every { event.data } returns bytes
                service.onMessageReceived(event)
            }
            override suspend fun sendChannel(path: String, bytes: ByteArray) {
                val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
                acks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
            }
        }
        kotlinx.coroutines.runBlocking {
            WatchTurnIntake(orchestrator, acks, failures = outbox).onTurnChannel(WatchLinkPaths.turnPath(turnId),
                WatchTurnUpload(turnId, TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, ByteArray(300) { 7 }).toFrame().encode(), transport)
        }
        idle(100)
        return outbox
    }

    @Test fun N5_theActualPhoneSenderBytesForAFailedHandOverAreIngestedByTheActualWatchListener() {
        val refused = "watch-wire-0006"
        val outbox = phoneEndsWith(refused, HermesHttpException(403, "synthetic refusal"))
        assertTrue(app.owedFailure.value?.detail.orEmpty(), app.owedFailure.value?.detail.orEmpty().startsWith("Not delivered"))
        assertEquals(refused, app.owedFailure.value?.turnId)
        launch()
        assertTrue(await { acks(refused).isNotEmpty() })
        assertTrue("the Phone's outbox settles on that acknowledgement", outbox.acknowledge(refused, phone))
        val uncertainId = "watch-wire-0007"
        recreateInbox()
        phoneEndsWith(uncertainId, HermesPromptUncertainException(java.io.IOException("synthetic")))
        assertTrue("the resumed screen shows it and acknowledges it to the Phone node", await { acks(uncertainId).isNotEmpty() })
        assertTrue(acks(uncertainId).all { it.first == phone })
        assertNull(app.owedFailure.value)
        assertEquals("no request was made again from the Watch", emptyList<String>(),
            sent.toList().map { it.second }.filter { it.startsWith(WatchLinkPaths.TURN_PREFIX) })
    }
}
