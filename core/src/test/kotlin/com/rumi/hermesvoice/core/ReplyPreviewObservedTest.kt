package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.notify.ReplyAlertResult
import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.ReplyAlertMessage
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview an unspoken final answer's arrival alert carries, observed at the executable boundaries through the production
 * wiring (real OkHttp + gateway socket, orchestrator, chat service, alert decision) against [FakeHermesDashboard]: only the
 * delivered final answer is previewed, never progress or the request, distinct answers never mix, the Watch alert and the stored
 * conversation stay untouched. The Phone's notification surface is [PreviewRig]'s recording port (the real one is covered by the
 * Phone Robolectric tests).
 */
class ReplyPreviewObservedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rig = PreviewRig(scope)
    private val shown: MutableList<ShownAlert> get() = rig.shown

    @After
    fun stop() = scope.cancel()

    private fun existing(h: CoreHarness, alias: String, firstReply: String): String {
        val owned = runBlocking { h.core.sessions.createConversation(alias.replaceFirstChar { it.uppercase() }, alias, "$alias things") }
        h.fake.scripts[owned.storedSessionId] = { listOf(FakeHermesDashboard.complete(firstReply)) }
        return owned.storedSessionId
    }

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    private fun settle() = runBlocking { delay(400) }

    private val finalFails = PlaybackSink { _, cue -> if (cue.role == SpokenRole.FINAL) throw IOException("no audio output") }
    private val speaks = PlaybackSink { _, _ -> }

    private fun phoneTurn(h: CoreHarness, turnId: String, work: String, sink: PlaybackSink) = runBlocking {
        h.core.orchestrator.run(VoiceTurnRequest(turnId, VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", sink, routing = TurnRouting.Direct(work)))
    }

    @Test
    fun `a Phone voice answer whose audio failed previews exactly the final answer, not the progress lines or the request`() {
        rig.harness().use { h ->
            val work = existing(h, "work", "x")
            h.fake.scripts[work] = { listOf(
                FakeHermesDashboard.interim("Checking the calendar."),
                FakeHermesDashboard.interim("Almost there."),
                FakeHermesDashboard.complete("You have **3** meetings. See [agenda](https://a.example/day)."),
            ) }
            assertTrue(phoneTurn(h, "p-final-0001", work, finalFails) is VoiceTurnOutcome.DeliveredResponseFailed)
            waitFor("the alert") { shown.isNotEmpty() }
            settle()
            val alert = shown.single()
            assertEquals("p-final-0001#final@$work", alert.key)
            assertEquals("You have 3 meetings. See agenda.", alert.summary)
            assertEquals("You have 3 meetings. See agenda.", alert.expanded)
            assertFalse(alert.expanded!!.contains("Checking") || alert.expanded!!.contains("Almost"))
        }
    }

    @Test
    fun `a spoken answer and a non-final completion preview and alert nothing`() {
        rig.harness().use { h ->
            val work = existing(h, "work", "Spoken answer text.")
            assertTrue(phoneTurn(h, "p-spoken-0001", work, speaks) is VoiceTurnOutcome.Completed)
            h.fake.scripts[work] = { listOf(FakeHermesDashboard.complete("stopped halfway", "interrupted")) }
            phoneTurn(h, "p-stopped-0001", work, finalFails)
            settle()
            assertTrue("$shown", shown.isEmpty())
        }
    }

    @Test
    fun `text chat replies each preview their own answer, and the stored answer stays whole`() {
        rig.harness().use { h ->
            val work = existing(h, "work", "x")
            val other = existing(h, "other", "x")
            val body = "Section\n" + "긴 답변입니다. ".repeat(300)
            h.fake.scripts[work] = { listOf(FakeHermesDashboard.complete("First answer for work.")) }
            val first = runBlocking { h.core.chat.send(work, "question one") } as ChatSendResult.Replied
            h.fake.scripts[work] = { listOf(FakeHermesDashboard.complete(body)) }
            val second = runBlocking { h.core.chat.send(work, "question two") } as ChatSendResult.Replied
            h.fake.scripts[other] = { listOf(FakeHermesDashboard.complete("Answer for the other conversation.")) }
            runBlocking { h.core.chat.send(other, "question three") }
            waitFor("three alerts") { shown.size == 3 }
            settle()
            assertEquals("First answer for work.", first.text)
            assertEquals("the chat reply is the untruncated canonical answer", body, second.text)
            val all = shown.toList()
            assertEquals(3, all.size)
            assertTrue(all.all { alert -> alert.summary != null && !alert.summary!!.contains("question") })
            val firstAlert = all.single { it.expanded == "First answer for work." }
            val long = all.single { it.expanded?.startsWith("Section\n긴 답변입니다.") == true }
            val otherAlert = all.single { it.expanded == "Answer for the other conversation." }
            assertTrue(long.summary!!.length <= 140 && long.expanded!!.length <= 1000 && long.expanded!!.endsWith("…"))
            assertEquals(work, firstAlert.key.substringAfter('@'))
            assertEquals(work, long.key.substringAfter('@'))
            assertEquals(other, otherAlert.key.substringAfter('@'))
            val stored = runBlocking { h.core.sessions.history(work).messages }
            assertTrue("history keeps the whole answer: ${stored.map { it.text.length }}", stored.any { it.text.trimEnd() == body.trimEnd() })
        }
    }

    @Test
    fun `a later reply that was not spoken previews that later reply`() {
        rig.harness().use { h ->
            val work = existing(h, "work", "Started.")
            val reply: (String) -> List<Pair<String, org.json.JSONObject?>> =
                { listOf(FakeHermesDashboard.complete("""{"action":"route","destination":"work","ack":"Sending to work."}""")) }
            h.fake.sourceScripts[AppSources.ROUTER] = reply
            h.registry.router()?.let { h.fake.scripts[it.storedSessionId] = reply }
            phoneTurn(h, "p-later-0001", work, finalFails)
            waitFor("own alert") { shown.size == 1 }
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Later: **build** finished."))
            waitFor("later alert") { shown.size == 2 }
            settle()
            assertEquals("Started.", shown[0].expanded)
            assertEquals("Later: build finished.", shown[1].expanded)
            assertTrue(shown[1].key.startsWith("p-later-0001#later"))
        }
    }

    @Test
    fun `duplicate replays are one alert, heard or cancelled answers none, and a Watch alert carries no answer text`() = runBlocking {
        val secret = "TOP-SECRET-ANSWER-TEXT"
        val port = PreviewRig(scope)
        val a = port.alerts
        assertEquals(ReplyAlertResult.SHOWN_HERE, a.dispatch(PreviewRig.reply("t-1#final", "s-1", secret)))
        assertEquals(ReplyAlertResult.DUPLICATE, a.dispatch(PreviewRig.reply("t-1#final", "s-1", secret)))
        assertEquals(ReplyAlertResult.SUPPRESSED_HEARD, a.dispatch(PreviewRig.reply("t-2#final", "s-1", secret, heard = true)))
        assertEquals(ReplyAlertResult.SUPPRESSED_CANCELLED, a.dispatch(PreviewRig.reply("t-3#final", "s-1", secret, cancelled = true)))
        assertEquals(1, port.shown.size)
        assertEquals(secret, port.shown.single().expanded)
        var carried: ReplyAlert? = null
        val sink = object : PlaybackSink {
            override suspend fun play(audio: SpokenAudio, cue: PlaybackCue) {}
            override suspend fun deliverReplyAlert(alert: ReplyAlert): Boolean { carried = alert; return true }
        }
        assertEquals(ReplyAlertResult.SENT_TO_WATCH, a.dispatch(PreviewRig.reply("t-4#final", "s-1", secret, target = VoiceOrigin.WATCH, sink = sink)))
        assertEquals("the Watch-bound alert did not become a Phone alert", 1, port.shown.size)
        assertEquals(setOf("identity", "storedSessionId"), ReplyAlert::class.java.declaredFields.filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
        assertFalse(carried.toString().contains(secret))
        assertFalse(String(ReplyAlertMessage(carried!!.identity, carried!!.storedSessionId).encode()).contains(secret))
        assertEquals(setOf("v", "identity", "session_id"), org.json.JSONObject(String(ReplyAlertMessage(carried!!.identity, carried!!.storedSessionId).encode())).keys().asSequence().toSet())
    }
}
