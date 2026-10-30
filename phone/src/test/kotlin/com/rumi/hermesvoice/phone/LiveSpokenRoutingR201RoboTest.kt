package com.rumi.hermesvoice.phone

import android.Manifest
import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.R8WireFaults
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PHONE-R2-01: the supported provider events (input/output transcript deltas and client `session.delegation.created`)
 * reach the real AppSessionRepository submit through an app-validated SPOKEN conversation choice, without a Phone
 * Use/Send tap, a hidden router pass, a provider target field or assistant speech as authority. Synthetic provider peer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveSpokenRoutingR201RoboTest : PhoneRoboBase() {
    private class Media : LiveTransport {
        @Volatile var event: ((String) -> Unit)? = null
        val sends = java.util.concurrent.ConcurrentLinkedQueue<String>()
        private var clock = 0
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) { this.event = event; event("{\"type\":\"session.started\",\"session\":{\"id\":\"provider\"}}") }
        override fun send(json: String): Boolean { sends += json; return true }
        override fun mute(muted: Boolean) {}
        override fun output(enabled: Boolean) {}
        override fun silence() {}
        override fun close() {}
        private fun transcript(type: String, text: String) { val start = clock; clock += 100; event?.invoke(JSONObject().put("type", type).put("delta", text).put("start_ms", start).put("end_ms", start + 100).toString()) }
        fun user(text: String) = transcript("session.input_transcript.delta", text)
        fun assistant(text: String) = transcript("session.output_transcript.delta", text)
        fun delegate(id: String) { event?.invoke(JSONObject().put("type", "session.delegation.created").put("offset_ms", clock + 1)
            .put("delegation", JSONObject().put("id", id).put("type", "delegation").put("target", "client")).toString()) }
        fun appends(type: String, id: String) = sends.map { JSONObject(it) }.filter { it.optString("type") == type && it.optString("delegation_id") == id }
    }
    private val media = ArrayList<Media>()
    @Before fun setUp() {
        baseSetUp(); shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.live.transportFactory = { Media().also { media += it } }
        app.live.watchTransportFactory = { _, _ -> Media().also { media += it } }
        app.live.routeFactory = { object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.watchRouteFactory = { object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.admission = { app.live.admitted(it) }
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = true, watchLiveEnabled = true))
    }
    @After fun tearDown() { app.live.end(); settle(); baseTearDown() }
    private fun ready(origin: VoiceOrigin = VoiceOrigin.PHONE): Media {
        assertTrue(app.live.start(origin, if (origin == VoiceOrigin.WATCH) "watch-node" else null))
        assertTrue(await { app.live.state.value.ready && app.live.state.value.choices.isNotEmpty() })
        return media.last()
    }
    private fun titled(title: String, alias: String): String {
        val id = runBlocking { app.wiring().core.sessions.createConversation(title, alias, "$alias things") }.storedSessionId
        fake.scripts[id] = { emptyList() }
        return id
    }

    @Test fun spokenUniqueTargetAndDelegationSubmitOnceThroughRepositoryWithoutPhoneTap() {
        val alpha = conversation("alpha"); conversation("beta")
        fake.scripts[alpha] = { listOf(FakeHermesDashboard.complete("alpha-done")) }
        val m = ready()
        m.user("alpha 대화에 회의록 요약을 부탁해"); m.delegate("d1")
        assertTrue("a validated spoken choice must reach the real submit without Use/Send", await { fake.prompts.size == 1 })
        assertEquals(alpha, fake.prompts.single().first)
        assertEquals("alpha 대화에 회의록 요약을 부탁해", fake.prompts.single().second)
        val submit = fake.rpcLog.single { it.optString("method") == "prompt.submit" }.getJSONObject("params")
        assertEquals("voice-live", submit.getString("surface"))
        assertNull("no hidden router session is created or used", app.wiring().core.sessions.registry.router())
        assertTrue(await { !app.live.state.value.working && app.live.state.value.boundSessionId == alpha })
        assertTrue(await { m.sends.map { JSONObject(it) }.any { it.optString("type") == "session.commentary.append" && it.getString("content").contains("alpha-done") } })
        m.delegate("d1"); settle(); assertEquals("a duplicate delegation is never a second submit", 1, fake.prompts.size)
        m.delegate("d2"); settle(); assertEquals("a delegation without new user speech is not a request", 1, fake.prompts.size)
        assertNotEquals("selection and work do not navigate", alpha, app.selectedConversationId)
        assertEquals(0, fake.speakRequests.get())
    }

    @Test fun equalTitlesAreClarifiedThroughLiveAndTheSpokenAliasSubmitsOnce() {
        val home = titled("Notes", "notes-home"); val work = titled("Notes", "notes-work")
        fake.scripts[work] = { listOf(FakeHermesDashboard.complete("work-done")) }
        val m = ready()
        m.user("notes 대화에 오늘 일정 정리해 줘"); m.delegate("d1"); settle()
        assertTrue("an equal title is never guessed", fake.prompts.isEmpty())
        assertTrue("the app asks Live to clarify by the registered aliases", await {
            m.appends("session.instructions.append", "d1").any { val c = it.getString("content"); c.contains("notes-home") && c.contains("notes-work") }
        })
        m.user("notes work 쪽이야"); m.delegate("d2")
        assertTrue("the clarified spoken alias completes the same request hands-free", await { fake.prompts.size == 1 })
        assertEquals(work, fake.prompts.single().first)
        assertTrue(fake.prompts.single().second.contains("오늘 일정 정리해 줘"))
        settle(); assertEquals(1, fake.prompts.size); assertFalse(fake.prompts.any { it.first == home })
        assertNull(app.wiring().core.sessions.registry.router())
    }

    @Test fun assistantProposedOrEchoedTitlesAreNeverRouteAuthority() {
        conversation("alpha"); val beta = conversation("beta")
        fake.scripts[beta] = { listOf(FakeHermesDashboard.complete("beta-done")) }
        val m = ready()
        m.user("회의록 요약 부탁해"); m.assistant("Beta 대화로 보낼까요?"); m.user("응"); m.delegate("d1"); settle()
        assertTrue("an assistant-proposed title plus a casual yes is not a target", fake.prompts.isEmpty())
        assertTrue(await { m.appends("session.instructions.append", "d1").isNotEmpty() })
        m.assistant("I will send this to Beta now"); m.user("I will send this to Beta now"); m.delegate("d2"); settle()
        assertTrue("an input transcript that only repeats assistant speech is not a target", fake.prompts.isEmpty())
        m.user("beta 로 보내 줘"); m.delegate("d3")
        assertTrue("the user's own spoken choice is accepted", await { fake.prompts.size == 1 })
        assertEquals(beta, fake.prompts.single().first)
        assertTrue(fake.prompts.single().second.contains("회의록 요약 부탁해"))
    }

    @Test fun archivedNamedTargetIsRefusedBeforeEffectAndAnotherSpokenChoiceStillSubmits() {
        val alpha = conversation("alpha"); val beta = conversation("beta")
        fake.scripts[beta] = { listOf(FakeHermesDashboard.complete("beta-done")) }
        val m = ready()
        fake.rows.getValue(alpha).archived = true
        m.user("alpha 에 작업 보내 줘"); m.delegate("d1"); settle()
        assertTrue(await { m.appends("session.instructions.append", "d1").isNotEmpty() })
        assertTrue("an archived target is revalidated and never submitted", fake.prompts.isEmpty())
        m.user("그럼 beta 로"); m.delegate("d2")
        assertTrue(await { fake.prompts.size == 1 })
        assertEquals(beta, fake.prompts.single().first)
        assertFalse(fake.prompts.any { it.first == alpha })
    }

    @Test fun ambiguousAcceptanceIsNeverResentByDuplicateDelegationOrReconnect() {
        val alpha = conversation("alpha")
        val release = CountDownLatch(1)
        fake.turnBehavior = { "async_hold" }
        fake.submitReplyFault = { frame -> R8WireFaults.mutate(frame, "rpcGeneric"); release.await(15, TimeUnit.SECONDS) }
        try {
            val m = ready()
            m.user("alpha 에 보고서 써 줘"); m.delegate("d1")
            assertTrue("the spoken request reaches the real submit once", await { fake.prompts.size == 1 })
            release.countDown()
            assertTrue(await { !app.live.state.value.working })
            assertTrue("uncertainty is reported, never retried", await { m.appends("session.commentary.append", "d1").isNotEmpty() })
            m.delegate("d1"); m.delegate("d2"); settle(); assertEquals(1, fake.prompts.size)
            app.live.end(); settle()
            val n = ready(); n.delegate("d1"); n.delegate("d3"); settle()
            assertEquals("reconnect never replays maybe-accepted work", 1, fake.prompts.size)
            assertEquals(alpha, fake.prompts.single().first)
        } finally { release.countDown(); fake.submitReplyFault = null }
    }

    @Test fun watchOriginAndWakeSeedSpokenChoicesUseTheSameHandsFreeSubmit() {
        val alpha = conversation("alpha"); val beta = conversation("beta")
        fake.scripts[alpha] = { listOf(FakeHermesDashboard.complete("alpha-done")) }
        fake.scripts[beta] = { listOf(FakeHermesDashboard.complete("beta-done")) }
        val w = ready(VoiceOrigin.WATCH)
        assertEquals(VoiceOrigin.WATCH, app.live.state.value.origin)
        w.user("beta 에 할 일 추가해 줘"); w.delegate("watch-d1")
        assertTrue("Watch-origin Live needs no Phone tap", await { fake.prompts.size == 1 })
        assertEquals(beta, fake.prompts.single().first)
        app.live.end(); settle()
        PhoneViewModel(app).sendRecognizedRequest("alpha 에 알람 정리 부탁해")
        assertTrue(await { app.live.state.value.ready })
        media.last().delegate("wake-d1")
        assertTrue("a final wake request naming its target is submitted hands-free", await { fake.prompts.size == 2 })
        assertEquals(alpha to "alpha 에 알람 정리 부탁해", fake.prompts.last())
        assertNull(fake.lastTranscribedWav); assertEquals(0, fake.speakRequests.get())
    }
}
