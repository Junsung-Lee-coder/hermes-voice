package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.activity.ActivityLabels
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The receiving bubble's transient activity through the REAL MainActivity, PhoneViewModel, PhoneApp
 * wiring, ChatService / orchestrator and the Hermes gateway contract double (tool/status/interim frames pushed by the test).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneActivityRoboTest : PhoneRoboBase() {
    @Before fun setUp() = baseSetUp()
    @After fun tearDown() = baseTearDown()

    private fun labels(model: PhoneViewModel, session: String) = model.state.value.activity.filter { it.sessionId == session }.map { it.label }

    @Test fun A01_aTypedRequestShowsOneWaitingBubbleThatTwoToolUpdatesReplaceAndTheFinalReplyRemoves() {
        val work = conversation("work")
        val model = open(work)
        model.setDraft("do it")
        model.send()
        assertTrue("waiting state first", await { labels(model, work) == listOf(ActivityLabels.WAITING) })
        fake.pushLaterTurn(work, tool("execute_code", "run a script"), withStart = false)
        assertTrue(await { labels(model, work).singleOrNull()?.startsWith("🐍") == true })
        fake.pushLaterTurn(work, tool("search_files", "*.kt in core"), withStart = false)
        assertTrue("replaced in place, never a growing log", await { labels(model, work) == listOf(ActivityLabels.tool("search_files", "*.kt in core")) })
        assertEquals(1, model.state.value.activity.size)
        assertTrue("no final text is in the transcript yet", model.state.value.history.none { it.role == "assistant" })

        fake.pushLaterTurn(work, FakeHermesDashboard.complete("All done."), withStart = false)
        assertTrue("the final reply replaces the slot", await { model.state.value.history.any { it.role == "assistant" && it.text == "All done." } })
        assertTrue("and the slot is gone", await { model.state.value.activity.isEmpty() })
        settle()
        assertEquals("canonical, shown once", 1, model.state.value.history.count { it.text == "All done." })
        assertTrue(model.state.value.activity.isEmpty())
    }

    @Test fun A02_progressIsNeverPersistedAnywhere() {
        val work = conversation("work")
        val model = open(work)
        model.setDraft("go")
        model.send()
        assertTrue(await { labels(model, work).isNotEmpty() })
        fake.pushLaterTurn(work, tool("search_files", "UNIQUE-PREVIEW-9"), withStart = false)
        assertTrue(await { labels(model, work).singleOrNull()?.contains("UNIQUE-PREVIEW-9") == true })
        fake.pushLaterTurn(work, FakeHermesDashboard.complete("final"), withStart = false)
        assertTrue(await { model.state.value.activity.isEmpty() && model.state.value.history.any { it.text == "final" } })
        val stored = fake.rows.getValue(work).messages
        assertTrue("no stored row of any role mentions the progress", stored.none { it.toString().contains("UNIQUE-PREVIEW-9") })
        val conversationRows = stored.filter { it.optString("role") != "system" }
        assertEquals("server history holds only the user row and the final reply (the fixture's hidden system seed row aside)",
            listOf("user:go", "assistant:final"), conversationRows.map { it.optString("role") + ":" + it.optString("content") })
        assertTrue("no history text mentions the progress", model.state.value.history.none { it.text.contains("UNIQUE-PREVIEW-9") })
    }

    @Test fun A03_aBubbleOfOneConversationNeverShowsInAnotherAndOtherRequestsKeepTheirOwn() {
        val work = conversation("work")
        val home = conversation("home")
        val model = open(work)
        model.setDraft("from work")
        model.send()
        assertTrue(await { labels(model, work).isNotEmpty() })
        openOwned(model, home)
        model.setDraft("from home")
        model.send()
        assertTrue(await { labels(model, home).isNotEmpty() })
        fake.pushLaterTurn(work, tool("terminal", "ls"), withStart = false)
        assertTrue(await { labels(model, work).singleOrNull()?.startsWith("💻") == true })
        assertEquals("home's slot is untouched by work's frame", listOf(ActivityLabels.WAITING), labels(model, home))
        fake.pushLaterTurn(work, FakeHermesDashboard.complete("work done"), withStart = false)
        assertTrue(await { labels(model, work).isEmpty() })
        assertEquals("home keeps waiting", listOf(ActivityLabels.WAITING), labels(model, home))
        fake.pushLaterTurn(home, FakeHermesDashboard.complete("home done"), withStart = false)
        assertTrue(await { model.state.value.activity.isEmpty() })
    }

    @Test fun A04_aFailedTurnRemovesTheBubbleTruthfullyAndNeverInventsAFinalAnswer() {
        val work = conversation("work")
        val model = open(work)
        model.setDraft("go")
        model.send()
        assertTrue(await { labels(model, work).isNotEmpty() })
        fake.pushLaterTurn(work, tool("terminal", "ls"), "error" to org.json.JSONObject().put("message", "boom"), withStart = false)
        assertTrue(await { model.state.value.activity.isEmpty() })
        settle()
        assertTrue("no fake assistant text", model.state.value.history.none { it.role == "assistant" })
        assertFalse("a late frame cannot bring it back", run {
            fake.pushLaterTurn(work, tool("search_files", "late"), withStart = false)
            settle()
            model.state.value.activity.isNotEmpty()
        })
    }

    @Test fun A05_aVoiceTurnToTheSelectedConversationShowsTheSameBubbleAndNeverSpeaksIt() {
        val work = conversation("work")
        val model = open(work)
        val spoken = java.util.Collections.synchronizedList(mutableListOf<String>())
        val core = app.wiring().core
        val run = CoroutineScope(Dispatchers.IO).async {
            core.orchestrator.run(VoiceTurnRequest("v-1", VoiceOrigin.PHONE, ByteArray(4), "audio/wav",
                PlaybackSink { audio, cue -> spoken += "${cue.role}:${audio.bytes.size}" }, recognizedText = "run the thing",
                routing = TurnRouting.Direct(work)))
        }
        assertTrue("voice request shows a waiting bubble in its conversation", await { labels(model, work) == listOf(ActivityLabels.WAITING) })
        fake.pushLaterTurn(work, tool("web_search", "weather"), withStart = false)
        assertTrue(await { labels(model, work).singleOrNull()?.startsWith("🌐") == true })
        val playedDuringProgress = spoken.toList()
        fake.pushLaterTurn(work, FakeHermesDashboard.complete("Sunny."), withStart = false)
        assertTrue(await { model.state.value.activity.isEmpty() && model.state.value.history.any { it.text == "Sunny." } })
        runCatching { kotlinx.coroutines.runBlocking { run.await() } }
        assertTrue("a tool frame was never speech: $playedDuringProgress", playedDuringProgress.none { it.startsWith("FIRST") || it.startsWith("MIDDLE") })
        assertNull(model.state.value.activity.firstOrNull())
    }
}
