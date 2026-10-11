package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.voice.UnplayedReplyStore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnplayedReplyStoreTest {
    private var now = 1_000_000L
    private val kv = InMemoryKeyValueStore()

    private fun store(max: Int = 8, ttlMs: Long = 1_000L) = UnplayedReplyStore(kv, { now }, max, ttlMs)
    private fun entry(id: String, text: String = "the answer") =
        UnplayedReplyStore.Entry(id, "turn-$id", VoiceOrigin.WATCH, "node-1", "session-1", true, text, now)

    @Test fun `a new store over the same storage sees what an earlier one kept`() {
        assertTrue(store().put(entry("a#final", "한국어 답변 \"quoted\"\nline")))
        val reopened = store().get("a#final")
        assertNotNull(reopened)
        assertEquals("한국어 답변 \"quoted\"\nline", reopened!!.text)
        assertEquals(VoiceOrigin.WATCH, reopened.origin)
        assertEquals("node-1", reopened.originNodeId)
        assertEquals("session-1", reopened.storedSessionId)
        assertTrue(reopened.private)
    }

    @Test fun `removing an entry is durable`() {
        store().put(entry("a#final"))
        store().remove("a#final")
        assertNull(store().get("a#final"))
        assertTrue(store().all().isEmpty())
    }

    @Test fun `an entry older than the time limit is dropped`() {
        store().put(entry("old#final"))
        now += 1_001L
        assertNull(store().get("old#final"))
        assertTrue(store().all().isEmpty())
    }

    @Test fun `the oldest entry goes first when the bound is exceeded`() {
        val s = store(max = 2)
        s.put(entry("1#final")); s.put(entry("2#final")); s.put(entry("3#final"))
        assertEquals(listOf("2#final", "3#final"), s.all().map { it.identity })
    }

    @Test fun `unreadable stored content is treated as empty and never throws`() {
        kv.putString(UnplayedReplyStore.KEY, "{not json")
        assertTrue(store().all().isEmpty())
        assertTrue(store().put(entry("a#final")))
        assertEquals(1, store().all().size)
    }

    @Test fun `a failed durable commit is reported`() {
        val failing = object : com.rumi.hermesvoice.core.KeyValueStore by kv {
            override fun commitString(key: String, value: String): Boolean = false
        }
        assertFalse(UnplayedReplyStore(failing, { now }).put(entry("a#final")))
    }

    @Test fun `an entry without text is never stored`() {
        assertFalse(store().put(entry("blank#final", "  ")))
        assertTrue(store().all().isEmpty())
    }
}
