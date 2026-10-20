package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.AcceptedKind
import com.rumi.hermesvoice.core.net.AcceptedTurn
import com.rumi.hermesvoice.core.net.AcceptedTurnReconciler
import com.rumi.hermesvoice.core.net.AcceptedTurnStore
import com.rumi.hermesvoice.core.net.HermesConversationPort
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.net.SubmittedTurn
import com.rumi.hermesvoice.core.net.TurnAcceptance
import com.rumi.hermesvoice.core.notify.ConversationPresence
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
import com.rumi.hermesvoice.core.notify.ReplyAlertLedger
import com.rumi.hermesvoice.core.notify.ReplyAlertResult
import com.rumi.hermesvoice.core.notify.ReplyAlerts
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.RecipientEvent
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review-repair regression tests for the four reply/failure delivery paths (findings F02, F03, F04, F09 and the unattended
 * recovery of F11). They use only APIs that exist on the predecessor, so the same bytes run against it (RED) and against the
 * repaired tree (GREEN): real stores that refuse writes, the real core wiring against [FakeHermesDashboard], an app restart
 * between acceptance and delivery, and an alert surface that can refuse or throw. Contract double, not an authenticated Hermes E2E.
 */
class ReviewRepairDeliveryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stored = "20260101_120000_workwork"

    @After
    fun stop() {
        process.cancel()
        scope.cancel()
    }

    /** The Phone's arrival-alert surface that can refuse or throw, wired like [AlertRig] (one ledger across restarts). */
    private inner class Rig(ledgerStore: KeyValueStore = InMemoryKeyValueStore()) {
        val shown: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val attempts = AtomicInteger()
        @Volatile var accept = true
        @Volatile var throwing = false
        val alerts = ReplyAlerts(ReplyAlertLedger(ledgerStore), { alert ->
            attempts.incrementAndGet()
            if (throwing) throw IllegalStateException("surface failed")
            if (!accept) false else { shown += "${alert.identity}@${alert.storedSessionId}"; true }
        }, scope)

        fun harness(store: KeyValueStore = InMemoryKeyValueStore()): CoreHarness =
            CoreHarness(store = store, laterScope = scope, laterWindowMs = 60_000L, replyAlerts = alerts).also { it.laterConsent.enabled = true }

        fun harness(previous: CoreHarness): CoreHarness =
            CoreHarness(previous.fake, previous.store, previous.localStore, laterScope = scope, laterWindowMs = 60_000L, replyAlerts = alerts)
                .also { it.laterConsent.enabled = true }

        fun notices(): List<String> = synchronized(shown) { shown.filter { "#notice@" in it } }
        fun replies(): List<String> = synchronized(shown) { shown.filter { "#reply@" in it || "#final@" in it } }
        fun limits(): List<String> = synchronized(shown) { shown.filter { "#limit@" in it } }
    }

    private fun waitFor(what: String, timeoutMs: Long = 12_000, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    private fun settle(ms: Long = 1_500) = runBlocking { delay(ms) }

    private fun submits(h: CoreHarness) = h.fake.rpcLog.toList().count { it.optString("method") == "prompt.submit" }

    private fun existing(h: CoreHarness, alias: String, script: List<Pair<String, JSONObject?>> =
        listOf(FakeHermesDashboard.interim("Working."), FakeHermesDashboard.complete("Done."))): String {
        val owned = runBlocking { h.core.sessions.createConversation(alias.replaceFirstChar { it.uppercase() }, alias, "$alias things") }
        h.fake.scripts[owned.storedSessionId] = { script }
        h.fake.turnBehavior = { "hold" }
        return owned.storedSessionId
    }

    private fun held(h: CoreHarness, work: String) = waitFor("the server holding the turn") { h.fake.heldTurns.containsKey(work) }

    private fun chat(h: CoreHarness, work: String) = process.async { h.core.chat.send(work, "hello") }

    private val sink = PlaybackSink { _, _ -> }

    private fun voice(h: CoreHarness, turnId: String, work: String) = process.async {
        h.core.orchestrator.run(VoiceTurnRequest(turnId, VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", sink, routing = TurnRouting.Direct(work)))
    }

    private fun turn(id: String, runtime: String = "rt-x") =
        AcceptedTurn(id, AcceptedKind.TEXT, stored, TurnAcceptance(runtime, 5L, "e1", 0), System.currentTimeMillis())

    private fun reply(identity: String, sinkOf: PlaybackSink? = null, watch: Boolean = false) =
        FinalReply(identity, stored, if (watch) VoiceOrigin.WATCH else VoiceOrigin.PHONE, heard = false, cancelled = false,
            source = FinalReplySource.LATER, sink = sinkOf, text = "An answer")

    // ---- F02: the accepted-turn record is only called durable when the write was ----

    @Test
    fun `S1 a refused begin keeps the accepted turn owed in this process and writes it once storage works again`() {
        val file = FailingKeyValueStore()
        val turns = AcceptedTurnStore(file)
        file.failing = true
        runBlocking { turns.begin(turn("chat-s1")) }
        assertTrue("the write was refused: ${file.refusedKeys()}", AcceptedTurnStore.KEY in file.refusedKeys())
        assertNull("nothing reached the file", file.persisted(AcceptedTurnStore.KEY))
        assertEquals("the turn is still owed by this process", listOf("chat-s1"), turns.pending().map { it.id })
        file.failing = false
        assertEquals(listOf("chat-s1"), turns.pending().map { it.id })
        assertTrue("it was written once the file accepted writes: ${file.persisted(AcceptedTurnStore.KEY)}",
            file.persisted(AcceptedTurnStore.KEY)?.contains("chat-s1") == true)
        assertEquals("a new process now finds it", listOf("chat-s1"), AcceptedTurnStore(file).pending().map { it.id })
    }

    @Test
    fun `S2 a refused settle does not make a delivered turn owed again in this process and is written once storage works again`() {
        val file = FailingKeyValueStore()
        val turns = AcceptedTurnStore(file)
        runBlocking { turns.begin(turn("chat-s2")) }
        assertTrue(file.persisted(AcceptedTurnStore.KEY)?.contains("chat-s2") == true)
        file.failing = true
        turns.settle("chat-s2")
        assertTrue(AcceptedTurnStore.KEY in file.refusedKeys())
        assertTrue("this process does not deliver it again: ${turns.pending().map { it.id }}", turns.pending().isEmpty())
        file.failing = false
        assertTrue(turns.pending().isEmpty())
        assertFalse("the settle was written once the file accepted writes: ${file.persisted(AcceptedTurnStore.KEY)}",
            file.persisted(AcceptedTurnStore.KEY)?.contains("chat-s2") == true)
        assertTrue(AcceptedTurnStore(file).pending().isEmpty())
    }

    @Test
    fun `S3 a refused write of an expiry does not bring the expired turn back in this process`() {
        val file = FailingKeyValueStore()
        var now = 1_000_000L
        val turns = AcceptedTurnStore(file, clock = { now }, maxAgeMs = 10_000L)
        runBlocking { turns.begin(AcceptedTurn("chat-s3", AcceptedKind.TEXT, stored, TurnAcceptance("rt-x", 5L, "e1", 0), now)) }
        now += 20_000L
        file.failing = true
        assertTrue(turns.pending().isEmpty())
        assertTrue(turns.pending().isEmpty())
        file.failing = false
        assertTrue(turns.pending().isEmpty())
        assertFalse(file.persisted(AcceptedTurnStore.KEY)?.contains("chat-s3") == true)
    }

    @Test
    fun `F2 a text send whose record cannot be saved says the reply may not be recoverable, is never sent again, and is still delivered live`() {
        val file = FailingKeyValueStore(affects = { it == AcceptedTurnStore.KEY })
        val rig = Rig()
        rig.harness(file).use { h ->
            val work = existing(h, "work")
            file.failing = true
            val sent = chat(h, work)
            held(h, work)
            waitFor("the notice that the reply may be lost") { rig.limits().isNotEmpty() }
            assertTrue(rig.limits().single().let { it.startsWith("chat-") && it.endsWith("#limit@$work") })
            assertEquals("never sent again", 1, submits(h))
            h.fake.finishHeldTurn(work)
            val result = runBlocking { withTimeout(20_000) { sent.await() } }
            assertTrue("$result", result is ChatSendResult.Replied && result.text == "Done.")
            waitFor("the live reply alert") { rig.replies().isNotEmpty() }
            settle()
            assertEquals(1, rig.limits().size)
            assertEquals(1, rig.replies().size)
            assertEquals(1, submits(h))
        }
    }

    @Test
    fun `F2 a voice turn whose record cannot be saved says the reply may not be recoverable and is not sent again`() {
        val file = FailingKeyValueStore(affects = { it == AcceptedTurnStore.KEY })
        val rig = Rig()
        rig.harness(file).use { h ->
            val work = existing(h, "work")
            file.failing = true
            voice(h, "r-voice-lim1", work)
            held(h, work)
            waitFor("the notice that the reply may be lost") { rig.limits().isNotEmpty() }
            assertEquals(listOf("r-voice-lim1#limit@$work"), rig.limits())
            assertEquals(1, submits(h))
        }
    }

    // ---- F03: an alert identity is spent only by a delivery that happened ----

    @Test
    fun `L1 an alert the surface refused is shown by a later attempt and is then deduplicated for good`() {
        val file = InMemoryKeyValueStore()
        var ok = false
        val shownIds = Collections.synchronizedList(mutableListOf<String>())
        val alerts = ReplyAlerts(ReplyAlertLedger(file), { if (ok) { shownIds += it.identity; true } else false }, scope)
        val r = reply("t1#later1")
        assertEquals(ReplyAlertResult.NOT_SHOWN_HERE, runBlocking { alerts.dispatch(r) })
        ok = true
        assertEquals("the refused alert was not spent", ReplyAlertResult.SHOWN_HERE, runBlocking { alerts.dispatch(r) })
        assertEquals(ReplyAlertResult.DUPLICATE, runBlocking { alerts.dispatch(r) })
        assertEquals(listOf("t1#later1"), shownIds.toList())
        val restarted = ReplyAlerts(ReplyAlertLedger(file), { shownIds += it.identity; true }, scope)
        assertEquals("a shown alert stays deduplicated across a restart", ReplyAlertResult.DUPLICATE, runBlocking { restarted.dispatch(r) })
    }

    @Test
    fun `L2 a surface that throws leaves the alert unspent for a later attempt`() {
        var throwing = true
        val shownIds = Collections.synchronizedList(mutableListOf<String>())
        val alerts = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), { if (throwing) throw IllegalStateException("x") else { shownIds += it.identity; true } }, scope)
        val r = reply("t2#later1")
        assertEquals(ReplyAlertResult.NOT_SHOWN_HERE, runBlocking { alerts.dispatch(r) })
        throwing = false
        assertEquals(ReplyAlertResult.SHOWN_HERE, runBlocking { alerts.dispatch(r) })
        assertEquals(listOf("t2#later1"), shownIds.toList())
    }

    @Test
    fun `L3 a Watch link that could not be told and a Phone surface that refused leave the alert unspent`() {
        var linkUp = false
        var phoneUp = false
        val phone = Collections.synchronizedList(mutableListOf<String>())
        val link = object : PlaybackSink {
            override suspend fun play(audio: SpokenAudio, cue: com.rumi.hermesvoice.core.voice.PlaybackCue) = Unit
            override suspend fun deliverReplyAlert(alert: com.rumi.hermesvoice.core.notify.ReplyAlert): Boolean = linkUp
        }
        val alerts = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), { if (phoneUp) { phone += it.identity; true } else false }, scope)
        val r = reply("t3#later1", link, watch = true)
        assertEquals(ReplyAlertResult.NOT_SHOWN_HERE, runBlocking { alerts.dispatch(r) })
        linkUp = true
        assertEquals("the Watch is told on the next attempt", ReplyAlertResult.SENT_TO_WATCH, runBlocking { alerts.dispatch(r) })
        assertEquals(ReplyAlertResult.DUPLICATE, runBlocking { alerts.dispatch(r) })
        assertTrue(phone.isEmpty())
    }

    @Test
    fun `L4 a dispatch cancelled while the screen is being asked does not spend the alert`() {
        val presence = ConversationPresence().apply { setForeground(true); setShown(stored) }
        val asked = CountDownLatch(1)
        val shownIds = Collections.synchronizedList(mutableListOf<String>())
        val alerts = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), { shownIds += it.identity; true }, scope, presence)
        alerts.confirmDisplay = { asked.countDown(); awaitCancellation() }
        val r = reply("t4#later1")
        val job = scope.async { alerts.dispatch(r) }
        assertTrue(asked.await(10, TimeUnit.SECONDS))
        job.cancel()
        runBlocking { job.join() }
        alerts.confirmDisplay = { true }
        assertEquals("the retry decides afresh", ReplyAlertResult.SUPPRESSED_VISIBLE, runBlocking { alerts.dispatch(r) })
        alerts.confirmDisplay = { false }
        assertEquals("once decided it is deduplicated", ReplyAlertResult.DUPLICATE, runBlocking { alerts.dispatch(r) })
        assertTrue(shownIds.isEmpty())
    }

    @Test
    fun `L5 the ledger is written only after the alert was shown, never before`() {
        val file = FailingKeyValueStore()
        var atShow: String? = "unset"
        val alerts = ReplyAlerts(ReplyAlertLedger(file), { atShow = file.persisted(ReplyAlertLedger.KEY); true }, scope)
        assertEquals(ReplyAlertResult.SHOWN_HERE, runBlocking { alerts.dispatch(reply("t5#later1")) })
        assertNull("nothing was saved while the surface was still being asked: $atShow", atShow)
        assertTrue(file.persisted(ReplyAlertLedger.KEY)?.contains("t5#later1") == true)
    }

    @Test
    fun `L6 two attempts at the same identity at once show it once`() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val shows = AtomicInteger()
        val alerts = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), {
            shows.incrementAndGet(); inside.countDown(); release.await(10, TimeUnit.SECONDS); true
        }, scope)
        val r = reply("t6#later1")
        val first = scope.async { alerts.dispatch(r) }
        assertTrue(inside.await(10, TimeUnit.SECONDS))
        val second = runBlocking { alerts.dispatch(r) }
        release.countDown()
        assertEquals(ReplyAlertResult.SHOWN_HERE, runBlocking { first.await() })
        assertTrue("the concurrent attempt did not show it too: $second", second != ReplyAlertResult.SHOWN_HERE)
        assertEquals(1, shows.get())
    }

    // ---- F04 / F09 / F03 on the recovery path: the restart reconciler ----

    private fun lostProcessWithHeldTurn(rig: Rig, work: String, first: CoreHarness, start: () -> Unit) {
        start()
        held(first, work)
        process.cancel()
        first.stop()
    }

    @Test
    fun `R1 a server restart between acceptance and recovery is told to the user once, never as a reply, and the prompt is not sent again`() {
        val rig = Rig()
        val first = rig.harness()
        val work = existing(first, "work")
        lostProcessWithHeldTurn(rig, work, first) { chat(first, work) }
        first.fake.replayEpoch = "e2"
        first.fake.finishHeldTurn(work)
        rig.harness(first).use { second ->
            waitFor("the notice that the reply could not be recovered") { rig.notices().isNotEmpty() }
            settle(2_000)
            assertEquals("${rig.shown}", 1, rig.notices().size)
            assertTrue(rig.notices().single().let { it.startsWith("chat-") && it.endsWith("#notice@$work") })
            assertTrue("no reply was invented: ${rig.shown}", rig.replies().isEmpty())
            assertEquals(1, submits(second))
            second.stop()
            rig.harness(second).use { third ->
                settle(2_000)
                assertEquals("settled: not told again after another restart", 1, rig.notices().size)
                assertEquals(1, submits(third))
            }
        }
    }

    @Test
    fun `R2 events the server no longer holds are told to the user once for a voice turn too`() {
        val rig = Rig()
        val first = rig.harness()
        val work = existing(first, "work")
        lostProcessWithHeldTurn(rig, work, first) { voice(first, "r-voice-rec2", work) }
        first.fake.finishHeldTurn(work)
        first.fake.evictRing("rt-$work")
        rig.harness(first).use { second ->
            waitFor("the notice") { rig.notices().isNotEmpty() }
            settle(2_000)
            assertEquals(listOf("r-voice-rec2#notice@$work"), rig.notices())
            assertTrue(rig.replies().isEmpty())
            assertEquals(1, submits(second))
        }
    }

    @Test
    fun `R3 a recovered turn that ended without a complete answer is told to the user, not dropped silently`() {
        val rig = Rig()
        val first = rig.harness()
        val work = existing(first, "work", listOf(FakeHermesDashboard.interim("Working."), FakeHermesDashboard.complete("Partial", "interrupted")))
        lostProcessWithHeldTurn(rig, work, first) { chat(first, work) }
        first.fake.finishHeldTurn(work)
        rig.harness(first).use { second ->
            waitFor("the notice") { rig.notices().isNotEmpty() }
            settle(1_500)
            assertEquals(1, rig.notices().size)
            assertTrue(rig.replies().isEmpty())
            assertEquals(1, submits(second))
        }
    }

    @Test
    fun `R4 a recovered turn whose answer is empty is told to the user, not dropped silently`() {
        val rig = Rig()
        val first = rig.harness()
        val work = existing(first, "work", listOf(FakeHermesDashboard.complete("")))
        lostProcessWithHeldTurn(rig, work, first) { chat(first, work) }
        first.fake.finishHeldTurn(work)
        rig.harness(first).use { second ->
            waitFor("the notice") { rig.notices().isNotEmpty() }
            settle(1_500)
            assertEquals(1, rig.notices().size)
            assertTrue(rig.replies().isEmpty())
            assertEquals(1, submits(second))
        }
    }

    @Test
    fun `R5 a record of a conversation this app does not own is told to the user once and then ends`() {
        val rig = Rig()
        val first = rig.harness()
        val ghost = "20260101_130000_ghostgh"
        runBlocking { AcceptedTurnStore(first.store).begin(AcceptedTurn("chat-ghost-0001", AcceptedKind.TEXT, ghost, TurnAcceptance("rt-ghost", 7L, "e1", 0), System.currentTimeMillis())) }
        first.stop()
        rig.harness(first).use { second ->
            waitFor("the notice") { rig.notices().isNotEmpty() }
            settle(1_500)
            assertEquals(listOf("chat-ghost-0001#notice@$ghost"), rig.notices())
            assertTrue(rig.replies().isEmpty())
            assertEquals("nothing was sent", 0, submits(second))
        }
    }

    @Test
    fun `R6 a reply for a conversation archived before recovery is not delivered as a reply but is told to the user`() {
        val rig = Rig()
        val first = rig.harness()
        val work = existing(first, "work")
        lostProcessWithHeldTurn(rig, work, first) {
            chat(first, work)
            held(first, work)
            runBlocking { first.core.sessions.setArchived(work, true) }
        }
        first.fake.finishHeldTurn(work)
        rig.harness(first).use { second ->
            waitFor("the notice") { rig.notices().isNotEmpty() }
            settle(1_500)
            assertEquals(1, rig.notices().size)
            assertTrue("an archived conversation receives no reply alert: ${rig.shown}", rig.replies().isEmpty())
            assertEquals(1, submits(second))
        }
    }

    @Test
    fun `R7 a recovered reply whose alert the surface refused stays owed and is delivered by a later reconcile, once`() {
        val rig = Rig()
        val first = rig.harness()
        val work = existing(first, "work")
        lostProcessWithHeldTurn(rig, work, first) { chat(first, work) }
        first.fake.finishHeldTurn(work)
        rig.accept = false
        rig.harness(first).use { second ->
            waitFor("the refused attempt") { rig.attempts.get() >= 1 }
            settle(1_000)
            assertTrue(rig.shown.isEmpty())
            rig.accept = true
            second.core.reconcileAccepted()
            waitFor("the reply alert after the surface works again") { rig.replies().isNotEmpty() }
            settle(2_000)
            assertEquals("${rig.shown}", 1, rig.replies().size)
            assertEquals(1, submits(second))
            second.core.reconcileAccepted()
            settle(1_500)
            assertEquals("delivered once only", 1, rig.replies().size)
        }
    }

    @Test
    fun `R8 a live text reply whose alert the surface refused stays owed and is delivered by a later reconcile, once`() {
        val rig = Rig()
        rig.accept = false
        rig.harness().use { h ->
            val work = existing(h, "work")
            val sent = chat(h, work)
            held(h, work)
            h.fake.finishHeldTurn(work)
            val result = runBlocking { withTimeout(20_000) { sent.await() } }
            assertTrue("$result", result is ChatSendResult.Replied)
            waitFor("the refused attempt") { rig.attempts.get() >= 1 }
            settle(1_000)
            rig.accept = true
            h.core.reconcileAccepted()
            waitFor("the reply alert after the surface works again") { rig.replies().isNotEmpty() }
            settle(2_000)
            assertEquals("${rig.shown}", 1, rig.replies().size)
            assertEquals(1, submits(h))
        }
    }

    // ---- direct reconciler: a handoff that fails or is cancelled ----

    private class OneTurnPort(val events: () -> List<RecipientEvent>, val hang: Boolean = false) : HermesConversationPort {
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) = throw UnsupportedOperationException()
        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn = throw UnsupportedOperationException()
        override suspend fun reattach(storedSessionId: String, acceptance: TurnAcceptance): SubmittedTurn = object : SubmittedTurn {
            override val submitStatus = "streaming"
            override val attributable = true
            override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
                if (hang) awaitCancellation()
                for (event in events()) onEvent(event)
            }

            override fun release() {}
        }
    }

    private fun idleReconciler(turns: AcceptedTurnStore, id: String) =
        waitFor("the reconciler to finish with $id") { turns.claimForReconcile(id).also { if (it) turns.reconciled(id) } }

    @Test
    fun `D1 a hand-off that throws leaves the accepted turn owed and does not lose the reply`() {
        val turns = AcceptedTurnStore(InMemoryKeyValueStore())
        runBlocking { turns.begin(turn("chat-d1")) }
        turns.idle("chat-d1")
        val port = OneTurnPort({ listOf(RecipientEvent.Complete("An answer", "complete")) })
        val calls = AtomicInteger()
        val reconciler = AcceptedTurnReconciler(turns, port, { calls.incrementAndGet(); throw IllegalStateException("surface failed") }, { false }, scope)
        reconciler.reconcile()
        waitFor("the hand-off attempt") { calls.get() >= 1 }
        idleReconciler(turns, "chat-d1")
        assertEquals("still owed", listOf("chat-d1"), turns.pending().map { it.id })
    }

    @Test
    fun `D2 a cancelled recovery leaves the accepted turn owed`() {
        val turns = AcceptedTurnStore(InMemoryKeyValueStore())
        runBlocking { turns.begin(turn("chat-d2")) }
        turns.idle("chat-d2")
        val own = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reconciler = AcceptedTurnReconciler(turns, OneTurnPort({ emptyList() }, hang = true), {}, { false }, own)
        reconciler.reconcile()
        settle(300)
        own.cancel()
        settle(300)
        assertEquals(listOf("chat-d2"), turns.pending().map { it.id })
    }

    @Test
    fun `D3 a normal hand-off settles the accepted turn after the reply was handed over`() {
        val turns = AcceptedTurnStore(InMemoryKeyValueStore())
        runBlocking { turns.begin(turn("chat-d3")) }
        turns.idle("chat-d3")
        val handed = Collections.synchronizedList(mutableListOf<String>())
        val reconciler = AcceptedTurnReconciler(turns, OneTurnPort({ listOf(RecipientEvent.Complete("An answer", "complete")) }),
            { handed += it.identity }, { false }, scope)
        reconciler.reconcile()
        waitFor("the hand-off") { handed.isNotEmpty() }
        idleReconciler(turns, "chat-d3")
        assertEquals(listOf("chat-d3#reply"), handed.toList())
        assertTrue(turns.pending().isEmpty())
    }

    @Test
    fun F04_R10b_unknownOriginalOutputInAcceptedJournalNeverAuthorizesSound() {
        val turns=AcceptedTurnStore(InMemoryKeyValueStore())
        runBlocking { turns.begin(turn("chat-private-unknown")) }
        turns.idle("chat-private-unknown")
        val delivered=CompletableDeferred<FinalReply>()
        val port=OneTurnPort({ listOf(RecipientEvent.Complete("canonical recovered body","complete")) })
        val reconciler=AcceptedTurnReconciler(turns,port,{ delivered.complete(it) },{ false },scope)
        reconciler.reconcile()
        val reply=runBlocking { withTimeout(5_000) { delivered.await() } }
        assertEquals("canonical recovered body",reply.text)
        assertEquals("chat-private-unknown#reply",reply.identity)
        assertTrue("the accepted journal has no original privacy policy; current no-headset is not speaker permission",reply.quiet)
    }

    @Test
    fun `D4 the recovered answer text is the delivered answer and nothing else`() {
        val turns = AcceptedTurnStore(InMemoryKeyValueStore())
        runBlocking { turns.begin(turn("chat-d4")) }
        turns.idle("chat-d4")
        val text = CompletableDeferred<String?>()
        val reconciler = AcceptedTurnReconciler(turns, OneTurnPort({ listOf(RecipientEvent.Interim("thinking"), RecipientEvent.Complete("The answer", "complete")) }),
            { text.complete(it.text) }, { false }, scope)
        reconciler.reconcile()
        assertEquals("The answer", runBlocking { withTimeout(10_000) { text.await() } })
    }
}
