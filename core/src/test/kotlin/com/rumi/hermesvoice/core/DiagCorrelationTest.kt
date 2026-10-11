package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.diag.DiagApp
import com.rumi.hermesvoice.core.diag.DiagBuild
import com.rumi.hermesvoice.core.diag.DiagBuildStatus
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagJournal
import com.rumi.hermesvoice.core.diag.DiagJournalOversize
import com.rumi.hermesvoice.core.diag.DiagJournalStorage
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.DiagNetwork
import com.rumi.hermesvoice.core.diag.DiagOrigin
import com.rumi.hermesvoice.core.diag.DiagPersistStatus
import com.rumi.hermesvoice.core.diag.DiagReportV2
import com.rumi.hermesvoice.core.diag.DiagWatchResponder
import com.rumi.hermesvoice.core.diag.DiagWire
import com.rumi.hermesvoice.core.diag.DiagWireV2
import com.rumi.hermesvoice.core.diag.WatchDiag
import com.rumi.hermesvoice.core.diag.WatchDiagStatus
import com.rumi.hermesvoice.core.diag.WireCoverage
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One request must stay correlatable inside ONE export whether its events are still in memory or were restored from the
 * journal, and between the Phone and the Watch. Everything here runs the real log, journal, wire codec and report builder.
 */
class DiagCorrelationTest {
    private class Clocks(var mono: Long = 1_000_000L, var wall: Long = 1_800_000_000_000L) {
        fun advance(ms: Long) { mono += ms; wall += ms }
    }

    private class MemStorage : DiagJournalStorage {
        @Volatile var bytes: ByteArray? = null
        override fun read(maxBytes: Int): ByteArray? {
            val stored = bytes ?: return null
            if (stored.size > maxBytes) throw DiagJournalOversize()
            return stored
        }
        override fun write(bytes: ByteArray) { this.bytes = bytes }
        override fun delete() { bytes = null }
    }

    private val inline = Executor { it.run() }
    private val salt = "0123456789abcdef"
    private val app = DiagApp("0.1.18-dev", 19, DiagBuild(DiagBuildStatus.UNAVAILABLE))
    private val turn = "6f1c2c1e-6e0b-4c5e-9d0e-0a1b2c3d4e5f"

    /** A device "process": a fresh log attached to a journal over [storage], restoring what an earlier process flushed. */
    private fun process(c: Clocks, storage: MemStorage): DiagLog {
        val log = DiagLog(clock = { c.mono }, wallClock = { c.wall })
        DiagJournal(log, storage, inline).attach()
        return log
    }

    private fun report(phone: DiagLog, watch: WatchDiag): JSONObject =
        JSONObject(DiagReportV2.build(1_800_000_000_000L, app, phone.snapshot(), phone.now(), salt,
            WireCoverage.of(phone.coverage(), DiagPersistStatus.NOT_ENABLED), DiagNetwork.UNKNOWN, watch, emptyMap()))

    private fun refs(events: org.json.JSONArray, code: DiagCode): List<String> =
        (0 until events.length()).map { events.getJSONObject(it) }.filter { it.getString("code") == code.wire }.map { it.getString("ref") }

    private fun watchV2(log: DiagLog): WatchDiag =
        DiagWireV2.decodeResponse(DiagWatchResponder(log, { DiagPersistStatus.NOT_ENABLED }, { app }).answer(DiagWire.encodeRequestV2(salt))!!, salt)

    @Test
    fun `a phone turn that spans a restart keeps one pseudonym for its restored and its live events`() {
        val c = Clocks(); val storage = MemStorage()
        process(c, storage).record(DiagCode.REQUEST_QUEUED, DiagOrigin.PHONE, turn)
        c.advance(30_000)
        val restarted = process(c, storage)
        restarted.record(DiagCode.REQUEST_DELIVERED, DiagOrigin.PHONE, turn)
        val events = report(restarted, WatchDiag(WatchDiagStatus.OFFLINE)).getJSONObject("phone").getJSONArray("events")
        val queued = refs(events, DiagCode.REQUEST_QUEUED).single()
        val delivered = refs(events, DiagCode.REQUEST_DELIVERED).single()
        assertEquals("the restored and the live event of one turn are one request in the export", queued, delivered)
    }

