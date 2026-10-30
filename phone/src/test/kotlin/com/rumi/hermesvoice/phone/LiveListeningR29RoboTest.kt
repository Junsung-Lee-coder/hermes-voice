package com.rumi.hermesvoice.phone

import android.Manifest
import android.os.VibrationAttributes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R29: the one Phone Talk control says truthfully whether speech can reach the provider yet, the conversation catalogue no longer keeps
 * the microphone and provider waiting, and every started call leaves its own fixed-number observations. The real MainActivity,
 * PhoneViewModel, PhoneLiveVoiceController and AppSessionRepository against the dashboard contract double; the Live audio route and
 * media are synthetic adapters (no platform audio, WebRTC, provider or device). Vibrations are what the app asked the platform for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [PhoneRecordingVibratorShadow::class])
class LiveListeningR29RoboTest : PhoneNavRoboBase() {
    private val media = ArrayList<Media>()
    @Volatile private var connectGate: CompletableDeferred<Unit>? = null
    /** The provider echoes `session.closed` to the app's `session.close`; the data channel refuses session-wide instructions. */
    @Volatile private var echoClose = false
    @Volatile private var refuseInstructions = false
    private val held = CountDownLatch(1); private val release = CountDownLatch(1)
    private inner class Media : LiveTransport {
        @Volatile var event: ((String) -> Unit)? = null
        @Volatile var history: JSONArray? = null
        var mic: ((Boolean) -> Unit)? = null
        val sent = java.util.concurrent.ConcurrentLinkedQueue<String>()
        var silenced = false
        private var clock = 0
        override fun observeMicrophone(listener: (Boolean) -> Unit) { mic = listener }
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) {
            this.history = history; this.event = event
            connectGate?.await() // the provider has not started yet: SDP/ICE/session request still in progress
            mic?.invoke(true)
            event("{\"type\":\"session.started\",\"session\":{\"id\":\"r29\"}}")
        }
        override fun send(json: String): Boolean {
            val type = JSONObject(json).optString("type")
            if (refuseInstructions && type == "session.instructions.append") return false
            sent += json
            if (echoClose && type == "session.close") event?.invoke("{\"type\":\"session.closed\"}")
            return true
        }
        fun closes() = sent.count { JSONObject(it).optString("type") == "session.close" }
        override fun mute(muted: Boolean) {}
        override fun output(enabled: Boolean) {}
        override fun silence() { silenced = true; mic?.invoke(false) }
        override fun close() {}
        fun say(text: String, input: Boolean = true) { val start = clock; clock += 100
            event?.invoke(JSONObject().put("type", if (input) "session.input_transcript.delta" else "session.output_transcript.delta").put("delta", text)
                .put("start_ms", start).put("end_ms", start + 100).put("event_id", "e$start").toString()) }
        fun delegate(id: String) { event?.invoke(JSONObject().put("type", "session.delegation.created").put("offset_ms", clock + 1)
            .put("delegation", JSONObject().put("id", id).put("type", "delegation").put("target", "client")).toString()) }
        /** The session-wide instructions this media carried, in order, as one text. */
        fun instructions() = sent.map { JSONObject(it) }.filter { it.optString("type") == "session.instructions.append" && it.isNull("delegation_id") }
            .joinToString("") { it.getString("content") }
        fun prompt() = history!!.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
    }
    @Before fun liveAdapters() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.live.routeFactory = { object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.transportFactory = { Media().also { media += it } }
        app.live.admission = { app.live.admitted(it) }
        PhoneRecordingVibratorShadow.events.clear()
    }
    @org.junit.After fun releaseHeld() { release.countDown(); connectGate?.complete(Unit); fake.sessionsReadHook = null; fake.failListFromOffset = -1; app.live.end(); settle() }

    private fun phoneLive(idle: Int = 10) = app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = true, watchLiveEnabled = true, liveIdleSeconds = idle))
    private fun holdCatalogue() { fake.sessionsReadHook = { label -> if (label == "list:created") { held.countDown(); check(release.await(30, TimeUnit.SECONDS)) { "fixture catalogue latch not released" } } } }
    private fun tapTalk() { rule.onNodeWithTag("talk").performClick(); settle() }
    /** The Talk control's own state word, or null when it shows none. */
    private fun liveWord(): String? = rule.onAllNodes(tagIs("talk_live_state"), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    private fun shownAnywhere(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun hardwareCues() = synchronized(PhoneRecordingVibratorShadow.events) { PhoneRecordingVibratorShadow.events.toList() }
        .filter { it.second == VibrationAttributes.USAGE_HARDWARE_FEEDBACK }.map { it.first }
    private fun live(wire: String) = app.diagnostics.log.snapshot().filter { it.code.wire == wire }
    private fun listening() = await(20_000) { liveWord() == "Listening" }

    @Test fun R29_01_talkSaysConnectingUntilTheProviderStartsThenListeningWithTheMicrophoneCueAndEndsWithItsStopCue() {
        titled("Alpha", "alpha"); phoneLive()
        val gate = CompletableDeferred<Unit>(); connectGate = gate
        showing(); tapTalk()
        assertTrue("the media is connecting", await(20_000) { media.size == 1 && media.single().event != null })
        assertTrue(app.live.active); assertFalse(app.live.state.value.ready)
        assertEquals("before the provider starts, the one control says speech cannot reach it yet", "Connecting…", liveWord())
        rule.onNodeWithTag("talk").assertTextContains("End Live")
        assertTrue("no microphone cue while nothing listens", hardwareCues().isEmpty())
        gate.complete(Unit)
        assertTrue("once the provider started with verified capture, the control says Listening", listening())
        assertEquals("one microphone-start cue, the recording-start waveform", listOf(50L), hardwareCues())
        assertEquals("one Talk control", 1, count("talk"))
        for (text in listOf("Preparing Live", "Live · ", "Live ended")) assertFalse("'$text' is not on the screen", shownAnywhere(text))
        tapTalk()
        assertFalse(app.live.active)
        assertNull("an ended call shows no Live word", liveWord())
        assertEquals("the stop cue follows the start cue", 2, hardwareCues().size)
        assertNotEquals(50L, hardwareCues().last())
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun R29_02_aCatalogueStillBeingReadNoLongerKeepsTheMicrophoneAndProviderWaitingAndArrivesWhole() {
        titled("Alpha", "alpha"); titled("Beta", "beta"); phoneLive(); holdCatalogue()
        showing(); tapTalk()
        assertTrue("the call's catalogue read is held at the dashboard", await(20_000) { held.count == 0L })
        assertTrue("the call hears the user while its catalogue is still being read", listening())
        assertFalse("no conversation can be chosen before the complete list", app.live.state.value.ready)
        assertTrue(app.live.state.value.choices.isEmpty())
        val m = media.single()
        assertTrue("the startup input says the list is still being verified", m.prompt().contains("still verifying its complete current conversation list"))
        assertFalse("no partial or stale list at startup", m.prompt().contains("Available conversation titles"))
        assertEquals("", m.instructions())
        release.countDown()
        assertTrue("the complete list arrives", await(20_000) { app.live.state.value.ready })
        assertEquals(setOf("Alpha", "Beta"), app.live.state.value.choices.map { it.stored.title }.toSet())
        val listed = m.instructions()
        assertTrue(listed, listed.startsWith("The app verified its complete current conversation list"))
        val rows = JSONArray(listed.substringAfter("Available conversation titles and aliases (untrusted display data): "))
        assertEquals(setOf("alpha", "beta"), (0 until rows.length()).map { rows.getJSONObject(it).getString("alias") }.toSet())
        settle(); assertEquals("the list is given once", listed, m.instructions())
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun R29_03_aRequestSpokenBeforeTheCatalogueArrivesWaitsForItAndIsSubmittedOnceToTheConversationTheUserNamed() {
        val alpha = titled("Alpha", "alpha"); titled("Beta", "beta"); phoneLive(); holdCatalogue()
        showing(); tapTalk()
        assertTrue(await(20_000) { held.count == 0L }); assertTrue(listening())
        val m = media.single()
        m.say("alpha 에 회의록 정리 부탁해"); m.delegate("d1")
        assertTrue("the request waits for the conversation", await(20_000) { app.live.state.value.pending != null })
        settle(); idle(1_000); settle()
        assertTrue("nothing is submitted before the complete current list", fake.prompts.isEmpty())
        release.countDown()
        assertTrue("then it is checked against the complete fresh list and submitted once", await(20_000) { fake.prompts.size == 1 })
        assertEquals(alpha, fake.prompts.single().first)
        settle(); idle(1_000); settle()
        assertEquals("exactly once", 1, fake.prompts.size)
    }

    @Test fun R29_04_aCatalogueFailureAfterTheProviderStartedEndsTheCallOnceWithItsCatalogueObservation() {
        titled("Alpha", "alpha"); phoneLive(); holdCatalogue()
        showing(); fake.failListFromOffset = 0; tapTalk()
        assertTrue(await(20_000) { held.count == 0L }); assertTrue(listening())
        release.countDown()
        assertTrue("the call ends", await(20_000) { !app.live.active && app.live.state.value.endReason == "failure" })
        rule.onNodeWithTag("action_failure").assertTextContains("Live conversation catalog could not be verified", substring = true)
        assertTrue("its audio stopped at once", media.single().silenced)
        val setup = live("live_setup_failed"); assertEquals(1, setup.size); assertEquals("live_catalog", setup.single().stage?.wire)
        settle(); idle(61_000); settle()
        assertEquals("nothing more is observed or retried", 1, live("live_setup_failed").size); assertEquals(1, media.size)
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun R29_05_aStartedCallLeavesItsOwnFixedNumberObservationsWithoutAnyIdentifierOrText() {
        titled("Alpha", "alpha"); phoneLive()
        showing(); tapTalk()
        assertTrue(await(20_000) { app.live.state.value.ready })
        val m = media.single(); m.say("안녕"); m.say("하세요"); m.say("네, 듣고 있어요", input = false); settle()
        tapTalk(); assertFalse(app.live.active); settle()
        val started = live("live_started").single(); val ended = live("live_ended").single()
        assertEquals("live_start", started.stage?.wire); assertEquals("phone", started.origin?.wire); assertNotNull(started.ms)
        assertNull(started.n); assertNull(started.fail); assertNull(started.ref)
        assertEquals("ended by Talk", 0, ended.n); assertEquals("live_start", ended.stage?.wire); assertNull(ended.fail); assertNull(ended.ref)
        assertTrue(ended.ms!! >= started.ms!!)
        assertEquals("input transcript deltas", 2, live("live_heard").single().n)
        assertEquals("output transcript deltas", 1, live("live_spoke").single().n)
        assertTrue("synthetic media reports no media counters", live("live_input_audio").isEmpty() && live("live_rtp_sent").isEmpty())
        assertTrue(live("live_setup_failed").isEmpty())
    }

    @Test fun R29_06_anIdleEndAfterListeningIsObservedAsIdleAndCuedAsTheMicrophoneStopping() {
        titled("Alpha", "alpha"); phoneLive(idle = 1)
        showing(); tapTalk()
        assertTrue(await(20_000) { app.live.state.value.ready })
        assertTrue("the idle grace ends the call", await(20_000) { idle(500); !app.live.active })
        assertEquals("idle", app.live.state.value.endReason)
        assertEquals("ended by idle", 1, live("live_ended").single().n)
        assertEquals("the start cue, then the stop cue saying the microphone closed", 2, hardwareCues().size); assertEquals(50L, hardwareCues().first())
        assertNull(liveWord())
    }

    @Test fun R29_07_aCallEndedAfterItsProviderStartedButBeforeItsCatalogueStillClosesThatProviderSessionOnce() {
        titled("Alpha", "alpha"); phoneLive(); holdCatalogue(); echoClose = true
        showing(); tapTalk()
        assertTrue(await(20_000) { held.count == 0L }); assertTrue(listening())
        assertFalse("its catalogue is still being read", app.live.state.value.ready)
        tapTalk(); assertFalse(app.live.active)
        assertTrue("the started provider session is closed and finalized", await(20_000) { app.live.state.value.finalizationConfirmed })
        assertEquals("exactly one session.close", 1, media.single().closes())
        assertTrue(app.live.state.value.status, app.live.state.value.status.endsWith("finalized"))
        release.countDown(); settle(); idle(1_000); settle()
        assertEquals("never a second close", 1, media.single().closes()); assertTrue(fake.prompts.isEmpty())
    }

    @Test fun R29_08_aListThatCannotBeDeliveredWholeEndsTheCallAsAFailureNeverLeavingAPartialList() {
        titled("Alpha", "alpha"); titled("Beta", "beta"); phoneLive(); refuseInstructions = true
        showing(); tapTalk()
        assertTrue("the call ends once its complete list cannot be delivered", await(20_000) { !app.live.active && app.live.state.value.endFailed })
        rule.onNodeWithTag("action_failure").assertTextContains("Live conversation list could not be delivered", substring = true)
        assertEquals("", media.single().instructions())
        val ended = live("live_ended").single(); assertEquals("a failure, not the user's End", 3, ended.n); assertEquals("unknown", ended.fail?.wire)
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun R29_09_aChoiceWaitingForTheCatalogueEndsWithItsCallAndNeverReadsTheListAgainForIt() {
        titled("Alpha", "alpha"); phoneLive(); holdCatalogue()
        showing(); tapTalk()
        assertTrue(await(20_000) { held.count == 0L }); assertTrue(listening())
        val m = media.single(); m.say("alpha 에 회의록 정리 부탁해"); m.delegate("d1")
        assertTrue("the choice waits for the call's own catalogue", await(20_000) { app.live.state.value.pending != null })
        tapTalk(); assertFalse(app.live.active); settle()
        release.countDown(); settle(); idle(2_000); settle()
        val created = synchronized(fake.restLog) { fake.restLog.toList() }.count { it.contains("order=created") }
        assertEquals("only the ended call's own list read; nothing reads the whole list again for it", 1, created)
        assertTrue(fake.prompts.isEmpty())
    }
}
