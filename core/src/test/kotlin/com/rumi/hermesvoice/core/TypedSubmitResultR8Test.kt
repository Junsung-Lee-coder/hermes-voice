package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.voice.*
import com.rumi.hermesvoice.core.watchlink.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.WebSocket
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real HTTP/WebSocket -> port -> ChatService and shared voice/Watch. Synthetic faults only. */
class TypedSubmitResultR8Test {
    private val notice = HermesPromptUncertainException(Exception()).message!!
    private fun session(h: CoreHarness) = runBlocking { h.core.sessions.createConversation("Work", "work", "") }.storedSessionId
    private fun faults(h: CoreHarness, kind: String) {
        h.fake.turnBehavior = { "async_hold" }
        h.fake.submitReplyFault = { frame -> R8WireFaults.mutate(frame, kind) }
    }
    private fun chatFault(kind: String) = runBlocking<Unit> {
        CoreHarness().use { h ->
            val id = session(h)
            val notices = mutableListOf<FinalReply>()
            val store = AcceptedTurnStore(InMemoryKeyValueStore())
            val chat = ChatService(h.core.sessions, finalReply = { notices += it }, accepted = store)
            faults(h, kind)
            h.fake.beforeAsyncReply = { h.fake.pushLaterTurn(id, FakeHermesDashboard.complete("original-effect")) }
            var callbacks = 0
            val result = chat.send(id, "R", onAccepted = { callbacks++ })
            assertTrue("R08 typed uncertainty for $kind: $result", result is ChatSendResult.Failed)
            result as ChatSendResult.Failed
            assertEquals("R08 no NOT_SENT authority for $kind", SendDelivery.POSSIBLY_SENT, result.delivery)
            assertFalse(result.acceptanceObserved)
            assertEquals("R08 accepted callback must be absent", 0, callbacks)
            assertEquals(notice, result.reason)
            assertEquals(listOf(notice), notices.map { it.text })
            assertTrue(store.pending().isEmpty())
            // Even a completed first effect and idle-shaped resume cannot retire unproven custody.
            h.fake.pushLaterTurn(id, FakeHermesDashboard.complete("original-R"))
            h.fake.submitReplyFault = null; h.fake.turnBehavior = { "start_first" }
            h.fake.scripts[id] = { listOf(FakeHermesDashboard.complete("NEIGHBOUR")) }
            val next = h.core.sessions.sendMessage(id, "NEW")
            assertNull("R08 no trusted acceptance after unknown custody", next.acceptance)
            val got = mutableListOf<String>()
            val error = try { next.collect(3_000) { if (it is RecipientEvent.Complete) got += it.text }; null }
                catch (e: HermesException) { e } finally { next.release() }
            assertEquals(emptyList<String>(), got)
            assertEquals("HermesReplyUnrecoverableException", error?.javaClass?.simpleName)
            assertEquals(listOf("R", "NEW"), h.fake.rawPrompts.map { it.second })
        }
    }
    @Test fun E01_rpcGenericIsPossiblySentWithoutAcceptedCallback() = chatFault("rpcGeneric")
    @Test fun E02_rpcMissingReasonIsPossiblySentWithoutAcceptedCallback() = chatFault("rpcMissingReason")
    @Test fun E03_rpcWrongReasonIsPossiblySentWithoutAcceptedCallback() = chatFault("rpcWrongReason")
    @Test fun E04_rpcPrefixIsPossiblySentWithoutAcceptedCallback() = chatFault("rpcPrefix")
    @Test fun E05_rpcWrongCodeIsPossiblySentWithoutAcceptedCallback() = chatFault("rpcWrongCode")
    @Test fun E06_rpcStringCodeIsPossiblySentWithoutAcceptedCallback() = chatFault("rpcStringCode")
    @Test fun E07_unknownIsPossiblySentWithoutAcceptedCallback() = chatFault("unknown")
    @Test fun E08_emptyIsPossiblySentWithoutAcceptedCallback() = chatFault("empty")
    @Test fun E09_missingStatusIsPossiblySentWithoutAcceptedCallback() = chatFault("missingStatus")
    @Test fun E10_nullStatusIsPossiblySentWithoutAcceptedCallback() = chatFault("nullStatus")
    @Test fun E11_numberStatusIsPossiblySentWithoutAcceptedCallback() = chatFault("numberStatus")
    @Test fun E12_objectStatusIsPossiblySentWithoutAcceptedCallback() = chatFault("objectStatus")
    @Test fun E13_arrayStatusIsPossiblySentWithoutAcceptedCallback() = chatFault("arrayStatus")
    @Test fun E14_missingResultIsPossiblySentWithoutAcceptedCallback() = chatFault("missingResult")
    @Test fun E15_nullResultIsPossiblySentWithoutAcceptedCallback() = chatFault("nullResult")
    @Test fun E16_stringResultIsPossiblySentWithoutAcceptedCallback() = chatFault("stringResult")
    @Test fun E17_arrayResultIsPossiblySentWithoutAcceptedCallback() = chatFault("arrayResult")
    @Test fun E18_numberResultIsPossiblySentWithoutAcceptedCallback() = chatFault("numberResult")
    @Test fun E19_malformedErrorIsPossiblySentWithoutAcceptedCallback() = chatFault("malformedError")

