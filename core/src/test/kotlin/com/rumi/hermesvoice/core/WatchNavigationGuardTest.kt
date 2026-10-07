package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.WatchNavigation
import com.rumi.hermesvoice.core.watchlink.WatchNavigationGuard
import com.rumi.hermesvoice.core.watchlink.WatchNavigationGuard.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Watch's guard for a Phone-told conversation move: its own newest request, once, from its node, with no user navigation since. */
class WatchNavigationGuardTest {
    private val guard = WatchNavigationGuard()

    private fun nav(turn: String, generation: Long = 0, session: String = "work_session") = WatchNavigation(turn, session, generation)

    private fun started(turn: String, node: String = "phone-1"): Long = guard.onTurnStarted(turn).also { guard.onUploadNode(turn, node) }

    @Test
    fun `the newest own request's move applies once, and a repeat is a duplicate`() {
        val generation = started("watch-turn-0001")
        assertEquals(Verdict.APPLY, guard.accept(nav("watch-turn-0001", generation), "phone-1"))
        assertEquals(Verdict.DUPLICATE, guard.accept(nav("watch-turn-0001", generation), "phone-1"))
    }

    @Test
    fun `a turn this Watch never started is refused, as a Phone-origin or forged one would be`() {
        assertEquals(Verdict.UNKNOWN_TURN, guard.accept(nav("phone-turn-0001"), "phone-1"))
    }

    @Test
    fun `a message from a node the request did not go to is refused`() {
        val generation = started("watch-turn-0002")
        assertEquals(Verdict.WRONG_NODE, guard.accept(nav("watch-turn-0002", generation), "other-phone"))
    }

    @Test
    fun `an older request's late move after a newer request began is refused, and the newer one still applies`() {
        val first = started("watch-turn-0003")
        val second = started("watch-turn-0004")
        assertEquals(Verdict.SUPERSEDED_TURN, guard.accept(nav("watch-turn-0003", first), "phone-1"))
        assertEquals(Verdict.APPLY, guard.accept(nav("watch-turn-0004", second), "phone-1"))
    }

    @Test
    fun `out-of-order delivery - the newer move first - still leaves the older one refused`() {
        val first = started("watch-turn-0005")
        val second = started("watch-turn-0006")
        assertEquals(Verdict.APPLY, guard.accept(nav("watch-turn-0006", second), "phone-1"))
        assertEquals(Verdict.SUPERSEDED_TURN, guard.accept(nav("watch-turn-0005", first), "phone-1"))
        assertEquals(Verdict.DUPLICATE, guard.accept(nav("watch-turn-0005", first), "phone-1"))
    }

    @Test
    fun `a deliberate selection or screen change made after the request began is never overwritten`() {
        val generation = started("watch-turn-0007")
        guard.onUserNavigation()
        assertEquals(Verdict.STALE_SELECTION, guard.accept(nav("watch-turn-0007", generation), "phone-1"))
        assertEquals("and it cannot be retried", Verdict.DUPLICATE, guard.accept(nav("watch-turn-0007", generation), "phone-1"))
        // A request begun after the user's choice carries the new generation and applies.
        val later = started("watch-turn-0008")
        assertEquals(1L, later)
        assertEquals(Verdict.APPLY, guard.accept(nav("watch-turn-0008", later), "phone-1"))
    }

    @Test
    fun `a move that claims another generation than the one the request carried is refused`() {
        val generation = started("watch-turn-0009")
        assertEquals(Verdict.STALE_SELECTION, guard.accept(nav("watch-turn-0009", generation + 1), "phone-1"))
    }

    @Test
    fun `Stop forgets every pending request`() {
        val generation = started("watch-turn-0010")
        guard.onStopped()
        assertEquals(Verdict.UNKNOWN_TURN, guard.accept(nav("watch-turn-0010", generation), "phone-1"))
    }

    @Test
    fun `a request registered only when its upload is built is tracked as the newest`() {
        val generation = guard.generationFor("watch-turn-0011")
        assertEquals(generation, guard.generationFor("watch-turn-0011"))
        assertEquals(Verdict.APPLY, guard.accept(nav("watch-turn-0011", generation), "phone-1"))
    }

    @Test
    fun `bookkeeping is bounded - the oldest requests are forgotten`() {
        val small = WatchNavigationGuard(maxTracked = 2)
        small.onTurnStarted("watch-turn-0012")
        small.onTurnStarted("watch-turn-0013")
        small.onTurnStarted("watch-turn-0014")
        assertEquals(Verdict.UNKNOWN_TURN, small.accept(nav("watch-turn-0012"), "phone-1"))
        assertEquals(Verdict.APPLY, small.accept(nav("watch-turn-0014"), "phone-1"))
    }
}
