package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.activity.ActivityEvent
import com.rumi.hermesvoice.core.activity.ActivityLabels
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gateway's tool/status/interim frames of an in-flight typed request, as the production wiring (real WebSocket against the
 * contract double) turns them into ONE replace-in-place activity slot per request, tied to its own conversation.
 */
class ActivityWireTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rig = AlertRig(scope)
    private val h = rig.harness(consent = false)
    private val seen = CopyOnWriteArrayList<ActivityEvent>()
    private val collector: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { h.core.activity.events.collect { seen += it } }

    @After fun tearDown() {
        collector.cancel()
        h.close()
        scope.cancel()
    }

    private fun conversation(name: String) = runBlocking { h.core.sessions.createConversation(name, name.lowercase(), "") }.storedSessionId
    private fun held(vararg ids: String) = ids.forEach { h.fake.scripts[it] = { emptyList() } }
    private fun tool(type: String, name: String, context: String? = null, extra: JSONObject.() -> Unit = {}): Pair<String, JSONObject> =
        type to JSONObject().put("tool_id", "t-$name").put("name", name).apply { if (context != null) put("context", context) }.apply(extra)

    private fun waitFor(what: String, limitMs: Long = 10_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + limitMs
        while (!check()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what; seen=$seen")
            Thread.sleep(15)
        }
    }

    private fun settle() = Thread.sleep(400)
    private fun updates(key: String) = seen.filterIsInstance<ActivityEvent.Update>().filter { it.key == key }.map { it.label }

    @Test
    fun `a typed request opens one slot, two tool updates replace it, and the final reply ends the same slot once`(): Unit = runBlocking {
        val id = conversation("Work")
        held(id)
        val result = async(Dispatchers.IO) { h.core.chat.send(id, "do the thing") }
        waitFor("begin") { seen.any { it is ActivityEvent.Begin } }
        val begin = seen.filterIsInstance<ActivityEvent.Begin>().single()
        assertEquals(id, begin.sessionId)
        assertEquals("the waiting state is the snapshot before any tool event", listOf(begin.key), h.core.activity.snapshot().bubbles.map { it.key })
        assertEquals(ActivityLabels.WAITING, h.core.activity.snapshot().bubbles.single().label)

        h.fake.pushLaterTurn(id, tool("tool.start", "execute_code", "run a script"), withStart = false)
        waitFor("first update") { updates(begin.key).isNotEmpty() }
        h.fake.pushLaterTurn(id, tool("tool.start", "search_files", "*.kt in core"), withStart = false)
        waitFor("second update") { updates(begin.key).size == 2 }
        assertEquals("one slot, latest label", listOf(ActivityLabels.tool("search_files", "*.kt in core")), listOf(h.core.activity.snapshot().bubbles.single().label))
        assertTrue(updates(begin.key)[0].startsWith("🐍"))
        assertTrue(rig.shown.isEmpty())

        h.fake.pushLaterTurn(id, FakeHermesDashboard.complete("All done."), withStart = false)
        val replied = result.await() as ChatSendResult.Replied
        assertEquals("All done.", replied.text)
        waitFor("end") { seen.any { it is ActivityEvent.End } }
        assertEquals(ActivityEvent.End(begin.key, replied = true), seen.last())
        assertEquals(1, seen.count { it is ActivityEvent.End })
        assertTrue("no active slot remains", h.core.activity.snapshot().bubbles.isEmpty())
        waitFor("exactly one final alert") { rig.shown.size == 1 }
        settle()
        assertEquals(1, rig.shown.size)
    }

    @Test
    fun `tool completion keeps the slot working and its raw output never reaches a label`(): Unit = runBlocking {
        val id = conversation("Work")
        held(id)
        val result = async(Dispatchers.IO) { h.core.chat.send(id, "go") }
        waitFor("begin") { seen.any { it is ActivityEvent.Begin } }
        val key = seen.filterIsInstance<ActivityEvent.Begin>().single().key
        h.fake.pushLaterTurn(id,
            tool("tool.start", "terminal", "curl -H 'Authorization: Bearer sk-live-0123456789abcdef0123456789abcdef' https://x.test"),
            tool("tool.complete", "terminal") { put("result", "TOP-SECRET-OUTPUT").put("summary", "TOP-SECRET-SUMMARY").put("args", JSONObject().put("command", "cat /etc/shadow")) },
            "reasoning.delta" to JSONObject().put("text", "PRIVATE-THOUGHT"), withStart = false)
        waitFor("completion label") { updates(key).size >= 2 }
        settle()
        val all = seen.filterIsInstance<ActivityEvent.Update>().map { it.label } + h.core.activity.snapshot().bubbles.map { it.label }
        assertTrue(all.toString(), all.none { it.contains("SECRET") || it.contains("PRIVATE-THOUGHT") || it.contains("shadow") || it.contains("sk-live") || it.contains("Bearer") })
        assertEquals(ActivityLabels.WORKING, h.core.activity.snapshot().bubbles.single().label)
        h.fake.pushLaterTurn(id, FakeHermesDashboard.complete("ok"), withStart = false)
        result.await()
    }

    @Test
    fun `status and a conversational interim replace the slot and the interim is still the same interim`(): Unit = runBlocking {
        val id = conversation("Work")
        held(id)
        val result = async(Dispatchers.IO) { h.core.chat.send(id, "go") }
        waitFor("begin") { seen.any { it is ActivityEvent.Begin } }
        val key = seen.filterIsInstance<ActivityEvent.Begin>().single().key
        h.fake.pushLaterTurn(id, "status.update" to JSONObject().put("kind", "compress").put("text", "Compressing context…"), withStart = false)
        waitFor("status") { updates(key).contains("ℹ️ Compressing context…") }
        h.fake.pushLaterTurn(id, FakeHermesDashboard.interim("Checking the file now"), withStart = false)
        waitFor("interim") { updates(key).contains("💬 Checking the file now") }
        assertEquals("💬 Checking the file now", h.core.activity.snapshot().bubbles.single().label)
        h.fake.pushLaterTurn(id, FakeHermesDashboard.complete("done"), withStart = false)
        result.await()
    }

    @Test
    fun `a replayed identical frame changes nothing and a late frame after the final cannot resurrect the slot`(): Unit = runBlocking {
        val id = conversation("Work")
        held(id)
        val result = async(Dispatchers.IO) { h.core.chat.send(id, "go") }
        waitFor("begin") { seen.any { it is ActivityEvent.Begin } }
        val key = seen.filterIsInstance<ActivityEvent.Begin>().single().key
        h.fake.pushLaterTurn(id, tool("tool.start", "read_file", "notes.md"), withStart = false)
        waitFor("update") { updates(key).size == 1 }
        h.fake.pushLaterTurn(id, tool("tool.start", "read_file", "notes.md"), tool("tool.start", "read_file", "notes.md"), withStart = false)
        settle()
        assertEquals("duplicates are not new activity", 1, updates(key).size)
        h.fake.pushLaterTurn(id, FakeHermesDashboard.complete("final text"), withStart = false)
        result.await()
        waitFor("end") { seen.any { it is ActivityEvent.End } }
        val before = seen.toList()
        h.fake.pushLaterTurn(id, tool("tool.start", "search_files", "late"), withStart = false)
        h.fake.pushLaterTurn(id, tool("tool.start", "search_files", "replayed"))
        settle()
        assertEquals("nothing after the end", before, seen.toList())
        assertTrue(h.core.activity.snapshot().bubbles.isEmpty())
    }

    @Test
    fun `two conversations in flight keep separate slots and a frame for one never reaches the other`(): Unit = runBlocking {
        val a = conversation("Alpha")
        val b = conversation("Bravo")
        held(a, b)
        val ra = async(Dispatchers.IO) { h.core.chat.send(a, "from alpha") }
        waitFor("alpha begin") { seen.filterIsInstance<ActivityEvent.Begin>().any { it.sessionId == a } }
        val rb = async(Dispatchers.IO) { h.core.chat.send(b, "from bravo") }
        waitFor("bravo begin") { seen.filterIsInstance<ActivityEvent.Begin>().any { it.sessionId == b } }
        val keyA = seen.filterIsInstance<ActivityEvent.Begin>().single { it.sessionId == a }.key
        val keyB = seen.filterIsInstance<ActivityEvent.Begin>().single { it.sessionId == b }.key
        assertFalse(keyA == keyB)

        h.fake.pushLaterTurn(a, tool("tool.start", "execute_code"), withStart = false)
        waitFor("alpha update") { updates(keyA).size == 1 }
        settle()
        assertTrue("bravo is untouched", updates(keyB).isEmpty())
        h.fake.pushLaterTurn(b, tool("tool.start", "web_search", "weather"), withStart = false)
        waitFor("bravo update") { updates(keyB).size == 1 }
        assertEquals(1, updates(keyA).size)

        h.fake.pushLaterTurn(a, FakeHermesDashboard.complete("alpha done"), withStart = false)
        ra.await()
        waitFor("alpha end") { seen.any { it is ActivityEvent.End && it.key == keyA } }
        assertEquals("bravo still has its slot", listOf(keyB), h.core.activity.snapshot().bubbles.map { it.key })
        h.fake.pushLaterTurn(b, FakeHermesDashboard.complete("bravo done"), withStart = false)
        rb.await()
        waitFor("bravo end") { seen.any { it is ActivityEvent.End && it.key == keyB } }
    }

    @Test
    fun `a gateway error ends the slot as not replied, truthfully, with no final reply or alert`(): Unit = runBlocking {
        val id = conversation("Work")
        held(id)
        val result = async(Dispatchers.IO) { h.core.chat.send(id, "go") }
        waitFor("begin") { seen.any { it is ActivityEvent.Begin } }
        val key = seen.filterIsInstance<ActivityEvent.Begin>().single().key
        val accepted = com.rumi.hermesvoice.core.net.AcceptedTurnStore(h.store).pending().single()
        assertEquals(id, accepted.storedSessionId)
        h.fake.pushLaterTurn(id, tool("tool.start", "terminal", "ls"), "error" to JSONObject().put("message", "boom"), withStart = false)
        assertTrue(result.await() is ChatSendResult.Failed)
        waitFor("end") { seen.any { it is ActivityEvent.End } }
        assertEquals(ActivityEvent.End(key, replied = false), seen.last())
        assertTrue(h.core.activity.snapshot().bubbles.isEmpty())
        settle()
        assertEquals(listOf("${accepted.id}#notice@$id"), rig.shown.toList())
        assertEquals(1, seen.count { it is ActivityEvent.End })
        assertEquals(listOf(id to "go"), h.fake.rawPrompts.toList())
        h.core.reconcileAccepted()
        settle()
        assertEquals("no duplicate notice or guessed successful reply", listOf("${accepted.id}#notice@$id"), rig.shown.toList())
        assertEquals(listOf(id to "go"), h.fake.rawPrompts.toList())
    }

    @Test
    fun `an inactivity timeout ends the slot as not replied`(): Unit = runBlocking {
        val id = conversation("Work")
        held(id)
        val quick = ChatService(h.core.sessions, replyTimeoutMs = 600L, activity = h.core.activity)
        val result = async(Dispatchers.IO) { quick.send(id, "go") }
        waitFor("begin") { seen.any { it is ActivityEvent.Begin } }
        val key = seen.filterIsInstance<ActivityEvent.Begin>().single().key
        result.await()
        waitFor("end") { seen.any { it is ActivityEvent.End } }
        assertEquals(ActivityEvent.End(key, replied = false), seen.last())
        assertTrue(h.core.activity.snapshot().bubbles.isEmpty())
    }

    @Test
    fun `cancelling the sender ends its slot and progress frames write no row and raise no alert`(): Unit = runBlocking {
        val id = conversation("Work")
        held(id)
        val rowsBefore = h.fake.rows.getValue(id).messages.size
        val result = async(Dispatchers.IO) { h.core.chat.send(id, "go") }
        waitFor("begin") { seen.any { it is ActivityEvent.Begin } }
        val key = seen.filterIsInstance<ActivityEvent.Begin>().single().key
        h.fake.pushLaterTurn(id, tool("tool.start", "patch", "a.kt"), tool("tool.start", "write_file", "b.kt"), withStart = false)
        waitFor("updates") { updates(key).size == 2 }
        assertEquals("only the user's own row was written", rowsBefore + 1, h.fake.rows.getValue(id).messages.size)
        assertTrue(rig.shown.isEmpty())
        result.cancel()
        waitFor("cancel end") { seen.any { it is ActivityEvent.End } }
        assertEquals(ActivityEvent.End(key, replied = false), seen.last())
        assertTrue(h.core.activity.snapshot().bubbles.isEmpty())
        settle()
        assertTrue(rig.shown.isEmpty())
    }

    @Test
    fun `a request that is not attributable opens no slot`(): Unit = runBlocking {
        val id = conversation("Work")
        h.fake.turnBehavior = { "start_after_reply" }
        h.fake.scripts[id] = { listOf(FakeHermesDashboard.complete("quick")) }
        h.core.chat.send(id, "go")
        settle()
        assertTrue(h.core.activity.snapshot().bubbles.isEmpty())
        assertTrue("every Begin is closed", seen.filterIsInstance<ActivityEvent.Begin>().all { b -> seen.any { it is ActivityEvent.End && it.key == b.key } })
    }
}
