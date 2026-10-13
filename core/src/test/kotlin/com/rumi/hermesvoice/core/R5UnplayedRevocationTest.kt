package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.InMemoryHermesTokenStore
import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundStatus
import com.rumi.hermesvoice.core.headset.HeadsetBackground
import com.rumi.hermesvoice.core.headset.HeadsetBackgroundState
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import com.rumi.hermesvoice.core.voice.UnplayedReplyStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R5 tier that needs the R5 APIs (so it cannot be compiled against R4): clearing the cache, revoking it on the orchestrator with a late
 * callback, the metadata the banner carries (stored conversation, playable here), the background-answers state, and the speech-failure
 * text no longer taken from the server.
 */
class R5UnplayedRevocationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val kv = InMemoryKeyValueStore()
    private val rigs = mutableListOf<HeadsetRig>()
    private val secret = "R5-CACHED-SECRET-ANSWER"

    @After fun tearDown() {
        rigs.forEach { it.close() }
        scope.cancel()
    }

    private fun entry(id: String, isPrivate: Boolean, text: String = secret, node: String? = "node-1") =
        UnplayedReplyStore.Entry(id, "turn-$id", VoiceOrigin.WATCH, node, "session-1", isPrivate, text, System.currentTimeMillis())

    private fun rig(store: UnplayedReplyStore, headsetOn: Boolean = true) =
        HeadsetRig(scope, FakeDevices(listOf(Gear.speaker, Gear.a2dp)), headsetOn = headsetOn, unplayedStore = store).also { rigs += it }

    private fun rawStored() = kv.getString(UnplayedReplyStore.KEY) ?: ""

    private fun flaky(failing: () -> Boolean) = object : KeyValueStore by kv {
        override fun commitString(key: String, value: String): Boolean = if (failing()) false else kv.commitString(key, value)
    }

    @Test fun `clearing the store removes every kept reply and reports it`() {
        val store = UnplayedReplyStore(kv)
        store.put(entry("a#final", true))
        store.put(entry("b#final", false))
        assertTrue(rawStored().contains(secret))
        assertTrue(store.clear())
        assertFalse(rawStored().contains(secret))
        assertTrue(store.all().isEmpty())
    }

    @Test fun `a clear whose commit fails reports false and does not claim the text is gone`() {
        UnplayedReplyStore(kv).put(entry("a#final", true))
        val store = UnplayedReplyStore(flaky { true })
        assertFalse(store.clear())
        assertTrue("the stored string still holds it, and nothing says otherwise", rawStored().contains(secret))
    }

    @Test fun `revoking the orchestrator cache clears the list and the stored text and refuses a replay`() {
        UnplayedReplyStore(kv).put(entry("w1#final", true))
        val r = rig(UnplayedReplyStore(kv))
        assertEquals(listOf("w1#final"), r.core.orchestrator.unplayedReplies.value.map { it.identity })
        assertTrue(r.core.orchestrator.revokeUnplayed())
        assertTrue(r.core.orchestrator.unplayedReplies.value.isEmpty())
        assertFalse(rawStored().contains(secret))
        assertFalse("nothing left to replay", r.core.orchestrator.replayAudio("w1#final"))
        assertTrue(r.sink.offered.isEmpty())
    }

    @Test fun `a revoke whose commit fails empties the visible list and reports false`() {
        UnplayedReplyStore(kv).put(entry("w1#final", true))
        val r = rig(UnplayedReplyStore(flaky { true }))
        assertTrue(r.core.orchestrator.unplayedReplies.value.isNotEmpty())
        assertFalse("not claimed as deleted", r.core.orchestrator.revokeUnplayed())
        assertTrue("but nothing is shown or playable", r.core.orchestrator.unplayedReplies.value.isEmpty())
        assertFalse(r.core.orchestrator.replayAudio("w1#final"))
    }

    @Test fun `a reply that fails to play after the revoke is not stored or listed again`() {
        val r = rig(UnplayedReplyStore(kv))
        val stored = r.existing("work", "Started.")
        r.phoneTurn("prime", stored)
        r.sink.failNext = "phone playback error 1/0"
        val revoked = AtomicInteger()
        r.sink.onCue = { if (r.core.orchestrator.revokeUnplayed()) revoked.incrementAndGet() }
        r.later(stored, "A late answer that cannot play.")
        r.waitFor("the revoke and the failed playback", 15_000) { revoked.get() > 0 && r.alertsShown.isNotEmpty() }
        r.settle(1_000)
        assertTrue("the late callback did not repopulate the list", r.core.orchestrator.unplayedReplies.value.isEmpty())
        assertFalse("nor the stored text", rawStored().contains("A late answer"))
        assertTrue(UnplayedReplyStore(kv).all().isEmpty())
    }

    @Test fun `the list carries the stored conversation and says which replies can be played from the Phone`() {
        val store = UnplayedReplyStore(kv)
        store.put(entry("p#final", true))
        store.put(entry("o#final", false))
        val replies = rig(UnplayedReplyStore(kv)).core.orchestrator.unplayedReplies.value.associateBy { it.identity }
        assertEquals("session-1", replies.getValue("p#final").storedSessionId)
        assertTrue("a restored private reply is playable from the Phone", replies.getValue("p#final").playableHere)
        assertEquals("session-1", replies.getValue("o#final").storedSessionId)
        assertFalse("a restored non-private Watch reply has no approved device here", replies.getValue("o#final").playableHere)
    }

    @Test fun `the state is not needed before Android 15 or with Use headset off`() {
        val off = BackgroundStatus(wanted = false, running = false, microphone = false, notice = BackgroundNotice.OFF)
        assertEquals(HeadsetBackgroundState.NOT_NEEDED, HeadsetBackground.state(true, 34, off, false))
        assertEquals(HeadsetBackgroundState.NOT_NEEDED, HeadsetBackground.state(false, 35, off, false))
        assertNull(HeadsetBackground.text(HeadsetBackgroundState.NOT_NEEDED))
        assertFalse(HeadsetBackground.offersAction(HeadsetBackgroundState.NOT_NEEDED))
    }

    @Test fun `the state tells setup, asked, refused, stopped and admitted apart and only the admitted one is active`() {
        val off = BackgroundStatus(wanted = false, running = false, microphone = false, notice = BackgroundNotice.OFF)
        val asked = BackgroundStatus(wanted = true, running = true, microphone = false, notice = BackgroundNotice.RUNNING)
        val refused = BackgroundStatus(wanted = true, running = false, microphone = false, notice = BackgroundNotice.REFUSED)
        val needsVisible = BackgroundStatus(wanted = true, running = false, microphone = false, notice = BackgroundNotice.NEEDS_VISIBLE)
        val stopped = BackgroundStatus(wanted = true, running = false, microphone = false, notice = BackgroundNotice.PAUSED)
        assertEquals(HeadsetBackgroundState.NEEDS_SETUP, HeadsetBackground.state(true, 35, off, false))
        assertEquals(HeadsetBackgroundState.STARTING, HeadsetBackground.state(true, 35, asked, false))
        assertEquals(HeadsetBackgroundState.REFUSED, HeadsetBackground.state(true, 35, refused, false))
        assertEquals(HeadsetBackgroundState.REFUSED, HeadsetBackground.state(true, 35, needsVisible, false))
        assertEquals(HeadsetBackgroundState.PAUSED, HeadsetBackground.state(true, 35, stopped, false))
        assertEquals("asked for is not admitted", HeadsetBackgroundState.STARTING, HeadsetBackground.state(true, 36, asked, false))
        assertEquals(HeadsetBackgroundState.ACTIVE, HeadsetBackground.state(true, 35, asked, true))
        val actions = HeadsetBackgroundState.values().filter { HeadsetBackground.offersAction(it) }
        assertEquals(listOf(HeadsetBackgroundState.NEEDS_SETUP, HeadsetBackgroundState.REFUSED, HeadsetBackgroundState.PAUSED), actions)
    }

    @Test fun `the wording states the precondition and promises no audibility`() {
        val setup = HeadsetBackground.text(HeadsetBackgroundState.NEEDS_SETUP)!!
        assertTrue(setup.contains("background relay"))
        assertTrue(setup.contains("if Android allows"))
        val active = HeadsetBackground.text(HeadsetBackgroundState.ACTIVE)!!
        assertTrue(active.contains("Not a guarantee"))
        for (state in HeadsetBackgroundState.values()) {
            val text = HeadsetBackground.text(state) ?: continue
            assertFalse(state.name, text.contains("will hear", ignoreCase = true) || text.contains("always", ignoreCase = true))
        }
    }

    @Test fun `a failed speech synthesis keeps the server's wording out of the error`() {
        val fake = FakeHermesDashboard()
        try {
            fake.speakStatuses.add(503)
            val tokens = InMemoryHermesTokenStore(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))
            val client = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), OkHttpClient(), tokens)
            try {
                runBlocking { client.speak("anything") }
                fail("the scripted 503 must fail")
            } catch (e: HermesHttpException) {
                assertEquals(503, e.status)
                assertEquals("/api/audio/speak failed (503)", e.message)
            }
        } finally {
            fake.close()
        }
    }
}
