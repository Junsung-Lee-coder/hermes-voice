package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.WakePhrasePatterns
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.wake.WakeArmGate
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeBlock
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeHandoff
import com.rumi.hermesvoice.core.wake.WakeOutcome
import com.rumi.hermesvoice.core.wake.WakeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeContractTest {
    private val defaults = WakePhrasePatterns.DEFAULT_PATTERNS

    @Test
    fun `request after the wake token is preserved and phrase-only yields an empty request`() {
        assertEquals("", WakePhrasePatterns.requestAfterWake(defaults, "루미야"))
        assertEquals("", WakePhrasePatterns.requestAfterWake(defaults, "  룸미봇!  "))
        assertEquals("내일 일정 알려줘", WakePhrasePatterns.requestAfterWake(defaults, "루미야 내일 일정 알려줘"))
        assertEquals("녹음해 줘", WakePhrasePatterns.requestAfterWake(defaults, "룸미야, 녹음해 줘"))
        assertEquals("날씨 어때?", WakePhrasePatterns.requestAfterWake(defaults, "안녕, 루미! 날씨 어때?"))
        assertEquals("what's next", WakePhrasePatterns.requestAfterWake("hey-hermes herm*", "Hermione what's next"))
        for (negative in listOf("루미네이트 해줘", "푸루미야", "루미봇입니다", "룸이봇처럼", "룸미야말로", "안녕하세요", "")) {
            assertNull(negative, WakePhrasePatterns.requestAfterWake(defaults, negative))
        }
    }

    @Test
    fun `legacy and wildcard pattern strings keep their meaning`() {
        assertEquals(defaults, WakePhrasePatterns.normalize(null))
        assertEquals(defaults, WakePhrasePatterns.normalize("   "))
        assertEquals("루미 hey*", WakePhrasePatterns.normalize(" 루미  hey*  루미 "))
        assertTrue(WakePhrasePatterns.matches("hey*", "heyyy there"))
        assertFalse(WakePhrasePatterns.matches("hey*", "they are"))
        assertTrue(WakePhrasePatterns.matches("a.b", "a.b"))
        assertFalse("regex metacharacters are literal", WakePhrasePatterns.matches("a.b", "axb"))
        // A legacy settings payload without the new fields keeps wake OFF and the default patterns.
        val legacy = WatchSettings.fromJson("""{"wake_patterns":"루미 hey*","max_turn_seconds":45}""")
        assertFalse(legacy.wakePhraseEnabled)
        assertEquals("루미 hey*", legacy.wakePatterns)
        assertEquals(0L, legacy.revision)
    }

    @Test
    fun `phone-owned settings snapshots reject stale and equal-revision conflicts`() {
        val current = WatchSettings(wakePhraseEnabled = true, wakePatterns = "루미", revision = 200)
        assertFalse(WatchSettings.shouldApply(current, current.copy(wakePhraseEnabled = false, revision = 100)))
        assertFalse(WatchSettings.shouldApply(current, current.copy(wakePhraseEnabled = false, revision = 200)))
        assertTrue(WatchSettings.shouldApply(current, current.copy(wakePhraseEnabled = false, revision = 201)))
        // An unsynced Watch (or a legacy Phone payload) accepts whatever the Phone sends.
        assertTrue(WatchSettings.shouldApply(WatchSettings(), WatchSettings(wakePhraseEnabled = true)))
        val round = WatchSettings.fromJson(current.toJson())
        assertEquals(current, round)
    }

    @Test
    fun `phrase-only final hands off to a second utterance after the ready cue`() {
        val session = WakeSession()
        session.open(generation = 1, nowMs = 0)
        assertEquals(WakeOutcome.None, session.onResults(1, listOf("루미"), final = false, nowMs = 400, patterns = defaults))
        val outcome = session.onResults(1, listOf("루미야"), final = true, nowMs = 900, patterns = defaults)
        assertEquals(WakeOutcome.Handoff(WakeHandoff.SECOND_UTTERANCE, ""), outcome)
        assertEquals("exactly once", WakeOutcome.None, session.onResults(1, listOf("루미야"), final = true, nowMs = 950, patterns = defaults))
    }

    @Test
    fun `a request spoken in the same breath is carried as the recognized request, never dropped`() {
        val session = WakeSession()
        session.open(generation = 2, nowMs = 0)
        assertEquals(WakeOutcome.None, session.onResults(2, listOf("루미야 내일"), final = false, nowMs = 1_000, patterns = defaults))
        // The recognizer's own endpoint arrives after the 5 s arm window; the matched turn has a grace period.
        val outcome = session.onResults(2, listOf("루미야 내일 일정 알려줘"), final = true, nowMs = 6_500, patterns = defaults)
        assertEquals(WakeOutcome.Handoff(WakeHandoff.RECOGNIZED_REQUEST, "내일 일정 알려줘"), outcome)
    }

    @Test
    fun `a matched partial is not lost when the recognizer errors or the grace deadline passes`() {
        val errored = WakeSession().apply { open(3, 0) }
        errored.onResults(3, listOf("루미야 불 꺼"), final = false, nowMs = 500, patterns = defaults)
        assertEquals(WakeOutcome.Handoff(WakeHandoff.RECOGNIZED_REQUEST, "불 꺼"), errored.onError(3, 7))

        val timedOut = WakeSession().apply { open(4, 0) }
        timedOut.onResults(4, listOf("루미"), final = false, nowMs = 500, patterns = defaults)
        assertEquals(WakeOutcome.None, timedOut.onDeadline(4, nowMs = 5_000))
        assertEquals(WakeOutcome.Handoff(WakeHandoff.SECOND_UTTERANCE, ""), timedOut.onDeadline(4, nowMs = 500 + WakeContract.FINAL_GRACE_MS))
    }

    @Test
    fun `no match, timeouts, stale generations and late results close without a handoff`() {
        val miss = WakeSession().apply { open(5, 0) }
        assertEquals(WakeOutcome.Closed("not_matched"), miss.onResults(5, listOf("안녕하세요"), final = true, nowMs = 800, patterns = defaults))

        val quiet = WakeSession().apply { open(6, 0) }
        assertEquals(WakeOutcome.None, quiet.onDeadline(6, nowMs = 4_999))
        assertEquals(WakeOutcome.Closed("timeout"), quiet.onDeadline(6, nowMs = WakeContract.WINDOW_MS))

        val stale = WakeSession().apply { open(7, 0) }
        assertEquals(WakeOutcome.None, stale.onResults(6, listOf("루미"), final = true, nowMs = 100, patterns = defaults))
        assertEquals(WakeOutcome.Closed("screen_off"), stale.cancel("screen_off"))
        assertEquals(WakeOutcome.None, stale.onResults(7, listOf("루미"), final = true, nowMs = 200, patterns = defaults))

        val late = WakeSession().apply { open(8, 0) }
        assertEquals(WakeOutcome.Closed("deadline"), late.onResults(8, listOf("루미야"), final = true, nowMs = WakeContract.WINDOW_MS + 1, patterns = defaults))
    }

    @Test
    fun `recognized requests are bounded`() {
        val session = WakeSession().apply { open(9, 0) }
        val outcome = session.onResults(9, listOf("루미 " + "가".repeat(5_000)), final = true, nowMs = 10, patterns = defaults)
        val handoff = outcome as WakeOutcome.Handoff
        assertEquals(WakeContract.MAX_REQUEST_CHARS, handoff.request.length)
    }

    @Test
    fun `arm gate is foreground-only, opt-in and quiet while busy or cooling down`() {
        val ok = WakeArmInputs(enabled = true, resumed = true, interactive = true, ambient = false, permission = true,
            microphoneMuted = false, talkIdle = true, phoneReachable = true, nowMs = 10_000, cooldownUntilMs = 0,
            generation = 3, lastArmedGeneration = 2)
        assertNull(WakeArmGate.block(ok))
        assertEquals(WakeBlock.DISABLED, WakeArmGate.block(ok.copy(enabled = false)))
        assertEquals(WakeBlock.NOT_FOREGROUND, WakeArmGate.block(ok.copy(resumed = false)))
        assertEquals(WakeBlock.NOT_FOREGROUND, WakeArmGate.block(ok.copy(interactive = false)))
        assertEquals(WakeBlock.NOT_FOREGROUND, WakeArmGate.block(ok.copy(ambient = true)))
        assertEquals(WakeBlock.PERMISSION, WakeArmGate.block(ok.copy(permission = false)))
        assertEquals(WakeBlock.MICROPHONE_MUTED, WakeArmGate.block(ok.copy(microphoneMuted = true)))
        assertEquals(WakeBlock.BUSY, WakeArmGate.block(ok.copy(talkIdle = false)))
        assertEquals(WakeBlock.PHONE_UNREACHABLE, WakeArmGate.block(ok.copy(phoneReachable = false)))
        assertNull("unknown reachability does not block", WakeArmGate.block(ok.copy(phoneReachable = null)))
        assertEquals(WakeBlock.COOLDOWN, WakeArmGate.block(ok.copy(cooldownUntilMs = 10_001)))
        assertEquals(WakeBlock.ALREADY_ARMED, WakeArmGate.block(ok.copy(lastArmedGeneration = 3)))
    }
}
