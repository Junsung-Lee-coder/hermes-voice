package com.rumi.hermesvoice.phone

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.voice.UnplayedReplyStore
import com.rumi.hermesvoice.core.watchlink.ReplayRequest
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * R5 (F-02, F-04) through the PRODUCTION Phone boundaries Robolectric can run: the real PhoneApp wiring, the real PhoneViewModel
 * sign-out and connection-save actions, the real SharedPreferences backing of the unplayed-reply cache, the real Phone replay
 * entrypoint ([PhoneApp.replayReply]) and the real Watch REPLAY ingress through PhoneWatchListenerService. Simulated: the Data
 * Layer (mockk, records what the Phone sent); no audio hardware (no headset output exists here, so nothing can be heard).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class R5PhoneUnplayedRevocationTest : PhoneRoboBase() {
    private class Sent(val node: String, val path: String, val data: ByteArray)

    private val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
    private val secret = "R5-SECRET-CACHED-ANSWER-TEXT"

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

    private fun prefs() = app.getSharedPreferences(UnplayedReplyStore.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun storeKey(): String {
        val endpoint = HermesDashboardEndpoint.parse(app.settings.dashboardUrl)
        val digest = MessageDigest.getInstance("SHA-256").digest("$endpoint|${app.settings.profile}".toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        return UnplayedReplyStore.KEY + "_" + digest
    }

    private fun seed(turnId: String, node: String?, isPrivate: Boolean, session: String = "s-1"): String {
        val identity = ReplyAlert.identityOf(turnId, "final")
        val store = UnplayedReplyStore(SharedPreferencesKeyValueStore(prefs()), key = storeKey())
        assertTrue(store.put(UnplayedReplyStore.Entry(identity, turnId, VoiceOrigin.WATCH, node, session, isPrivate, secret, System.currentTimeMillis())))
        return identity
    }

    private fun storedAnywhere(): Boolean = prefs().all.values.any { it?.toString()?.contains(secret) == true }

    private fun deliver(path: String, data: ByteArray, node: String = "watch-node") {
        val service = Robolectric.setupService(PhoneWatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns path
        every { event.sourceNodeId } returns node
        every { event.data } returns data
        service.onMessageReceived(event)
    }

    private fun states(turnId: String) = sent.filter { it.path == WatchLinkPaths.STATE }.mapNotNull { TurnStateMessage.decode(it.data) }.filter { it.turnId == turnId }

    @Test fun F04_signingOutClearsTheCachedReplyTextAndTheListButNotTheOtherPhoneData() {
        val identity = seed("turn-so", "watch-node", isPrivate = true)
        val session = conversation("alias")
        assertTrue("the real wiring restored it", app.wiring().core.orchestrator.unplayedReplies.value.any { it.identity == identity })
        assertTrue(await { app.unplayedReplies.value.any { it.identity == identity } })
        val model = PhoneViewModel(app)
        model.signOut()
        assertFalse("the answer text is gone from the stored file", storedAnywhere())
        assertTrue("and from the list", await { app.unplayedReplies.value.isEmpty() })
        assertNull("credentials are cleared as before", app.tokens.load())
        assertEquals("settings are not touched", fake.baseUrl, app.settings.dashboardUrl)
        assertNotNull("the owned conversation registry is not touched", app.wiring().core.sessions.activeConversation(session))
    }

    @Test fun F04_changingTheDashboardProfileClearsTheCachedReplyTextBeforeAnythingElseUsesTheNewIdentity() {
        val identity = seed("turn-id", "watch-node", isPrivate = true)
        assertTrue(app.wiring().core.orchestrator.unplayedReplies.value.any { it.identity == identity })
        val model = PhoneViewModel(app)
        model.saveConnection(app.settings.dashboardUrl, "another-profile")
        assertFalse(storedAnywhere())
        assertTrue(await { app.unplayedReplies.value.isEmpty() })
        assertTrue("the new identity starts with nothing", app.wiring().core.orchestrator.unplayedReplies.value.isEmpty())
        assertNull("the old bearer pair is not replayed", app.tokens.load())
    }

    @Test fun F04_savingTheSameConnectionKeepsTheCachedReply() {
        val identity = seed("turn-same", "watch-node", isPrivate = true)
        assertTrue(app.wiring().core.orchestrator.unplayedReplies.value.any { it.identity == identity })
        PhoneViewModel(app).saveConnection(app.settings.dashboardUrl, app.settings.profile)
        assertTrue(storedAnywhere())
        assertTrue(await { app.unplayedReplies.value.any { it.identity == identity } })
    }

    @Test fun F02_aRestoredPrivateWatchReplyStartsFromThePhoneEntrypointAndReportsOnceWithoutAnySpeaker() {
        val identity = seed("turn-private", "watch-node", isPrivate = true)
        app.settings.useHeadset = true
        assertTrue(app.wiring().core.orchestrator.unplayedReplies.value.any { it.identity == identity })
        val calls = AtomicInteger()
        val result = AtomicReference<Pair<Boolean, String>?>(null)
        assertTrue("accepted: a private reply needs no Watch link", app.replayReply(identity) { heard, detail -> result.set(heard to detail); calls.incrementAndGet() })
        assertTrue("the result arrives", await(30_000) { calls.get() > 0 })
        settle()
        assertEquals("exactly once", 1, calls.get())
        assertEquals("no headset output exists here, so it cannot be heard", false, result.get()?.first)
        assertTrue("the same reply is kept", UnplayedReplyStore(SharedPreferencesKeyValueStore(prefs()), key = storeKey()).get(identity) != null)
        assertTrue("nothing was sent to the Watch or Hermes", sent.isEmpty() && fake.rawPrompts.isEmpty())
    }

    @Test fun F02_aRestoredNonPrivateWatchReplyIsRefusedFromThePhoneEntrypointAndKept() {
        val identity = seed("turn-open", "watch-node", isPrivate = false)
        app.settings.useHeadset = true
        assertTrue(app.wiring().core.orchestrator.unplayedReplies.value.any { it.identity == identity })
        val calls = AtomicInteger()
        assertFalse(app.replayReply(identity) { _, _ -> calls.incrementAndGet() })
        settle()
        assertEquals(0, calls.get())
        assertTrue(UnplayedReplyStore(SharedPreferencesKeyValueStore(prefs()), key = storeKey()).get(identity) != null)
        assertTrue(sent.isEmpty())
    }

    @Test fun F02_theOwningWatchsReplayRequestForARestoredPrivateReplyIsAcceptedAndAnsweredToThatNodeOnly() {
        val identity = seed("turn-watch", "watch-node", isPrivate = true)
        app.settings.useHeadset = true
        assertTrue(app.wiring().core.orchestrator.unplayedReplies.value.any { it.identity == identity })
        deliver(WatchLinkPaths.REPLAY, ReplayRequest("turn-watch").encode())
        assertTrue("the Phone told the Watch the result", await(30_000) { states("turn-watch").isNotEmpty() })
        val state = states("turn-watch").first()
        assertEquals("done", state.stage)
        assertTrue("it was accepted and ran (not the unavailable refusal)", state.detail.isNotBlank() && !state.detail.contains("can't be played again now"))
        assertEquals(listOf("watch-node"), sent.map { it.node }.distinct())
        assertTrue("no audio channel or other path was used", sent.all { it.path == WatchLinkPaths.STATE })
        assertTrue("the same reply is kept", UnplayedReplyStore(SharedPreferencesKeyValueStore(prefs()), key = storeKey()).get(identity) != null)
    }

    @Test fun F02_aReplayRequestFromAnotherWatchIsRefusedForARestoredPrivateReply() {
        val identity = seed("turn-other", "watch-node", isPrivate = true)
        app.settings.useHeadset = true
        assertTrue(app.wiring().core.orchestrator.unplayedReplies.value.any { it.identity == identity })
        deliver(WatchLinkPaths.REPLAY, ReplayRequest("turn-other").encode(), node = "other-watch")
        assertTrue(await(15_000) { states("turn-other").isNotEmpty() })
        assertTrue(states("turn-other").first().detail.contains("can't be played again now"))
        assertEquals(listOf("other-watch"), sent.map { it.node }.distinct())
        assertTrue(UnplayedReplyStore(SharedPreferencesKeyValueStore(prefs()), key = storeKey()).get(identity) != null)
    }
}
