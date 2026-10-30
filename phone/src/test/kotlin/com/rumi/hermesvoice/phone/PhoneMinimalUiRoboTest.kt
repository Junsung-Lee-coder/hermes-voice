package com.rumi.hermesvoice.phone

import android.Manifest
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
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
 * The Phone's minimal screen (owner's UI contract of 2026-10-27): the conversation list, the chat, the Settings gear/menu and one
 * stateful Talk control; no status prose under the top bar or over the Talk control, no Live panel (context picker, Mute,
 * transcripts, confirmation, "Do not submit"); the one Talk control still ends a preparing or active Live at once, and a genuine
 * failure of the user's own Talk action is still said, beside Talk. The real MainActivity, PhoneViewModel, PhoneLiveVoiceController
 * and AppSessionRepository against the dashboard contract double; the Live audio route and media are synthetic adapters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneMinimalUiRoboTest : PhoneNavRoboBase() {
    private val media = ArrayList<Media>()
    private val held = CountDownLatch(1); private val release = CountDownLatch(1)
    /** Holds the provider start (SDP/ICE/session request) of the next media: the call is still preparing. */
    @Volatile private var connectGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private inner class Media : LiveTransport {
        @Volatile var event: ((String) -> Unit)? = null
        private var clock = 0
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) {
            this.event = event
            connectGate?.await()
            event("{\"type\":\"session.started\",\"session\":{\"id\":\"minimal-ui\"}}")
        }
        override fun send(json: String) = true
        override fun mute(muted: Boolean) {}
        override fun output(enabled: Boolean) {}
        override fun silence() {}
        override fun close() {}
        fun user(text: String) { val start = clock; clock += 100; event?.invoke(JSONObject().put("type", "session.input_transcript.delta").put("delta", text).put("start_ms", start).put("end_ms", start + 100).toString()) }
        fun delegate(id: String) { event?.invoke(JSONObject().put("type", "session.delegation.created").put("offset_ms", clock + 1)
            .put("delegation", JSONObject().put("id", id).put("type", "delegation").put("target", "client")).toString()) }
    }
    @Before fun liveAdapters() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.live.routeFactory = { object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.transportFactory = { Media().also { media += it } }
        app.live.admission = { app.live.admitted(it) }
    }
    @org.junit.After fun releaseHeld() { release.countDown(); connectGate?.complete(Unit); fake.sessionsReadHook = null; fake.failListFromOffset = -1; app.live.end(); settle() }

    private fun phoneLive(on: Boolean) = app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = on, watchLiveEnabled = true))
    private fun shown(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    /** Everything the owner said does not belong on the screen: status prose, Live panel parts and their controls. (A pending request's
     *  own Stop and a reply's Play again stay: they are a Stop and a retry, shown only while there is one; none arises here.) */
    private fun assertNothingButTheMinimalScreen() {
        for (text in listOf("Reading the dashboard", "Live ended", "Hermes work continues", "Phone Live:", "Phone live", "Preparing Live",
                "Choose conversation context", "Use Alpha context", "Mute microphone", "Unmute microphone", "Do not submit", "Optional: send on Phone",
                "Send to ", "You · ", "Live · ", "Live is asking", "Matching the conversation", "Listening for the wake phrase", "Routing off"))
            assertFalse("'$text' is not on the screen", shown(text))
        for (tag in listOf("live_status", "live_mute", "live_bound_title", "live_confirm_request", "live_input_notice", "live_output_notice",
                "routing_target", "hands_free"))
            assertFalse("no $tag", exists(tag))
        assertEquals("one Talk control", 1, count("talk"))
    }
    private fun tapTalk() { rule.onNodeWithTag("talk").performClick(); settle() }

    @Test fun U27_01_theListShowsNoStatusProseWhileTheDashboardIsReadAndKeepsTheGearAndNewConversation() {
        titled("Alpha", "alpha")
        fake.sessionsReadHook = { label -> if (label == "list:recent") { held.countDown(); check(release.await(30, TimeUnit.SECONDS)) { "fixture list latch not released" } } }
        val model = showing()
        assertTrue("the Phone list is reading the dashboard", await(20_000) { held.count == 0L && model.state.value.status.startsWith("Reading") })
        assertNothingButTheMinimalScreen()
        assertTrue(exists("settings_button")); assertTrue(exists("new_conversation_button"))
        release.countDown()
        waitFor("the list is read") { !model.state.value.status.startsWith("Reading") }
        assertNothingButTheMinimalScreen()
    }

    @Test fun U27_02_aPreparingPhoneLiveShowsOnlyTheTalkControlWhichEndsItAtOnce() {
        titled("Alpha", "alpha"); phoneLive(true)
        connectGate = kotlinx.coroutines.CompletableDeferred() // R29: the catalogue no longer holds a call back; its provider start does
        showing()
        tapTalk()
        assertTrue("Live is preparing its media", await(20_000) { media.size == 1 && media.single().event != null && app.live.active && !app.live.state.value.ready })
        assertNothingButTheMinimalScreen()
        rule.onNodeWithTag("talk").assertTextContains("End Live")
        tapTalk()
        assertFalse("the one Talk control ended the preparing Live at once", app.live.active)
        assertNothingButTheMinimalScreen()
        assertEquals("no second media", 1, media.size); assertTrue(fake.prompts.isEmpty())
    }

    @Test fun U27_03_aReadyPhoneLiveShowsNoContextPickerMuteOrTranscripts() {
        titled("Alpha", "alpha"); titled("Beta", "beta"); phoneLive(true)
        showing()
        tapTalk()
        assertTrue("Live is ready", await(20_000) { app.live.state.value.ready })
        media.single().user("안녕")
        settle()
        assertNothingButTheMinimalScreen()
        rule.onNodeWithTag("talk").assertTextContains("End Live")
        tapTalk()
        assertFalse(app.live.active)
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun U27_04_aRequestAwaitingItsConversationShowsNoConfirmationFormAndSubmitsNothing() {
        titled("Alpha", "alpha"); titled("Beta", "beta"); phoneLive(true)
        showing()
        tapTalk()
        assertTrue(await(20_000) { app.live.state.value.ready })
        media.single().user("회의록 정리 부탁해"); media.single().delegate("d1")
        assertTrue("the request waits for the user to name a conversation", await(20_000) { app.live.state.value.pending != null })
        assertNothingButTheMinimalScreen()
        settle(); idle(1_000); settle()
        assertTrue("viewing submits nothing", fake.prompts.isEmpty())
    }

    @Test fun U27_05_aLiveSetupFailureIsSaidOnceBesideTalkAndANewTalkClearsIt() {
        titled("Alpha", "alpha"); phoneLive(true)
        val model = showing()
        fake.failListFromOffset = 0
        tapTalk()
        assertTrue("Live ended in a setup failure", await(20_000) { !app.live.active && app.live.state.value.endReason == "failure" })
        rule.onNodeWithTag("action_failure").assertTextContains("Live conversation catalog could not be verified", substring = true)
        assertEquals("said once", 1, count("action_failure"))
        assertNothingButTheMinimalScreen()
        fake.failListFromOffset = -1
        tapTalk()
        assertTrue(await(20_000) { app.live.state.value.ready })
        assertFalse("a new Talk clears the old failure", exists("action_failure"))
        assertTrue(model.state.value.signedIn)
    }

    @Test fun U27_06_aTalkRefusedForWantOfAConversationSaysWhyBesideTalk() {
        phoneLive(false); app.settings.routingEnabled = false
        showing()
        tapTalk()
        rule.onNodeWithTag("action_failure").assertTextContains("Open a conversation", substring = true)
        assertFalse("nothing records", app.manualCapture.busy)
        assertNothingButTheMinimalScreen()
    }

    @Test fun U27_07_navigationAndSettingsNeverRecordOrStartLiveAndTheDraftStays() {
        val alpha = titled("Alpha", "alpha")
        val model = showWith(alpha)
        openSettled(model, alpha)
        rule.onNodeWithTag("composer").performTextInput("draft words")
        systemBack()
        rule.onNodeWithTag("settings_button").performClick(); settle()
        rule.onNodeWithTag("phone_live_enabled").performScrollTo().performClick(); settle()
        assertTrue("the Phone Live setting is on", app.settings.watchSettings().phoneLiveEnabled)
        systemBack()
        openSettled(model, alpha)
        assertTrue("the draft is kept", model.state.value.draft == "draft words")
        assertFalse("no Live started", app.live.active); assertTrue(media.isEmpty())
        assertFalse("nothing records", app.manualCapture.busy || model.state.value.recording)
        assertTrue("nothing submitted", fake.prompts.isEmpty())
        assertNothingButTheMinimalScreen()
    }
}
