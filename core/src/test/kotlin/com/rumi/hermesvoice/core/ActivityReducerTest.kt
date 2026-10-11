package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.activity.ActivityBubble
import com.rumi.hermesvoice.core.activity.ActivityEvent
import com.rumi.hermesvoice.core.activity.ActivityFeed
import com.rumi.hermesvoice.core.activity.ActivityLabels
import com.rumi.hermesvoice.core.activity.ActivityReducer
import com.rumi.hermesvoice.core.activity.ActivityState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The seam that makes one replace-in-place slot per request, with no resurrection after the end. */
class ActivityReducerTest {
    private fun run(vararg events: ActivityEvent): ActivityState = events.fold(ActivityState()) { s, e -> ActivityReducer.apply(s, e) }
    private fun labels(s: ActivityState) = s.bubbles.map { it.key to it.label }

    @Test fun `begin opens one waiting slot and updates replace its label, never growing a log`() {
        val s = run(ActivityEvent.Begin("k1", "sA"), ActivityEvent.Update("k1", "🐍 Running code"), ActivityEvent.Update("k1", "🔎 Searching files"))
        assertEquals(listOf("k1" to "🔎 Searching files"), labels(s))
        assertEquals("sA", s.bubbles.single().sessionId)
        assertEquals(listOf("k1" to ActivityLabels.WAITING), labels(run(ActivityEvent.Begin("k1", "sA"))))
    }

    @Test fun `a repeated begin or an unchanged label changes nothing`() {
        val once = run(ActivityEvent.Begin("k1", "sA"), ActivityEvent.Update("k1", "x"))
        assertEquals(once, ActivityReducer.apply(once, ActivityEvent.Begin("k1", "sB")))
        assertEquals(once, ActivityReducer.apply(once, ActivityEvent.Update("k1", "x")))
    }

    @Test fun `an update for an unknown key is ignored`() {
        assertEquals(ActivityState(), run(ActivityEvent.Update("ghost", "🐍 Running code")))
    }

    @Test fun `two requests keep two independent slots`() {
        val s = run(ActivityEvent.Begin("k1", "sA"), ActivityEvent.Begin("k2", "sB"), ActivityEvent.Update("k2", "📖 Reading"))
        assertEquals(listOf("k1" to ActivityLabels.WAITING, "k2" to "📖 Reading"), labels(s))
    }

    @Test fun `a final reply marks the slot closing so it can be replaced in place, and nothing resurrects it`() {
        val ended = run(ActivityEvent.Begin("k1", "sA"), ActivityEvent.Update("k1", "🐍 Running code"), ActivityEvent.End("k1", replied = true))
        assertTrue(ended.bubbles.single().closing)
        assertEquals("🐍 Running code", ended.bubbles.single().label)
        val late = ActivityReducer.apply(ActivityReducer.apply(ended, ActivityEvent.Update("k1", "🔎 Searching files")), ActivityEvent.Begin("k1", "sA"))
        assertEquals(ended, late)
        val released = ActivityReducer.release(late, "sA")
        assertTrue(released.bubbles.isEmpty())
        assertEquals("a released key never returns", released, ActivityReducer.apply(ActivityReducer.apply(released, ActivityEvent.Begin("k1", "sA")), ActivityEvent.Update("k1", "x")))
    }

    @Test fun `a failed or cancelled end removes the slot at once and it cannot return`() {
        val failed = run(ActivityEvent.Begin("k1", "sA"), ActivityEvent.End("k1", replied = false))
        assertTrue(failed.bubbles.isEmpty())
        assertEquals(failed, ActivityReducer.apply(failed, ActivityEvent.Begin("k1", "sA")))
        assertEquals(failed, ActivityReducer.apply(failed, ActivityEvent.Update("k1", "🐍 Running code")))
    }

    @Test fun `releasing a conversation drops only its own closing slots`() {
        val s = run(ActivityEvent.Begin("k1", "sA"), ActivityEvent.Begin("k2", "sA"), ActivityEvent.Begin("k3", "sB"),
            ActivityEvent.End("k1", true), ActivityEvent.End("k3", true))
        val released = ActivityReducer.release(s, "sA")
        assertEquals(listOf("k2", "k3"), released.bubbles.map { it.key })
        assertTrue("an active slot is never released by a history refresh", !released.bubbles.first().closing)
    }

    @Test fun `state is bounded in slots and remembered keys`() {
        var s = ActivityState()
        repeat(ActivityReducer.MAX_BUBBLES + 5) { s = ActivityReducer.apply(s, ActivityEvent.Begin("k$it", "sA")) }
        assertTrue(s.bubbles.size <= ActivityReducer.MAX_BUBBLES)
        repeat(ActivityReducer.MAX_CLOSED + 30) { i -> s = ActivityReducer.apply(ActivityReducer.apply(s, ActivityEvent.Begin("c$i", "sB")), ActivityEvent.End("c$i", false)) }
        assertTrue(s.closed.size <= ActivityReducer.MAX_CLOSED)
    }

    // ── the feed the transport writes to ─────────────────────────────────────────────────────

    @Test fun `the feed emits begin, changed updates and the end, and its snapshot holds only active slots`(): Unit = runBlocking {
        val feed = ActivityFeed()
        val seen = ArrayList<ActivityEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { feed.events.toList(seen) }
        feed.begin("k1", "sA")
        feed.update("k1", "🐍 Running code")
        feed.update("k1", "🐍 Running code")
        assertEquals(listOf("k1" to "🐍 Running code"), labels(feed.snapshot()))
        feed.end("k1", replied = true)
        assertTrue(feed.snapshot().bubbles.isEmpty())
        yield()
        job.cancel()
        assertEquals(listOf(ActivityEvent.Begin("k1", "sA"), ActivityEvent.Update("k1", "🐍 Running code"), ActivityEvent.End("k1", true)), seen)
    }

    @Test fun `the feed ignores late, duplicate and replayed events after the end`(): Unit = runBlocking {
        val feed = ActivityFeed()
        val seen = ArrayList<ActivityEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { feed.events.toList(seen) }
        feed.begin("k1", "sA")
        feed.end("k1", replied = false)
        feed.update("k1", "🔎 Searching files")
        feed.begin("k1", "sA")
        feed.end("k1", replied = true)
        yield()
        job.cancel()
        assertEquals(listOf(ActivityEvent.Begin("k1", "sA"), ActivityEvent.End("k1", false)), seen)
        assertTrue(feed.snapshot().bubbles.isEmpty())
    }

    @Test fun `a viewer created late is seeded from the snapshot`() {
        val feed = ActivityFeed()
        feed.begin("k1", "sA"); feed.update("k1", "📖 Reading"); feed.begin("k2", "sB"); feed.end("k2", true)
        assertEquals(listOf(ActivityBubble("k1", "sA", "📖 Reading")), feed.snapshot().bubbles)
    }
}
