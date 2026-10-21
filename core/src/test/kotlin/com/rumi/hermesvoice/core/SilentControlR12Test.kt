package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.AcceptedTurnStore
import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A whole-response assistant control (the gateway's exact silence contract) is projection noise: never a shown message, a spoken
 * answer, an arrival alert or an owed reply. Everything else stays: human input, quoted or explanatory assistant text, answers
 * that merely mention a marker. Raw row ids and counts keep the older-page cursor moving.
 */
class SilentControlR12Test {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rig = AlertRig(scope)
    private val shown: MutableList<String> get() = rig.shown
    private val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val sink = PlaybackSink { _, cue -> played += cue.role.name }

    @After fun stop() {
        process.cancel()
        scope.cancel()
    }

    private fun parsed(vararg rows: String, returned: Int = rows.size) = HistoryPage.parse(
        JSONObject("{\"session_id\":\"s\",\"messages\":[" + rows.joinToString(",") + "],\"pagination\":{\"returned\":" + returned + "}}"),
    )

    private fun assistant(id: Int, content: String, extra: String = "") =
        """{"id":$id,"role":"assistant","content":${JSONObject.quote(content)}$extra}"""

    private fun user(id: Int, content: String) = """{"id":$id,"role":"user","content":${JSONObject.quote(content)}}"""

    private fun existing(h: CoreHarness, reply: String, hold: Boolean = false): String {
        val owned = runBlocking { h.core.sessions.createConversation("Work", "work", "work things") }
        h.fake.scripts[owned.storedSessionId] = { listOf(FakeHermesDashboard.complete(reply)) }
        if (hold) h.fake.turnBehavior = { "hold" }
        return owned.storedSessionId
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what (shown=$shown played=$played)")
        }
    }

    private fun settle(ms: Long = 1_200) = runBlocking { delay(ms) }

    private fun owed(h: CoreHarness) = AcceptedTurnStore(h.store).pending().map { it.id }

    private fun submits(h: CoreHarness) = h.fake.rpcLog.toList().count { it.optString("method") == "prompt.submit" }

    private val controls = listOf(
        "NO_REPLY", "no_reply", " NO_REPLY\n", "NO REPLY", "no   reply", "SILENT", "[SILENT]", "[silent]",
        "[静默]", "静默", "[沉默]", "沉默", "**沉默**", "【静默】", "静默。", "NO_REPLY.", "*NO_REPLY*", " NO_REPLY ",
    )

    @Test fun S01_wholeResponseAssistantControlsAreNotShownButKeepEveryRawIdAndTheCount() {
        val rows = controls.mapIndexed { index, text -> assistant(100 + index, text) }
        val page = parsed(user(99, "question"), *rows.toTypedArray(), assistant(200, "the real answer"))
        assertEquals(listOf(99L, 200L), page.messages.map { it.rowId })
        assertEquals(listOf(99L) + controls.indices.map { 100L + it } + 200L, page.rawIds)
        assertEquals(controls.size + 2, page.returned)
    }

    @Test fun S02_humanQuotedExplanatoryOversizedAndMalformedTextStaysVisible() {
        val kept = listOf(
            "Use NO_REPLY when nothing needs saying.",
            "NO_REPLY is the marker I use.",
            "status: 静默 means the lane is quiet",
            "\"NO_REPLY\" is what the server calls it",
            "[SILENT",
            "SILENT]",
            "NO_REPLY please",
            "NO_REPLY.".padEnd(80, '.'),
            "Done.\nNO_REPLY",
        )
        val rows = kept.mapIndexed { index, text -> assistant(10 + index, text) }
        val page = parsed(*rows.toTypedArray())
        assertEquals(kept, page.messages.map { it.text })
        assertEquals(kept.indices.map { 10L + it }, page.rawIds)
    }

    @Test fun S03_aHumanTurnWithTheSameWordsAndRowsOfUnknownProvenanceStayVisible() {
        val page = parsed(
            user(1, "NO_REPLY"),
            user(2, "[SILENT]"),
            """{"id":3,"role":"tool","content":"NO_REPLY"}""",
            """{"id":4,"content":"NO_REPLY"}""",
            assistant(5, "NO_REPLY", extra = ""","display_kind":"a_kind_from_a_newer_server""""),
        )
        assertEquals("tool and role-less rows are not transcript rows; a row of unknown provenance is not silenced", listOf(1L, 2L, 5L), page.messages.map { it.rowId })
        assertEquals(listOf(1, 2, 3, 4, 5).map { it.toLong() }, page.rawIds)
        assertEquals(5, page.returned)
    }

    @Test fun S04_displayContentDecidesTheProjectionLikeEveryOtherRow() {
        val page = parsed(
            assistant(1, "raw wrapper", extra = ""","display_content":"NO_REPLY""""),
            assistant(2, "NO_REPLY", extra = ""","display_content":"a real display text""""),
        )
        assertEquals(listOf(2L), page.messages.map { it.rowId })
        assertEquals(listOf("a real display text"), page.messages.map { it.text })
    }

    @Test fun S05_aPageOfOnlyControlsHasNoMessagesAndKeepsTheRawCursorProgress() {
        val page = parsed(assistant(31, "NO_REPLY"), assistant(32, "[SILENT]"), assistant(33, "NO REPLY"), returned = 3)
        assertTrue(page.messages.isEmpty())
        assertEquals(listOf(31L, 32L, 33L), page.rawIds)
        assertEquals(3, page.returned)
    }

    @Test fun S06_aTypedSilentReplyIsNotAlertedAndLeavesNothingOwed() {
        rig.harness().use { h ->
            val work = existing(h, "NO_REPLY")
            val result = runBlocking { withTimeout(20_000) { h.core.chat.send(work, "hello") } }
            settle()
            assertTrue("a silent terminal is a completed turn, not a failure: $result", result is ChatSendResult.Replied && result.text.isBlank())
            assertTrue("no arrival alert for a control: $shown", shown.isEmpty())
            assertEquals("the accepted turn is settled, not left owed", emptyList<String>(), owed(h))
            assertEquals(1, submits(h))
        }
    }

    @Test fun S07_aTypedReplyThatMerelyMentionsTheMarkerStillAlertsOnce() {
        rig.harness().use { h ->
            val work = existing(h, "NO_REPLY is the marker I use.")
            val result = runBlocking { withTimeout(20_000) { h.core.chat.send(work, "hello") } }
            assertTrue("$result", result is ChatSendResult.Replied && result.text == "NO_REPLY is the marker I use.")
            waitFor("the alert") { shown.isNotEmpty() }
            settle()
            assertEquals("$shown", 1, shown.size)
            assertEquals(emptyList<String>(), owed(h))
        }
    }

    @Test fun S08_aSilentReplyFinishedAfterTheConnectionDroppedIsSettledWithoutAlert() {
        rig.harness().use { h ->
            val work = existing(h, "NO_REPLY", hold = true)
            val sent = process.async { h.core.chat.send(work, "hello") }
            waitFor("the server holding the turn") { h.fake.heldTurns.containsKey(work) }
            h.fake.dropGateway()
            h.fake.finishHeldTurn(work)
            runBlocking { withTimeout(20_000) { sent.await() } }
            settle()
            assertTrue("$shown", shown.isEmpty())
            assertEquals(emptyList<String>(), owed(h))
            assertEquals("the prompt is never sent again", 1, submits(h))
        }
    }

    @Test fun S09_aSilentReplyFinishedWhileTheProcessWasGoneIsSettledAfterRestartWithoutAlertOrNotice() {
        val first = rig.harness()
        val work = existing(first, "NO_REPLY", hold = true)
        process.async { first.core.chat.send(work, "hello") }
        waitFor("the server holding the turn") { first.fake.heldTurns.containsKey(work) }
        process.cancel()
        first.stop()
        first.fake.finishHeldTurn(work)
        rig.harness(first).use { second ->
            waitFor("the owed turn is settled") { owed(second).isEmpty() }
            settle(2_000)
            assertTrue("a control is neither a reply alert nor a 'no answer' notice: $shown", shown.isEmpty())
            assertEquals(1, submits(second))
        }
    }

    @Test fun S10_aSilentVoiceReplyIsNotSpokenAlertedOrLeftOwed() {
        rig.harness().use { h ->
            val work = existing(h, "NO_REPLY")
            val outcome = runBlocking {
                withTimeout(20_000) {
                    h.core.orchestrator.run(VoiceTurnRequest("r12-voice-0001", VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", sink, routing = TurnRouting.Direct(work)))
                }
            }
            settle()
            assertTrue("a control is never spoken as the answer: $played ($outcome)", played.none { it == "FINAL" })
            assertTrue("$shown", shown.isEmpty())
            assertEquals(emptyList<String>(), owed(h))
            assertEquals(1, submits(h))
        }
    }

    @Test fun S11_aVoiceReplyThatMerelyMentionsTheMarkerIsStillSpokenOnce() {
        rig.harness().use { h ->
            val work = existing(h, "Use NO_REPLY when there is nothing to say.")
            runBlocking {
                withTimeout(20_000) {
                    h.core.orchestrator.run(VoiceTurnRequest("r12-voice-0002", VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", sink, routing = TurnRouting.Direct(work)))
                }
            }
            settle()
            assertEquals(listOf("FINAL"), played.filter { it == "FINAL" })
            assertEquals(emptyList<String>(), owed(h))
        }
    }
}