    private fun voiceFault(kind: String, origin: VoiceOrigin) = runBlocking<Unit> {
        val delivered = AtomicInteger()
        CoreHarness(voiceListener = object : VoiceTurnListener {
            override fun onDelivered(route: AssembledRoute) { delivered.incrementAndGet() }
        }).use { h ->
            val id = session(h)
            val audio = mutableListOf<SpokenRole>()
            val notices = mutableListOf<FinalReply>()
            val portConnector = HermesGatewayConnector(HermesDashboardClient(HermesDashboardEndpoint.parse(h.fake.baseUrl), h.http, h.tokens), h.http)
            val port = GatewayConversationPort { portConnector.connection() }
            val accepted = AcceptedTurnStore(InMemoryKeyValueStore())
            val o = VoiceTurnOrchestrator(object : HermesSpeechGateway {
                override suspend fun transcribe(audio: ByteArray, mimeType: String) = "R"
                override suspend fun speak(text: String) = SpokenAudio(text.toByteArray(), "audio/mpeg")
            }, port, config = { VoiceTurnConfig(null, DestinationAllowlist.create(listOf(DestinationEntry("work", id)), "router"), ResponsePlaybackSettings()) },
                listener = object : VoiceTurnListener { override fun onDelivered(route: AssembledRoute) { delivered.incrementAndGet() } },
                acceptedTurns = accepted, finalReply = { notices += it })
            faults(h, kind)
            val request = VoiceTurnRequest("r8-voice-0001", origin, byteArrayOf(), "audio/wav",
                PlaybackSink { _, cue -> audio += cue.role }, recognizedText = "R", routing = TurnRouting.Direct(id))
            val result = o.run(request)
            assertTrue("R08 voice uncertainty: $result", result is VoiceTurnOutcome.NotDelivered && result.possiblySent)
            assertEquals(0, delivered.get())
            assertTrue("no accepted voice ledger", accepted.pending().isEmpty())
            assertEquals("Acceptance uncertain: check the conversation. Do not retry this request", VoiceOutcomeText.describe(result))
            assertEquals(listOf(notice), notices.map { it.text })
            assertFalse(audio.contains(SpokenRole.FINAL))
            assertEquals(VoiceTurnOutcome.Duplicate(request.turnId), o.run(request))
            assertEquals(listOf("🎙 R"), h.fake.rawPrompts.map { it.second })
            portConnector.close()
        }
    }
    @Test fun E20_unknownVoiceNeverReportsDelivered() = voiceFault("unknown", VoiceOrigin.PHONE)
    @Test fun E21_rpcVoiceNeverReportsNotSent() = voiceFault("rpcGeneric", VoiceOrigin.PHONE)

