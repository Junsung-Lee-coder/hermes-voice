package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.sessions.OwnedRole
import com.rumi.hermesvoice.core.sessions.OwnedSession
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.PlaybackTarget
import com.rumi.hermesvoice.core.watchlink.PhoneReaderService
import com.rumi.hermesvoice.core.watchlink.ReaderError
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.WatchReaderLimits
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone side of the Watch reader: app-owned active sessions and paged history over the Data Layer only. */
class WatchReaderTest {
    private val h = CoreHarness()
    private val reader get() = PhoneReaderService(h.core.sessions)

    @After fun tearDown() = h.close()

    private fun ask(request: ReaderRequest): ReaderResponse = runBlocking {
        ReaderResponse.decode(reader.handle(request.encode())!!)!!
    }

    @Test
    fun `request codec round trips and rejects malformed ids, kinds and limits`() {
        val request = ReaderRequest("req-00000001", ReaderKind.HISTORY, "sess-1", offset = 20, limit = 10)
        assertEquals(request, ReaderRequest.decode(request.encode()))
        assertNull(ReaderRequest.decode("not json".toByteArray()))
        assertNull(ReaderRequest.decode("""{"v":1,"req_id":"bad id!","kind":"sessions"}""".toByteArray()))
        assertNull(ReaderRequest.decode("""{"v":1,"req_id":"req-00000001","kind":"delete"}""".toByteArray()))
        assertNull(ReaderRequest.decode("""{"v":2,"req_id":"req-00000001","kind":"sessions"}""".toByteArray()))
        assertNull(ReaderRequest.decode("""{"v":1,"req_id":"req-00000001","kind":"history"}""".toByteArray()))
        val clamped = ReaderRequest.decode("""{"v":1,"req_id":"req-00000001","kind":"history","session_id":"s","offset":-5,"limit":900}""".toByteArray())!!
        assertEquals(0, clamped.offset)
        assertEquals(WatchReaderLimits.MAX_PAGE_MESSAGES, clamped.limit)
    }

    @Test
    fun `sessions lists only app-owned unarchived conversations, never the router or foreign rows`() = runBlocking {
        val work = h.core.sessions.createConversation("Work", "work", "")
        val home = h.core.sessions.createConversation("Home", "home", "")
        h.core.sessions.setArchived(home.storedSessionId, true)
        h.core.sessions.ensureRoutingSession()
        h.fake.addForeignRow("desktop-chat", "desktop")
        h.fake.addForeignRow("other-install", AppSources.CONVERSATION)
        val response = ask(ReaderRequest("req-00000001", ReaderKind.SESSIONS))
        assertTrue(response.ok)
        assertEquals("req-00000001", response.reqId)
        assertEquals(listOf(work.storedSessionId), response.sessions.map { it.id })
        assertEquals("work", response.sessions.single().alias)
    }

    @Test
    fun `history pages newest first and marks sent versus received by role`() = runBlocking {
        val work = h.core.sessions.createConversation("Work", "work", "")
        h.core.chat.send(work.storedSessionId, "hello")
        h.core.chat.send(work.storedSessionId, "again")
        val latest = ask(ReaderRequest("req-00000002", ReaderKind.HISTORY, work.storedSessionId, limit = 2))
        assertTrue(latest.ok)
        assertEquals(work.storedSessionId, latest.sessionId)
        assertEquals(listOf("user:again", "assistant:reply from ${work.storedSessionId}"), latest.messages.map { "${it.role}:${it.text}" })
        assertTrue(latest.messages.first().sent)
        assertFalse(latest.messages.last().sent)
        assertTrue(latest.hasOlder)
        assertEquals(2, latest.nextOffset)
        val older = ask(ReaderRequest("req-00000003", ReaderKind.HISTORY, work.storedSessionId, offset = latest.nextOffset, limit = 2))
        assertEquals(listOf("hello", "reply from ${work.storedSessionId}"), older.messages.map { it.text })
    }

