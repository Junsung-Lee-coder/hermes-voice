package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.voice.UnplayedReplyStore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A manual Replay survives the end of the process: a new orchestrator over the same device-local storage lists and plays the reply. */
class DurableReplayTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val kv = InMemoryKeyValueStore()
    private val rigs = mutableListOf<HeadsetRig>()

    @After fun tearDown() {
        rigs.forEach { it.close() }
        scope.cancel()
    }

    private fun rig(headsetOn: Boolean, store: UnplayedReplyStore?) =
        HeadsetRig(scope, FakeDevices(listOf(Gear.speaker, Gear.a2dp)), headsetOn = headsetOn, unplayedStore = store).also { rigs += it }

    private fun watchEntry(id: String = "w1#final", createdAt: Long = System.currentTimeMillis()) =
        UnplayedReplyStore.Entry(id, "w1", VoiceOrigin.WATCH, "node-1", "session-1", false, "Watch owned answer.", createdAt)

    @Test
    fun `a Phone reply that did not play is replayed by a new process from the stored text`() {
        val first = rig(true, UnplayedReplyStore(kv))
        val stored = first.existing("work", "Started.")
        first.phoneTurn("prime", stored)
        first.sink.failNext = "phone playback error 1/0"
        first.later(stored, "Survives the process end.")
        first.waitFor("the no-audio notification", 10_000) { first.alertsShown.isNotEmpty() }
        val identity = first.alertsShown.first().substringBefore('@')
        assertEquals(listOf(identity), UnplayedReplyStore(kv).all().map { it.identity })
        first.close()
        rigs.clear()

        val second = rig(true, UnplayedReplyStore(kv))
        assertEquals("the list is rebuilt from storage", listOf(identity), second.core.orchestrator.unplayedReplies.value.map { it.identity })
        val submitsBefore = second.fake.timeline.toList().count { it.startsWith("submit") }
        val done = AtomicReference<Pair<Boolean, String>?>(null)
        assertTrue(second.core.orchestrator.replayAudio(identity) { heard, detail -> done.set(heard to detail) })
        second.waitFor("the replay to be heard", 10_000) { second.sink.played.isNotEmpty() }
        assertEquals(listOf("FINAL:Survives the process end."), second.sink.played.toList())
        assertEquals(true, done.get()?.first)
        assertEquals("nothing is sent to Hermes", submitsBefore, second.fake.timeline.toList().count { it.startsWith("submit") })
        second.waitFor("the list to empty", 5_000) { second.core.orchestrator.unplayedReplies.value.isEmpty() }
        assertTrue("a heard reply leaves the storage", UnplayedReplyStore(kv).all().isEmpty())
    }

    @Test
    fun `a Watch reply is replayed through the sink the new process supplies and only for its own node`() {
        UnplayedReplyStore(kv).put(watchEntry())
        val r = rig(false, UnplayedReplyStore(kv))
        assertFalse("another node is refused", r.core.orchestrator.replayAudio("w1#final", "node-2", r.sink))
        assertFalse("without a sink to play through nothing is played", r.core.orchestrator.replayAudio("w1#final", "node-1", null))
        val done = AtomicReference<Pair<Boolean, String>?>(null)
        assertTrue(r.core.orchestrator.replayAudio("w1#final", "node-1", r.sink) { heard, detail -> done.set(heard to detail) })
        r.waitFor("the replay to be heard", 10_000) { r.sink.played.isNotEmpty() }
        assertEquals(listOf("FINAL:Watch owned answer."), r.sink.played.toList())
        assertEquals(true, done.get()?.first)
        assertTrue(UnplayedReplyStore(kv).all().isEmpty())
    }

    @Test
    fun `a reply of another conversation or an unknown identity is refused`() {
        UnplayedReplyStore(kv).put(watchEntry())
        val r = rig(false, UnplayedReplyStore(kv))
        assertFalse("another conversation", r.core.orchestrator.replayAudio("w1#final", "node-1", r.sink, "session-2"))
        assertFalse("unknown identity", r.core.orchestrator.replayAudio("zzz#final", "node-1", r.sink))
        assertTrue(r.sink.offered.isEmpty())
        assertTrue("still kept for the right conversation", r.core.orchestrator.replayAudio("w1#final", "node-1", r.sink, "session-1"))
    }

    @Test
    fun `a stored reply older than the limit is not listed and cannot be replayed`() {
        val time = AtomicLong(0L)
        val store = UnplayedReplyStore(kv, { time.get() }, ttlMs = 1_000L)
        store.put(watchEntry(createdAt = 0L))
        time.set(5_000L)
        val r = rig(false, UnplayedReplyStore(kv, { time.get() }, ttlMs = 1_000L))
        assertTrue(r.core.orchestrator.unplayedReplies.value.isEmpty())
        assertFalse(r.core.orchestrator.replayAudio("w1#final", "node-1", r.sink))
        assertNull(UnplayedReplyStore(kv, { time.get() }, ttlMs = 1_000L).get("w1#final"))
    }
}
