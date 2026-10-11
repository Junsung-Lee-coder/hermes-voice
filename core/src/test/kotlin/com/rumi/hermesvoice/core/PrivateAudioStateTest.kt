package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.headset.PrivateAudioMonitor
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.notify.ReplyAlertLedger
import com.rumi.hermesvoice.core.notify.ReplyAlertPort
import com.rumi.hermesvoice.core.notify.ReplyAlertResult
import com.rumi.hermesvoice.core.notify.ReplyAlerts
import com.rumi.hermesvoice.core.notify.ReplyPreview
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.ReplicaUpdate
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.settings.WatchSettingsReplica
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.watchlink.PrivateAudioLedger
import com.rumi.hermesvoice.core.watchlink.PrivateAudioReceipt
import com.rumi.hermesvoice.core.watchlink.PrivateWatchStatus
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Phone-side state of private headset output: the monitor, the quiet alert, the persisted Watch flag and the Watch's receipt. */
class PrivateAudioStateTest {
    // ── the monitor ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the monitor watches the devices only while the setting is on and reports each change of the private state once`() {
        val devices = FakeDevices(listOf(Gear.speaker))
        var on = false
        val got = mutableListOf<Boolean>()
        val monitor = PrivateAudioMonitor(HeadsetPolicy({ on }, devices)) { got += it }
        monitor.refresh()
        assertEquals(listOf(false), got)
        assertEquals("nothing is watched while the setting is off", 0, devices.watching)

        on = true
        monitor.refresh()
        assertEquals(1, devices.watching)
        assertEquals("on, but nothing connected: still not private", listOf(false), got)

        devices.connect(Gear.a2dp)
        assertEquals(listOf(false, true), got)
        devices.set(listOf(Gear.speaker, Gear.a2dp, Gear.usbOut))
        assertEquals("another change that leaves it private is not reported again", listOf(false, true), got)
        devices.disconnectAll()
        assertEquals(listOf(false, true, false), got)

