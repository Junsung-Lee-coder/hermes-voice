package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.DeviceLocalFlags
import com.rumi.hermesvoice.core.background.LaterReplyConsent
import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B30-F1 at the seam the Phone adapters use ([com.rumi.hermesvoice.core.voice.AudioOwnership]): later
 * replies never play over a recording (they wait, or are stopped before the microphone opens and come
 * back afterwards) or into an open wake window, they are bounded, and the follow reports why it ended
 * (B30-F5). B30-F2 / B31-N6: the opt-in's default, its device-local persistence and restore.
 */
class LaterReplyMicrophoneTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val later: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val ended: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val listener = object : VoiceTurnListener {
        override fun onLaterReply(turnId: String, played: Boolean, detail: String) {
            later += "$turnId:${if (played) "played" else "not_played"}:$detail"
        }

        override fun onLaterFollowEnded(turnId: String, reason: String) {
            ended += "$turnId:$reason"
        }
    }

    @Volatile private var phoneWindow = false
    @Volatile private var holding = 0
    @Volatile private var phoneSpeaker = 0

    @After
    fun stop() = scope.cancel()

    private fun harness(deferMaxMs: Long = 60_000L) = CoreHarness(laterScope = scope, voiceListener = listener,
        wakeListening = { it == VoiceOrigin.PHONE && phoneWindow },
        // Like PhoneApp: the CPU hold of one clip's handoff and playback (never its synthesis)...
        laterWork = { work ->
            holding += 1
            try {
                work()
            } finally {
                holding -= 1
            }
        },
        laterDeferMaxMs = deferMaxMs,
        // ...and the Phone's "a later reply holds the speaker" signal, which closes its wake windows.
        laterSpeaker = { device, on ->
            if (device == VoiceOrigin.PHONE) {
                phoneSpeaker += if (on) 1 else -1
                if (on) phoneWindow = false
            }
        }).also { it.laterConsent.enabled = true }

    private fun routeTo(h: CoreHarness, alias: String) {
        val reply: (String) -> List<Pair<String, JSONObject?>> =
            { listOf(FakeHermesDashboard.complete("""{"action":"route","destination":"$alias","ack":"Sending to $alias."}""")) }
        h.fake.sourceScripts[AppSources.ROUTER] = reply
        h.registry.router()?.let { h.fake.scripts[it.storedSessionId] = reply }
    }

    private fun existing(h: CoreHarness, alias: String, firstReply: String): String {
        val owned = runBlocking { h.core.sessions.createConversation(alias.replaceFirstChar { it.uppercase() }, alias, "$alias things") }
        h.fake.scripts[owned.storedSessionId] = { listOf(FakeHermesDashboard.complete(firstReply)) }
        return owned.storedSessionId
    }

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(15_000) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what (later=$later ended=$ended)")
        }
    }

    private fun settle(ms: Long = 800) = runBlocking { delay(ms) }

    /** The Phone speaker: records what it plays and whether the microphone was claimed or a wake window open then. */
    private inner class PhoneSpeaker(val h: CoreHarness) {
        val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val overlaps: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val holds = mutableMapOf<String, CompletableDeferred<Unit>>()

        /** Utterances still on the speaker (a stopped one leaves it when its cancellation finished). */
        @Volatile var active = 0
        val sink = PlaybackSink { audio, cue ->
            val text = h.fake.decodeSpoken(audio)
            if (h.ownership.microphoneClaimed(VoiceOrigin.PHONE) || phoneWindow) overlaps += text
            played += "${cue.role}:$text"
            active += 1
            try {
                holds[text]?.await()
            } finally {
                active -= 1
            }
        }
    }

    private fun phoneTurn(h: CoreHarness, speaker: PhoneSpeaker, turnId: String) = runBlocking {
        h.core.orchestrator.run(VoiceTurnRequest(turnId, VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", speaker.sink))
    }

    @Test
    fun `a later reply waits while the Phone records and plays once after, never over the recording`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val speaker = PhoneSpeaker(h)
            phoneTurn(h, speaker, "m-turn-0001")
            // push-to-talk (or a hands-free capture) records: no turn exists yet
            val recording = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Report ready."))
            settle(1_200)
            assertFalse(speaker.played.contains("FINAL:Report ready."))
            assertEquals("no CPU hold while it waits", 0, holding)
            assertEquals("the Phone's speaker is not 'held' while it waits", 0, phoneSpeaker)
            recording.release()
            waitFor("played after the recording") { speaker.played.contains("FINAL:Report ready.") }
            settle()
            assertEquals(1, speaker.played.count { it == "FINAL:Report ready." })
            assertTrue(speaker.overlaps.isEmpty())
            assertEquals(listOf("m-turn-0001:played:phone"), later.toList())
            assertEquals(0, phoneSpeaker)
        }
    }

    @Test
    fun `a recording that starts while a later reply plays stops it before the microphone opens, and it plays again afterwards`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val speaker = PhoneSpeaker(h)
            phoneTurn(h, speaker, "m-turn-0002")
            val long = CompletableDeferred<Unit>().also { speaker.holds["A long report."] = it }
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("A long report."))
            waitFor("playing") { speaker.played.contains("FINAL:A long report.") }
            // What every Phone capture path does: claim first, open only once the reply has stopped.
            val recording = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            val opened = CompletableDeferred<Boolean>()
            // At once if it already stopped by now, otherwise once it has: never while it still holds the speaker.
            recording.whenSpeakerStopped(scope) { stopped -> opened.complete(stopped && speaker.active == 0 && phoneSpeaker == 0) }
            assertTrue("opened after the reply's speaker was let go", runBlocking { withTimeout(5_000) { opened.await() } })
            settle()
            assertTrue("not reported: it will be played again", later.isEmpty())
            speaker.holds.remove("A long report.")
            long.complete(Unit)
            recording.release()
            waitFor("played again after the recording") { later.isNotEmpty() }
            assertEquals(2, speaker.played.count { it == "FINAL:A long report." })
            assertEquals(listOf("m-turn-0002:played:phone"), later.toList())
        }
    }

    @Test
    fun `an open wake window closes for a later reply and is never spoken over`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val speaker = PhoneSpeaker(h)
            phoneTurn(h, speaker, "m-turn-0003")
            phoneWindow = true // the Phone's wake window is listening (foreground or background)
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Window test."))
            waitFor("played") { speaker.played.contains("FINAL:Window test.") }
            assertTrue(speaker.overlaps.isEmpty())
        }
    }

    @Test
    fun `a device that stays busy is bounded - reported as not played, never played elsewhere`() {
        harness(deferMaxMs = 1_500).use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val speaker = PhoneSpeaker(h)
            phoneTurn(h, speaker, "m-turn-0004")
            val recording = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Never over a recording."))
            waitFor("the bounded report") { later.isNotEmpty() }
            assertEquals(listOf("m-turn-0004:not_played:not played: the speaker or microphone stayed busy"), later.toList())
            assertFalse(speaker.played.contains("FINAL:Never over a recording."))
            recording.release()
        }
    }

    @Test
    fun `a busy refusal from another node does not count, only the playing Watch's answer does`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
            val watch = object : WatchTransport {
                override val nodeId = "watch-node-1"
                override suspend fun sendMessage(path: String, bytes: ByteArray) {}
                override suspend fun sendChannel(path: String, bytes: ByteArray) {
                    val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
                    if (play.later) {
                        // A stale or forged busy from another node is ignored by the registry...
                        assertFalse(h.core.watchAcks.onPlayedMessage("other-node", PlayedAck(play.turnId, play.sequence, false, PlayedAck.BUSY_RECORDING).encode()))
                    }
                    played += "${play.role}:${String(play.audio).removePrefix("AUDIO:")}:later=${play.later}"
                    // ...and the real Watch confirms it played.
                    h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
                }
            }
            runBlocking {
                val frame = WatchTurnUpload("m-turn-0005", TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav()).toFrame().encode()
                h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("m-turn-0005"), frame, watch)
            }
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Later."))
            waitFor("played") { later.isNotEmpty() }
            assertEquals(listOf("ACK:Sending to work.:later=false", "FINAL:Started.:later=false", "FINAL:Later.:later=true"), played.toList())
            assertEquals(listOf("m-turn-0005:played:watch"), later.toList())
            assertEquals("a reply to the Watch never holds the Phone's speaker", 0, phoneSpeaker)
        }
    }

    @Test
    fun `Stop ends the follow and reports a waiting reply, switching off reports off, and a dropped connection is reported`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val speaker = PhoneSpeaker(h)
            phoneTurn(h, speaker, "m-turn-0006")
            val recording = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Waiting reply."))
            settle()
            h.core.orchestrator.stopFollowing() // a Stop (relay, background listening) or the option switched off
            waitFor("stopped") { ended.contains("m-turn-0006:stopped") }
            waitFor("the waiting reply reported") { later.contains("m-turn-0006:not_played:not played: stopped") }
            recording.release()
            settle()
            assertFalse("a stopped reply never plays later", speaker.played.contains("FINAL:Waiting reply."))

            phoneTurn(h, speaker, "m-turn-0007")
            h.laterConsent.enabled = false
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("After off."))
            waitFor("off") { ended.contains("m-turn-0007:off") }
            assertFalse(speaker.played.contains("FINAL:After off."))

            h.laterConsent.enabled = true
            phoneTurn(h, speaker, "m-turn-0008")
            h.stop() // the gateway connection goes away (process keeps running): nothing re-subscribes
            waitFor("disconnected") { ended.contains("m-turn-0008:disconnected") }
            assertEquals("each reply that arrived is reported once", 1, later.count { it.startsWith("m-turn-0006:") })
        }
    }

    @Test
    fun `the CPU-holding part covers handoff and playback only, never the wait for synthesis - and synthesis once per spoken reply`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val speaker = PhoneSpeaker(h)
            phoneTurn(h, speaker, "m-turn-0009")
            val recording = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Held only to speak."))
            settle(1_000)
            val heldWhileWaiting = holding
            recording.release()
            waitFor("played") { speaker.played.contains("FINAL:Held only to speak.") }
            assertEquals("nothing held during the wait", 0, heldWhileWaiting)
            assertEquals(1, h.fake.timeline.count { it == "speak:Held only to speak." })
        }
    }

    // ── the opt-in itself (B30-F2, B31-N6) ────────────────────────────────────────────────────

    @Test
    fun `the option is off on a fresh install, after migration and any other setting, and persists on this device when switched on`() {
        val shared = InMemoryKeyValueStore()
        val local = InMemoryKeyValueStore()
        val settings = AppSettings(shared)
        assertFalse(LaterReplyConsent(local).enabled)
        // An install with older saved settings: migration and other settings never switch it on.
        shared.putString(AppSettings.KEY_WAKE_LOCATION, "BOTH")
        settings.migrate()
        settings.saveWatchSettings(WatchSettings(WakeLocation.PHONE, "루미", revision = 1))
        settings.routingEnabled = false
        settings.autoNavigateToRouted = true
        settings.playFirstResponse = true
        DeviceLocalFlags.dropLegacy(shared, local)
        assertFalse(LaterReplyConsent(local).enabled)
        assertFalse("never part of the Watch's settings snapshot", settings.watchSettings().toJson().contains("later"))
        LaterReplyConsent(local).enabled = true
        assertTrue("same device, reloaded", LaterReplyConsent(local).enabled)
        assertFalse("never written to the backed-up settings", shared.getBoolean(DeviceLocalFlags.KEY_LEGACY_LATER_REPLIES, false))
        LaterReplyConsent(local).enabled = false
        assertFalse(LaterReplyConsent(local).enabled)
    }

    @Test
    fun `a restored or transferred settings file with the old opt-in on is never consent, and is switched off`() {
        // What a backup (or device transfer) of an earlier build carries: the backed-up settings file, not the device-local one.
        val restored = InMemoryKeyValueStore().apply { putBoolean(DeviceLocalFlags.KEY_LEGACY_LATER_REPLIES, true) }
        val freshLocal = InMemoryKeyValueStore()
        assertFalse(LaterReplyConsent(freshLocal).enabled)
        assertTrue(LaterReplyConsent.dropLegacy(restored))
        assertFalse(restored.getBoolean(DeviceLocalFlags.KEY_LEGACY_LATER_REPLIES, true))
        assertFalse("found only once", LaterReplyConsent.dropLegacy(restored))
        assertFalse(LaterReplyConsent(freshLocal).enabled)
        // Through the production core wiring as well: nothing is followed or spoken.
        CoreHarness(store = InMemoryKeyValueStore().apply { putBoolean(DeviceLocalFlags.KEY_LEGACY_LATER_REPLIES, true) },
            laterScope = scope, voiceListener = listener).use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val speaker = PhoneSpeaker(h)
            phoneTurn(h, speaker, "m-turn-0010")
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Not consented on this device."))
            settle(1_000)
            assertFalse(speaker.played.contains("FINAL:Not consented on this device."))
            assertTrue(later.isEmpty())
            assertTrue(h.fake.timeline.none { it == "speak:Not consented on this device." })
        }
    }
}

