package com.rumi.hermesvoice.phone

import android.Manifest
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.live.LiveTextMessage
import com.rumi.hermesvoice.core.live.LiveWire
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
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
 * R30 (successor API): the greeting request also waits for the call's output to be admitted (a Watch relay's private Phone output, say),
 * and the startup input keeps the greeting whole within its existing budget or leaves it out. Real controller; synthetic media.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveGreetingOutputR30RoboTest : PhoneRoboBase() {
    @Volatile private var outputAdmitted = false
    private val sent = ConcurrentLinkedQueue<String>()
    @Volatile private var mic: ((Boolean) -> Unit)? = null
    @Before fun setUp() {
        baseSetUp(); shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = false, watchLiveEnabled = true, liveIdleSeconds = 60))
        app.live.watchRouteFactory = { object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.watchTransportFactory = { _, _ -> object : LiveTransport {
            override fun observeMicrophone(listener: (Boolean) -> Unit) { mic = listener }
            override suspend fun connect(history: JSONArray, event: (String) -> Unit) { mic?.invoke(true); event("{\"type\":\"session.started\",\"session\":{\"id\":\"r30-out\"}}") }
            override fun send(json: String): Boolean { sent += json; return true }
            override fun mute(muted: Boolean) {}
            override fun output(enabled: Boolean) {}
            override fun silence() {}
            override fun close() {}
            override fun outputReady() = outputAdmitted
        } }
        app.live.admission = { app.live.admitted(it) }
        conversation("alpha")
    }
    @After fun tearDown() { app.live.end(); settle(); baseTearDown() }
    private fun greetings() = sent.map { JSONObject(it) }.filter { it.optString("type") == "session.commentary.append" && it.optString("content") == LiveStartupInput.GREETING }

    @Test fun aListeningCallWhoseOutputIsNotYetAdmittedIsGreetedOnlyOnceItIs() {
        assertTrue(app.live.start(VoiceOrigin.WATCH, "watch-node"))
        assertTrue("started with verified Watch input", await(20_000) { app.live.state.value.ready && app.live.state.value.inputActive }); settle()
        assertTrue("its output is not admitted: no greeting the caller could not hear", greetings().isEmpty())
        outputAdmitted = true
        app.live.outputPending("Watch microphone · Phone private Live output ready")
        assertTrue(await(20_000) { greetings().size == 1 })
        app.live.outputPending("Watch microphone · Phone private Live output ready"); settle()
        assertEquals("once", 1, greetings().size)
    }

    @Test fun theStartupGreetingIsOneWholeAppendPieceAndYieldsOnlyToTheBudgetNeverToATargetOrReplay() {
        assertTrue("one append piece", LiveStartupInput.GREETING.toByteArray(Charsets.UTF_8).size <= 480)
        assertEquals(1, LiveWire.append("commentary", null, LiveStartupInput.GREETING).size)
        val previous = (1..40).map { LiveTextMessage(if (it % 2 == 0) "assistant" else "user", "earlier speech $it " + "말".repeat(60)) }
        val withGreeting = LiveStartupInput.pending(previous, null, greeting = true)
        assertTrue(LiveStartupInput.greets(withGreeting))
        val last = withGreeting.getJSONObject(withGreeting.length() - 1)
        assertEquals("developer", last.getString("role")); assertEquals(LiveStartupInput.GREETING, last.getJSONArray("content").getJSONObject(0).getString("text"))
        val without = LiveStartupInput.pending(previous, null)
        assertFalse(LiveStartupInput.greets(without))
        val cost = { h: JSONArray -> (0 until h.length()).sumOf { h.getJSONObject(it).getJSONArray("content").getJSONObject(0).getString("text").toByteArray(Charsets.UTF_8).size + 128 } }
        assertTrue("the whole startup input stays within its budget", cost(withGreeting) <= LiveStartupInput.MAX_COST)
        val kept = (1 until withGreeting.length() - 1).map { withGreeting.getJSONObject(it).getJSONArray("content").getJSONObject(0).getString("text") }
        assertEquals("earlier speech yields oldest first, never reordered", previous.map { it.text }.takeLast(kept.size), kept)
        val wake = LiveStartupInput.pending(emptyList(), "현재 요청", greeting = true)
        assertFalse("never beside a current wake request", LiveStartupInput.greets(wake))
        assertEquals("현재 요청", wake.getJSONObject(wake.length() - 1).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun aCompleteListThatLeavesNoRoomForTheGreetingOmitsTheGreetingWholeAndKeepsTheListByteIdentical() {
        fun row(i: Int) = com.rumi.hermesvoice.core.sessions.AppConversation(
            com.rumi.hermesvoice.core.sessions.OwnedSession("s-$i", com.rumi.hermesvoice.core.sessions.OwnedRole.CONVERSATION, "t", "alias-$i", "", false, 0L),
            com.rumi.hermesvoice.core.net.StoredSession("s-$i", "recorder-phone", "회의 기록 " + "가".repeat(30) + " $i", "", 0.0, 0.0, 0, false))
        val list = ArrayList<com.rumi.hermesvoice.core.sessions.AppConversation>()
        while (runCatching { LiveStartupInput.build(list + row(list.size), null, emptyList(), null) }.isSuccess) list += row(list.size)
        assertTrue("the next row would exceed the budget", list.size > 10)
        val plain = LiveStartupInput.build(list, null, emptyList(), null)
        val asked = LiveStartupInput.build(list, null, emptyList(), null, greeting = true)
        assertFalse("no room for the whole greeting: none at all", LiveStartupInput.greets(asked))
        assertEquals("the complete list is untouched", plain.toString(), asked.toString())
        assertTrue((0 until list.size).all { asked.toString().contains("alias-$it") })
    }
}
