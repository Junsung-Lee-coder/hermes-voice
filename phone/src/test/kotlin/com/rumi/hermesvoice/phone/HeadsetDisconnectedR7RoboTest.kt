package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.FakeDevices
import com.rumi.hermesvoice.core.Gear
import com.rumi.hermesvoice.core.InMemoryKeyValueStore
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.PrivateWatchStatus
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Phone privacy monitor/store/publisher: no hardware or paired-device claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeadsetDisconnectedR7RoboTest {
    @Test fun togglingOnWithoutPersonalOutputNeverSuppressesWatch() {
        val settings = AppSettings(InMemoryKeyValueStore())
        val devices = FakeDevices(listOf(Gear.speaker))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val snapshots = mutableListOf<WatchSettings>()
        val hub = PrivateAudioHub(settings, HeadsetPolicy({ settings.useHeadset }, devices), scope,
            publish = { snapshots += it }, onCore = {})
        try {
            hub.refresh()
            repeat(3) {
                settings.useHeadset = true; hub.refresh()
                assertFalse(settings.watchSettings().privateAudio)
                assertEquals(PrivateWatchStatus.NOT_ACTIVE, hub.ledger.status())
                assertTrue(snapshots.none { it.privateAudio })
                settings.useHeadset = false; hub.refresh()
            }
        } finally { hub.close(); scope.cancel() }
    }

    @Test fun reentrantDisconnectCannotCommitAnOlderConnectedSnapshotAfterNewerNoOutput() {
        val settings = AppSettings(InMemoryKeyValueStore()).apply { useHeadset = true }
        val devices = FakeDevices(listOf(Gear.speaker))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val snapshots = mutableListOf<WatchSettings>()
        var disconnectOnce = true
        val hub = PrivateAudioHub(settings, HeadsetPolicy({ settings.useHeadset }, devices), scope,
            publish = { snapshots += it }, onCore = { active ->
                // Actual report callback boundary: private core admission may synchronously process local device loss.
                if (active && disconnectOnce) { disconnectOnce = false; devices.disconnectAll() }
            })
        try {
            hub.refresh()
            devices.connect(Gear.a2dp)
            assertNull(HeadsetPolicy({ settings.useHeadset }, devices).output())
            assertFalse("older connected callback cannot overwrite the newer disconnected state", settings.watchSettings().privateAudio)
            assertEquals(PrivateWatchStatus.NOT_ACTIVE, hub.ledger.status())
            assertTrue("never publish a stale suppression after device loss", snapshots.none { it.privateAudio })
            hub.refresh()
            assertFalse("duplicate no-output refresh must not leave suppression stuck", settings.watchSettings().privateAudio)
        } finally { hub.close(); scope.cancel() }
    }
}
