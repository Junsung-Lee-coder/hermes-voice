package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.*
import org.junit.Test
import org.junit.Assert.*

/** Pure matching contract of the validated spoken choice (PHONE-R2-01): the app catalog only, never a guess. */
class LiveTargetChoiceTest {
    private val alpha = LiveTarget("s-alpha", "Alpha", "alpha")
    private val home = LiveTarget("s-home", "Notes", "notes-home")
    private val work = LiveTarget("s-work", "Notes", "notes-work")
    private val korean = LiveTarget("s-ko", "회의록", "minutes")
    private val all = listOf(alpha, home, work, korean)
    private fun chosen(speech: String, bound: String? = null) = (LiveTargetChoice.resolve(speech, all, bound) as? LiveChoice.Chosen)?.target

    @Test fun titleAliasAndAttachedKoreanParticleNameExactlyOneConversation() {
        assertEquals(alpha, chosen("Alpha, 요약해 줘"))
        assertEquals(work, chosen("notes work 에 일정 추가"))
        assertEquals(work, chosen("NOTES-WORK please"))
        assertEquals(korean, chosen("회의록에 정리해 줘"))
        assertEquals(korean, chosen("put it in minutes"))
    }
    @Test fun equalTitlesSeveralNamesAndPartialLatinWordsAreNeverGuessed() {
        val equal = LiveTargetChoice.resolve("notes 정리", all, null) as LiveChoice.Ambiguous
        assertEquals(setOf(home, work), equal.candidates.toSet())
        assertTrue(LiveTargetChoice.resolve("alpha 그리고 minutes", all, null) is LiveChoice.Ambiguous)
        assertEquals(LiveChoice.Unnamed, LiveTargetChoice.resolve("alphabet soup", all, null))
        assertEquals(LiveChoice.Unnamed, LiveTargetChoice.resolve("", all, null))
        assertEquals(LiveChoice.Unnamed, LiveTargetChoice.resolve("alpha", emptyList(), null))
    }
    @Test fun onlyAnAlreadyBoundCatalogConversationIsTheDefaultWhenNothingIsNamed() {
        assertEquals(alpha, chosen("계속 진행해 줘", bound = "s-alpha"))
        assertEquals(work, chosen("notes work 로 보내", bound = "s-alpha"))
        assertEquals(LiveChoice.Unnamed, LiveTargetChoice.resolve("계속", all, "unlisted-id"))
        assertEquals("a b 1", LiveTargetChoice.normalize("  A—b_1!! "))
    }
}
