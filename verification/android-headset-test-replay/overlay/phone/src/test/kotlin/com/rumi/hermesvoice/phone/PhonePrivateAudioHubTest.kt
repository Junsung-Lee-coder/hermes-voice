package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.FakeDevices
import com.rumi.hermesvoice.core.Gear
import com.rumi.hermesvoice.core.InMemoryKeyValueStore
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.PrivateAudioReceipt
import com.rumi.hermesvoice.core.watchlink.PrivateWatchStatus
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Phone's private-output lifecycle object (amendment J) with the real settings store, a fake device set and a recording
 * publisher: it watches the devices only while "Use headset" is on, persists and publishes each change of the private state
 * to the Watch as a revisioned snapshot, tells the core, and counts the Watch as private only after its matching receipt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhonePrivateAudioHubTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val published: MutableList<WatchSettings> = Collections.synchronizedList(mutableListOf())
    private val core: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    private var failPublish = false
    private val store = InMemoryKeyValueStore()
    private val settings = AppSettings(store)
    private val hubs = mutableListOf<PrivateAudioHub>()

    @After fun tearDown() {
        hubs.forEach { it.close() }
        scope.cancel()
    }

    private fun hub(devices: FakeDevices, on: Boolean): PrivateAudioHub {
        settings.useHeadset = on
        return PrivateAudioHub(settings, HeadsetPolicy({ settings.useHeadset }, devices), scope,
            publish = { snapshot -> if (failPublish) throw IllegalStateException("watch unreachable"); published += snapshot },
            onCore = { core += it }).also { hubs += it }
    }

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what (published=${published.map { it.privateAudio to it.revision }}, core=$core)")
        }
    }

    @Test fun `off - nothing is watched nothing is published and the Watch is not asked to suppress anything`() {
        val devices = FakeDevices(listOf(Gear.speaker, Gear.a2dp))
        val hub = hub(devices, on = false)
        hub.refresh()
        assertEquals(0, devices.watching)
        assertTrue(published.isEmpty())
        assertEquals(PrivateWatchStatus.NOT_ACTIVE, hub.ledger.status())
        assertFalse(settings.watchSettings().privateAudio)
    }

    @Test fun `on with a headset connecting publishes one private snapshot with a newer revision and tells the core`() {
        val devices = FakeDevices(listOf(Gear.speaker))
        val hub = hub(devices, on = true)
        val before = settings.watchSettings().revision
        hub.refresh()
        assertEquals("watched while on", 1, devices.watching)
        assertTrue(published.isEmpty())
        devices.connect(Gear.a2dp)
        waitFor("the snapshot") { published.isNotEmpty() }
        val snapshot = published.single()
        assertTrue(snapshot.privateAudio)
        assertTrue(snapshot.revision > before)
        assertTrue(settings.watchSettings().privateAudio)
        assertEquals(PrivateWatchStatus.PENDING, hub.ledger.status())
        assertEquals(true, core.last())
    }

    @Test fun `a headset that is already connected when the app starts is published at the first refresh`() {
        val hub = hub(FakeDevices(listOf(Gear.speaker, Gear.wiredHeadphones)), on = true)
        hub.refresh()
        waitFor("the snapshot") { published.isNotEmpty() }
        assertTrue(published.single().privateAudio)
    }

    @Test fun `a private flag left over from a run in which the headset was removed is cleared at the first refresh`() {
        settings.useHeadset = true
        settings.savePrivateAudio(true)
        val hub = hub(FakeDevices(listOf(Gear.speaker)), on = true)
        hub.refresh()
        waitFor("the clearing snapshot") { published.isNotEmpty() }
        assertFalse(published.last().privateAudio)
        assertFalse(settings.watchSettings().privateAudio)
    }

    @Test fun `disconnecting or turning the setting off clears it and tells the Watch and the core`() {
        val devices = FakeDevices(listOf(Gear.speaker, Gear.a2dp))
        val hub = hub(devices, on = true)
        hub.refresh()
        waitFor("on") { published.size == 1 }
        devices.disconnectAll()
        waitFor("off by disconnect") { published.size == 2 }
        assertFalse(published.last().privateAudio)
        assertEquals(PrivateWatchStatus.NOT_ACTIVE, hub.ledger.status())
        devices.connect(Gear.a2dp)
        waitFor("on again") { published.size == 3 }
        settings.useHeadset = false
        hub.refresh()
        waitFor("off by setting") { published.size == 4 }
        assertFalse(published.last().privateAudio)
        assertEquals(0, devices.watching)
        assertEquals(listOf(false, true, false, true, false), core.toList().let { if (it.firstOrNull() == false) it else listOf(false) + it })
    }

    @Test fun `the Watch counts as private only after its receipt for the current revision`() {
        val devices = FakeDevices(listOf(Gear.speaker, Gear.a2dp))
        val hub = hub(devices, on = true)
        hub.refresh()
        waitFor("snapshot") { published.isNotEmpty() }
        val revision = published.single().revision
        assertEquals(PrivateWatchStatus.PENDING, hub.ledger.status())
        assertFalse(hub.onReceipt("not json".toByteArray()))
        assertFalse(hub.onReceipt(PrivateAudioReceipt(revision - 1, true).toJson().toByteArray()))
        assertEquals(PrivateWatchStatus.PENDING, hub.ledger.status())
        assertTrue(hub.onReceipt(PrivateAudioReceipt(revision, true).toJson().toByteArray()))
        assertEquals(PrivateWatchStatus.CONFIRMED, hub.ledger.status())
    }

    @Test fun `an unreachable Watch leaves the Phone private and the Watch pending - the failure is not hidden`() {
        failPublish = true
        val devices = FakeDevices(listOf(Gear.speaker, Gear.a2dp))
        val hub = hub(devices, on = true)
        hub.refresh()
        waitFor("the core was told") { core.contains(true) }
        assertTrue("the Phone's own privacy does not wait for the Watch", settings.watchSettings().privateAudio)
        assertEquals(PrivateWatchStatus.PENDING, hub.ledger.status())
        assertTrue(published.isEmpty())
    }

    @Test fun `closing releases the device watch`() {
        val devices = FakeDevices(listOf(Gear.speaker))
        val hub = hub(devices, on = true)
        hub.refresh()
        assertEquals(1, devices.watching)
        hub.close()
        assertEquals(0, devices.watching)
    }
}
