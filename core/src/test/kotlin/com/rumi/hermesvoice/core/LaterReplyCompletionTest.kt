package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchAckRegistry
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.resume

/**
 * Review b32 (B32-R1, B32-R2): the end of a later reply is recorded from the device's own signal
 * ([PlaybackSink.playConfirmed]: the Phone player's completion, the Watch's accepted PLAYED ack),
 * under the ownership lock, BEFORE the sink resumes its cancellable caller; and a microphone claim
 * is given back on every waiter end. The later-reply scope runs on one thread that each R1 test
 * demonstrably blocks (blockerEntered) before the end is signalled, the parent's deterministic
 * schedule, so a recording, a newer request or a Stop lands in exactly that window.
 */
class LaterReplyCompletionTest {
    private val later: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val phoneSpeaker = AtomicInteger()
    private val listener = object : VoiceTurnListener {
        override fun onLaterReply(turnId: String, played: Boolean, detail: String) {
            later += "$turnId:${if (played) "played" else "not_played"}:$detail"
        }

        override fun onPlayed(cue: PlaybackCue) {
            if (cue.later) played += cue.turnId
        }
    }

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

    private fun waitFor(what: String, ms: Long = 15_000, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(ms) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what (later=$later)")
        }
    }

    private fun settle(ms: Long) = runBlocking { delay(ms) }

    /** Blocks [pool]'s only thread until [gate] opens; returns once it really is blocked. */
    private fun block(pool: ExecutorService, gate: CountDownLatch) {
        val entered = CountDownLatch(1)
        pool.execute { entered.countDown(); gate.await(10, TimeUnit.SECONDS) }
        check(entered.await(5, TimeUnit.SECONDS))
    }

    private fun harness(scope: CoroutineScope) = CoreHarness(laterScope = scope, voiceListener = listener,
        laterSpeaker = { device, on -> if (device == VoiceOrigin.PHONE) phoneSpeaker.addAndGet(if (on) 1 else -1) })
        .also { it.laterConsent.enabled = true }

    /**
     * Shaped like PhoneSpeakerSink: the audio plays on a player thread inside withContext +
     * suspendCancellableCoroutine; its completion callback (the player thread) calls [finished]
     * only while the call still waits, then resumes; a cancellation stops it ([cut]). [beforeEnd]
     * runs on the player thread right before the completion callback, [afterEnd] right after it.
     */
    private inner class PhoneShapedSink(private val playerPool: ExecutorService) : PlaybackSink {
        val heardToEnd = AtomicInteger()
        val cut = AtomicInteger()
        @Volatile var active = false
        @Volatile var beforeEnd: (() -> Unit)? = null
        @Volatile var afterEnd: (() -> Unit)? = null
        @Volatile var confirmEvenIfStopped = false

        override suspend fun play(audio: com.rumi.hermesvoice.core.SpokenAudio, cue: PlaybackCue) = playConfirmed(audio, cue) {}

        override suspend fun playConfirmed(audio: com.rumi.hermesvoice.core.SpokenAudio, cue: PlaybackCue, finished: () -> Unit) {
            if (!cue.later) return
            try {
                withContext(playerPool.asCoroutineDispatcher()) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        active = true
                        cont.invokeOnCancellation { cut.incrementAndGet() }
                        playerPool.execute {
                            val hook = beforeEnd.also { beforeEnd = null }
                            hook?.invoke()
                            if (cont.isActive) {
                                heardToEnd.incrementAndGet()
                                finished()
                                cont.resume(Unit)
                            } else if (confirmEvenIfStopped) {
                                finished() // a stale completion after a stop: must not count
                            }
                            afterEnd.also { afterEnd = null }?.invoke()
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) { delay(100) } // releasing the player
                active = false
            }
        }
    }

    /**
     * [arm] runs BEFORE the later reply is pushed: the sink reads its hooks when the reply's audio executes, which can be right
     * after the push, so arming afterwards raced it (the r1-full "phone - ..." failures).
     */
    private fun phoneScenario(
        name: String,
        arm: (CoreHarness, PhoneShapedSink, ExecutorService, CountDownLatch) -> Unit = { _, _, _, _ -> },
        run: (CoreHarness, PhoneShapedSink, ExecutorService, CountDownLatch) -> Unit,
    ) {
        val aPool = Executors.newSingleThreadExecutor()
        val playerPool = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + aPool.asCoroutineDispatcher())
        val gate = CountDownLatch(1)
        try {
            harness(scope).use { h ->
                val work = existing(h, "work", "Started.")
                existing(h, "home", "Home.")
                routeTo(h, "work")
                val sink = PhoneShapedSink(playerPool)
                runBlocking { h.core.orchestrator.run(VoiceTurnRequest("$name-0", VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", sink)) }
                arm(h, sink, aPool, gate)
                h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Heard once."))
                run(h, sink, aPool, gate)
            }
        } finally {
            gate.countDown(); scope.cancel(); aPool.shutdownNow(); playerPool.shutdownNow()
        }
    }

    // ── R1, Phone-shaped sink through the completion seam ────────────────────────────────────

    private val endedClaim = CountDownLatch(1)
    private val endedNewer = CountDownLatch(1)
    private val stoppedEarly = CountDownLatch(1)
    private val earlyClaims: MutableList<com.rumi.hermesvoice.core.voice.MicrophoneClaim> = Collections.synchronizedList(mutableListOf())

    @Test
    fun `phone - a recording claim after the player's completion neither stops nor replays it, and opens only after the teardown`() =
        phoneScenario("c-phone-claim", arm = { _, sink, aPool, gate ->
            sink.beforeEnd = { block(aPool, gate) }
            sink.afterEnd = { endedClaim.countDown() }
        }) { h, sink, _, gate ->
            assertTrue(endedClaim.await(15, TimeUnit.SECONDS))
            val claim = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            val opened = CompletableDeferred<Boolean>()
            val now = claim.whenSpeakerStopped(CoroutineScope(Dispatchers.Default)) { ok -> opened.complete(ok && !sink.active && phoneSpeaker.get() == 0) }
            assertNull("the teardown is still to come: the microphone waits", now)
            settle(300)
            assertFalse(opened.isCompleted)
            gate.countDown()
            assertTrue("opened after the player was released and the speaker let go", runBlocking { withTimeout(5_000) { opened.await() } })
            waitFor("reported") { later.isNotEmpty() }
            claim.release()
            settle(1_500)
            assertEquals(1, sink.heardToEnd.get())
            assertEquals(0, sink.cut.get())
            assertEquals(listOf("c-phone-claim-0:played:phone"), later.toList())
            assertEquals("onPlayed once", listOf("c-phone-claim-0"), played.toList())
        }

    @Test
    fun `phone - a newer request at the same boundary leaves it played, and its own reply plays`() =
        phoneScenario("c-phone-newer", arm = { _, sink, aPool, gate ->
            sink.beforeEnd = { block(aPool, gate) }
            sink.afterEnd = { endedNewer.countDown() }
        }) { h, sink, _, gate ->
            assertTrue(endedNewer.await(15, TimeUnit.SECONDS))
            routeTo(h, "home")
            val newer = CoroutineScope(Dispatchers.Default).async {
                h.core.orchestrator.run(VoiceTurnRequest("c-phone-newer-1", VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", sink))
            }
            settle(1_200)
            gate.countDown()
            runBlocking { withTimeout(15_000) { newer.await() } }
            waitFor("reported") { later.isNotEmpty() }
            settle(800)
            assertEquals(listOf("c-phone-newer-0:played:phone"), later.toList())
            assertEquals(1, sink.heardToEnd.get())
        }

    @Test
    fun `phone - a Stop or switching off at the same boundary reports played, never stopped`() {
        for (off in listOf(false, true)) {
            later.clear()
            played.clear()
            val ended = CountDownLatch(1)
            phoneScenario("c-phone-stop-$off", arm = { _, sink, aPool, gate ->
                sink.beforeEnd = { block(aPool, gate) }
                sink.afterEnd = { ended.countDown() }
            }) { h, _, _, gate ->
                assertTrue(ended.await(15, TimeUnit.SECONDS))
                if (off) h.laterConsent.enabled = false
                h.core.orchestrator.stopFollowing()
                gate.countDown()
                waitFor("reported") { later.isNotEmpty() }
                settle(800)
                assertEquals(listOf("c-phone-stop-$off-0:played:phone"), later.toList())
                assertEquals(listOf("c-phone-stop-$off-0"), played.toList())
            }
        }
    }

    @Test
    fun `phone - a Stop before the player completed reports stopped, and a stale completion after it never counts`() =
        phoneScenario("c-phone-early-stop", arm = { h, sink, _, _ ->
            sink.confirmEvenIfStopped = true
            sink.beforeEnd = { h.core.orchestrator.stopFollowing(); stoppedEarly.countDown() }
        }) { _, sink, _, _ ->
            assertTrue(stoppedEarly.await(15, TimeUnit.SECONDS))
            waitFor("reported") { later.isNotEmpty() }
            settle(800)
            assertEquals(listOf("c-phone-early-stop-0:not_played:not played: stopped"), later.toList())
            assertEquals(0, sink.heardToEnd.get())
            assertTrue("never reported as played", played.isEmpty())
        }

    @Test
    fun `phone - a recording that claims before the player completed stops it, and it plays again once after the recording`() =
        phoneScenario("c-phone-early-claim", arm = { h, sink, _, _ ->
            sink.confirmEvenIfStopped = true // a late completion of the stopped player must not count either
            sink.beforeEnd = { earlyClaims += h.ownership.claimMicrophone(VoiceOrigin.PHONE) }
        }) { _, sink, _, _ ->
            val claims = earlyClaims
            waitFor("claimed") { claims.isNotEmpty() }
            settle(800)
            assertTrue("not reported: it will be played again", later.isEmpty())
            assertEquals(1, sink.cut.get())
            claims.single().release()
            waitFor("played again") { later.isNotEmpty() }
            settle(500)
            assertEquals(1, sink.heardToEnd.get())
            assertEquals(listOf("c-phone-early-claim-0:played:phone"), later.toList())
            assertEquals(listOf("c-phone-early-claim-0"), played.toList())
        }

    // ── R1, the real WatchPlaybackSink and ACK registry ──────────────────────────────────────

    private inner class BlockedAckWatch(val h: CoreHarness, val aPool: ExecutorService) : WatchTransport {
        override val nodeId = "watch-node-1"
        val gate = CountDownLatch(1)
        val ackAccepted = CountDownLatch(1)
        val playedLater = AtomicInteger()
        override suspend fun sendMessage(path: String, bytes: ByteArray) {}
        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            if (!play.later || playedLater.incrementAndGet() > 1) {
                h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
                return
            }
            Thread {
                Thread.sleep(150)
                block(aPool, gate)
                // Another node's ack, a duplicate after the real one: neither counts.
                check(!h.core.watchAcks.onPlayedMessage("other-node", PlayedAck(play.turnId, play.sequence, true).encode()))
                check(h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode()))
                check(!h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode()))
                ackAccepted.countDown()
            }.start()
        }
    }

    private fun watchTurn(h: CoreHarness, watch: WatchTransport, turnId: String) = runBlocking {
        val frame = WatchTurnUpload(turnId, TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav()).toFrame().encode()
        h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath(turnId), frame, watch)
    }

    private fun watchScenario(name: String, run: (CoreHarness, BlockedAckWatch) -> Unit) {
        val aPool = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + aPool.asCoroutineDispatcher())
        try {
            harness(scope).use { h ->
                val work = existing(h, "work", "Started.")
                existing(h, "home", "Home.")
                routeTo(h, "work")
                val watch = BlockedAckWatch(h, aPool)
                watchTurn(h, watch, "$name-0")
                h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Watch heard it."))
                assertTrue(watch.ackAccepted.await(15, TimeUnit.SECONDS))
                run(h, watch)
                waitFor("reported") { later.isNotEmpty() }
                settle(800)
                assertEquals(listOf("$name-0:played:watch"), later.toList())
                assertEquals("never sent again", 1, watch.playedLater.get())
                assertEquals(listOf("$name-0"), played.toList())
            }
        } finally {
            scope.cancel(); aPool.shutdownNow()
        }
    }

    @Test
    fun `watch - a newer request right after the PLAYED ack was accepted leaves it reported played`() = watchScenario("c-watch-newer") { h, watch ->
        routeTo(h, "home")
        val newer = CoroutineScope(Dispatchers.Default).async { watchTurn(h, watch, "c-watch-newer-1") }
        settle(1_500)
        watch.gate.countDown()
        runBlocking { withTimeout(15_000) { newer.await() } }
    }

    @Test
    fun `watch - a Stop right after the PLAYED ack was accepted leaves it reported played`() = watchScenario("c-watch-stop") { h, watch ->
        h.core.orchestrator.stopFollowing()
        watch.gate.countDown()
    }

    @Test
    fun `watch ACK registry - only the exact node's successful ack confirms, once, before the waiter resumes`() {
        val registry = WatchAckRegistry()
        val confirmed = AtomicInteger()
        var completedWhenConfirmed: Boolean? = null
        lateinit var waiter: CompletableDeferred<PlayedAck>
        waiter = registry.expect("t-1", 2, "node-a") { confirmed.incrementAndGet(); completedWhenConfirmed = waiter.isCompleted }
        assertFalse("another node", registry.onPlayedMessage("node-b", PlayedAck("t-1", 2, true).encode()))
        assertFalse("another sequence", registry.onPlayedMessage("node-a", PlayedAck("t-1", 3, true).encode()))
        assertEquals(0, confirmed.get())
        assertTrue(registry.onPlayedMessage("node-a", PlayedAck("t-1", 2, true).encode()))
        assertEquals(1, confirmed.get())
        assertEquals("confirmed before the waiter could resume", false, completedWhenConfirmed)
        assertFalse("duplicate", registry.onPlayedMessage("node-a", PlayedAck("t-1", 2, true).encode()))
        assertEquals(1, confirmed.get())
        // BUSY or a failure is not a confirmation; a stopped (forgotten) wait ignores a late PLAYED.
        val busy = AtomicInteger()
        registry.expect("t-2", 1, "node-a") { busy.incrementAndGet() }
        assertTrue(registry.onPlayedMessage("node-a", PlayedAck("t-2", 1, false, PlayedAck.BUSY_RECORDING).encode()))
        registry.expect("t-3", 1, "node-a") { busy.incrementAndGet() }
        assertTrue(registry.onPlayedMessage("node-a", PlayedAck("t-3", 1, false, "watch player 1/-1").encode()))
        registry.expect("t-4", 1, "node-a") { busy.incrementAndGet() }
        registry.forget("t-4", 1)
        assertFalse(registry.onPlayedMessage("node-a", PlayedAck("t-4", 1, true).encode()))
        assertEquals(0, busy.get())
    }

    // ── R2, the claim's waiter ────────────────────────────────────────────────────────────────

    private fun withPlayingReply(block: (CoreHarness, AtomicBoolean) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            harness(scope).use { h ->
                val work = existing(h, "work", "Started.")
                routeTo(h, "work")
                val hold = CompletableDeferred<Unit>()
                val audible = AtomicBoolean(false)
                val firstTime = AtomicBoolean(true)
                val sink = PlaybackSink { _, cue ->
                    // The first playback is long (until stopped); a replay afterwards is short.
                    if (!cue.later || !firstTime.getAndSet(false)) return@PlaybackSink
                    audible.set(true)
                    try { hold.await() } finally { withContext(NonCancellable) { delay(300) }; audible.set(false) }
                }
                runBlocking { h.core.orchestrator.run(VoiceTurnRequest("c-r2-0", VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", sink)) }
                h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Playing."))
                waitFor("playing") { audible.get() }
                block(h, audible)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a waiter on an already cancelled scope never opens and gives the claim back`() = withPlayingReply { h, audible ->
        val gone = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { it.cancel() }
        val claim = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
        val opened = AtomicBoolean(false)
        assertNull(claim.whenSpeakerStopped(gone) { opened.set(true) })
        waitFor("stopped") { !audible.get() }
        settle(500)
        assertFalse(opened.get())
        assertFalse("given back", claim.held)
        assertFalse(h.ownership.microphoneClaimed(VoiceOrigin.PHONE))
        // The reply it stopped is played again, so the Phone isn't wedged.
        waitFor("played again") { later.isNotEmpty() }
    }

    @Test
    fun `a scope cancelled while it waits, or an opening that throws, gives the claim back - a valid opening keeps it`() = withPlayingReply { h, audible ->
        val waiting = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
        val firstOpened = AtomicBoolean(false)
        first.whenSpeakerStopped(waiting) { firstOpened.set(true) }
        waiting.cancel()
        val failing = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
        val second = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
        second.whenSpeakerStopped<Unit>(failing) { throw IllegalStateException("recorder failed") }
        val third = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
        val thirdOpened = CompletableDeferred<Boolean>()
        third.whenSpeakerStopped(failing) { ok -> thirdOpened.complete(ok) }
        waitFor("stopped") { !audible.get() }
        assertTrue(runBlocking { withTimeout(5_000) { thirdOpened.await() } })
        settle(300)
        assertFalse(firstOpened.get())
        assertFalse(first.held)
        assertFalse(second.held)
        assertTrue("a recorder that opened keeps its claim", third.held)
        // Nothing playing any more: the synchronous path behaves the same.
        val fourth = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
        assertTrue(runCatching { fourth.whenSpeakerStopped<Unit>(failing) { throw IllegalStateException("recorder failed") } }.isFailure)
        assertFalse(fourth.held)
        third.release()
        failing.cancel()
    }
}

/** B32-R3/R4: the user-visible text and the CPU hold say what the code does. */
class LaterReplyTruthfulTextTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(path: String) = File(root, path).readText()

    /** The disclosure now lives behind the setting's ⓘ dialog (SettingsHelp); the screen renders exactly this text. */
    private fun settingsWarning(): String {
        val src = source("phone/src/main/kotlin/com/rumi/hermesvoice/phone/MainActivity.kt")
        assertTrue("the screen shows the later-reply help topic", src.contains("SettingsHelp.laterReplies("))
        return com.rumi.hermesvoice.core.settings.SettingsHelp.laterReplies(30).text
    }

    @Test
    fun `the consent text promises waiting only on the device a reply will play on, and keeps the disclosures`() {
        val text = settingsWarning()
        assertFalse(text.contains("this phone or the Watch records"))
        assertTrue(text, text.contains("the device it will play on"))
        assertTrue(text, text.contains("may play on the other device while one records"))
        assertTrue(text, text.contains("Off by default") && text.contains("isn't restored from a backup or moved to a new phone"))
        assertTrue(text, text.contains("from another Hermes app or the dashboard"))
        assertTrue(text, text.contains("background relay's Stop") && text.contains("background listening's Stop"))
    }

    @Test
    fun `the stated bounds are the code's - a free speaker within 10 minutes, no total time cut after it begins - and 1 plus 4 per follow`() {
        val text = settingsWarning()
        assertFalse(text.contains("must start within 10 minutes"))
        assertTrue(text, text.contains("within 10 minutes of arriving"))
        assertTrue(text, text.contains("no total time cuts a long reply"))
        val readme = source("README.md")
        assertFalse(readme.contains("must **start** playing within **10 minutes of its arrival**"))
        assertFalse(readme.contains("at most 4 wait per\n  followed request"))
        assertTrue(readme.contains("one being prepared or played plus 4 waiting"))
        assertTrue(readme.contains("may only **begin within 10 minutes of its arrival**"))
        assertTrue(readme.contains("is **not cut by\n  a total time**"))
        assertTrue(readme.contains("It does **not** wait for the other device"))
        assertEquals(10 * 60_000L, com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator.LATER_DEFER_MAX_MS)
        // v19 self-check R1: the former 2 min synthesis, 5 min playback AND the per-piece 10 min synthesis totals are all gone
        // (migrated from `ChunkedSpeech.STALL_MS == 10 min`, whose removal is proven behaviourally by SynthesisNoCutoffTest).
        assertFalse(readme.contains("10 minutes without a finished piece"))
        assertFalse(readme.contains("makes no progress for 10 minutes"))
        assertEquals(4, com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator.LATER_QUEUE_MAX)
        assertEquals(8, com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator.LATER_PENDING_MAX)
    }

    @Test
    fun `the per-unit CPU hold covers one piece's window close and playback, never its synthesis - 7 min 5 s, renewed per piece - and the README says so`() {
        assertTrue(source("phone/src/main/kotlin/com/rumi/hermesvoice/phone/PhoneApp.kt").contains("LATER_REPLY_HOLD_MS = 7 * 60_000L + 5_000L"))
        assertTrue(source("README.md").contains("at most 7 minutes 5 seconds, renewed per piece"))
        // v19 self-check R1: migrated from "covers one piece's synthesis" - waiting for generation holds nothing (proved behaviourally by ChunkedReplyGapTest).
        assertFalse(source("README.md").contains("(the synthesis of\n  one piece, or"))
        assertTrue(source("README.md").contains("Waiting for the server to generate a piece's speech holds no CPU, microphone or audio focus"))
    }

    @Test
    fun `both production sinks confirm the end from their own signal, before resuming`() {
        val phone = source("phone/src/main/kotlin/com/rumi/hermesvoice/phone/PhoneAudio.kt")
        val completion = phone.substringAfter("onCompleted = {").substringBefore("onError = {")
        assertTrue(completion, Regex("if \\(continuation\\.isActive\\) \\{\\s+finished\\(\\)\\s+continuation\\.resume\\(Unit\\)").containsMatchIn(completion))
        assertFalse("never from the error, focus-loss or cancellation paths", phone.substringAfter("onError = {").contains("finished()") ||
            phone.substringBefore("onCompleted = {").substringAfter("override suspend fun playConfirmed").contains("finished()"))
        val watch = source("core/src/main/kotlin/com/rumi/hermesvoice/core/watchlink/PhoneWatchRelay.kt")
        assertTrue(watch.contains("val waiter = acks.expectPlayback(cue.turnId, wireSequence, transport.nodeId, played = finished)"))
        assertTrue(watch.contains("if (ack.ok) runCatching { waiter.played?.invoke() }\n        return waiter.wait.ack.complete(ack)"))
        assertTrue(source("core/src/main/kotlin/com/rumi/hermesvoice/core/voice/VoiceTurnOrchestrator.kt")
            .contains("if (last) markPlayed(slot, u, fullCue, returned = false) else chunked.played(index)"))
    }
}