/** The Watch's own decision (WatchApp.play / newTurn): later replies never play over its recording; in-turn replies are untouched. */
class LaterPlaybackGuardTest {
    private fun request(later: Boolean) = com.rumi.hermesvoice.core.watchlink.PlayRequest("turn-guard-1", 2, "FINAL", "audio/mpeg", byteArrayOf(1), later)

    @Test
    fun `a later reply is refused as busy while recording, played otherwise, and an in-turn reply is never refused`() {
        val guard = com.rumi.hermesvoice.core.watchlink.LaterPlaybackGuard
        assertEquals(PlayedAck.BUSY_RECORDING, guard.refusal(request(later = true), recording = true))
        assertEquals(null, guard.refusal(request(later = true), recording = false))
        assertEquals(null, guard.refusal(request(later = false), recording = true))
        assertEquals(PlayedAck.BUSY_RECORDING, guard.onRecordingStarted(request(later = true)))
        assertEquals(null, guard.onRecordingStarted(request(later = false)))
        assertEquals(null, guard.onRecordingStarted(null))
    }

    @Test
    fun `the later flag travels in the play header, and an older frame without it is an in-turn reply`() {
        val frame = request(later = true).toFrame()
        assertTrue(PlayRequest.fromFrame(LinkFrame.decode(frame.encode())).later)
        val legacy = LinkFrame(JSONObject(frame.header.toString()).apply { remove("later") }, frame.payload)
        assertFalse(PlayRequest.fromFrame(LinkFrame.decode(legacy.encode())).later)
    }
}
