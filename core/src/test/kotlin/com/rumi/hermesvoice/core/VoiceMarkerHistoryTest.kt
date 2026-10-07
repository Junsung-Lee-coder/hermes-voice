package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.PhoneReaderService
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stored history is shown exactly as the gateway keeps it: a voice request's row reads `marker + words` on the raw HTTP route,
 * in [com.rumi.hermesvoice.core.sessions.AppSessionRepository.history] (the Phone chat) and in the Watch reader, and no row is
 * ever filtered, rewritten or reassigned by the app, whatever the legacy origin record holds. Real HTTP via
 * [HermesDashboardClient]; the dashboard double stores each prompt verbatim as the gateway does.
 */
class VoiceMarkerHistoryTest {
    private val old = OLD_VOICE_HINT

    private fun CoreHarness.conversation(): String {
        val work = runBlocking { core.sessions.createConversation("Work", "work", "") }.storedSessionId
        fake.scripts[work] = { listOf(FakeHermesDashboard.complete("Done.")) }
        return work
    }

    private fun CoreHarness.attempt(id: String, words: String, work: String, origin: VoiceOrigin = VoiceOrigin.PHONE): VoiceTurnOutcome = runBlocking {
        core.orchestrator.run(VoiceTurnRequest(id, origin, TestAudio.speechWav(), "audio/wav", PlaybackSink { _, _ -> },
            recognizedText = words, routing = TurnRouting.Direct(work)))
    }

    private fun CoreHarness.say(id: String, words: String, work: String, origin: VoiceOrigin = VoiceOrigin.PHONE) {
        val outcome = attempt(id, words, work, origin)
        assertTrue("turn $id: $outcome", outcome is VoiceTurnOutcome.Completed)
    }

