package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.sessions.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.voice.*
import com.rumi.hermesvoice.core.watchlink.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipientResponseTrackerTest {
    private fun spokenFor(settings: ResponsePlaybackSettings, vararg events: RecipientEvent): List<Pair<SpokenRole, String?>> {
        val tracker = RecipientResponseTracker(settings)
        return events.mapNotNull(tracker::onEvent).map { it.role to it.speakText }
    }

    private val threeResponses = arrayOf(
        RecipientEvent.Interim("Looking at the calendar."),
        RecipientEvent.Interim("Found two conflicts."),
        RecipientEvent.Complete("Moved the meeting to 3pm.", "complete"),
    )

    @Test
    fun `defaults speak only the final response`() {
        assertEquals(
            listOf(SpokenRole.FIRST to null, SpokenRole.MIDDLE to null, SpokenRole.FINAL to "Moved the meeting to 3pm."),
            spokenFor(ResponsePlaybackSettings(), *threeResponses),
        )
    }

    @Test
    fun `first switch alone speaks first and final`() {
        assertEquals(
            listOf(SpokenRole.FIRST to "Looking at the calendar.", SpokenRole.MIDDLE to null,
                SpokenRole.FINAL to "Moved the meeting to 3pm."),
            spokenFor(ResponsePlaybackSettings(playFirstResponse = true), *threeResponses),
        )
    }

    @Test
    fun `middle switch alone speaks middle and final`() {
        assertEquals(
            listOf(SpokenRole.FIRST to null, SpokenRole.MIDDLE to "Found two conflicts.",
                SpokenRole.FINAL to "Moved the meeting to 3pm."),
            spokenFor(ResponsePlaybackSettings(playMiddleResponses = true), *threeResponses),
        )
    }

    @Test
    fun `both switches speak every distinct response`() {
        assertEquals(
            listOf(SpokenRole.FIRST to "Looking at the calendar.", SpokenRole.MIDDLE to "Found two conflicts.",
                SpokenRole.FINAL to "Moved the meeting to 3pm."),
            spokenFor(ResponsePlaybackSettings(true, true), *threeResponses),
        )
    }

    @Test
    fun `several middle responses are each middle`() {
        val roles = spokenFor(ResponsePlaybackSettings(playMiddleResponses = true),
            RecipientEvent.Interim("a"), RecipientEvent.Interim("b"), RecipientEvent.Interim("c"),
            RecipientEvent.Complete("d", "complete"))
        assertEquals(listOf(SpokenRole.FIRST to null, SpokenRole.MIDDLE to "b", SpokenRole.MIDDLE to "c",
            SpokenRole.FINAL to "d"), roles)
    }

    @Test
    fun `single completed response plays once as final whatever the switches`() {
        for (settings in listOf(ResponsePlaybackSettings(), ResponsePlaybackSettings(true, true))) {
            assertEquals(listOf(SpokenRole.FINAL to "Done."),
                spokenFor(settings, RecipientEvent.Complete("Done.", "complete")))
        }
    }

    @Test
    fun `final identical to spoken first is not re-spoken`() {
        assertEquals(
            listOf(SpokenRole.FIRST to "The answer is 42.", SpokenRole.FINAL to null),
            spokenFor(ResponsePlaybackSettings(playFirstResponse = true),
                RecipientEvent.Interim("The answer is 42."), RecipientEvent.Complete("The  answer is 42.\n", "complete")),
        )
    }

    @Test
    fun `final identical to an unheard interim still plays`() {
        assertEquals(
            listOf(SpokenRole.FIRST to null, SpokenRole.FINAL to "The answer is 42."),
            spokenFor(ResponsePlaybackSettings(),
                RecipientEvent.Interim("The answer is 42."), RecipientEvent.Complete("The answer is 42.", "complete")),
        )
    }

    @Test
    fun `final extending the spoken interim speaks only the new remainder`() {
        assertEquals(
            listOf(SpokenRole.FIRST to "The answer is 42.", SpokenRole.FINAL to "It was verified twice."),
            spokenFor(ResponsePlaybackSettings(playFirstResponse = true),
                RecipientEvent.Interim("The answer is 42."),
                RecipientEvent.Complete("The answer is 42. It was verified twice.", "complete")),
        )
    }

    @Test
    fun `duplicate interims and late events are ignored`() {
        val tracker = RecipientResponseTracker(ResponsePlaybackSettings(true, true))
        assertEquals(SpokenRole.FIRST, tracker.onEvent(RecipientEvent.Interim("x"))?.role)
        assertNull(tracker.onEvent(RecipientEvent.Interim(" x ")))
        assertNull(tracker.onEvent(RecipientEvent.Interim("   ")))
        assertEquals(SpokenRole.FINAL, tracker.onEvent(RecipientEvent.Complete("y", "complete"))?.role)
        assertNull(tracker.onEvent(RecipientEvent.Complete("y", "complete")))
        assertNull(tracker.onEvent(RecipientEvent.Interim("z")))
    }

    @Test
    fun `empty final is classified but silent`() {
        val decision = RecipientResponseTracker(ResponsePlaybackSettings()).onEvent(RecipientEvent.Complete("", "error"))
        assertEquals(SpokenRole.FINAL, decision?.role)
        assertNull(decision?.speakText)
        assertEquals("final_empty", decision?.reason)
    }
}
