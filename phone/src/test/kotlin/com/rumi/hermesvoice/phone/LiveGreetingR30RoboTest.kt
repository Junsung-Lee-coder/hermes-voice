package com.rumi.hermesvoice.phone

import android.Manifest
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * R30: on an explicit fresh call GPT-Live is asked to speak a short Korean ready greeting first, by the provider's own "greet before the
 * caller speaks" sequence: one developer message in the creation-time session input and, once the call genuinely listens, the same text
 * once as session commentary with delegation_id null. The real controller and AppSessionRepository against the dashboard contract double;
 * the media is a synthetic boundary, so these prove the requests, never that a greeting was generated, spoken or heard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveGreetingR30RoboTest : PhoneRoboBase() {
    private val media = ArrayList<Media>()
    @Volatile private var connectGate: CompletableDeferred<Unit>? = null
    @Volatile private var verifiesInput = true
    private lateinit var alpha: String
    private inner class Media : LiveTransport {
        @Volatile var event: ((String) -> Unit)? = null
        @Volatile var history: JSONArray? = null
        @Volatile var mic: ((Boolean) -> Unit)? = null
        val sent = ConcurrentLinkedQueue<String>()
        private var clock = 0
        override fun observeMicrophone(listener: (Boolean) -> Unit) { mic = listener }
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) {
            this.history = history; this.event = event
            connectGate?.await() // the provider has not started yet
            if (verifiesInput) mic?.invoke(true)
            started()
        }
        fun started() { event?.invoke("{\"type\":\"session.started\",\"session\":{\"id\":\"r30\"}}") }
        override fun send(json: String): Boolean { sent += json; return true }
        override fun mute(muted: Boolean) {}
        override fun output(enabled: Boolean) {}
        override fun silence() {}
        override fun close() {}
        fun say(text: String) { val start = clock; clock += 100
            event?.invoke(JSONObject().put("type", "session.input_transcript.delta").put("delta", text).put("start_ms", start).put("end_ms", start + 100).put("event_id", "u$start").toString()) }
        fun sayModel(text: String) { val start = clock; clock += 100
            event?.invoke(JSONObject().put("type", "session.output_transcript.delta").put("delta", text).put("start_ms", start).put("end_ms", start + 100).put("event_id", "a$start").toString()) }
        fun delegate(id: String) { event?.invoke(JSONObject().put("type", "session.delegation.created").put("offset_ms", clock + 1)
            .put("delegation", JSONObject().put("id", id).put("type", "delegation").put("target", "client")).toString()) }
        fun ack(id: String) { event?.invoke(JSONObject().put("type", "session.commentary.appended").put("event_id", "ack-$id").put("client_event_id", id).toString()) }
        /** The ready-greeting requests this media carried. */
        fun greetings() = sent.map { JSONObject(it) }.filter { it.optString("type") == "session.commentary.append" && it.optString("content").startsWith(PREFIX) }
    }
    @Before fun setUp() {
        baseSetUp(); shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        idleSeconds(60)
        val route = { object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.routeFactory = { route() }; app.live.watchRouteFactory = route
        app.live.transportFactory = { Media().also { media += it } }
        app.live.watchTransportFactory = { _, _ -> Media().also { media += it } }
        app.live.admission = { app.live.admitted(it) }
        alpha = conversation("alpha")
    }
    @After fun tearDown() { connectGate?.complete(Unit); app.live.end(); settle(); baseTearDown() }
    private fun idleSeconds(s: Int) = app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = true, watchLiveEnabled = true, liveIdleSeconds = s))
    private fun text(m: JSONObject) = m.getJSONArray("content").getJSONObject(0).getString("text")
    private fun developerAfterFrontDesk(h: JSONArray) = (1 until h.length()).map { h.getJSONObject(it) }.filter { it.getString("role") == "developer" }
    private fun live(wire: String) = app.diagnostics.log.snapshot().filter { it.code.wire == wire }
    private fun talk(origin: VoiceOrigin = VoiceOrigin.PHONE): Media {
        assertTrue(app.live.start(origin, if (origin == VoiceOrigin.WATCH) "watch-node" else null))
        assertTrue(await(20_000) { media.isNotEmpty() && media.last().history != null })
        return media.last()
    }

    @Test fun G01_anExplicitTalkStartsWithOneKoreanGreetingDeveloperMessageAfterTheUnchangedFrontDeskAndNoUserRow() {
        val h = talk().history!!
        assertTrue("the front desk is unchanged and first", text(h.getJSONObject(0)).startsWith("You are the conversational session front desk"))
        val greeting = developerAfterFrontDesk(h)
        assertEquals("one greeting developer message", 1, greeting.size)
        val content = greeting.single().getJSONArray("content")
        assertEquals("one input_text part", 1, content.length()); assertEquals("input_text", content.getJSONObject(0).getString("type"))
        val g = text(greeting.single())
        assertTrue(g, g.startsWith(PREFIX) && g.contains("준비됐습니다") && g.contains("들으세요"))
        assertEquals("the greeting is the last startup message", g, text(h.getJSONObject(h.length() - 1)))
        assertTrue("the greeting is never user speech", (0 until h.length()).none { h.getJSONObject(it).getString("role") == "user" })
    }

    @Test fun G02_onceTheCallListensTheSameGreetingIsRequestedOnceAsNullDelegationCommentaryAndItsAcknowledgementIsOnlyObserved() {
        val m = talk()
        assertTrue("the request is made once the call listens", await(20_000) { m.greetings().size == 1 })
        val request = m.greetings().single()
        assertTrue("delegation_id is JSON null", request.has("delegation_id") && request.isNull("delegation_id"))
        val id = request.getString("event_id"); assertTrue(id.isNotBlank())
        assertEquals("the same instructions as the startup developer message", text(developerAfterFrontDesk(m.history!!).single()), request.getString("content"))
        live("live_greeting").single().let { assertEquals(1, it.n); assertNotNull(it.ms); assertNull(it.ref); assertEquals("phone", it.origin?.wire) }
        m.started(); settle()
        assertEquals("a duplicate session.started requests nothing more", 1, m.greetings().size)
        m.ack(id); m.ack(id); settle()
        assertEquals("the provider's acknowledgement is observed once; it is not speech", listOf(1, 2), live("live_greeting").map { it.n })
        assertTrue("no backend submit", fake.prompts.isEmpty()); assertNull(app.live.state.value.pending)
        assertEquals("no user words", "", app.live.state.value.input)
        app.live.end(); settle(); assertEquals("nothing after End", 1, m.greetings().size)
        val next = talk(); assertTrue(await(20_000) { next.greetings().size == 1 })
        assertNotEquals("a new call's request has its own event id", id, next.greetings().single().getString("event_id"))
    }

    @Test fun G03_noGreetingBeforeVerifiedInputThenExactlyOnceWhenTheInputIsVerified() {
        verifiesInput = false
        val m = talk()
        assertTrue(await(20_000) { app.live.state.value.ready }); settle()
        assertTrue("started, but its input is not verified: nothing is requested", m.greetings().isEmpty())
        m.mic!!(true)
        assertTrue(await(20_000) { m.greetings().size == 1 })
        m.mic!!(false); m.mic!!(true); settle()
        assertEquals("input readiness again is no second request", 1, m.greetings().size)
    }

    @Test fun G04_aStopWhileReadinessIsPendingNeverLetsALateStartOrInputGreet() {
        connectGate = CompletableDeferred()
        val m = talk()
        app.live.end(); settle()
        connectGate!!.complete(Unit); m.started(); m.mic?.invoke(true); settle(); idle(1_000); settle()
        assertTrue("an ended call's late start or input requests nothing", m.greetings().isEmpty())
        connectGate = null; verifiesInput = false
        val n = talk(); assertTrue(await(20_000) { app.live.state.value.ready })
        app.live.end(); settle()
        n.mic!!(true); n.started(); settle(); idle(1_000); settle()
        assertTrue(n.greetings().isEmpty()); assertTrue(live("live_greeting").isEmpty())
    }

    @Test fun G05_userSpeechBeforeTheGreetingMeansNoGreeting() {
        verifiesInput = false
        val m = talk()
        assertTrue(await(20_000) { app.live.state.value.ready })
        m.say("안녕하세요"); settle()
        m.mic!!(true); settle(); idle(1_000); settle()
        assertTrue("the caller already spoke: no greeting interrupts", m.greetings().isEmpty())
    }

    @Test fun G06_aWakeRequestNeverGreetsAndKeepsItsOwnWordsLast() {
        PhoneViewModel(app).sendRecognizedRequest("wake-greeting-r30")
        assertTrue(await(20_000) { app.live.state.value.ready })
        val h = media.single().history!!
        assertTrue("no greeting developer message", developerAfterFrontDesk(h).isEmpty())
        val last = h.getJSONObject(h.length() - 1); assertEquals("user", last.getString("role")); assertEquals("wake-greeting-r30", text(last))
        settle(); idle(1_000); settle()
        assertTrue(media.single().greetings().isEmpty())
    }

    @Test fun G07_anAutomaticLateAnswerResumeNeverGreets() {
        assertTrue(app.live.resumeFromIdle(alpha))
        assertTrue(await(20_000) { app.live.state.value.ready })
        val m = media.single()
        assertTrue("no greeting developer message", developerAfterFrontDesk(m.history!!).isEmpty())
        settle(); idle(1_000); settle()
        assertTrue("no greeting request", m.greetings().isEmpty())
    }

    @Test fun G08_anOrdinaryRequestAfterTheGreetingIsSubmittedOnceWithTheUsersOwnWordsOnly() {
        val m = talk()
        assertTrue(await(20_000) { m.greetings().size == 1 && app.live.state.value.ready })
        m.say("alpha 에 회의록 정리 부탁해"); m.delegate("d1")
        assertTrue("submitted once to the conversation the user named", await(20_000) { fake.prompts.size == 1 })
        assertEquals(alpha, fake.prompts.single().first)
        assertEquals("only the user's own words", "alpha 에 회의록 정리 부탁해", fake.prompts.single().second)
        settle(); idle(1_000); settle()
        assertEquals(1, fake.prompts.size); assertEquals(1, m.greetings().size)
    }

    @Test fun G09_greetingAcknowledgementsNeverKeepAnIdleCallResident() {
        idleSeconds(1)
        val m = talk()
        assertTrue(await(20_000) { m.greetings().size == 1 })
        val id = m.greetings().single().getString("event_id")
        assertTrue("the idle grace still ends a silent call", await(20_000) { m.ack(id); idle(300); !app.live.active })
        assertEquals("idle", app.live.state.value.endReason)
    }

    @Test fun G10_aPlainWatchHoldGetsTheSameOnceGreetingOverThePhoneOwnedRelayTransport() {
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = false, watchLiveEnabled = true, liveIdleSeconds = 60))
        val m = talk(VoiceOrigin.WATCH)
        assertEquals(VoiceOrigin.WATCH, app.live.state.value.origin)
        assertEquals("the Watch call's startup input has the greeting", 1, developerAfterFrontDesk(m.history!!).size)
        assertTrue("requested once over the Watch relay transport", await(20_000) { m.greetings().size == 1 })
        assertTrue(m.greetings().single().isNull("delegation_id"))
        assertEquals("watch", live("live_greeting").single().origin?.wire)
        assertFalse("no Phone microphone substitution", app.phoneMic.occupied)
    }

    @Test fun G11_aCallMutedWhileItBecameReadyIsGreetedOnceWhenUnmutedBeforeAnyoneSpoke() {
        verifiesInput = false
        val m = talk()
        assertTrue(await(20_000) { app.live.state.value.ready })
        app.live.setMuted(true); m.mic!!(true); settle()
        assertTrue("muted: speech cannot reach the provider, nothing is requested", m.greetings().isEmpty())
        app.live.setMuted(false)
        assertTrue(await(20_000) { m.greetings().size == 1 })
        app.live.setMuted(true); app.live.setMuted(false); settle()
        assertEquals("once", 1, m.greetings().size)
    }

    @Test fun G12_aModelThatAlreadySpokeBeforeTheCallListensGetsNoLateSecondGreeting() {
        verifiesInput = false
        val m = talk()
        assertTrue(await(20_000) { app.live.state.value.ready })
        m.sayModel("안녕하세요. 준비됐습니다."); settle()
        m.mic!!(true); settle(); idle(1_000); settle()
        assertTrue(m.greetings().isEmpty())
    }

    @Test fun G13_conversationContextGivenBeforeTheCallListensMeansNoGreetingAfterIt() {
        fake.injectRow(alpha, "assistant", "earlier alpha answer r30")
        verifiesInput = false
        val m = talk()
        assertTrue(await(20_000) { app.live.state.value.ready })
        app.live.bindSession(alpha)
        assertTrue("the bound conversation's context reached the provider", await(20_000) { m.sent.any { it.contains("earlier alpha answer r30") } })
        m.mic!!(true); settle(); idle(1_000); settle()
        assertTrue(m.greetings().isEmpty())
    }

    @Test fun G14_aBlankModelDeltaIsNotSpeechSoTheGreetingIsStillRequestedAndTheObservationStillCountsIt() {
        verifiesInput = false
        val m = talk()
        assertTrue(await(20_000) { app.live.state.value.ready })
        m.sayModel(" "); settle()
        m.mic!!(true)
        assertTrue("nothing was said: the one request follows", await(20_000) { m.greetings().size == 1 })
        app.live.end(); settle()
        assertEquals("every model output transcript is still observed", 1, live("live_spoke").single().n)
    }

    @Test fun G15_aCallWhoseModelAlreadySpokeLeavesTheNextCallItsOwnGreeting() {
        verifiesInput = false
        val first = talk()
        assertTrue(await(20_000) { app.live.state.value.ready })
        first.sayModel("안녕하세요. 준비됐습니다."); first.mic!!(true); settle(); idle(1_000); settle()
        assertTrue(first.greetings().isEmpty())
        app.live.end(); settle()
        verifiesInput = true
        val next = talk()
        assertTrue("a new call is owed its own greeting", await(20_000) { next.greetings().size == 1 })
    }

    private companion object { const val PREFIX = "지금 바로 한국어로" }
}