    private fun CoreHarness.rawRows(work: String, limit: Int = 50, offset: Int = 0): List<HistoryMessage> =
        runBlocking { HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens).getMessages(work, limit, offset) }.messages

    private fun CoreHarness.phoneRows(work: String, limit: Int = 50, offset: Int = 0): List<HistoryMessage> =
        runBlocking { core.sessions.history(work, limit, offset) }.messages

    private fun CoreHarness.watchRows(work: String, limit: Int = 50, offset: Int = 0) = runBlocking {
        ReaderResponse.decode(PhoneReaderService(core.sessions).handle(ReaderRequest("req-0000000${offset + 1}", ReaderKind.HISTORY, work, offset = offset, limit = limit).encode())!!)!!
    }.messages

    private fun List<HistoryMessage>.users() = filter { it.role == "user" }.map { it.text }

    @JvmName("readerUsers")
    private fun List<ReaderMessageRow>.users() = filter { it.role == "user" }.map { it.text }

    @Test
    fun `a Phone and a Watch voice request read marker and words on the raw route, the Phone chat and the Watch reader alike`() {
        CoreHarness().use { h ->
            val work = h.conversation()
            h.say("t1", "phone words", work, VoiceOrigin.PHONE)
            h.say("t2", "watch words", work, VoiceOrigin.WATCH)
            val expected = listOf(VOICE_MARK + "phone words", VOICE_MARK + "watch words")
            assertEquals(expected, h.rawRows(work).users())
            assertEquals("the app adds nothing and removes nothing: same rows, ids, roles and text", h.rawRows(work), h.phoneRows(work))
            assertEquals(expected, h.watchRows(work).users())
            assertEquals(h.phoneRows(work).map { it.role to it.text }, h.watchRows(work).map { it.role to it.text })
        }
    }

    @Test
    fun `the conversation list preview is the gateway's own last text, marker included, never projected`() {
        CoreHarness().use { h ->
            val work = h.conversation()
            h.say("t1", "first words", work)
            val answered = runBlocking { h.core.sessions.listConversations(archived = false) }.single { it.stored.id == work }.stored.preview
            assertEquals("the preview is the stored last row, here the assistant's", h.rawRows(work).last().text, answered)
            // The double stores a refused turn's user row before answering the refusal, so the voice row is the last one here.
            h.fake.turnBehavior = { "session_limit" }
            h.attempt("t2", "preview words", work)
            val preview = runBlocking { h.core.sessions.listConversations(archived = false) }.single { it.stored.id == work }.stored.preview
            assertEquals(VOICE_MARK + "preview words", preview)
        }
    }

    @Test
    fun `typed rows, rows with the old instruction text, foreign rows and assistant rows come back exactly as stored`() {
        CoreHarness().use { h ->
            val work = h.conversation()
            h.say("t1", "spoken one", work)
            runBlocking { h.core.chat.send(work, "typed plain") }
            runBlocking { h.core.chat.send(work, "typed literal\n\n$old") }
            h.fake.injectRow(work, "user", "spoken one\n\n$old")
            h.fake.injectRow(work, "user", "foreign\n\n$old")
            h.fake.injectRow(work, "assistant", "assistant said\n\n$old")
            val raw = h.rawRows(work)
            assertEquals(listOf(VOICE_MARK + "spoken one", "typed plain", "typed literal\n\n$old", "spoken one\n\n$old", "foreign\n\n$old"), raw.users())
            assertEquals(raw, h.phoneRows(work))
            assertEquals(raw.map { it.role to it.text }, h.watchRows(work).map { it.role to it.text })
        }
    }

    @Test
    fun `identical text voice and typed rows stay two rows with the same text in any read order`() {
        CoreHarness().use { h ->
            val work = h.conversation()
            runBlocking { h.core.chat.send(work, VOICE_MARK + "yes") }
            h.say("t1", "yes", work)
            runBlocking { h.core.chat.send(work, VOICE_MARK + "yes") }
            val raw = h.rawRows(work)
            assertEquals(listOf(VOICE_MARK + "yes", VOICE_MARK + "yes", VOICE_MARK + "yes"), raw.users())
            val newestFirst = h.phoneRows(work, 2, 0) + h.phoneRows(work, 2, 2) + h.phoneRows(work, 2, 4)
            val oldestFirst = h.phoneRows(work, 2, 4) + h.phoneRows(work, 2, 2) + h.phoneRows(work, 2, 0)
            assertEquals(newestFirst.sortedBy { it.rowId }, oldestFirst.sortedBy { it.rowId })
            assertEquals(raw.sortedBy { it.rowId }, newestFirst.sortedBy { it.rowId })
        }
    }

    @Test
    fun `a refused voice request and a no-speech request leave every stored row alone`() {
        CoreHarness().use { h ->
            val work = h.conversation()
            h.say("t1", "first words", work)
            val before = h.rawRows(work)
            h.fake.turnBehavior = { "session_limit" }
            val refused = h.attempt("t2", "refused words", work)
            assertTrue("$refused", refused !is VoiceTurnOutcome.Completed)
            h.fake.turnBehavior = { "start_first" }
            runBlocking { h.core.chat.send(work, VOICE_MARK + "refused words") }
            val raw = h.rawRows(work)
            assertEquals(before, raw.take(before.size))
            assertEquals(raw, h.phoneRows(work))
            assertEquals(raw.map { it.role to it.text }, h.watchRows(work).map { it.role to it.text })
        }
    }

    /** A store whose legacy origin key is corrupt, unreadable or unwritable, switched during a test. */
    private class LegacyKeyStore(private val inner: KeyValueStore = InMemoryKeyValueStore()) : KeyValueStore by inner {
        @Volatile var refuseCommit = false
        @Volatile var throwCommit = false
        @Volatile var throwRead = false
        override fun commitString(key: String, value: String): Boolean {
            if (key.startsWith("voice_origin")) {
                if (throwCommit) throw IllegalStateException("disk")
                if (refuseCommit) return false
            }
            return inner.commitString(key, value)
        }
        override fun putString(key: String, value: String) {
            if (key.startsWith("voice_origin") && throwCommit) throw IllegalStateException("disk")
            inner.putString(key, value)
        }
        override fun getString(key: String): String? {
            if (throwRead && key.startsWith("voice_origin")) throw IllegalStateException("read")
            return inner.getString(key)
        }
    }

    @Test
    fun `a corrupt, unknown-version, unreadable or unwritable legacy origin record neither blocks a voice request nor changes any row`() {
        val legacy = listOf(
            "corrupt" to { s: LegacyKeyStore -> s.putString("voice_origin_v1", "{not json") },
            "unknown version" to { s: LegacyKeyStore -> s.putString("voice_origin_v1", """{"v":99,"salt":"x","sessions":{}}""") },
            "unreadable" to { s: LegacyKeyStore -> s.throwRead = true },
            "refusing commit" to { s: LegacyKeyStore -> s.refuseCommit = true },
            "throwing commit" to { s: LegacyKeyStore -> s.throwCommit = true },
        )
        for ((name, damage) in legacy) {
            val store = LegacyKeyStore()
            damage(store)
            CoreHarness(store = store).use { h ->
                val work = h.conversation()
                val outcome = runCatching { h.attempt("t1", "still delivered", work) }
                assertTrue("$name: the voice request must be delivered, was $outcome", outcome.getOrNull() is VoiceTurnOutcome.Completed)
                val shown = runCatching { h.phoneRows(work) }
                assertTrue("$name: history must be readable, was $shown", shown.isSuccess)
                assertEquals("$name", listOf(VOICE_MARK + "still delivered"), h.rawRows(work).users())
                assertEquals("$name", h.rawRows(work), shown.getOrThrow())
                assertEquals("$name", h.rawRows(work).map { it.role to it.text }, h.watchRows(work).map { it.role to it.text })
            }
            if (name == "unknown version") assertEquals("the legacy key is left as it was", """{"v":99,"salt":"x","sessions":{}}""", store.getString("voice_origin_v1"))
        }
    }

    @Test
    fun `paged history and an app restart show the same literal rows on every page`() {
        val first = CoreHarness()
        val work = first.conversation()
        val words = (1..5).map { "request number $it" }
        words.forEachIndexed { i, w -> first.say("t$i", w, work) }
        val expected = words.map { VOICE_MARK + it }
        first.stop()
        CoreHarness(fake = first.fake, store = first.store, localStore = first.localStore).use { second ->
            val newest = second.phoneRows(work, 4, 0)
            val middle = second.phoneRows(work, 4, 4)
            val oldest = second.phoneRows(work, 4, 8)
            assertEquals(expected, (oldest + middle + newest).users())
            assertEquals(second.rawRows(work, 4, 4), middle)
            assertEquals(middle.map { it.text }, second.watchRows(work, 4, 4).map { it.text })
            second.say("t9", "after the restart", work)
            assertEquals(expected + (VOICE_MARK + "after the restart"), second.rawRows(work).users())
            assertEquals(second.rawRows(work), second.phoneRows(work))
        }
    }
}
