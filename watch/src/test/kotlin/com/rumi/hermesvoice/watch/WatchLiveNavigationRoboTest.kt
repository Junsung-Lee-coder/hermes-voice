package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.PowerManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.ReaderSessionRow
import com.rumi.hermesvoice.core.watchlink.ReaderSurface
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

/**
 * Watch Live and the Watch's two screens (parent HOLD W1 of the owner's UI contract of 2026-10-27): a swipe that changes nothing (the
 * list with nothing selected) leaves an active Watch Live untouched; Back from a conversation's history to the list is a real reader
 * navigation and, like every Watch reader navigation, ends the Watch Live locally first, telling the Phone to end only the call (accepted
 * Hermes work goes on, no request is stopped); a later call is not touched by that earlier gesture, and hiding the app is not navigation.
 * The ACTUAL WatchActivity, WatchApp and WatchLiveVoiceRuntime. Simulated: the Data Layer (mockk; reader pages answered as the Phone
 * would), the Phone's relay packets and the native media boundary. No device or provider.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchLiveNavigationRoboTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var app: WatchApp
    private lateinit var live: WatchLiveVoiceRuntime
    private var scenario: ActivityScenario<WatchActivity>? = null
    private val sent: MutableList<Triple<String, String, ByteArray>> = Collections.synchronizedList(mutableListOf())
    private val requests = ConcurrentLinkedQueue<ReaderRequest>()
    private val rows = (1..3).map { ReaderSessionRow("qa-fixture-$it", "QA fixture $it", "qa$it", "synthetic rows", 0.0) }
    private val peers = ArrayList<Peer>()
    private var now = 1000L; private var id = ""; private var seq = 0L; private var media = "m1"

    private class Peer : WatchLiveMedia {
        var mic: ((Boolean) -> Unit)? = null; var event: ((String) -> Unit)? = null
        var closed = false; var silenced = false
        override suspend fun connect(signal: suspend (String) -> String, event: (String) -> Unit) {
            this.event = event; mic?.invoke(true)
            assertEquals("v=0\r\nanswer\r\n", signal("v=0\r\nwatch-offer\r\n"))
            event("{\"type\":\"session.started\",\"session\":{\"id\":\"provider\"}}")
        }
        override fun send(json: String): Boolean = true
        override fun observeActivity(listener: (Boolean) -> Unit) {}
        override fun observeMicrophone(listener: (Boolean) -> Unit) { mic = listener }
        override fun mute(muted: Boolean) { mic?.invoke(!muted) }
        override fun output(enabled: Boolean) {}
        override fun silence() { silenced = true; mic?.invoke(false) }
        override fun close() { closed = true }
    }

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
        app = RuntimeEnvironment.getApplication() as WatchApp
        app.localStore.putString("live_control_id", ""); app.localStore.putString("live_control_node", "")
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        stubWearable()
        app.applySettings(WatchSettings(phoneLiveEnabled = false, watchLiveEnabled = true, revision = 100).toJson())
        live = app.live; live.now = { now }; live.admission = { live.admitted(it) }
        live.routeFactory = { _ -> object : WatchLiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        live.mediaFactory = { Peer().also { peers += it } }
        idle(50)
    }
    @After fun tearDown() { live.end(); idle(100); scenario?.close(); WatchVoiceService.running?.finish(); unmockkAll() }

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
    private fun launch() { scenario = ActivityScenario.launch(WatchActivity::class.java); idle(500) }
    private fun swipeLeft() { compose.onNodeWithTag("reader_root").performTouchInput { swipeLeft() }; idle(300) }
    private fun swipeRight() { compose.onNodeWithTag("reader_root").performTouchInput { swipeRight() }; idle(300) }
    private fun back() { scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; idle(300) }
    private fun hides() = ShadowLog.getLogsForTag(TAG).count { it.msg.contains("task moved to back") }
    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun onSelector() = app.reader.value.surface == ReaderSurface.SESSIONS && has("session_list") && !has("chat_list")
    private fun controls(op: String) = sent.toList().mapNotNull { runCatching { JSONObject(String(it.third, Charsets.UTF_8)) }.getOrNull() }.filter { it.optString("op") == op }
    private fun packet(op: String, body: String = "") = live.onStatus(PHONE, WatchLiveWire.packet(id, media, "phone-epoch", ++seq, op, body))
    /** A Watch Live call through the Phone relay, up to its microphone being live. */
    private fun ready() {
        val starts = controls("start").size
        live.toggle(); idle(100)
        id = controls("start").also { assertEquals("one Start", starts + 1, it.size) }.last().getString("id")
        media = "media-" + java.util.UUID.randomUUID().toString(); seq = 0; packet("open"); idle(100)
        assertEquals("v=0\r\nwatch-offer\r\n", sent.toList().mapNotNull { WatchLiveWire.parse(it.third) }.last { it.op == "offer" }.body)
        packet("answer", "v=0\r\nanswer\r\n"); idle(100)
        assertTrue("the Watch Live is live", live.state.value.active && live.state.value.inputActive)
    }

    @Test fun V27_01_aSwipeThatChangesNothingOnTheListLeavesAnActiveWatchLiveUntouched() {
        launch()
        assertTrue(onSelector()); assertNull(app.reader.value.selectedSessionId)
        ready()
        val ends = controls("end").size
        swipeLeft()
        assertTrue("nothing to open: the list stays", onSelector())
        assertTrue("the Watch Live goes on", live.state.value.active && live.state.value.inputActive)
        assertFalse(peers.last().closed); assertFalse(peers.last().silenced)
        assertEquals("the Phone was not told to end it", ends, controls("end").size)
    }

    @Test fun V27_02_backFromAHistoryIsReaderNavigationAndEndsTheWatchLiveLocallyFirst() {
        launch()
        compose.onNodeWithTag("session_qa2").performClick(); idle(300)
        assertTrue(app.reader.value.surface == ReaderSurface.CHAT && app.reader.value.selectedSessionId == "qa-fixture-2")
        ready()
        val ends = controls("end").size
        back()
        assertTrue("Back returned to the list", onSelector()); assertEquals("not hidden", 0, hides())
        assertFalse("a real reader navigation ends the Watch Live", live.state.value.active)
        assertTrue("its microphone stopped here first", peers.last().silenced || peers.last().closed)
        assertEquals("the Phone is told to end only the call", ends + 1, controls("end").size)
        assertTrue("no voice request was stopped: accepted Hermes work goes on", sent.none { it.second == WatchLinkPaths.STOP })
        assertEquals("the selection is kept", "qa-fixture-2", app.reader.value.selectedSessionId)
    }

    @Test fun V27_03_aLaterCallIsNotTouchedByThatBackAndHidingTheAppIsNotNavigation() {
        launch()
        compose.onNodeWithTag("session_qa1").performClick(); idle(300)
        ready()
        val first = id
        back()
        assertFalse(live.state.value.active)
        ready()
        assertNotEquals("a new call", first, id)
        idle(1_000)
        assertTrue("the earlier Back does not touch the new call", live.state.value.active && live.state.value.inputActive)
        val ends = controls("end").size
        swipeRight()
        assertEquals("on the list the right swipe hides the app", 1, hides())
        assertTrue("hiding is not reader navigation: the call goes on", live.state.value.active)
        assertEquals(ends, controls("end").size)
    }

    companion object {
        private const val TAG = "HermesVoiceWatch"
        private const val PHONE = "phone-node"
    }
}