    @Test
    fun `history fails closed for foreign, router, archived and wrong-source ids`() = runBlocking {
        val home = h.core.sessions.createConversation("Home", "home", "")
        h.core.sessions.setArchived(home.storedSessionId, true)
        val router = h.core.sessions.ensureRoutingSession()
        h.fake.addForeignRow("desktop-chat", "desktop")
        h.fake.addForeignRow("spoofed", "desktop")
        h.registry.put(OwnedSession("spoofed", OwnedRole.CONVERSATION, "t", "spoof", "", false, 0))
        for (id in listOf("desktop-chat", router.storedSessionId, home.storedSessionId, "spoofed", "missing")) {
            val response = ask(ReaderRequest("req-00000004", ReaderKind.HISTORY, id))
            assertFalse(id, response.ok)
            assertEquals(id, ReaderError.NOT_OWNED, response.error)
            assertTrue(response.messages.isEmpty())
        }
        assertFalse(h.fake.restLog.any { it.contains("/desktop-chat/messages") || it.contains("/spoofed/messages") })
    }

    @Test
    fun `signed-out and unreachable dashboards return explicit errors, not empty success`() = runBlocking {
        val work = h.core.sessions.createConversation("Work", "work", "")
        h.tokens.clear()
        val signedOut = ask(ReaderRequest("req-00000005", ReaderKind.SESSIONS))
        assertFalse(signedOut.ok)
        assertEquals(ReaderError.SIGN_IN_REQUIRED, signedOut.error)
        h.fake.close()
        val history = ask(ReaderRequest("req-00000006", ReaderKind.HISTORY, work.storedSessionId))
        assertFalse(history.ok)
        assertTrue(history.error in setOf(ReaderError.SIGN_IN_REQUIRED, ReaderError.UNAVAILABLE))
    }

    @Test
    fun `responses stay within the message size bound even for long multibyte history`() = runBlocking {
        val work = h.core.sessions.createConversation("Work", "work", "")
        repeat(WatchReaderLimits.MAX_PAGE_MESSAGES) { h.core.chat.send(work.storedSessionId, "가".repeat(5_000) + it) }
        val bytes = reader.handle(ReaderRequest("req-00000007", ReaderKind.HISTORY, work.storedSessionId).encode())!!
        assertTrue("${bytes.size}", bytes.size <= WatchReaderLimits.MAX_RESPONSE_BYTES)
        val response = ReaderResponse.decode(bytes)!!
        assertEquals(WatchReaderLimits.MAX_PAGE_MESSAGES, response.messages.size)
        assertTrue(response.messages.all { it.text.length <= WatchReaderLimits.MAX_TEXT_CHARS })
        assertTrue("every long user message is marked truncated", response.messages.filter { it.sent }.all { it.truncated })
    }

    @Test
    fun `reading never moves the playback route or submits anything`() = runBlocking {
        val work = h.core.sessions.createConversation("Work", "work", "")
        h.core.orchestrator.playbackRoute.accept(PlaybackTarget(VoiceOrigin.PHONE, PlaybackSink { _, _ -> }))
        val routeBefore = h.core.orchestrator.playbackRoute.current()
        val promptsBefore = h.fake.prompts.size
        ask(ReaderRequest("req-00000008", ReaderKind.SESSIONS))
        ask(ReaderRequest("req-00000009", ReaderKind.HISTORY, work.storedSessionId))
        assertSame(routeBefore, h.core.orchestrator.playbackRoute.current())
        assertEquals(VoiceOrigin.PHONE, h.core.orchestrator.playbackRoute.device.value)
        assertEquals(promptsBefore, h.fake.prompts.size)
    }

    @Test
    fun `undecodable requests get no response at all`() = runBlocking {
        assertNull(reader.handle("{}".toByteArray()))
        assertNull(reader.handle(ByteArray(WatchReaderLimits.MAX_REQUEST_BYTES + 1) { 'a'.code.toByte() }))
        assertNotNull(reader.handle(ReaderRequest("req-00000010", ReaderKind.SESSIONS).encode()))
    }
}
