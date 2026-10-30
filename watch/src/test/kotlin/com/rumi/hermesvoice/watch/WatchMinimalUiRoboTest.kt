package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.PowerManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
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
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.ReaderSessionRow
import com.rumi.hermesvoice.core.watchlink.ReaderSurface
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchPhase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * The Watch's two screens (owner's UI contract of 2026-10-27): the conversation selector and the selected conversation's history.
 * A fresh launcher entry opens the selector, whatever is selected; Back or the right swipe in a history returns to the selector;
 * nothing of the old Home (phone status, hints, Watch Live controls, the log chip) is drawn; the whole-screen hold still starts and
 * stops a recording, which says so on either screen; a request the Phone could not send is said on either screen and acknowledged.
 * The ACTUAL WatchActivity / WatchApp / WatchVoiceRuntime / WatchListenerService under Robolectric. Simulated: the Data Layer
 * (mockk; reader pages are answered by this test as the Phone would), AudioRecord PCM (synthetic noise), the platform service start.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchMinimalUiRoboTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var app: WatchApp
    private var scenario: ActivityScenario<WatchActivity>? = null
    private val sent: MutableList<Triple<String, String, ByteArray>> = Collections.synchronizedList(mutableListOf())
    private val requests = ConcurrentLinkedQueue<ReaderRequest>()
    private val rows = (1..3).map { ReaderSessionRow("qa-fixture-$it", "QA fixture $it", "qa$it", "synthetic rows", 0.0) }

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val node = mockk<Node>()
        every { node.id } returns PHONE
        every { node.isNearby } returns true
        every { node.displayName } returns "phone"
        val info = mockk<CapabilityInfo>()
        every { info.nodes } returns setOf(node)
        every { info.name } returns WatchLinkPaths.CAPABILITY_PHONE
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } returns Tasks.forResult(info)
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            if (secondArg<String>() == WatchLinkPaths.READER_REQUEST) ReaderRequest.decode(thirdArg<ByteArray>())?.let(requests::add)
            sent += Triple(firstArg<String>(), secondArg<String>(), thirdArg<ByteArray>())
            Tasks.forResult(1)
        }
        val channels = mockk<ChannelClient>()
        every { channels.openChannel(any(), any()) } answers {
            val c = mockk<ChannelClient.Channel>()
            every { c.path } returns secondArg()
            every { c.nodeId } returns PHONE
            Tasks.forResult(c)
        }
        every { channels.getOutputStream(any()) } answers { Tasks.forResult(ByteArrayOutputStream()) }
        val data = mockk<DataClient>()
        every { data.getDataItems(any<Uri>()) } returns Tasks.forException<DataItemBuffer>(IllegalStateException("harness: no data item"))
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { Wearable.getDataClient(any<Context>()) } returns data
        every { Wearable.getDataClient(any<Activity>()) } returns data
    }

    @Before fun setUp() {
        WatchVoiceService.running?.finish()
        ShadowLog.stream = System.out
        RecordingVibratorShadow.events.clear()
        app = RuntimeEnvironment.getApplication() as WatchApp
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        stubWearable()
        app.applySettings(WatchSettings(WakeLocation.WATCH, "루미", revision = 1).toJson())
        idle(50)
    }
    @After fun tearDown() { scenario?.close(); WatchVoiceService.running?.finish(); unmockkAll() }

    /** The Phone's side of the reader, synthetic: the three fixture conversations, and two messages for any history read. */
    private fun answerReads() {
        while (true) {
            val r = requests.poll() ?: break
            val response = if (r.kind == ReaderKind.SESSIONS) ReaderResponse(r.reqId, r.kind, true, sessions = rows)
                else ReaderResponse(r.reqId, r.kind, true, sessionId = r.sessionId, nextOffset = 2,
                    messages = listOf(ReaderMessageRow(1, "user", "question of ${r.sessionId}", false), ReaderMessageRow(2, "assistant", "answer of ${r.sessionId}", false)))
            app.onReaderResponse(PHONE, response.encode())
        }
    }
    private fun idle(ms: Long) { answerReads(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); answerReads() }
    private fun launch(intent: Intent? = null) {
        scenario = (if (intent == null) ActivityScenario.launch(WatchActivity::class.java) else ActivityScenario.launch<WatchActivity>(intent))
        idle(500)
    }
    private fun deliverService(): Boolean {
        val intent = shadowOf(app).nextStartedService ?: return false
        Robolectric.buildService(WatchVoiceService::class.java, intent).create().startCommand(0, 1)
        idle(200)
        return true
    }
    private fun startNoisePcm() {
        val random = Random(7)
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, isBlocking: Boolean): Int {
                    for (i in 0 until sizeInBytes / 2) {
                        val s = random.nextInt(-40, 40)
                        audioData[offsetInBytes + 2 * i] = (s and 0xff).toByte()
                        audioData[offsetInBytes + 2 * i + 1] = (s shr 8 and 0xff).toByte()
                    }
                    return sizeInBytes
                }
            }
        }
    }
    private fun holdToTalk() { compose.onNodeWithTag("reader_root").performTouchInput { longClick(durationMillis = 1_300) }; idle(300) }
    private fun swipeLeft() { compose.onNodeWithTag("reader_root").performTouchInput { swipeLeft() }; idle(300) }
    private fun swipeRight() { compose.onNodeWithTag("reader_root").performTouchInput { swipeRight() }; idle(300) }
    private fun back() { scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; idle(300) }
    private fun hides() = ShadowLog.getLogsForTag(TAG).count { it.msg.contains("task moved to back") }
    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun surface() = app.reader.value.surface
    private fun onSelector() = surface() == ReaderSurface.SESSIONS && has("session_list") && !has("chat_list")
    private fun inHistory(id: String) = surface() == ReaderSurface.CHAT && app.reader.value.selectedSessionId == id && has("chat_list")
    /** Nothing of the old Home or the removed panels is drawn. */
    private fun assertTwoScreensOnly() {
        for (text in listOf("Phone connected", "Checking phone", "Swipe left for conversations", "Hold anywhere",
                "Hold 1 s to talk", "Say the wake phrase", "Listening for the wake phrase", "Watch Live", "About Watch Live", "Idle ",
                "Clear this watch's log", "Updating…"))
            assertFalse("'$text' is not drawn", shown(text))
        for (tag in listOf("watch_live_enabled", "watch_live_help", "watch_live_idle", "watch_live_mute", "clear_watch_diagnostics"))
            assertFalse("no $tag", has(tag))
    }
    private fun phoneSays(turnId: String, detail: String) {
        val service = Robolectric.setupService(WatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns WatchLinkPaths.STATE
        every { event.sourceNodeId } returns PHONE
        every { event.data } returns TurnStateMessage(turnId, "done", detail, true).encode()
        service.onMessageReceived(event)
        idle(300)
    }
    private fun acks(turnId: String) = sent.toList().filter { it.second == WatchLinkPaths.STATE_ACK && TurnStateMessage.decode(it.third)?.turnId == turnId }
    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (condition()) return true; idle(50); Thread.sleep(10) }
        return condition()
    }

    @Test fun W27_01_aFirstLaunchWithNothingSelectedOpensTheSelectorAndNothingElse() {
        launch()
        assertTrue("the conversation selector is the screen", onSelector())
        assertTrue("its conversations are listed", has("session_qa1") && has("session_qa3"))
        assertTwoScreensOnly()
        assertFalse("no status line while nothing records or failed", has("talk_status"))
    }

    @Test fun W27_02_aLaterLaunchWithAStoredSelectionOpensTheSelectorKeepsTheSelectionAndBackReturnsToIt() {
        app.seedReaderForQa(6, 6)
        launch()
        assertTrue("the selector, not the stored conversation", onSelector())
        assertEquals("the stored selection is kept", "qa-fixture-1", app.reader.value.selectedSessionId)
        assertTwoScreensOnly()
        compose.onNodeWithTag("session_qa2").performClick(); idle(300)
        assertTrue("another conversation opens in its history", inHistory("qa-fixture-2") && shown("answer of qa-fixture-2"))
        back()
        assertTrue("Back from a history returns to the selector", onSelector())
        assertEquals("not sent to the background", 0, hides())
        compose.onNodeWithTag("session_qa1").performClick(); idle(300)
        assertTrue(inHistory("qa-fixture-1"))
        assertTwoScreensOnly()
        swipeRight()
        assertTrue("the back swipe from a history returns to the selector", onSelector())
        assertEquals("still not hidden", 0, hides())
        swipeRight()
        assertEquals("from the selector the back swipe sends the app to the background", 1, hides())
    }

    @Test fun W27_03_recreationAndAForegroundReturnKeepAnOpenHistory() {
        launch()
        if (surface() != ReaderSurface.SESSIONS) swipeLeft()
        compose.onNodeWithTag("session_qa2").performClick(); idle(300)
        assertTrue(inHistory("qa-fixture-2"))
        scenario!!.recreate(); idle(500)
        assertTrue("recreation keeps the open history", inHistory("qa-fixture-2"))
        scenario!!.moveToState(Lifecycle.State.CREATED); idle(200)
        scenario!!.moveToState(Lifecycle.State.RESUMED); idle(500)
        assertTrue("a foreground return keeps it too", inHistory("qa-fixture-2"))
    }

    @Test fun W27_04_anArrivalAlertTapOpensItsConversationsHistoryDirectly() {
        launch(WatchReplyAlertNotifier.openIntent(app, ReplyAlert("tap-1#later1", "qa-fixture-3")))
        assertTrue("the named conversation's history", inHistory("qa-fixture-3"))
        assertTwoScreensOnly()
    }

    @Test fun W27_05_aRequestThePhoneCouldNotSendIsSaidOnTheSelectorAndAcknowledged() {
        app.seedReaderForQa(6, 6)
        launch()
        if (surface() != ReaderSurface.SESSIONS) swipeLeft()
        assertTrue(onSelector())
        phoneSays("watch-ui-0005", "Not sent: the recording was rejected. Say it again")
        assertTrue("said on the selector", shown("Not sent: the recording was rejected"))
        assertTrue("and acknowledged once it was on screen", await { acks("watch-ui-0005").isNotEmpty() })
    }

    @Test fun W27_06_aRecordingStartedOnTheSelectorSaysSoThereAndTheNextHoldStopsIt() {
        startNoisePcm()
        app.seedReaderForQa(6, 6)
        launch()
        deliverService()
        if (surface() != ReaderSurface.SESSIONS) swipeLeft()
        assertTrue(onSelector())
        holdToTalk()
        assertTrue("recording", app.voice.capturing && app.talk.value.phase == WatchPhase.RECORDING)
        assertTrue("the selector says it records", shown("● Recording"))
        holdToTalk()
        assertFalse("the next hold stops it", app.talk.value.phase == WatchPhase.RECORDING)
        assertTrue(onSelector())
    }

    @Test fun W27_07_aLeftSwipeOnTheSelectorWithNothingSelectedStaysOnTheSelector() {
        launch()
        swipeLeft(); swipeLeft()
        assertTrue("never an empty history or the old Home", onSelector())
        assertTwoScreensOnly()
    }

    companion object {
        private const val TAG = "HermesVoiceWatch"
        private const val PHONE = "phone-node"
    }
}
