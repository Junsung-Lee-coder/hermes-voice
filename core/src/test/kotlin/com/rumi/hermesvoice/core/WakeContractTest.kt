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
        // A legacy Watch recording cap is ignored: Watch recordings have no total duration limit.
        assertFalse(legacy.toJson().contains("max_turn_seconds"))
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
    fun `only a leading wake phrase counts, with at most one short greeting before it`() {
        assertEquals("내일 일정 알려줘", WakePhrasePatterns.leadingRequest(defaults, "루미야 내일 일정 알려줘"))
        assertEquals("날씨 어때?", WakePhrasePatterns.leadingRequest(defaults, "안녕, 루미! 날씨 어때?"))
        assertEquals("what's next", WakePhrasePatterns.leadingRequest("hermes", "Hey Hermes, what's next"))
        assertEquals("", WakePhrasePatterns.leadingRequest(defaults, "루미야."))
        for (ambient in listOf("I told hermes about the budget", "그래서 루미가 그랬대", "안녕 반가워 루미야 불 꺼", "hey there hermes stop")) {
            assertNull(ambient, WakePhrasePatterns.leadingRequest("hermes 루미 루미야 루미가", ambient))
        }
        assertNull(WakePhrasePatterns.leadingRequest(defaults, "루미네이트 해줘"))
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
    fun `only the final hypothesis of a same-breath request is sent, however long the speech continues`() {
        for (seconds in listOf(30, 60, 120)) {
            val session = WakeSession().apply { open(2, 0) }
            val words = StringBuilder("루미야")
            var t = 300L
            while (t < seconds * 1_000L) {
                words.append(" 말").append(t / 500)
                assertEquals("$seconds s partial at $t", WakeOutcome.None,
                    session.onResults(2, listOf(words.toString()), final = false, nowMs = t, patterns = defaults))
                assertEquals(WakeOutcome.None, session.onDeadline(2, nowMs = t + 100))
                t += 500
            }
            val final = session.onResults(2, listOf(words.toString()), final = true, nowMs = t + 700, patterns = defaults)
            assertEquals(WakeOutcome.Handoff(WakeHandoff.RECOGNIZED_REQUEST, words.removePrefix("루미야 ").toString()), final)
        }
    }

    @Test
    fun `an unfinished request is never sent when the recognizer errors or goes quiet`() {
        val errored = WakeSession().apply { open(3, 0) }
        errored.onResults(3, listOf("루미야 불 꺼"), final = false, nowMs = 500, patterns = defaults)
        assertEquals(WakeOutcome.Closed("unfinished_request"), errored.onError(3, 2))

        val quiet = WakeSession().apply { open(4, 0) }
        quiet.onResults(4, listOf("루미야 문 열어"), final = false, nowMs = 500, patterns = defaults)
        assertEquals(WakeOutcome.None, quiet.onDeadline(4, nowMs = 500 + WakeContract.PENDING_INACTIVITY_MS - 1))
        assertEquals(WakeOutcome.Closed("unfinished_request"), quiet.onDeadline(4, nowMs = 500 + WakeContract.PENDING_INACTIVITY_MS))

        val late = WakeSession().apply { open(5, 0) }
        late.onResults(5, listOf("루미야 문 열어"), final = false, nowMs = 500, patterns = defaults)
        assertEquals("a final after the recognizer went quiet is not trusted", WakeOutcome.Closed("unfinished_request"),
            late.onResults(5, listOf("루미야 문 열어 줘"), final = true, nowMs = 500 + WakeContract.PENDING_INACTIVITY_MS + 1, patterns = defaults))
    }

    @Test
    fun `a phrase-only partial still cues a second utterance when the recognizer stops`() {
        val timedOut = WakeSession().apply { open(6, 0) }
        timedOut.onResults(6, listOf("루미"), final = false, nowMs = 500, patterns = defaults)
        assertEquals(WakeOutcome.None, timedOut.onDeadline(6, nowMs = 5_000))
        assertEquals(WakeOutcome.Handoff(WakeHandoff.SECOND_UTTERANCE, ""), timedOut.onDeadline(6, nowMs = 500 + WakeContract.PENDING_INACTIVITY_MS))
        val errored = WakeSession().apply { open(7, 0) }
        errored.onResults(7, listOf("루미야"), final = false, nowMs = 500, patterns = defaults)
        assertEquals(WakeOutcome.Handoff(WakeHandoff.SECOND_UTTERANCE, ""), errored.onError(7, 7))
    }

    @Test
    fun `ambient speech with no leading wake phrase closes silently`() {
        val ambient = WakeSession().apply { open(8, 0) }
        assertEquals(WakeOutcome.None, ambient.onResults(8, listOf("I told hermes"), final = false, nowMs = 300, patterns = "hermes"))
        assertEquals(WakeOutcome.Closed("not_matched"),
            ambient.onResults(8, listOf("I told hermes about the budget"), final = true, nowMs = 900, patterns = "hermes"))
        val empty = WakeSession().apply { open(16, 0) }
        assertEquals(WakeOutcome.Closed("not_matched"), empty.onResults(16, emptyList(), final = true, nowMs = 900, patterns = defaults))
    }

    @Test
    fun `a heard wake command whose final disagrees or is empty is never dropped silently or sent`() {
        val revised = WakeSession().apply { open(9, 0) }
        revised.onResults(9, listOf("루미야 불"), final = false, nowMs = 300, patterns = defaults)
        assertEquals(WakeOutcome.Closed("unfinished_request"), revised.onResults(9, listOf("누구야 불 꺼"), final = true, nowMs = 900, patterns = defaults))

        val emptyFinal = WakeSession().apply { open(17, 0) }
        emptyFinal.onResults(17, listOf("hermes turn on the"), final = false, nowMs = 300, patterns = "hermes")
        assertEquals(WakeOutcome.Closed("unfinished_request"), emptyFinal.onResults(17, emptyList(), final = true, nowMs = 900, patterns = "hermes"))

        val misheard = WakeSession().apply { open(18, 0) }
        misheard.onResults(18, listOf("hermes turn on the"), final = false, nowMs = 300, patterns = "hermes")
        assertEquals(WakeOutcome.Closed("unfinished_request"),
            misheard.onResults(18, listOf("her mess turn on the lights"), final = true, nowMs = 900, patterns = "hermes"))

        val phraseOnly = WakeSession().apply { open(19, 0) }
        phraseOnly.onResults(19, listOf("루미야"), final = false, nowMs = 300, patterns = defaults)
        assertEquals("no recorder is started from a contradicted phrase", WakeOutcome.Closed("unfinished_request"),
            phraseOnly.onResults(19, listOf("누구야"), final = true, nowMs = 900, patterns = defaults))
    }

    @Test
    fun `no match, timeouts, stale generations and late results close without a handoff`() {
        val miss = WakeSession().apply { open(10, 0) }
        assertEquals(WakeOutcome.Closed("not_matched"), miss.onResults(10, listOf("안녕하세요"), final = true, nowMs = 800, patterns = defaults))

        val quiet = WakeSession().apply { open(11, 0) }
        assertEquals(WakeOutcome.None, quiet.onDeadline(11, nowMs = 4_999))
        assertEquals(WakeOutcome.Closed("timeout"), quiet.onDeadline(11, nowMs = WakeContract.WINDOW_MS))

        val stale = WakeSession().apply { open(12, 0) }
        assertEquals(WakeOutcome.None, stale.onResults(11, listOf("루미"), final = true, nowMs = 100, patterns = defaults))
        assertEquals(WakeOutcome.Closed("screen_off"), stale.cancel("screen_off"))
        assertEquals(WakeOutcome.None, stale.onResults(12, listOf("루미"), final = true, nowMs = 200, patterns = defaults))

        val late = WakeSession().apply { open(13, 0) }
        assertEquals(WakeOutcome.Closed("deadline"), late.onResults(13, listOf("루미야"), final = true, nowMs = WakeContract.WINDOW_MS + 1, patterns = defaults))
    }

    @Test
    fun `an over-long recognized request is refused explicitly, never truncated`() {
        val session = WakeSession().apply { open(14, 0) }
        val outcome = session.onResults(14, listOf("루미 " + "가".repeat(WakeContract.MAX_REQUEST_CHARS + 1)), final = true, nowMs = 10,
            patterns = defaults)
        assertEquals(WakeOutcome.Closed("request_too_long"), outcome)
        val fits = WakeSession().apply { open(15, 0) }
        val ok = fits.onResults(15, listOf("루미 " + "가".repeat(WakeContract.MAX_REQUEST_CHARS)), final = true, nowMs = 10, patterns = defaults)
        assertEquals(WakeContract.MAX_REQUEST_CHARS, (ok as WakeOutcome.Handoff).request.length)
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
        assertFalse(WakeBlock.values().any { it.name.contains("DURATION") })
    }
}
