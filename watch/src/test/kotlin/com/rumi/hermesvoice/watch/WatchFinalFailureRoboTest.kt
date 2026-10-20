package com.rumi.hermesvoice.watch

import android.Manifest
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
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * SCRATCH HARNESS (never packaged). The Phone's "not sent" terminal state through the REAL WatchListenerService and WatchApp: it is shown
 * even for a request that was no longer the current one, acknowledged to the Phone node it came from, shown once however often the Phone
 * repeats it, and the Watch asks the Phone for what it missed when it sees the Phone again. Simulated: the Data Layer (mockk).
 * The new wire paths are given as literals so the same bytes also run on the tree that does not know them.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchFinalFailureRoboTest {
    private lateinit var app: WatchApp
    private var controller: ActivityController<WatchActivity>? = null
    private class Sent(val node: String, val path: String, val data: ByteArray)

    private val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val node = mockk<Node>()
        every { node.id } returns "phone-node"
        every { node.isNearby } returns true
        every { node.displayName } returns "phone"
        val info = mockk<CapabilityInfo>()
        every { info.nodes } returns setOf(node)
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
        idle(100)
    }

    private fun shown() = talkHint(app.talk.value, false)

    private fun acks(turnId: String) = sent.toList().filter { it.path == "/hv/v1/state/ack" && TurnStateMessage.decode(it.data)?.turnId == turnId }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
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

    private fun showOnScreen() {
        controller = Robolectric.buildActivity(WatchActivity::class.java, Intent(app, WatchActivity::class.java)).setup()
        idle(300)
    }

    @Test fun K01_aNotSentStateForTheCurrentRequestIsShown() {
        val turn = app.newTurn(TurnTrigger.PUSH_TO_TALK)!!
        phoneSays(TurnStateMessage(turn, "transcribing", "", false))
        phoneSays(TurnStateMessage(turn, "done", "Not sent: the recording was rejected. Say it again", true))
        assertTrue(shown(), shown().startsWith("Not sent"))
    }

    @Test fun K02_aNotSentStateForAnEarlierRequestIsShownAndNotDropped() {
        val first = app.newTurn(TurnTrigger.PUSH_TO_TALK)!!
        phoneSays(TurnStateMessage(first, "transcribing", "", false))
        val second = app.newTurn(TurnTrigger.PUSH_TO_TALK)!!
        assertTrue("the first request moved to the earlier ones", first in app.talk.value.earlier)
        phoneSays(TurnStateMessage(second, "responding", "", false))
        phoneSays(TurnStateMessage(first, "done", "Not sent: the recording was rejected. Say it again", true))
        assertFalse("the failed request no longer waits", first in app.talk.value.earlier)
        assertTrue("the failure is visible: '${shown()}'", shown().contains("Not sent"))
        assertTrue("the newer request still waits", app.talk.value.turnId == second)
    }

    @Test fun K03_aNotSentStateIsAcknowledgedToThePhoneNodeItCameFromAndNotToAnyOther() {
        val turn = app.newTurn(TurnTrigger.PUSH_TO_TALK)!!
        phoneSays(TurnStateMessage(turn, "done", "Not sent: the recording was rejected. Say it again", true))
        // Migrated (review F01): e52e52d7 asserted `assertTrue("acknowledged", await { acks(turn).isNotEmpty() })` right here, with no screen
        // on. The acknowledgement now follows the resumed screen showing it, so it is first withheld and then sent.
        assertTrue("not acknowledged while nothing showed it", acks(turn).isEmpty())
        showOnScreen()
        assertTrue("acknowledged", await { acks(turn).isNotEmpty() })
        assertTrue(acks(turn).all { it.node == "phone-node" })
        assertTrue("sent once", acks(turn).size == 1)
    }

    @Test fun K04_aFailureTheWatchHadNoRecordOfIsShownOnceAndAcknowledgedEachTimeItIsRepeated() {
        val lost = "watch-lost-0001"
        phoneSays(TurnStateMessage(lost, "done", "Not sent: the recording was rejected. Say it again", true))
        assertTrue("shown although this Watch no longer tracked the request: '${shown()}'", shown().contains("Not sent"))
        // Migrated (review F01): e52e52d7 acknowledged here without any screen; now only after the resumed screen showed it.
        assertTrue("not acknowledged before it was shown", acks(lost).isEmpty())
        showOnScreen()
        assertTrue(await { acks(lost).isNotEmpty() })

        app.newTurn(TurnTrigger.PUSH_TO_TALK)!!
        assertFalse("the next request starts clean", shown().contains("Not sent"))
        phoneSays(TurnStateMessage(lost, "done", "Not sent: the recording was rejected. Say it again", true))
        assertFalse("a repeat of a handled failure is not shown again: '${shown()}'", shown().contains("Not sent"))
        assertTrue("but it is acknowledged again so the Phone can stop", await { acks(lost).size >= 2 })
        assertFalse("and still not shown again after the acknowledgement: '${shown()}'", shown().contains("Not sent"))
    }

    @Test fun K05_theWatchAsksThePhoneForWhatItMissedWhenItSeesThePhoneAgain() {
        runBlocking { app.refreshPhoneReachable() }
        assertTrue("a sync request went to the Phone node", await { sent.any { it.path == "/hv/v1/state/sync" && it.node == "phone-node" } })
    }

    @Test fun K06_theWatchManifestStillDeliversTheStatePathToTheWatchListener() {
        val intent = Intent("com.google.android.gms.wearable.MESSAGE_RECEIVED").setPackage(app.packageName).setData(Uri.parse("wear://phone-node${WatchLinkPaths.STATE}"))
        assertTrue(app.packageManager.queryIntentServices(intent, 0).any { it.serviceInfo.name == WatchListenerService::class.java.name })
    }
}
