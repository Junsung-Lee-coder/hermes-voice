package com.rumi.hermesvoice.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A typed message's answer is spoken through a connected headset with "Use headset" on, whatever the later-reply option, and only then. */
class HeadsetTypedFinalTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rig: HeadsetRig? = null

    @After fun tearDown() {
        rig?.close()
        scope.cancel()
    }

    private fun rig(on: Boolean, connected: Boolean = true, laterOptIn: Boolean = false) =
        HeadsetRig(scope, FakeDevices(if (connected) listOf(Gear.speaker, Gear.a2dp) else listOf(Gear.speaker)), laterOptIn = laterOptIn, headsetOn = on)
            .also { rig = it }

    private fun send(r: HeadsetRig, stored: String) = runBlocking { r.core.chat.send(stored, "hello") as ChatSendResult.Replied }

    @Test
    fun `off - a typed answer is shown  not spoken  and notifies as before`() {
        val r = rig(on = false)
        val stored = r.existing("work", "Typed answer.")
        assertEquals("Typed answer.", send(r, stored).text)
        r.waitFor("notification") { r.alertsShown.isNotEmpty() }
        r.settle()
        assertTrue(r.sink.played.isEmpty())
        assertEquals(1, r.alertsShown.size)
    }

    @Test
    fun `on with no headset connected is the same as off`() {
        val r = rig(on = true, connected = false)
        val stored = r.existing("work", "Typed answer.")
        send(r, stored)
        r.waitFor("notification") { r.alertsShown.isNotEmpty() }
        r.settle()
        assertTrue(r.sink.played.isEmpty())
        assertEquals(1, r.alertsShown.size)
    }

    @Test
    fun `on with a headset - the typed answer is spoken through it  once  with no notification on success`() {
        val r = rig(on = true)
        val stored = r.existing("work", "Typed answer.")
        assertEquals("Typed answer.", send(r, stored).text)
        r.waitFor("spoken") { r.sink.played.isNotEmpty() }
        r.settle()
        assertEquals(listOf("FINAL:Typed answer."), r.sink.played.toList())
        assertEquals(Gear.a2dp, r.sink.cues.single().headset)
        assertTrue(r.alertsShown.isEmpty())
    }

    @Test
    fun `it does not need Speak later replies and ignores it either way`() {
        for (optIn in listOf(false, true)) {
            val r = rig(on = true, laterOptIn = optIn)
            val stored = r.existing("work", "Typed answer.")
            send(r, stored)
            r.waitFor("spoken") { r.sink.played.isNotEmpty() }
            assertEquals(listOf("FINAL:Typed answer."), r.sink.played.toList())
            r.close()
        }
    }

    @Test
    fun `the headset leaving while the typed answer plays ends it without a speaker  and notifies once`() {
        val r = rig(on = true)
        val stored = r.existing("work", "Typed answer.")
        val gate = CompletableDeferred<Unit>()
        r.sink.hold = gate
        send(r, stored)
        r.waitFor("playing") { r.sink.starts.get() == 1 }
        r.devices.disconnectAll()
        r.waitFor("notification") { r.alertsShown.isNotEmpty() }
        r.settle()
        assertEquals(1, r.alertsShown.size)
        assertTrue(r.sink.played.isEmpty())
        gate.complete(Unit)
    }

    @Test
    fun `a Stop ends the typed answer without announcing it as a new reply  and a reconnect does not play it`() {
        val r = rig(on = true)
        val stored = r.existing("work", "Typed answer.")
        r.sink.hold = CompletableDeferred()
        send(r, stored)
        r.waitFor("playing") { r.sink.starts.get() == 1 }
        r.core.orchestrator.stopFollowing()
        r.waitFor("cut") { r.sink.cut.get() == 1 }
        r.devices.disconnectAll()
        r.devices.connect(Gear.a2dp)
        r.settle(800)
        assertEquals(1, r.sink.starts.get())
        assertTrue(r.sink.played.isEmpty())
        assertTrue(r.alertsShown.isEmpty())
    }

    @Test
    fun `a sink failure leaves the text and exactly one notification`() {
        val r = rig(on = true)
        val stored = r.existing("work", "Typed answer.")
        r.sink.failNext = "synthesis failed"
        assertEquals("Typed answer.", send(r, stored).text)
        r.waitFor("notification") { r.alertsShown.isNotEmpty() }
        r.settle()
        assertEquals(1, r.alertsShown.size)
        assertTrue(r.sink.played.isEmpty())
    }

    @Test
    fun `turning the preference off mid-playback lets the typed answer finish on the same headset`() {
        val r = rig(on = true)
        val stored = r.existing("work", "Typed answer.")
        val gate = CompletableDeferred<Unit>()
        r.sink.hold = gate
        send(r, stored)
        r.waitFor("playing") { r.sink.starts.get() == 1 }
        r.settings.useHeadset = false
        gate.complete(Unit)
        r.waitFor("finished") { r.sink.played.isNotEmpty() }
        r.settle()
        assertEquals(0, r.sink.cut.get())
        assertEquals(Gear.a2dp, r.sink.cues.single().headset)
        assertTrue(r.alertsShown.isEmpty())
    }
}
