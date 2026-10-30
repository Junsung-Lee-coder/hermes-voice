package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import io.mockk.every
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Which Live ends the Phone says beside Talk (parent HOLD P2 of the owner's UI contract of 2026-10-27): a routine end (Live mode OFF,
 * an idle end, a Stop from the Watch) is not a failure of the Phone user's action, and a Watch call's failure is not painted as the Phone's;
 * a Phone call that broke off by itself after it was ready (the provider's error, the audio route lost) is said, once, and nothing is
 * reconnected. The real MainActivity, PhoneViewModel, PhoneLiveVoiceController and PhoneLiveWatchControl against the dashboard contract
 * double; the Live audio route, the media and the Watch node are synthetic adapters. No device or provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneLiveEndTruthRoboTest : PhoneNavRoboBase() {
    private val media = ArrayList<Media>()
    private val lost = ArrayList<() -> Unit>()
    private val held = CountDownLatch(1); private val release = CountDownLatch(1)
    @Volatile private var now = 1_000L
    private inner class Media : LiveTransport {
        @Volatile var event: ((String) -> Unit)? = null
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) {
            this.event = event
            event("{\"type\":\"session.started\",\"session\":{\"id\":\"live-end-truth\"}}")
        }
        override fun send(json: String) = true
        override fun mute(muted: Boolean) {}
        override fun output(enabled: Boolean) {}
        override fun silence() {}
        override fun close() {}
        fun error() { event?.invoke(JSONObject().put("type", "error").put("error", JSONObject().put("type", "server_error")).toString()) }
    }
    @Before fun liveAdapters() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.live.routeFactory = { onLost -> lost += onLost; object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.transportFactory = { Media().also { media += it } }
        app.live.admission = { app.live.admitted(it) }
        app.live.monotonicMs = { now }
    }
    @After fun releaseHeld() { release.countDown(); fake.sessionsReadHook = null; fake.failListFromOffset = -1; app.live.end(); settle() }

    private fun phoneLive(on: Boolean) = app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = on, watchLiveEnabled = true, liveIdleSeconds = 5))
    private fun shown(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun tapTalk() { rule.onNodeWithTag("talk").performClick(); settle() }
    private fun holdCatalogue() {
        fake.sessionsReadHook = { label -> if (label == "list:created") { held.countDown(); check(release.await(30, TimeUnit.SECONDS)) { "fixture catalogue latch not released" } } }
    }
    private fun ready() {
        tapTalk()
        assertTrue("Phone Live is ready", await(20_000) { app.live.state.value.ready && app.live.state.value.origin == VoiceOrigin.PHONE })
    }
    /** The paired Watch for a Watch-origin call (the Phone relays it); the Watch's own media is never reached here. */
    private fun pairWatch() {
        val node = mockk<Node>(); every { node.id } returns "watch-node"; every { node.isNearby } returns true
        val info = mockk<CapabilityInfo>(); every { info.nodes } returns setOf(node); every { info.name } returns "hermes_voice_watch"
        val caps = mockk<CapabilityClient>(); every { caps.getCapability(any(), any()) } returns Tasks.forResult(info)
        every { Wearable.getCapabilityClient(any<Context>()) } returns caps
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = false, watchLiveEnabled = true))
    }
    private fun fromWatch(op: String) = app.liveWatch.receive("watch-node",
        JSONObject().put("v", 1).put("id", "watch-call").put("op", op).put("probe", "probe-$op").toString().toByteArray())

    @Test fun L27_01_turningLiveModeOffWhileItPreparesIsNotAFailureBesideTalk() {
        titled("Alpha", "alpha"); phoneLive(true); holdCatalogue()
        val model = showing()
        tapTalk()
        assertTrue("Live is preparing", await(20_000) { held.count == 0L && app.live.active && !app.live.state.value.ready })
        model.setLiveMode(VoiceOrigin.PHONE, false)
        assertTrue("Live mode OFF ended the call", await(20_000) { !app.live.active })
        release.countDown(); settle()
        assertFalse("a routine end is not the user's failed action", exists("action_failure"))
        assertFalse(shown("Live mode off")); assertFalse(shown("Hermes work continues"))
        assertEquals("R29: only the one media acquired beside the held catalogue", 1, media.size); assertTrue(fake.prompts.isEmpty())
    }

    @Test fun L27_02_anIdleEndAfterReadyIsNotAFailureBesideTalk() {
        titled("Alpha", "alpha"); phoneLive(true)
        showing()
        ready()
        now += 20_000; app.live.checkLifetime(); settle()
        assertFalse(app.live.active); assertEquals("idle", app.live.state.value.endReason)
        assertFalse(exists("action_failure")); assertFalse(shown("inactivity"))
        assertEquals("nothing reconnected", 1, media.size); assertTrue(fake.prompts.isEmpty())
    }

    @Test fun L27_03_aStopFromTheWatchOfAWatchCallIsNotAPhoneFailure() {
        pairWatch(); titled("Alpha", "alpha"); holdCatalogue()
        showing()
        fromWatch("start")
        assertTrue("the Watch call is preparing on the Phone", await(20_000) { held.count == 0L && app.live.active && app.live.state.value.origin == VoiceOrigin.WATCH })
        fromWatch("end")
        assertTrue("the Watch's Stop ended it", await(20_000) { !app.live.active })
        release.countDown(); settle()
        assertFalse(exists("action_failure")); assertFalse(shown("Ended from Watch")); assertFalse(shown("Hermes work continues"))
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun L27_04_aWatchCallsSetupFailureIsNotPaintedAsThePhoneUsersFailedAction() {
        pairWatch(); titled("Alpha", "alpha")
        showing()
        fake.failListFromOffset = 0
        fromWatch("start")
        assertTrue("the Watch call failed in its setup", await(20_000) {
            !app.live.active && app.live.state.value.endReason == "failure" && app.live.state.value.origin == VoiceOrigin.WATCH })
        fake.failListFromOffset = -1; settle()
        assertFalse("the Watch's failure is not the Phone's", shown("Live conversation catalog could not be verified"))
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun L27_05_aProviderErrorAfterReadyIsSaidOnceAndNothingReconnects() {
        titled("Alpha", "alpha"); phoneLive(true)
        showing()
        ready()
        media.single().error()
        assertTrue("the provider's error ended the call", await(20_000) { !app.live.active })
        settle()
        rule.onNodeWithTag("action_failure").assertTextContains("Live reported an error", substring = true)
        assertEquals("said once", 1, count("action_failure"))
        idle(1_000); settle()
        assertFalse("no automatic reconnect", app.live.active); assertEquals(1, media.size); assertTrue(fake.prompts.isEmpty())
    }

    @Test fun L27_06_aLostAudioRouteAfterReadyIsSaidOnceAndNothingReconnects() {
        titled("Alpha", "alpha"); phoneLive(true)
        showing()
        ready()
        lost.single().invoke()
        assertTrue("the route loss ended the call", await(20_000) { !app.live.active })
        settle()
        rule.onNodeWithTag("action_failure").assertTextContains("Live audio route/focus lost", substring = true)
        assertEquals("said once", 1, count("action_failure"))
        idle(1_000); settle()
        assertFalse("no automatic reconnect", app.live.active); assertEquals(1, media.size); assertEquals(1, lost.size)
        assertTrue(fake.prompts.isEmpty())
    }
}