    private fun watchFault(kind: String) = runBlocking<Unit> {
        CoreHarness().use { h ->
            val id = session(h)
            // Routing stays authentic and successful; only the destination reply is faulted.
            h.fake.sourceScripts[com.rumi.hermesvoice.core.sessions.AppSources.ROUTER] = {
                listOf(FakeHermesDashboard.complete("""{"destination":"work","ack":"On it."}"""))
            }
            h.fake.turnBehavior = { stored -> if (stored == id) "async_hold" else "start_first" }
            h.fake.submitReplyFault = { frame ->
                if (h.fake.rawPrompts.last().first == id) R8WireFaults.mutate(frame, kind)
            }
            val states = mutableListOf<TurnStateMessage>()
            val audio = mutableListOf<SpokenRole>()
            val watch = object : WatchTransport {
                override val nodeId = "watch-r8"
                override suspend fun sendMessage(path: String, bytes: ByteArray) {
                    if (path == WatchLinkPaths.STATE) states += TurnStateMessage.decode(bytes)!!
                }
                override suspend fun sendChannel(path: String, bytes: ByteArray) {
                    val play = PlayRequest.fromFrame(LinkFrame.decode(bytes)); audio += SpokenRole.valueOf(play.role)
                    h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
                }
            }
            val upload = WatchTurnUpload("r8-watch-0001", TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav()).toFrame().encode()
            val result = h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("r8-watch-0001"), upload, watch)
            assertTrue("R08 originating Watch uncertainty: $result", result is VoiceTurnOutcome.NotDelivered && result.possiblySent)
            assertEquals("Acceptance uncertain: check the conversation. Do not retry this request", states.last().detail)
            assertTrue(states.last().terminal)
            assertFalse(states.any { it.detail.startsWith("Delivered") || it.detail.contains("destination busy") })
            assertFalse(audio.contains(SpokenRole.FINAL))
            assertTrue(h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("r8-watch-0001"), upload, watch) is VoiceTurnOutcome.Duplicate)
            assertEquals(1, h.fake.rawPrompts.count { it.first == id })
        }
    }
    @Test fun E22_unknownWatchHasTruthfulTerminalState() = watchFault("unknown")
    @Test fun E23_rpcWatchHasTruthfulTerminalState() = watchFault("rpcGeneric")

    @Test fun C01_actualSocketSendFalseRemainsDefinitivelyNotSent() = runBlocking<Unit> {
        CoreHarness().use { h ->
            val id = session(h)
            val c = HermesGatewayConnector(HermesDashboardClient(HermesDashboardEndpoint.parse(h.fake.baseUrl), h.http, h.tokens), h.http)
            val real = c.connection()
            val field = real.javaClass.getDeclaredField("socket").also { it.isAccessible = true }
            val socket = field.get(real) as WebSocket
            var actual: Boolean? = null
            field.set(real, object : WebSocket by socket {
                override fun send(text: String): Boolean { socket.close(1000, null); return socket.send(text).also { actual = it } }
            })
            val e = try { real.call("prompt.submit", JSONObject().put("session_id", "rt-$id").put("text", "R")); null }
                catch (e: HermesException) { e }
            assertEquals(false, actual)
            assertTrue("real send=false must remain unsent: $e", e is HermesPromptNotSentException)
            assertTrue(h.fake.rawPrompts.isEmpty()); c.close()
        }
    }
    @Test fun C02_knownStatusesAndSequentialStreamingRemainSupported() = runBlocking<Unit> {
        CoreHarness().use { h ->
            val id = session(h)
            for (text in listOf("first", "second")) assertTrue(h.core.chat.send(id, text) is ChatSendResult.Replied)
            for (status in listOf("steered", "redirected", "queued")) {
                h.fake.submitStatus = { status }
                val result = h.core.chat.send(id, status)
                if (status == "queued") assertEquals(SendDelivery.ACCEPTED, (result as ChatSendResult.Failed).delivery)
                else assertEquals(status, (result as ChatSendResult.Accepted).submitStatus)
            }
        }
    }
}
