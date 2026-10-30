package com.rumi.hermesvoice.phone

import android.Manifest
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveControllerScope01RoboTest : PhoneRoboBase() {
    private class Media : LiveTransport {
        var event: ((String) -> Unit)? = null
        var muted = false; var closed = false; var silenced = false; var outputEnabled = false
        val sends = ArrayList<String>()
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) { this.event = event; event("{\"type\":\"session.started\",\"session\":{\"id\":\"provider\"}}") }
        override fun send(json: String): Boolean { sends += json; return true }
        override fun mute(muted: Boolean) { this.muted = muted }
        override fun output(enabled: Boolean) { outputEnabled = enabled }
        override fun silence() { silenced = true; muted = true; outputEnabled = false }
        override fun close() { closed = true }
        fun input(text: String) { event?.invoke(JSONObject().put("type", "session.input_transcript.delta").put("delta", text).put("start_ms", 0).put("end_ms", 100).toString()) }
        fun delegate() { event?.invoke("{\"type\":\"session.delegation.created\",\"offset_ms\":200,\"delegation\":{\"id\":\"d1\",\"type\":\"delegation\",\"target\":\"client\"}}") }
    }
    private val media = ArrayList<Media>()
    @Before fun setUp() {
        baseSetUp()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.live.transportFactory = { Media().also { media += it } }
        app.live.routeFactory = { object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.watchTransportFactory = { _, _ -> Media().also { media += it } }
        app.live.watchRouteFactory = { object:LiveRoute { override fun open()=true;override fun valid()=true;override fun close(){} } }
        app.live.admission = { app.live.admitted(it) }
    }
    @After fun tearDown() { app.live.end(); settle(); baseTearDown() }
    private fun enabled(phone: Boolean, watch: Boolean) { app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = phone, watchLiveEnabled = watch)); app.live.settingsChanged() }
    private fun ready(origin: VoiceOrigin = VoiceOrigin.PHONE) { assertTrue(app.live.start(origin, if (origin == VoiceOrigin.WATCH) "watch-node" else null)); assertTrue(await { app.live.state.value.ready }) }

    @Test fun originatingModeMatrixIsIndependentAndSettingsNeverOpenMedia() {
        enabled(false, false); assertFalse(app.live.start()); assertFalse(app.live.start(VoiceOrigin.WATCH, "w")); assertTrue(media.isEmpty())
        enabled(true, false); assertTrue(media.isEmpty()); ready(); assertEquals(VoiceOrigin.PHONE, app.live.state.value.origin); app.live.end(); settle()
        assertFalse(app.live.start(VoiceOrigin.WATCH, "w"))
        enabled(false, true); assertFalse(app.live.start()); ready(VoiceOrigin.WATCH); app.live.end(); settle()
        enabled(true, true); ready(); assertFalse("one actual Phone media owner", app.live.start(VoiceOrigin.WATCH, "w"))
    }
    @Test fun liveEventsConfirmExactEligibleTargetAndBypassHiddenRouterUsingRealSubmit() {
        val target = conversation("alpha")
        enabled(true, false); ready()
        val m = media.last(); m.input("파일을 확인해 주세요"); m.delegate()
        assertTrue(await { app.live.state.value.pending != null })
        assertTrue(fake.prompts.isEmpty())
        app.live.confirm("d1", "unregistered", "no"); assertTrue(fake.prompts.isEmpty())
        fake.scripts[target] = { listOf(com.rumi.hermesvoice.core.FakeHermesDashboard.complete("확인했습니다")) }
        app.live.confirm("d1", target, "파일을 확인해 주세요")
        assertTrue(await { fake.prompts.size == 1 && !app.live.state.value.working })
        assertEquals(target, fake.prompts.single().first)
        assertEquals("🎙 파일을 확인해 주세요", fake.rawPrompts.single().second)
        val submit = fake.rpcLog.single { it.optString("method") == "prompt.submit" }.getJSONObject("params")
        assertEquals("voice-live", submit.getString("surface")); assertTrue(submit.getString("voice_context").contains("파일"))
        assertNull(app.wiring().core.sessions.registry.router())
        assertTrue(m.sends.any { JSONObject(it).optString("type") == "session.commentary.append" })
        m.delegate(); app.live.confirm("d1", target, "파일을 확인해 주세요"); settle()
        assertEquals(1, fake.prompts.size)
        assertFalse("selection and work do not navigate", app.selectedConversationId == target)
    }
    @Test fun offStopsOwnedMediaButDoesNotCancelAcceptedHermesWorkOrReplay() {
        val target = conversation("alpha"); fake.turnBehavior = { "hold" }; fake.scripts[target] = { listOf(com.rumi.hermesvoice.core.FakeHermesDashboard.complete("late final")) }
        enabled(true, false); ready(); val m = media.last(); m.input("do work"); m.delegate(); assertTrue(await { app.live.state.value.pending != null })
        app.live.confirm("d1", target, "do work"); assertTrue(await { fake.heldTurns.containsKey(target) })
        enabled(false, false); assertFalse(app.live.active); assertTrue(m.silenced); assertFalse(app.phoneMic.occupied)
        fake.finishHeldTurn(target)
        assertTrue(await { fake.rows.getValue(target).messages.any { it.optString("content") == "late final" } })
        settle(); assertEquals(1, fake.prompts.size)
        assertFalse(m.sends.any { JSONObject(it).optString("type") == "session.commentary.append" })
        m.event?.invoke("{\"type\":\"session.started\"}"); settle(); assertFalse(app.live.active)
    }
    @Test fun muteChangesActualTransportAndScopeLossFencesLateCallbacks() {
        enabled(true, false); ready(); val m = media.last()
        app.live.mute(); assertTrue(m.muted); assertTrue(app.live.active)
        app.live.mute(); assertFalse(m.muted)
        app.invalidateSessionTitles(); assertTrue(m.silenced); assertFalse(app.live.active)
        m.input("late"); m.delegate(); settle(); assertTrue(fake.prompts.isEmpty()); assertNull(app.live.state.value.pending)
    }
    @Test fun watchOriginRelayUsesCanonicalIdleEvenWhenPhoneLiveIsOff() {
        var now=1000L;app.live.monotonicMs={now}
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true,liveIdleSeconds=1))
        ready(VoiceOrigin.WATCH)
        now=1999;app.live.checkLifetime();assertTrue(app.live.active)
        now=2000;app.live.checkLifetime();assertFalse(app.live.active);assertEquals("idle",app.live.state.value.endReason)
        assertFalse(app.settings.watchSettings().phoneLiveEnabled);assertTrue(app.settings.watchSettings().watchLiveEnabled)
    }
    @Test fun phoneTalkEntryUsesLiveWhenEnabledAndKeepsOneController() {
        enabled(true, false)
        val model = PhoneViewModel(app)
        model.toggleRecording(); assertTrue(await { app.live.state.value.ready }); assertFalse(app.manualCapture.busy)
        model.toggleRecording(); assertFalse(app.live.active)
        assertTrue(media.single().silenced)
    }
}
