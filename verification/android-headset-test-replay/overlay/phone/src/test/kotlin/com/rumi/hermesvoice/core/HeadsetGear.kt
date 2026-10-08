package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.headset.AudioEndpoint
import com.rumi.hermesvoice.core.headset.HeadsetDevices
import java.util.concurrent.CopyOnWriteArrayList

/** Endpoints as Android reports them; the ids are the per-attachment ids (a re-plugged device gets a new one). */
object Gear {
    val speaker = AudioEndpoint(1, 2, "", "Phone speaker")
    val wiredHeadset = AudioEndpoint(11, 3, "", "Wired headset")
    val wiredHeadsetMic = AudioEndpoint(12, 3, "", "Wired headset")
    val wiredHeadphones = AudioEndpoint(13, 4, "", "Wired headphones")
    val usbOut = AudioEndpoint(21, 22, "", "USB headset")
    val usbIn = AudioEndpoint(22, 22, "", "USB headset")
    val a2dp = AudioEndpoint(31, 8, "AA:BB:CC:00:00:01", "Buds")
    val scoOut = AudioEndpoint(32, 7, "AA:BB:CC:00:00:01", "Buds")
    val scoIn = AudioEndpoint(33, 7, "AA:BB:CC:00:00:01", "Buds")
    val otherScoIn = AudioEndpoint(34, 7, "AA:BB:CC:00:00:99", "Other")
    val bleOut = AudioEndpoint(41, 26, "11:22:33:44:55:66", "LE buds")
    val bleIn = AudioEndpoint(42, 26, "11:22:33:44:55:66", "LE buds")
    val bleSpeaker = AudioEndpoint(43, 27, "11:22:33:44:55:77", "LE speaker")
    val hearingAid = AudioEndpoint(51, 23, "", "Hearing aid")
    val carA2dp = AudioEndpoint(61, 8, "CA:CA:CA:00:00:01", "Car")
}

/** The live endpoints with a change callback, like AudioManager + AudioDeviceCallback. */
class FakeDevices(outputs: List<AudioEndpoint> = listOf(Gear.speaker), inputs: List<AudioEndpoint> = emptyList()) : HeadsetDevices {
    @Volatile private var out = outputs
    @Volatile private var inp = inputs
    private val watchers = CopyOnWriteArrayList<() -> Unit>()
    val watching: Int get() = watchers.size

    override fun outputs() = out
    override fun inputs() = inp
    override fun watch(onChange: () -> Unit): AutoCloseable {
        watchers += onChange
        return AutoCloseable { watchers -= onChange }
    }

    /** Replaces the connected endpoints and tells the watchers. */
    fun set(outputs: List<AudioEndpoint>, inputs: List<AudioEndpoint> = emptyList()) {
        out = outputs
        inp = inputs
        watchers.forEach { it() }
    }

    fun connect(vararg ends: AudioEndpoint, inputs: List<AudioEndpoint> = emptyList()) = set(listOf(Gear.speaker) + ends, inputs)
    fun disconnectAll() = set(listOf(Gear.speaker), emptyList())
}
