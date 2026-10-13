package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.voice.UnplayedReplyStore
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5 F-04: the 3-day lifetime is enforced on the BACKING storage, not only on read: an expired reply's text is gone from the stored
 * string at the next access or mutation. A commit that fails never makes an expired reply visible and never counts as a deletion.
 */
class R5UnplayedStorePhysicalPurgeTest {
    private var now = 1_000_000L
    private val kv = InMemoryKeyValueStore()
    private val expiredText = "EXPIRED-SECRET-ANSWER"
    private val freshText = "fresh answer"

    private fun store(backing: KeyValueStore = kv, max: Int = 8, ttlMs: Long = 1_000L) = UnplayedReplyStore(backing, { now }, max, ttlMs)
    private fun entry(id: String, text: String, at: Long = now) =
        UnplayedReplyStore.Entry(id, "turn-$id", VoiceOrigin.WATCH, "node-1", "session-1", true, text, at)

    private fun raw() = kv.getString(UnplayedReplyStore.KEY) ?: ""

    private fun withExpired() {
        assertTrue(store().put(entry("old#final", expiredText)))
        now += 1_001L
    }

    @Test fun `listing after the limit removes the expired text from the stored string`() {
        withExpired()
        assertTrue(store().all().isEmpty())
        assertFalse("physically gone, not just filtered", raw().contains(expiredText))
    }

    @Test fun `reading one expired reply removes its text from the stored string`() {
        withExpired()
        assertNull(store().get("old#final"))
        assertFalse(raw().contains(expiredText))
    }

    @Test fun `removing a different reply also purges an expired one`() {
        assertTrue(store().put(entry("old#final", expiredText)))
        now += 600L
        assertTrue(store().put(entry("new#final", freshText)))
        now += 600L
        store().remove("new#final")
        assertFalse("the expired text is not left behind by a remove", raw().contains(expiredText))
        assertTrue(store().all().isEmpty())
    }

    @Test fun `removing an unknown identity still purges an expired reply`() {
        withExpired()
        store().remove("never-stored#final")
        assertFalse(raw().contains(expiredText))
    }

    @Test fun `storing a new reply drops the expired text and keeps the fresh one`() {
        withExpired()
        assertTrue(store().put(entry("new#final", freshText)))
        assertFalse(raw().contains(expiredText))
        assertEquals(listOf("new#final"), store().all().map { it.identity })
    }

    @Test fun `a fresh reply is untouched by the purge`() {
        assertTrue(store().put(entry("a#final", freshText)))
        now += 500L
        assertEquals(freshText, store().get("a#final")?.text)
        assertEquals(freshText, store().all().single().text)
        assertTrue(raw().contains(freshText))
    }

    @Test fun `an entry exactly at the limit is still listed and one millisecond later is not`() {
        assertTrue(store().put(entry("edge#final", freshText)))
        now += 1_000L
        assertEquals(listOf("edge#final"), store().all().map { it.identity })
        now += 1L
        assertTrue(store().all().isEmpty())
    }

    @Test fun `a reply dated in the future after the clock was set back is not kept either`() {
        assertTrue(store().put(entry("future#final", expiredText, at = now + 10_000L)))
        assertTrue(store().all().isEmpty())
        assertFalse("cannot be shown, so it is not kept", raw().contains(expiredText))
    }

    @Test fun `a failed commit never exposes the expired reply and a later working commit purges it`() {
        withExpired()
        val failures = AtomicInteger(1)
        val flaky = object : KeyValueStore by kv {
            override fun commitString(key: String, value: String): Boolean =
                if (failures.getAndDecrement() > 0) false else kv.commitString(key, value)
        }
        assertTrue("fails closed: the expired reply is not visible", store(flaky).all().isEmpty())
        assertNull(store(flaky).get("old#final"))
        assertFalse("and it is purged once a commit works", raw().contains(expiredText))
    }

    @Test fun `a commit that always fails keeps nothing visible and a put reports false`() {
        withExpired()
        val failing = object : KeyValueStore by kv {
            override fun commitString(key: String, value: String): Boolean = false
        }
        val s = store(failing)
        assertTrue(s.all().isEmpty())
        assertNull(s.get("old#final"))
        assertFalse(s.put(entry("new#final", freshText)))
    }

    @Test fun `the bound of eight and the three day lifetime are unchanged`() {
        assertEquals(8, UnplayedReplyStore.DEFAULT_MAX)
        assertEquals(3L * 24 * 60 * 60 * 1000, UnplayedReplyStore.DEFAULT_TTL_MS)
        val s = UnplayedReplyStore(kv, { now })
        (1..10).forEach { s.put(entry("e$it#final", "t$it")) }
        assertEquals((3..10).map { "e$it#final" }, s.all().map { it.identity })
    }
}
