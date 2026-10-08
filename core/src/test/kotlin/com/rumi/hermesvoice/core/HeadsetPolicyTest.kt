package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.headset.AudioEndpoint
import com.rumi.hermesvoice.core.headset.HeadsetClassifier
import com.rumi.hermesvoice.core.headset.HeadsetFamily
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.headset.MicChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Use headset": which endpoints count as a personal headset OUTPUT and which as a headset INPUT (separately), and the policy over them. */
class HeadsetPolicyTest {
    private fun policy(on: Boolean, devices: FakeDevices) = HeadsetPolicy({ on }, devices)

    @Test
    fun `personal outputs are wired  USB  Bluetooth A2DP and SCO and BLE headsets  and nothing else`() {
        for (end in listOf(Gear.wiredHeadset, Gear.wiredHeadphones, Gear.usbOut, Gear.a2dp, Gear.scoOut, Gear.bleOut)) {
            assertTrue("${end.name}/${end.type} is a headset output", HeadsetClassifier.isPersonalOutput(end))
        }
        // The built-in speaker, a BLE speaker and a hearing aid are not "an earphone"; a car's A2DP sink cannot be told from earbuds (documented boundary).
        for (end in listOf(Gear.speaker, Gear.bleSpeaker, Gear.hearingAid)) assertFalse("${end.name}/${end.type}", HeadsetClassifier.isPersonalOutput(end))
        for (type in listOf(1, 5, 6, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 24, 25, 27, 28, 29, 30)) {
            assertNull("type $type", HeadsetClassifier.outputFamily(type))
        }
    }

    @Test
    fun `personal inputs need a microphone - headphones and A2DP are outputs only`() {
        for (end in listOf(Gear.wiredHeadsetMic, Gear.usbIn, Gear.scoIn, Gear.bleIn)) assertTrue("${end.type}", HeadsetClassifier.isPersonalInput(end))
        for (end in listOf(Gear.wiredHeadphones, Gear.a2dp, Gear.speaker, Gear.hearingAid)) assertFalse("${end.type}", HeadsetClassifier.isPersonalInput(end))
        assertTrue(HeadsetClassifier.needsCommunicationLink(Gear.scoIn))
        assertTrue(HeadsetClassifier.needsCommunicationLink(Gear.bleIn))
        assertFalse(HeadsetClassifier.needsCommunicationLink(Gear.wiredHeadsetMic))
        assertFalse(HeadsetClassifier.needsCommunicationLink(Gear.usbIn))
    }

    @Test
    fun `off means no headset whatever is connected`() {
        val devices = FakeDevices(listOf(Gear.speaker, Gear.wiredHeadset), listOf(Gear.wiredHeadsetMic))
        val p = policy(false, devices)
        assertNull(p.output())
        assertNull(p.unit())
        assertEquals(MicChoice.Normal, p.micChoice())
        assertFalse(p.enabled)
    }

    @Test
    fun `on with nothing connected is the normal behaviour`() {
        val p = policy(true, FakeDevices())
        assertTrue(p.enabled)
        assertNull(p.output())
        assertEquals(MicChoice.Normal, p.micChoice())
    }

    @Test
    fun `an input alone or a paired but absent device is not an output`() {
        val p = policy(true, FakeDevices(listOf(Gear.speaker), listOf(Gear.wiredHeadsetMic)))
        assertNull("microphone presence is not output presence", p.output())
    }

    @Test
    fun `wired headset with a microphone offers both  headphones only the output`() {
        val both = policy(true, FakeDevices(listOf(Gear.speaker, Gear.wiredHeadset), listOf(Gear.wiredHeadsetMic)))
        assertEquals(Gear.wiredHeadset, both.output())
        assertEquals(MicChoice.Headset(Gear.wiredHeadsetMic, Gear.wiredHeadset), both.micChoice())
        val phones = policy(true, FakeDevices(listOf(Gear.speaker, Gear.wiredHeadphones)))
        assertEquals(Gear.wiredHeadphones, phones.output())
        assertEquals(MicChoice.NoHeadsetMic(Gear.wiredHeadphones), phones.micChoice())
    }

    @Test
    fun `a Bluetooth microphone must be the same device as the output  and A2DP has none`() {
        val a2dpOnly = policy(true, FakeDevices(listOf(Gear.speaker, Gear.a2dp)))
        assertEquals(HeadsetFamily.BLUETOOTH, a2dpOnly.unit()?.family)
        assertEquals(MicChoice.NoHeadsetMic(Gear.a2dp), a2dpOnly.micChoice())
        val same = policy(true, FakeDevices(listOf(Gear.speaker, Gear.a2dp, Gear.scoOut), listOf(Gear.scoIn)))
        assertEquals("A2DP is the output, SCO supplies the microphone of the same address", Gear.a2dp, same.output())
        assertEquals(MicChoice.Headset(Gear.scoIn, Gear.a2dp), same.micChoice())
        val other = policy(true, FakeDevices(listOf(Gear.speaker, Gear.a2dp), listOf(Gear.otherScoIn)))
        assertEquals("another device's microphone is not this headset's", MicChoice.NoHeadsetMic(Gear.a2dp), other.micChoice())
    }

    @Test
    fun `wired is preferred over USB over BLE over Bluetooth when several are connected`() {
        val all = FakeDevices(listOf(Gear.speaker, Gear.a2dp, Gear.bleOut, Gear.usbOut, Gear.wiredHeadset))
        assertEquals(Gear.wiredHeadset, policy(true, all).output())
        all.set(listOf(Gear.speaker, Gear.a2dp, Gear.bleOut, Gear.usbOut))
        assertEquals(Gear.usbOut, policy(true, all).output())
        all.set(listOf(Gear.speaker, Gear.a2dp, Gear.bleOut))
        assertEquals(Gear.bleOut, policy(true, all).output())
    }

    @Test
    fun `connected means the same attachment  not any headset - a re-plugged one is a new device`() {
        val devices = FakeDevices(listOf(Gear.speaker, Gear.wiredHeadset))
        val p = policy(true, devices)
        val bound = p.output()!!
        assertTrue(p.connected(bound))
        devices.set(listOf(Gear.speaker))
        assertFalse(p.connected(bound))
        devices.set(listOf(Gear.speaker, AudioEndpoint(99, 3, "", "Wired headset")))
        assertFalse("a new attachment of the same kind is not the one that was bound", p.connected(bound))
    }

    @Test
    fun `the policy reads the live preference and endpoints every time`() {
        var on = false
        val devices = FakeDevices()
        val p = HeadsetPolicy({ on }, devices)
        assertNull(p.output())
        devices.connect(Gear.usbOut)
        assertNull(p.output())
        on = true
        assertEquals(Gear.usbOut, p.output())
        devices.disconnectAll()
        assertNull(p.output())
    }
}
