package com.rumi.hermesvoice.core.headset

/**
 * One audio endpoint as Android reports it (an `AudioDeviceInfo`), kept as plain data so the policy is tested without a device.
 * [id] is Android's per-attachment device id, [type] its `AudioDeviceInfo.TYPE_*` constant, [address] the Bluetooth address
 * (blank for wired and USB). A device that is unplugged and plugged in again is a NEW endpoint (new [id]).
 */
data class AudioEndpoint(val id: Int, val type: Int, val address: String = "", val name: String = "") {
    /** The same attachment of the same device: what "still connected" means. The name is only for the user. */
    fun sameDevice(other: AudioEndpoint): Boolean = id == other.id && type == other.type && address == other.address
}

/** `AudioDeviceInfo.TYPE_*` values this feature distinguishes (a Phone test pins them to the platform's constants). */
object EndpointType {
    const val BUILTIN_SPEAKER = 2
    const val WIRED_HEADSET = 3
    const val WIRED_HEADPHONES = 4
    const val BLUETOOTH_SCO = 7
    const val BLUETOOTH_A2DP = 8
    const val USB_HEADSET = 22
    const val BLE_HEADSET = 26
}

/** A headset is one physical personal-listening device; its families are tried in this order when several are connected. */
enum class HeadsetFamily { WIRED, USB, BLE, BLUETOOTH }

/**
 * The classification boundary. Personal listening OUTPUT: wired headphones/headsets, USB headsets, Bluetooth A2DP/SCO and BLE
 * headsets. Android reports no "this A2DP sink is a headset rather than a speaker" flag, so a Bluetooth speaker, a car kit or a
 * hearing aid is classified only by the type Android gives it: an A2DP sink counts (it cannot be told from earbuds), a BLE
 * speaker, a generic USB audio device, line out, HDMI, a dock and the built-in speaker do not. A paired-but-disconnected
 * device is not listed by Android at all, and input-only devices are not outputs.
 *
 * Personal INPUT: the microphone of a wired headset, a USB headset, a Bluetooth SCO link or a BLE headset. Wired headphones
 * without a microphone, and A2DP (a one-way media profile), have none.
 */
object HeadsetClassifier {
    fun outputFamily(type: Int): HeadsetFamily? = when (type) {
        EndpointType.WIRED_HEADSET, EndpointType.WIRED_HEADPHONES -> HeadsetFamily.WIRED
        EndpointType.USB_HEADSET -> HeadsetFamily.USB
        EndpointType.BLE_HEADSET -> HeadsetFamily.BLE
        EndpointType.BLUETOOTH_A2DP, EndpointType.BLUETOOTH_SCO -> HeadsetFamily.BLUETOOTH
        else -> null
    }

    fun inputFamily(type: Int): HeadsetFamily? = when (type) {
        EndpointType.WIRED_HEADSET -> HeadsetFamily.WIRED
        EndpointType.USB_HEADSET -> HeadsetFamily.USB
        EndpointType.BLE_HEADSET -> HeadsetFamily.BLE
        EndpointType.BLUETOOTH_SCO -> HeadsetFamily.BLUETOOTH
        else -> null
    }

    fun isPersonalOutput(endpoint: AudioEndpoint): Boolean = outputFamily(endpoint.type) != null

    fun isPersonalInput(endpoint: AudioEndpoint): Boolean = inputFamily(endpoint.type) != null

    /** Bluetooth input needs a bidirectional communication link (SCO / LE communication device), unlike A2DP media output. */
    fun needsCommunicationLink(endpoint: AudioEndpoint): Boolean =
        endpoint.type == EndpointType.BLUETOOTH_SCO || endpoint.type == EndpointType.BLE_HEADSET
}

/** The current audio endpoints and their changes (the Phone: `AudioManager.getDevices` and an `AudioDeviceCallback`). */
interface HeadsetDevices {
    fun outputs(): List<AudioEndpoint>

    fun inputs(): List<AudioEndpoint>

    /** [onChange] runs on any endpoint added or removed until the returned handle is closed. Registered only around a playback or a capture. */
    fun watch(onChange: () -> Unit): AutoCloseable
}