    @Test
    fun `a restarted watch still pairs its restored event with the phone's live event of the same turn`() {
        val c = Clocks(); val watchStorage = MemStorage()
        process(c, watchStorage).record(DiagCode.WATCH_SENT, DiagOrigin.WATCH, turn)
        c.advance(30_000)
        val watchRestarted = process(c, watchStorage)
        val phone = DiagLog(clock = { c.mono }, wallClock = { c.wall })
        phone.record(DiagCode.REQUEST_QUEUED, DiagOrigin.PHONE, turn)
        val json = report(phone, watchV2(watchRestarted))
        val onPhone = refs(json.getJSONObject("phone").getJSONArray("events"), DiagCode.REQUEST_QUEUED).single()
        val onWatch = refs(json.getJSONObject("watch").getJSONArray("events"), DiagCode.WATCH_SENT).single()
        assertEquals("the same turn has the same reference on both devices", onPhone, onWatch)
    }

    @Test
    fun `a restarted phone still pairs its restored event with the watch's live event of the same turn`() {
        val c = Clocks(); val phoneStorage = MemStorage()
        process(c, phoneStorage).record(DiagCode.REQUEST_QUEUED, DiagOrigin.PHONE, turn)
        c.advance(30_000)
        val phoneRestarted = process(c, phoneStorage)
        val watch = DiagLog(clock = { c.mono }, wallClock = { c.wall })
        watch.record(DiagCode.WATCH_SENT, DiagOrigin.WATCH, turn)
        val json = report(phoneRestarted, watchV2(watch))
        val onPhone = refs(json.getJSONObject("phone").getJSONArray("events"), DiagCode.REQUEST_QUEUED).single()
        val onWatch = refs(json.getJSONObject("watch").getJSONArray("events"), DiagCode.WATCH_SENT).single()
        assertEquals(onPhone, onWatch)
    }

    @Test
    fun `two turns stay two requests and nothing raw reaches storage or the export`() {
        val c = Clocks(); val storage = MemStorage()
        val first = process(c, storage)
        first.record(DiagCode.REQUEST_QUEUED, DiagOrigin.PHONE, turn)
        first.record(DiagCode.REQUEST_QUEUED, DiagOrigin.PHONE, "another-$turn")
        val stored = String(storage.bytes!!, Charsets.UTF_8)
        assertFalse("the raw turn is never written", stored.contains(turn))
        val restarted = process(c, storage)
        val text = report(restarted, WatchDiag(WatchDiagStatus.OFFLINE)).toString()
        assertFalse(text.contains(turn))
        val events = JSONObject(text).getJSONObject("phone").getJSONArray("events")
        val refs = refs(events, DiagCode.REQUEST_QUEUED)
        assertEquals(2, refs.size)
        assertNotEquals(refs[0], refs[1])
    }

    @Test
    fun `an older schema 1 watch's live event still pairs with the phone's live event of the same turn`() {
        val c = Clocks()
        val watch = DiagLog(clock = { c.mono }, wallClock = { c.wall })
        watch.record(DiagCode.WATCH_SENT, DiagOrigin.WATCH, turn)
        val v1 = DiagWire.decodeResponse(DiagWire.encodeResponse(salt, watch.snapshot(), watch.dropped(), watch.now()), salt)
        assertEquals(WatchDiagStatus.REACHABLE, v1.status)
        val phone = DiagLog(clock = { c.mono }, wallClock = { c.wall })
        phone.record(DiagCode.REQUEST_QUEUED, DiagOrigin.PHONE, turn)
        val json = report(phone, v1)
        val onPhone = refs(json.getJSONObject("phone").getJSONArray("events"), DiagCode.REQUEST_QUEUED).single()
        val onWatch = refs(json.getJSONObject("watch").getJSONArray("events"), DiagCode.WATCH_SENT).single()
        assertEquals("live events keep pairing across the older wire", onPhone, onWatch)
        assertTrue(onPhone.isNotEmpty())
    }
}
