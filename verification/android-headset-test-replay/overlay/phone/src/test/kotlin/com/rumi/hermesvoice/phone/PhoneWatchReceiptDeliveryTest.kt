package com.rumi.hermesvoice.phone

import android.content.Intent
import android.net.Uri
import com.google.android.gms.wearable.MessageEvent
import com.rumi.hermesvoice.core.FakeDevices
import com.rumi.hermesvoice.core.Gear
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.settings.ReplicaUpdate
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.settings.WatchSettingsReplica
import com.rumi.hermesvoice.core.watchlink.PrivateAudioReceipt
import com.rumi.hermesvoice.core.watchlink.PrivateWatchStatus
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The Watch's private-output receipt reaching the Phone ledger through the PRODUCTION boundaries
 * this module can run: the Phone's merged manifest (which message paths Android would deliver to [PhoneWatchListenerService]),
 * the real [PhoneWatchListenerService.onMessageReceived], the real [PrivateAudioHub] and ledger, the real settings store and the
 * real Watch settings replica. The Wear message bridge itself (Google Play services delivering a real message) is NOT exercised.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneWatchReceiptDeliveryTest {
    private lateinit var app: PhoneApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val published: MutableList<WatchSettings> = Collections.synchronizedList(mutableListOf())
    private val core: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    private lateinit var devices: FakeDevices
    private lateinit var hub: PrivateAudioHub

    private fun substitute(name: String, value: Any) {
        val field = PhoneApp::class.java.getDeclaredField("$name\$delegate")
        field.isAccessible = true
        field.set(app, lazyOf(value))
    }

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        app = RuntimeEnvironment.getApplication() as PhoneApp
        devices = FakeDevices(listOf(Gear.speaker, Gear.a2dp))
        app.settings.useHeadset = true
        hub = PrivateAudioHub(app.settings, HeadsetPolicy({ app.settings.useHeadset }, devices), scope,
            publish = { snapshot -> published += snapshot }, onCore = { core += it })
        substitute("privateAudio", hub)
    }

    @After fun tearDown() {
        hub.close()
        scope.cancel()
    }

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    private fun resolving(path: String): List<String> {
        val intent = Intent("com.google.android.gms.wearable.MESSAGE_RECEIVED")
            .setPackage(app.packageName)
            .setData(Uri.parse("wear://watch-node$path"))
        return app.packageManager.queryIntentServices(intent, 0).map { it.serviceInfo.name }
    }

    private fun deliver(path: String, data: ByteArray, node: String = "watch-node") {
        val service = Robolectric.setupService(PhoneWatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns path
        every { event.sourceNodeId } returns node
        every { event.data } returns data
        service.onMessageReceived(event)
    }

    @Test fun W01_theMergedPhoneManifestDeliversThePrivateAudioReceiptPathToTheListenerServiceAndNoOtherUnhandledPath() {
        val service = PhoneWatchListenerService::class.java.name
        assertTrue("the receipt path is delivered to the Phone service", service in resolving(WatchLinkPaths.PRIVATE_AUDIO))
        for (path in listOf(WatchLinkPaths.PLAYED, WatchLinkPaths.PLAY_PROGRESS, WatchLinkPaths.CANCEL, WatchLinkPaths.DIAG_RESPONSE,
            WatchLinkPaths.READER_REQUEST, WatchLinkPaths.WAKE_CLAIM)) {
            assertTrue("$path still delivered", service in resolving(path))
        }
        assertFalse("a path the service does not handle is not delivered", service in resolving("/hv/v1/private_audio_other"))
        assertFalse(service in resolving(WatchLinkPaths.SETTINGS))
        assertFalse(service in resolving("/other/private_audio"))
    }

    @Test fun W02_aSnapshotIsPersistedTheWatchReplicaRefusesAudioAndItsReceiptThroughTheListenerConfirmsThePhoneLedger() {
        hub.refresh()
        waitFor("the private snapshot") { published.isNotEmpty() }
        val snapshot = published.single()
        assertTrue(snapshot.privateAudio)
        assertTrue("persisted before and independent of the Watch", app.settings.watchSettings().privateAudio)
        assertEquals(snapshot.revision, app.settings.watchSettings().revision)
        assertEquals(PrivateWatchStatus.PENDING, hub.ledger.status())

        val replica = WatchSettingsReplica(null)
        assertEquals(ReplicaUpdate.APPLIED, replica.offer(snapshot.toJson()))
        assertTrue("the Watch now refuses to play", replica.current.privateAudio)
        val receipt = PrivateAudioReceipt(replica.current.revision, replica.current.privateAudio)

        deliver(WatchLinkPaths.PRIVATE_AUDIO, receipt.toJson().toByteArray(Charsets.UTF_8))
        assertEquals(PrivateWatchStatus.CONFIRMED, hub.ledger.status())
        assertTrue("true: the Phone's own privacy never depended on it", core.last())
    }

    @Test fun W03_aStaleOrNotSuppressingOrGarbledReceiptThroughTheListenerNeverConfirms() {
        hub.refresh()
        waitFor("the private snapshot") { published.isNotEmpty() }
        val revision = published.single().revision
        deliver(WatchLinkPaths.PRIVATE_AUDIO, PrivateAudioReceipt(revision - 1, true).toJson().toByteArray())
        deliver(WatchLinkPaths.PRIVATE_AUDIO, PrivateAudioReceipt(revision, false).toJson().toByteArray())
        deliver(WatchLinkPaths.PRIVATE_AUDIO, "not json".toByteArray())
        deliver(WatchLinkPaths.PRIVATE_AUDIO, ByteArray(0))
        assertEquals(PrivateWatchStatus.PENDING, hub.ledger.status())
        deliver(WatchLinkPaths.PRIVATE_AUDIO, PrivateAudioReceipt(revision, true).toJson().toByteArray())
        assertEquals(PrivateWatchStatus.CONFIRMED, hub.ledger.status())
        deliver(WatchLinkPaths.PRIVATE_AUDIO, PrivateAudioReceipt(revision, true).toJson().toByteArray())
        assertEquals("a repeated receipt changes nothing", PrivateWatchStatus.CONFIRMED, hub.ledger.status())
    }

    @Test fun W04_aReceiptForASnapshotThatWasSupersededByLeavingPrivateModeNeverConfirms() {
        hub.refresh()
        waitFor("the private snapshot") { published.size == 1 }
        val first = published.single().revision
        devices.disconnectAll()
        waitFor("the clearing snapshot") { published.size == 2 }
        assertEquals(PrivateWatchStatus.NOT_ACTIVE, hub.ledger.status())
        deliver(WatchLinkPaths.PRIVATE_AUDIO, PrivateAudioReceipt(first, true).toJson().toByteArray())
        assertEquals(PrivateWatchStatus.NOT_ACTIVE, hub.ledger.status())
    }
}
