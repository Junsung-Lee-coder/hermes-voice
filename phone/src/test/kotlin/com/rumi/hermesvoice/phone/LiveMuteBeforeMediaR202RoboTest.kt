package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import io.mockk.every
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PHONE-R2-02: a Mute made while the actual controller is still preparing (no transport yet) must be the real capture state of the
 * media created afterwards, for Phone-origin and Watch-origin calls. Synthetic media boundary. R29: the session list no longer holds
 * media back, so the call is held before any media at its own OS admission instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveMuteBeforeMediaR202RoboTest : PhoneRoboBase() {
    private class Media : LiveTransport {
        @Volatile var muted = false
        @Volatile var mutedAtConnect: Boolean? = null
        @Volatile var connected = false
        var mic: ((Boolean) -> Unit)? = null
        override fun observeMicrophone(listener: (Boolean) -> Unit) { mic = listener }
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) {
            mutedAtConnect = muted; connected = true
            mic?.invoke(!muted) // stopRecordingOnMute: a muted module never starts native capture
            event("{\"type\":\"session.started\",\"session\":{\"id\":\"provider\"}}")
        }
        override fun send(json: String) = true
        override fun mute(muted: Boolean) { this.muted = muted; mic?.invoke(!muted && connected) }
        override fun output(enabled: Boolean) {}
        override fun silence() { muted = true; mic?.invoke(false) }
        override fun close() {}
    }
    private val media = ArrayList<Media>()
    /** The admission token of a call held before its media; null when none is held. */
    @Volatile private var held: Long? = null
    @Before fun setUp() {
        baseSetUp(); shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.live.transportFactory = { Media().also { media += it } }
        app.live.routeFactory = { object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.admission = { app.live.admitted(it) }
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = true, watchLiveEnabled = true))
    }
    @After fun tearDown() { app.live.end(); settle(); baseTearDown() }
    private fun arm() { held = null; app.live.admission = { held = it } }
    /** Android admits the held call now: its media is prepared from here on. */
    private fun admit() { val token = held ?: return; held = null; app.live.admission = { app.live.admitted(it) }; app.live.admitted(token) }
    private fun startHeld() {
        arm(); assertTrue(app.live.start())
        assertTrue("the actual controller is waiting for its OS admission", await { held != null })
        assertTrue(app.live.state.value.active && !app.live.state.value.ready)
    }

    @Test fun phoneMuteDuringSessionListPreparationIsAppliedBeforeNativeMediaAdmission() {
        conversation("alpha"); startHeld()
        val before = media.size
        app.live.mute(); assertTrue(app.live.state.value.muted)
        admit()
        assertTrue(await { media.size == before + 1 && media.last().connected && app.live.state.value.ready })
        assertEquals("the stored mute is the native capture state at media admission", true, media.last().mutedAtConnect)
        assertTrue(media.last().muted); assertTrue(app.live.state.value.muted)
        assertFalse("no false input-active claim while muted", app.live.state.value.inputActive)
        app.live.mute(); assertFalse("an explicit unmute still reaches the same media", media.last().muted)
        assertTrue(await { app.live.state.value.inputActive })
    }

    @Test fun muteUnmuteMuteBeforeMediaKeepsTheFinalStateAndUnmutedPreparationStillOpensTheMicrophone() {
        conversation("alpha"); startHeld()
        app.live.mute(); app.live.mute(); app.live.mute(); assertTrue(app.live.state.value.muted)
        admit()
        assertTrue(await { media.size == 1 && media.single().connected })
        assertEquals(true, media.single().mutedAtConnect); assertFalse(app.live.state.value.inputActive)
        app.live.end(); settle()
        startHeld()
        app.live.mute(); app.live.mute(); assertFalse(app.live.state.value.muted)
        admit()
        assertTrue(await { media.size == 2 && media.last().connected && app.live.state.value.ready })
        assertEquals("control: an unmuted preparation admits an active microphone", false, media.last().mutedAtConnect)
        assertTrue(await { app.live.state.value.inputActive })
    }

    @Test fun endDuringPreparationNeverCreatesMediaAndTheNextCallIsNotSilentlyMuted() {
        conversation("alpha"); startHeld()
        app.live.mute(); app.live.end()
        admit(); settle() // a late admission of the ended call opens nothing
        assertTrue("an ended preparation creates no media", media.isEmpty())
        assertFalse(app.live.active); assertFalse(app.phoneMic.occupied)
        assertTrue(app.live.start()); assertTrue(await { media.size == 1 && app.live.state.value.ready })
        assertEquals(false, media.single().mutedAtConnect); assertFalse(app.live.state.value.muted)
    }

    @Test fun watchControlMuteDuringPreparationIsDeclaredToTheWatchMediaAdmission() {
        conversation("alpha")
        val packets = java.util.concurrent.ConcurrentLinkedQueue<WatchLiveWire.Packet>()
        val node = mockk<Node>(); every { node.id } returns "watch-node"
        val info = mockk<CapabilityInfo>(); every { info.nodes } returns setOf(node)
        val caps = mockk<CapabilityClient>(); every { caps.getCapability(any(), any()) } returns Tasks.forResult(info)
        every { Wearable.getCapabilityClient(any<Context>()) } returns caps
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers { WatchLiveWire.parse(thirdArg<ByteArray>())?.let { packets += it }; Tasks.forResult(1) }
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        fun control(op: String, extra: JSONObject.() -> Unit = {}) = app.liveWatch.receive("watch-node",
            JSONObject().put("v", 1).put("id", "watch-call").put("op", op).put("probe", "probe-$op").apply(extra).toString().toByteArray())
        arm(); control("start")
        assertTrue(await { held != null && app.live.state.value.active })
        assertEquals(VoiceOrigin.WATCH, app.live.state.value.origin)
        assertTrue("no Watch media was opened before admission", packets.none { it.op == "open" })
        control("mute") { put("muted", true) }
        assertTrue(await { app.live.state.value.muted })
        admit()
        assertTrue(await { packets.any { it.op == "open" } })
        val open = packets.first { it.op == "open" }
        assertTrue("the Watch media admission carries the stored mute", JSONObject(open.body.ifBlank { "{}" }).optBoolean("muted"))
        settle()
        assertFalse(packets.any { it.op == "mute" && it.body == "false" })
        assertTrue(app.live.state.value.muted); assertFalse(app.phoneMic.occupied)
    }
}
