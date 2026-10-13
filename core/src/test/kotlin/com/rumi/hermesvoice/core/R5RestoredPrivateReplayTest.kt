package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.voice.UnplayedReplyStore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5 F-02: a PRIVATE Watch reply restored from the device-local store (new process: no recorded sink, no Watch link) can be played
 * again from the Phone (banner entrypoint = replayAudio with no node and no rebind) on the private headset sink only. The Watch
 * and the loudspeaker are never used instead; the origin-node and session guards are not relaxed; Hermes is never asked again.
 */
class R5RestoredPrivateReplayTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val kv = InMemoryKeyValueStore()
    private val rigs = mutableListOf<HeadsetRig>()

    @After fun tearDown() {
        rigs.forEach { it.close() }
        scope.cancel()
    }

    private fun entry(isPrivate: Boolean, text: String = "Private watch answer.") =
        UnplayedReplyStore.Entry("w1#final", "w1", VoiceOrigin.WATCH, "node-1", "session-1", isPrivate, text, System.currentTimeMillis())

    private fun rig(devices: FakeDevices = FakeDevices(listOf(Gear.speaker, Gear.a2dp)), headsetOn: Boolean = true) =
        HeadsetRig(scope, devices, headsetOn = headsetOn, unplayedStore = UnplayedReplyStore(kv)).also { rigs += it }

    private fun submits(r: HeadsetRig) = r.fake.timeline.toList().count { it.startsWith("submit") }

    private class Done {
        val calls = AtomicInteger()
        val result = AtomicReference<Pair<Boolean, String>?>(null)
        val callback: (Boolean, String) -> Unit = { heard, detail -> result.set(heard to detail); calls.incrementAndGet() }
    }

    @Test
    fun `a restored private Watch reply is replayed from the Phone on the headset only and Hermes is not asked again`() {
        UnplayedReplyStore(kv).put(entry(isPrivate = true))
        val r = rig()
        val done = Done()
        assertTrue("a restored private reply is playable from the Phone with no Watch link", r.core.orchestrator.replayAudio("w1#final", done = done.callback))
        r.waitFor("the replay to be heard", 15_000) { r.sink.played.isNotEmpty() }
        assertEquals(listOf("FINAL:Private watch answer."), r.sink.played.toList())
        assertTrue("every cue was bound to the headset: nothing for the speaker", r.sink.offered.isNotEmpty() && r.sink.offered.all { it.headset != null })
        r.waitFor("the callback", 5_000) { done.calls.get() > 0 }
        assertEquals(1, done.calls.get())
        assertEquals(true, done.result.get()?.first)
        assertEquals("nothing is sent to Hermes", 0, submits(r))
        r.waitFor("the list to empty", 5_000) { r.core.orchestrator.unplayedReplies.value.isEmpty() }
        assertTrue("a heard reply leaves the storage", UnplayedReplyStore(kv).all().isEmpty())
    }

    @Test
    fun `a transient player failure recovers on the same reply and cannot resubmit to Hermes`() {
        UnplayedReplyStore(kv).put(entry(isPrivate = true))
        val r = rig()
        r.sink.transientFailures = 1
        val done = Done()
        assertTrue(r.core.orchestrator.replayAudio("w1#final", done = done.callback))
        r.waitFor("the replay to be heard", 30_000) { r.sink.played.isNotEmpty() }
        r.waitFor("the callback", 5_000) { done.calls.get() > 0 }
        assertEquals(listOf("FINAL:Private watch answer."), r.sink.played.toList())
        assertEquals(1, done.calls.get())
        assertEquals(true, done.result.get()?.first)
        assertEquals("a retry is the same audio, never a new submission", 0, submits(r))
        assertTrue(r.sink.offered.all { it.headset != null })
    }

    @Test
    fun `a failed replay reports once keeps the reply and the same reply plays on the next try`() {
        UnplayedReplyStore(kv).put(entry(isPrivate = true))
        val r = rig()
        r.sink.failNext = "phone playback error 1/0"
        val first = Done()
        assertTrue(r.core.orchestrator.replayAudio("w1#final", done = first.callback))
        r.waitFor("the first callback", 30_000) { first.calls.get() > 0 }
        r.settle()
        assertEquals("exactly once", 1, first.calls.get())
        assertEquals(false, first.result.get()?.first)
        assertTrue("the same reply is still kept", UnplayedReplyStore(kv).all().map { it.identity } == listOf("w1#final"))
        assertTrue(r.sink.played.isEmpty())
        val second = Done()
        assertTrue(r.core.orchestrator.replayAudio("w1#final", done = second.callback))
        r.waitFor("the second replay to be heard", 15_000) { r.sink.played.isNotEmpty() }
        r.waitFor("the second callback", 5_000) { second.calls.get() > 0 }
        assertEquals(listOf("FINAL:Private watch answer."), r.sink.played.toList())
        assertEquals(1, second.calls.get())
        assertEquals(0, submits(r))
    }

    @Test
    fun `losing the headset during the replay reports once never reaches a speaker and keeps the reply for the next try`() {
        UnplayedReplyStore(kv).put(entry(isPrivate = true))
        val devices = FakeDevices(listOf(Gear.speaker, Gear.a2dp))
        val r = rig(devices)
        r.sink.hold = CompletableDeferred()
        val done = Done()
        assertTrue(r.core.orchestrator.replayAudio("w1#final", done = done.callback))
        r.waitFor("playback to start on the headset", 15_000) { r.sink.starts.get() > 0 }
        devices.disconnectAll()
        r.waitFor("the callback", 30_000) { done.calls.get() > 0 }
        r.settle(1_500)
        assertEquals("exactly once", 1, done.calls.get())
        assertEquals(false, done.result.get()?.first)
        assertTrue("no cue ever reached the speaker", r.sink.offered.all { it.headset != null })
        assertTrue(r.sink.played.isEmpty())
        assertTrue("the same reply is kept", UnplayedReplyStore(kv).get("w1#final") != null)
        r.sink.hold = null
        devices.connect(Gear.a2dp)
        val again = Done()
        assertTrue(r.core.orchestrator.replayAudio("w1#final", done = again.callback))
        r.waitFor("the replay to be heard", 15_000) { r.sink.played.isNotEmpty() }
        assertEquals(listOf("FINAL:Private watch answer."), r.sink.played.toList())
        assertEquals(0, submits(r))
    }

    @Test
    fun `stopping the replay reports once keeps the reply and silences nothing else`() {
        UnplayedReplyStore(kv).put(entry(isPrivate = true))
        val r = rig()
        r.sink.hold = CompletableDeferred()
        val done = Done()
        assertTrue(r.core.orchestrator.replayAudio("w1#final", done = done.callback))
        r.waitFor("playback to start", 15_000) { r.sink.starts.get() > 0 }
        r.core.orchestrator.stopFollowing()
        r.waitFor("the callback", 15_000) { done.calls.get() > 0 }
        r.settle()
        assertEquals("exactly once", 1, done.calls.get())
        assertEquals(false, done.result.get()?.first)
        assertEquals(1, r.sink.cut.get())
        assertTrue(r.sink.played.isEmpty())
        assertTrue("the same reply is kept", UnplayedReplyStore(kv).get("w1#final") != null)
    }

    @Test
    fun `a second request while the first replay runs is refused and the first still reports once`() {
        UnplayedReplyStore(kv).put(entry(isPrivate = true))
        val r = rig()
        val gate = CompletableDeferred<Unit>()
        r.sink.hold = gate
        val done = Done()
        assertTrue(r.core.orchestrator.replayAudio("w1#final", done = done.callback))
        r.waitFor("playback to start", 15_000) { r.sink.starts.get() > 0 }
        assertFalse(r.core.orchestrator.replayAudio("w1#final"))
        gate.complete(Unit)
        r.waitFor("the callback", 15_000) { done.calls.get() > 0 }
        r.settle()
        assertEquals(1, done.calls.get())
        assertEquals(listOf("FINAL:Private watch answer."), r.sink.played.toList())
    }

    @Test
    fun `the origin node and the conversation guards still hold for a restored private reply`() {
        UnplayedReplyStore(kv).put(entry(isPrivate = true))
        val r = rig()
        assertFalse("another node", r.core.orchestrator.replayAudio("w1#final", "node-2", null))
        assertFalse("another conversation", r.core.orchestrator.replayAudio("w1#final", null, null, "session-2"))
        assertFalse("unknown identity", r.core.orchestrator.replayAudio("zzz#final"))
        assertTrue("nothing reached any sink", r.sink.offered.isEmpty() && r.sink.played.isEmpty())
        assertTrue("the reply is kept", UnplayedReplyStore(kv).get("w1#final") != null)
    }

    @Test
    fun `a restored non private Watch reply with no Watch link is refused and makes no sound anywhere`() {
        UnplayedReplyStore(kv).put(entry(isPrivate = false, text = "A reply meant for the Watch speaker."))
        val r = rig()
        val done = Done()
        assertFalse("not playable from the Phone: it was never private", r.core.orchestrator.replayAudio("w1#final", done = done.callback))
        r.settle()
        assertEquals(0, done.calls.get())
        assertTrue("no Phone speaker or headset fallback", r.sink.offered.isEmpty() && r.sink.played.isEmpty())
        assertTrue("kept for the Watch's own request", UnplayedReplyStore(kv).get("w1#final") != null)
    }

    @Test
    fun `a restored private reply with no headset connected makes no sound and is kept`() {
        UnplayedReplyStore(kv).put(entry(isPrivate = true))
        val r = rig(FakeDevices(listOf(Gear.speaker)))
        r.core.orchestrator.replayAudio("w1#final")
        r.settle(1_500)
        assertTrue("no speaker fallback", r.sink.offered.isEmpty() && r.sink.played.isEmpty())
        assertTrue("the same reply is kept", UnplayedReplyStore(kv).get("w1#final") != null)
        assertEquals(0, submits(r))
    }
}