/** A headset as a unit: the output playback is bound to and, when it has one, the microphone of the SAME device. */
data class HeadsetUnit(val family: HeadsetFamily, val output: AudioEndpoint, val input: AudioEndpoint?)

/** How a new capture uses the headset, decided once when it starts (a later change of the setting never touches an accepted one). */
sealed class MicChoice {
    /** The setting is off or no headset is connected: the Phone's own microphone, no extra status. */
    object Normal : MicChoice()

    /** A headset is connected but supplies no usable microphone (output-only headphones, paired only). */
    data class NoHeadsetMic(val output: AudioEndpoint) : MicChoice()

    data class Headset(val input: AudioEndpoint, val output: AudioEndpoint) : MicChoice()
}

/**
 * "Use headset": the one Phone setting ([preference]) over the live endpoints ([devices]). Off is the existing behavior; on, a
 * connected personal headset takes the Phone's spoken answers and, when it has a microphone, the Phone's recordings.
 */
class HeadsetPolicy(private val preference: () -> Boolean, private val devices: HeadsetDevices) {
    val enabled: Boolean get() = preference()

    /** The connected headset, or null when the setting is off or none is connected (read now: never cached). */
    fun unit(): HeadsetUnit? {
        if (!enabled) return null
        val outputs = devices.outputs().filter(HeadsetClassifier::isPersonalOutput)
        val inputs = devices.inputs().filter(HeadsetClassifier::isPersonalInput)
        for (family in HeadsetFamily.values()) {
            val output = outputs.filter { HeadsetClassifier.outputFamily(it.type) == family }
                .minWithOrNull(compareBy<AudioEndpoint> { if (it.type == EndpointType.BLUETOOTH_A2DP) 0 else 1 }.thenBy { it.id }) ?: continue
            val input = inputs.filter { candidate ->
                HeadsetClassifier.inputFamily(candidate.type) == family &&
                    (family != HeadsetFamily.BLUETOOTH && family != HeadsetFamily.BLE || output.address.isBlank() || candidate.address.isBlank() || output.address == candidate.address)
            }.minByOrNull { it.id }
            return HeadsetUnit(family, output, input)
        }
        return null
    }

    fun output(): AudioEndpoint? = unit()?.output

    /** Whether [binding], the headset output a playback was bound to, is still connected (the same device, not any output). */
    fun connected(binding: AudioEndpoint): Boolean = devices.outputs().any { it.sameDevice(binding) }

    fun inputConnected(binding: AudioEndpoint): Boolean = devices.inputs().any { it.sameDevice(binding) }

    fun micChoice(): MicChoice {
        val unit = unit() ?: return MicChoice.Normal
        return unit.input?.let { MicChoice.Headset(it, unit.output) } ?: MicChoice.NoHeadsetMic(unit.output)
    }

    fun watch(onChange: () -> Unit): AutoCloseable = devices.watch(onChange)
}

/** What the user is told about the microphone a recording uses (shown only when the setting is on and a headset is connected). */
object HeadsetText {
    const val HEADSET_MIC = "headset microphone"
    const val NO_HEADSET_MIC = "the headset has no microphone: using the Phone microphone"
    const val ROUTE_UNAVAILABLE = "headset microphone unavailable: using the Phone microphone"
    const val NOT_CONFIRMED = "Android did not confirm the headset microphone: using the Phone microphone"
    const val NOT_ROUTED = "the headset microphone was not selected by Android: using the Phone microphone"
    const val PERMISSION = "Bluetooth permission not granted: using the Phone microphone"
    const val LINK_TIMEOUT = "the headset microphone did not connect in time: using the Phone microphone"
    const val CALL_ACTIVE = "a call or other audio mode is active: using the Phone microphone"
    const val LOST = "The headset disconnected: the recording was ended here and sent as far as it got"
    const val PLAYBACK_LOST = "not played: the headset disconnected"
    const val PLAYBACK_UNROUTED = "not played: Android did not route the speech to the headset"
    const val NO_HEADSET = "not played: the headset is no longer connected"
    const val NO_PRIVATE_SINK = "not played: the Phone cannot play to the headset"
}
