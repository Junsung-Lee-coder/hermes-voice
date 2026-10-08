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
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.LoadStatus
import com.rumi.hermesvoice.core.watchlink.ReaderSurface
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchNavigation
import com.rumi.hermesvoice.core.watchlink.WatchNavigationGuard.Verdict
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.time.Duration
import org.json.JSONObject
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
 * SCRATCH HARNESS (never packaged). The Watch side of "open the routed conversation on Watch", through the REAL WatchApp (settings
 * replica, navigation guard, reader state and request dispatch) and the REAL WatchListenerService NAVIGATE adapter. Platform inputs
 * (Data Layer clients) are simulated; no device, emulator or live Phone is involved.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchNavigationRoboTest {
    private lateinit var app: WatchApp
    private lateinit var messages: MessageClient
    private var revision = 0L

    private val phone = "phone-node"
    private val work = "20260101_120000_workwork"
    private val other = "20260101_130000_otherone"

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
        messages = mockk()
        every { messages.sendMessage(any(), any(), any()) } returns Tasks.forResult(1)
        val channels = mockk<ChannelClient>()
        val data = mockk<DataClient>()
        every { data.getDataItems(any<Uri>()) } returns Tasks.forException<DataItemBuffer>(IllegalStateException("harness: no data item"))
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { Wearable.getDataClient(any<Context>()) } returns data
    }

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun snapshot(autoNavigate: Boolean, rev: Long = ++revision): String =
        WatchSettings(watchAutoNavigateToRouted = autoNavigate, revision = rev).toJson()

    private fun option(on: Boolean) {
        app.applySettings(snapshot(on))
        idle()
    }

    private fun startTurn(): String {
        app.discard("harness: the previous recording ended")
        val turnId = app.newTurn(TurnTrigger.PUSH_TO_TALK)
        assertNotNull("the Watch started a request", turnId)
        app.navigationGuard.onUploadNode(turnId!!, phone)
        return turnId
    }

    private fun navigate(turnId: String, session: String, generation: Long, created: Boolean = false, from: String = phone): Verdict? {
        val verdict = app.onNavigation(from, WatchNavigation(turnId, session, generation, created).encode())
        idle()
        return verdict
    }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        stubWearable()
    }

    @After
    fun tearDown() = unmockkAll()

    @Test fun N01_aDeliveredNavigationWithTheOptionOnSelectsTheConversationAndHydratesItsHistory() {
        option(true)
        assertNull("nothing is selected before", app.reader.value.selectedSessionId)
        val turnId = startTurn()
        assertEquals(Verdict.APPLY, navigate(turnId, work, app.navigationGuard.generationFor(turnId)))
        assertEquals(work, app.reader.value.selectedSessionId)
        assertEquals(ReaderSurface.CHAT, app.reader.value.surface)
        assertEquals("the reader's history for it was requested", LoadStatus.LOADING, app.reader.value.selectedHistory?.status)
        verify(atLeast = 1) { messages.sendMessage(phone, WatchLinkPaths.READER_REQUEST, any()) }
    }

    @Test fun N02_withTheOptionOffNothingMovesAndNothingIsRequested() {
        option(false)
        val turnId = startTurn()
        assertNull(navigate(turnId, work, app.navigationGuard.generationFor(turnId)))
        assertNull(app.reader.value.selectedSessionId)
        verify(exactly = 0) { messages.sendMessage(any(), WatchLinkPaths.READER_REQUEST, any()) }
    }

    @Test fun N03_aNavigationForATurnThisWatchDidNotStartIsRefused() {
        option(true)
        assertEquals(Verdict.UNKNOWN_TURN, navigate("phone-origin-turn-0001", work, 0))
        assertNull(app.reader.value.selectedSessionId)
    }

    @Test fun N04_aRepeatedNavigationIsRefusedAndDoesNotOverwriteALaterSelection() {
        option(true)
        val turnId = startTurn()
        val gen = app.navigationGuard.generationFor(turnId)
        assertEquals(Verdict.APPLY, navigate(turnId, work, gen))
        app.selectSession(other)
        idle()
        assertEquals(Verdict.DUPLICATE, navigate(turnId, work, gen))
        assertEquals("the user's later choice stays", other, app.reader.value.selectedSessionId)
    }

    @Test fun N05_aSelectionOrScreenChangeMadeAfterTheRequestBeganIsNeverOverwritten() {
        option(true)
        val turnId = startTurn()
        val gen = app.navigationGuard.generationFor(turnId)
        app.selectSession(other)
        idle()
        assertEquals(Verdict.STALE_SELECTION, navigate(turnId, work, gen))
        assertEquals(other, app.reader.value.selectedSessionId)
        val second = startTurn()
        val genSecond = app.navigationGuard.generationFor(second)
        app.toggleReaderSurface()
        idle()
        val surface = app.reader.value.surface
        assertEquals(Verdict.STALE_SELECTION, navigate(second, work, genSecond))
        assertEquals("a screen change after the request is also the user's choice", surface, app.reader.value.surface)
        assertEquals(other, app.reader.value.selectedSessionId)
    }

    @Test fun N06_anOlderRequestIsSupersededByANewerOne() {
        option(true)
        val first = startTurn()
        val genFirst = app.navigationGuard.generationFor(first)
        val second = startTurn()
        assertEquals(Verdict.SUPERSEDED_TURN, navigate(first, work, genFirst))
        assertNull(app.reader.value.selectedSessionId)
        assertEquals(Verdict.APPLY, navigate(second, other, app.navigationGuard.generationFor(second)))
        assertEquals(other, app.reader.value.selectedSessionId)
    }

    @Test fun N07_aNavigationFromAnotherNodeIsRefused() {
        option(true)
        val turnId = startTurn()
        assertEquals(Verdict.WRONG_NODE, navigate(turnId, work, app.navigationGuard.generationFor(turnId), from = "someone-else"))
        assertNull(app.reader.value.selectedSessionId)
    }

    @Test fun N08_stopForgetsPendingRequestsSoALateNavigationDoesNothing() {
        option(true)
        val turnId = startTurn()
        val gen = app.navigationGuard.generationFor(turnId)
        app.cancelPendingUploads("stop")
        idle()
        assertEquals(Verdict.UNKNOWN_TURN, navigate(turnId, work, gen))
        assertNull(app.reader.value.selectedSessionId)
    }

    @Test fun N09_aCreatedDestinationAlsoRefreshesTheConversationList() {
        option(true)
        val turnId = startTurn()
        clearMocks(messages, answers = false, recordedCalls = true)
        assertEquals(Verdict.APPLY, navigate(turnId, work, app.navigationGuard.generationFor(turnId), created = true))
        assertEquals(work, app.reader.value.selectedSessionId)
        verify(exactly = 2) { messages.sendMessage(phone, WatchLinkPaths.READER_REQUEST, any()) }
    }

    @Test fun N10_anUndecodableOrInvalidPayloadChangesNothing() {
        option(true)
        startTurn()
        for (bytes in listOf("not json".toByteArray(), "{}".toByteArray(),
            JSONObject().put("v", 2).put("turn_id", "abcdefgh1").put("session_id", work).put("gen", 0).toString().toByteArray(),
            JSONObject().put("v", 1).put("turn_id", "abcdefgh1").put("session_id", "../x").put("gen", 0).toString().toByteArray(),
            JSONObject().put("v", 1).put("turn_id", "abcdefgh1").put("session_id", work).put("gen", -1).toString().toByteArray(),
            JSONObject().put("v", 1).put("turn_id", "abcdefgh1").put("session_id", work).put("gen", 0).put("created", "yes").toString().toByteArray())) {
            assertNull(String(bytes), app.onNavigation(phone, bytes))
        }
        assertNull(app.reader.value.selectedSessionId)
    }

    @Test fun N11_theOptionFollowsTheRevisionedSnapshotAndAMissingKeyMeansOff() {
        app.applySettings(snapshot(true, rev = 5))
        assertTrue(app.settings.value.watchAutoNavigateToRouted)
        app.applySettings(snapshot(false, rev = 5))
        assertTrue("an equal revision is refused", app.settings.value.watchAutoNavigateToRouted)
        app.applySettings(snapshot(false, rev = 4))
        assertTrue("a stale revision is refused", app.settings.value.watchAutoNavigateToRouted)
        app.applySettings(JSONObject(snapshot(true, rev = 6)).put("watch_auto_navigate_to_routed", JSONObject.NULL).toString())
        assertTrue("a malformed value rejects the whole snapshot", app.settings.value.watchAutoNavigateToRouted)
        assertEquals(5, app.settings.value.revision)
        app.applySettings(JSONObject(snapshot(true, rev = 7)).apply { remove("watch_auto_navigate_to_routed") }.toString())
        assertFalse("a snapshot without the key (older Phone) is off", app.settings.value.watchAutoNavigateToRouted)
        assertEquals(7, app.settings.value.revision)
    }

    @Test fun N12_theListenerServiceRoutesTheNavigatePathToTheApp() {
        option(true)
        val turnId = startTurn()
        val payload = WatchNavigation(turnId, work, app.navigationGuard.generationFor(turnId)).encode()
        val service = Robolectric.setupService(WatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns WatchLinkPaths.NAVIGATE
        every { event.sourceNodeId } returns phone
        every { event.data } returns payload
        service.onMessageReceived(event)
        idle(200)
        assertEquals(work, app.reader.value.selectedSessionId)
        assertEquals("the screen was not launched by the navigation", null, shadowOf(app).nextStartedActivity)
    }
}
