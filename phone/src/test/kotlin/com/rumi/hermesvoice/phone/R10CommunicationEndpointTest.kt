package com.rumi.hermesvoice.phone

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import com.rumi.hermesvoice.core.headset.AudioEndpoint
import com.rumi.hermesvoice.core.headset.EndpointType
import com.rumi.hermesvoice.core.headset.LinkResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers

/** Exercises the production Android adapter, with real SDK34 device handles and AudioManager shadows; no mic is opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class R10CommunicationEndpointTest {
    private lateinit var app: Application
    private lateinit var audio: AudioManager
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        audio = app.getSystemService(AudioManager::class.java)
    }
    private fun output(id: Int, type: Int, address: String): AudioDeviceInfo {
        val info = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        val port = ReflectionHelpers.getField<Any>(info, "mPort")
        val handle = ReflectionHelpers.getField<Any>(port, "mHandle")
        ReflectionHelpers.setField(handle, "mId", id)
        ReflectionHelpers.setField(port, "mRole", 2) // AudioPort.ROLE_SINK
        ReflectionHelpers.setField(port, "mAddress", address)
        assertEquals(id, info.id); assertEquals(type, info.type); assertEquals(address, info.address)
        assertTrue(info.isSink)
        return info
    }
    private fun acquire(input: AudioEndpoint): Pair<MutableList<LinkResult>, AutoCloseable> {
        val results = mutableListOf<LinkResult>()
        val held = AndroidCommsLink(app, 100).acquire(input) { results += it }
        shadowOf(Looper.getMainLooper()).idle()
        return results to held
    }
    @Test fun bleInputAndCommunicationOutputUseDifferentIdsOfTheSameHeadset() {
        val wrong = output(10, AudioDeviceInfo.TYPE_BLE_HEADSET, "fixture-other")
        val wanted = output(20, AudioDeviceInfo.TYPE_BLE_HEADSET, "fixture-one")
        shadowOf(audio).setAvailableCommunicationDevices(listOf(wrong, wanted))
        val (results, held) = acquire(AudioEndpoint(30, EndpointType.BLE_HEADSET, "fixture-one"))
        assertEquals(listOf(LinkResult.READY), results)
        assertEquals(20, audio.communicationDevice?.id)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        held.close(); held.close()
        assertNull(audio.communicationDevice); assertEquals(AudioManager.MODE_NORMAL, audio.mode)
    }
    @Test fun scoInputAndCommunicationOutputUseDifferentIdsOfTheSameHeadset() {
        val wanted = output(21, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "fixture-one")
        shadowOf(audio).setAvailableCommunicationDevices(listOf(wanted))
        val (results, held) = acquire(AudioEndpoint(31, EndpointType.BLUETOOTH_SCO, "fixture-one"))
        assertEquals(listOf(LinkResult.READY), results); assertEquals(21, audio.communicationDevice?.id)
        held.close(); assertEquals(AudioManager.MODE_NORMAL, audio.mode)
    }
    @Test fun matchingTypeNeverSelectsAnotherKnownAddressOrAnA2dpOutput() {
        shadowOf(audio).setAvailableCommunicationDevices(listOf(
            output(20, AudioDeviceInfo.TYPE_BLE_HEADSET, "fixture-other"),
            output(21, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "fixture-one")))
        val (results, held) = acquire(AudioEndpoint(30, EndpointType.BLE_HEADSET, "fixture-one"))
        assertEquals(listOf(LinkResult.UNAVAILABLE), results); assertNull(audio.communicationDevice)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode); held.close()
    }
    @Test fun ambiguousUnknownAddressesAreRefusedRatherThanFirstBluetoothSelection() {
        shadowOf(audio).setAvailableCommunicationDevices(listOf(
            output(20, AudioDeviceInfo.TYPE_BLE_HEADSET, ""), output(21, AudioDeviceInfo.TYPE_BLE_HEADSET, "")))
        val (results, held) = acquire(AudioEndpoint(30, EndpointType.BLE_HEADSET, ""))
        assertEquals(listOf(LinkResult.UNAVAILABLE), results); assertNull(audio.communicationDevice); held.close()
    }
    @Test fun uniqueUnknownAddressStillUsesTheSameCommunicationFamily() {
        shadowOf(audio).setAvailableCommunicationDevices(listOf(
            output(20, AudioDeviceInfo.TYPE_BLE_HEADSET, ""), output(21, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "")))
        val (results, held) = acquire(AudioEndpoint(30, EndpointType.BLE_HEADSET, ""))
        assertEquals(listOf(LinkResult.READY), results); assertEquals(20, audio.communicationDevice?.id); held.close()
    }
    @Test fun foreignCommunicationModeIsNeverTakenOver() {
        audio.mode = AudioManager.MODE_IN_CALL
        shadowOf(audio).setAvailableCommunicationDevices(listOf(output(20, AudioDeviceInfo.TYPE_BLE_HEADSET, "fixture-one")))
        val (results, held) = acquire(AudioEndpoint(30, EndpointType.BLE_HEADSET, "fixture-one"))
        assertEquals(listOf(LinkResult.IN_USE), results); assertEquals(AudioManager.MODE_IN_CALL, audio.mode)
        assertNull(audio.communicationDevice); held.close(); assertEquals(AudioManager.MODE_IN_CALL, audio.mode)
    }
    @Test fun missingBluetoothPermissionIsRefusedWithoutChangingMode() {
        shadowOf(app).denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        val (results, held) = acquire(AudioEndpoint(30, EndpointType.BLE_HEADSET, "fixture-one"))
        assertEquals(listOf(LinkResult.PERMISSION), results); assertEquals(AudioManager.MODE_NORMAL, audio.mode); held.close()
    }
    @Test fun readyCommunicationOutputDoesNotReplaceTheRecorderInputIdentity() {
        shadowOf(audio).setAvailableCommunicationDevices(listOf(output(20, AudioDeviceInfo.TYPE_BLE_HEADSET, "fixture-one")))
        val input = AudioEndpoint(30, EndpointType.BLE_HEADSET, "fixture-one")
        val devices = object : com.rumi.hermesvoice.core.headset.HeadsetDevices {
            override fun outputs() = listOf(AudioEndpoint(20, EndpointType.BLE_HEADSET, "fixture-one"))
            override fun inputs() = listOf(input)
            override fun watch(onChange: () -> Unit) = AutoCloseable { }
        }
        var plan: com.rumi.hermesvoice.core.headset.MicPlan? = null
        com.rumi.hermesvoice.core.headset.HeadsetMicRoute(
            com.rumi.hermesvoice.core.headset.HeadsetPolicy({ true }, devices), AndroidCommsLink(app))
            .prepare { plan = it }
        assertEquals(input, plan?.preferred)
        assertFalse("communication sink is not recorder input proof", plan!!.report(20).headset)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
    }
}