        devices.connect(Gear.wiredHeadphones)
        assertEquals(listOf(false, true, false, true), got)
        on = false
        monitor.refresh()
        assertEquals(listOf(false, true, false, true, false), got)
        assertEquals("the watch is released as soon as the setting is off", 0, devices.watching)
        devices.disconnectAll()
        devices.connect(Gear.a2dp)
        assertEquals(5, got.size)
        monitor.close()
    }

    @Test
    fun `the monitor reports a headset that is already connected at start and a speaker-only or input-only set as not private`() {
        val start = FakeDevices(listOf(Gear.speaker, Gear.a2dp))
        val got = mutableListOf<Boolean>()
        PrivateAudioMonitor(HeadsetPolicy({ true }, start)) { got += it }.also { it.refresh() }.close()
        assertEquals(listOf(true), got)

        for (outs in listOf(listOf(Gear.speaker), listOf(Gear.speaker, Gear.bleSpeaker), listOf(Gear.speaker, Gear.hearingAid))) {
            val seen = mutableListOf<Boolean>()
            PrivateAudioMonitor(HeadsetPolicy({ true }, FakeDevices(outs, listOf(Gear.scoIn))) ) { seen += it }.also { it.refresh() }.close()
            assertEquals("$outs", listOf(false), seen)
        }
    }

    @Test
    fun `closing the monitor releases the device watch and later changes report nothing`() {
        val devices = FakeDevices(listOf(Gear.speaker))
        val got = mutableListOf<Boolean>()
        val monitor = PrivateAudioMonitor(HeadsetPolicy({ true }, devices)) { got += it }
        monitor.refresh()
        assertEquals(1, devices.watching)
        monitor.close()
        assertEquals(0, devices.watching)
        devices.connect(Gear.a2dp)
        assertEquals(listOf(false), got)
    }

    // ── the quiet alert ──────────────────────────────────────────────────────────────────────

    private class QuietPort : ReplyAlertPort {
        val calls = mutableListOf<Pair<String, Boolean>>()
        override fun show(alert: ReplyAlert): Boolean {
            calls += alert.identity to false
            return true
        }

        override fun show(alert: ReplyAlert, preview: ReplyPreview?, quiet: Boolean): Boolean {
            calls += alert.identity to quiet
            return true
        }
    }

    private class WatchAlertSink : PlaybackSink {
        var delivered = 0
        override suspend fun play(audio: SpokenAudio, cue: PlaybackCue) = Unit
        override suspend fun deliverReplyAlert(alert: ReplyAlert): Boolean {
            delivered++
            return true
        }
    }

    private val stored = "20260101_120000_workwork"

    @Test
    fun `a quiet Watch-target reply is shown on the Phone silently and is never sent to the Watch to sound there`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val port = QuietPort()
            val sink = WatchAlertSink()
            val alerts = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), port, scope)
            val reply = FinalReply("t1#later1", stored, VoiceOrigin.WATCH, heard = false, cancelled = false, FinalReplySource.LATER, sink, "Hello", quiet = true)
            assertEquals(ReplyAlertResult.SHOWN_HERE, runBlocking { alerts.dispatch(reply) })
            assertEquals(0, sink.delivered)
            assertEquals(listOf("t1#later1" to true), port.calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a reply that is not quiet keeps the existing routing - the Watch is alerted for a Watch target`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val port = QuietPort()
            val sink = WatchAlertSink()
            val alerts = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), port, scope)
            val reply = FinalReply("t2#later1", stored, VoiceOrigin.WATCH, heard = false, cancelled = false, FinalReplySource.LATER, sink, "Hello")
            assertEquals(ReplyAlertResult.SENT_TO_WATCH, runBlocking { alerts.dispatch(reply) })
            assertEquals(1, sink.delivered)
            assertTrue(port.calls.isEmpty())
            val phone = FinalReply("t3#later1", stored, VoiceOrigin.PHONE, heard = false, cancelled = false, FinalReplySource.TEXT, null, "Hello")
            runBlocking { alerts.dispatch(phone) }
            assertEquals(listOf("t3#later1" to false), port.calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a quiet reply is still deduplicated by identity and a heard or cancelled one still never alerts`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val port = QuietPort()
            val alerts = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), port, scope)
            val reply = FinalReply("t4#later1", stored, VoiceOrigin.PHONE, heard = false, cancelled = false, FinalReplySource.LATER, null, "x", quiet = true)
            assertEquals(ReplyAlertResult.SHOWN_HERE, runBlocking { alerts.dispatch(reply) })
            assertEquals(ReplyAlertResult.DUPLICATE, runBlocking { alerts.dispatch(reply) })
            val heard = FinalReply("t5#later1", stored, VoiceOrigin.PHONE, heard = true, cancelled = false, FinalReplySource.LATER, null, "x", quiet = true)
            assertEquals(ReplyAlertResult.SUPPRESSED_HEARD, runBlocking { alerts.dispatch(heard) })
            assertEquals(1, port.calls.size)
        } finally {
            scope.cancel()
        }
    }

    // ── the persisted, replicated flag ───────────────────────────────────────────────────────

    @Test
    fun `the Watch flag is off by default and a legacy snapshot without it reads off`() {
        assertFalse(WatchSettings().privateAudio)
        assertFalse(WatchSettings.parse(JSONObject().put("revision", 5).toString())!!.privateAudio)
        assertFalse(AppSettings(InMemoryKeyValueStore()).watchSettings().privateAudio)
    }

    @Test
    fun `the flag travels in the snapshot json and a value of the wrong type invalidates it`() {
        val json = WatchSettings(privateAudio = true, revision = 9).toJson()
        assertTrue(JSONObject(json).getBoolean("private_audio"))
        assertTrue(WatchSettings.parse(json)!!.privateAudio)
        assertNull(WatchSettings.parse(JSONObject().put("private_audio", "yes").toString()))
        assertNull(WatchSettings.parse(JSONObject().put("private_audio", 1).toString()))
    }

    @Test
    fun `saving the private state is revisioned and persistent - and no other save can change it`() {
        val store = InMemoryKeyValueStore()
        val settings = AppSettings(store)
        val before = settings.watchSettings().revision
        val on = settings.savePrivateAudio(true)
        assertTrue(on.privateAudio)
        assertTrue("a strictly newer snapshot for the Watch", on.revision > before)
        assertTrue(AppSettings(store).watchSettings().privateAudio)

        val other = settings.saveWatchSettings(settings.watchSettings().copy(hapticsEnabled = false))
        assertTrue("an unrelated save keeps it", other.privateAudio)
        assertTrue(other.revision > on.revision)
        val forced = settings.saveWatchSettings(settings.watchSettings().copy(privateAudio = false))
        assertTrue("only the headset state changes it", forced.privateAudio)

        val off = settings.savePrivateAudio(false)
        assertFalse(off.privateAudio)
        assertTrue(off.revision > other.revision)
        assertFalse(AppSettings(store).watchSettings().privateAudio)
    }

    @Test
    fun `a stale or replayed snapshot cannot clear the Watch's private state`() {
        val replica = WatchSettingsReplica(WatchSettings(privateAudio = false, revision = 100).toJson())
        assertEquals(ReplicaUpdate.APPLIED, replica.offer(WatchSettings(privateAudio = true, revision = 200).toJson()))
        assertTrue(replica.current.privateAudio)
        assertEquals(ReplicaUpdate.STALE, replica.offer(WatchSettings(privateAudio = false, revision = 150).toJson()))
        assertEquals(ReplicaUpdate.STALE, replica.offer(WatchSettings(privateAudio = false, revision = 200).toJson()))
        assertTrue(replica.current.privateAudio)
        assertTrue("a restarted Watch keeps what it stored", WatchSettingsReplica(replica.current.toJson()).current.privateAudio)
    }

    // ── what the Watch reports back ──────────────────────────────────────────────────────────

    @Test
    fun `the receipt is a small strict json document on its own link path`() {
        assertEquals("/hv/v1/private_audio", WatchLinkPaths.PRIVATE_AUDIO)
        val receipt = PrivateAudioReceipt(revision = 77, suppressed = true)
        assertEquals(receipt, PrivateAudioReceipt.parse(receipt.toJson().toByteArray()))
        assertNull(PrivateAudioReceipt.parse("{}".toByteArray()))
        assertNull(PrivateAudioReceipt.parse("""{"revision":-1,"suppressed":true}""".toByteArray()))
        assertNull(PrivateAudioReceipt.parse("""{"revision":3,"suppressed":"yes"}""".toByteArray()))
        assertNull(PrivateAudioReceipt.parse("not json".toByteArray()))
        assertNotNull(PrivateAudioReceipt.parse("""{"revision":3,"suppressed":false}""".toByteArray()))
    }

    @Test
    fun `the Watch counts as private only after a receipt for the CURRENT revision says it is suppressed`() {
        val ledger = PrivateAudioLedger()
        assertEquals(PrivateWatchStatus.NOT_ACTIVE, ledger.status())
        ledger.published(revision = 10, active = true)
        assertEquals("sent is not confirmed", PrivateWatchStatus.PENDING, ledger.status())
        assertFalse("a receipt for an older snapshot", ledger.received(PrivateAudioReceipt(9, true)))
        assertEquals(PrivateWatchStatus.PENDING, ledger.status())
        assertFalse("the Watch applied it but did not suppress", ledger.received(PrivateAudioReceipt(10, false)))
        assertEquals(PrivateWatchStatus.PENDING, ledger.status())
        assertTrue(ledger.received(PrivateAudioReceipt(10, true)))
        assertEquals(PrivateWatchStatus.CONFIRMED, ledger.status())

        ledger.published(revision = 11, active = true)
        assertEquals("a newer snapshot needs its own receipt", PrivateWatchStatus.PENDING, ledger.status())
        ledger.published(revision = 12, active = false)
        assertEquals(PrivateWatchStatus.NOT_ACTIVE, ledger.status())
        assertFalse("a late receipt for the old active snapshot cannot revive it", ledger.received(PrivateAudioReceipt(11, true)))
        assertEquals(PrivateWatchStatus.NOT_ACTIVE, ledger.status())
    }

    @Test
    fun `a restarted Phone starts pending - it cannot claim the Watch confirmed anything it was not told this run`() {
        val ledger = PrivateAudioLedger()
        ledger.published(revision = 500, active = true)
        assertEquals(PrivateWatchStatus.PENDING, ledger.status())
    }
}
