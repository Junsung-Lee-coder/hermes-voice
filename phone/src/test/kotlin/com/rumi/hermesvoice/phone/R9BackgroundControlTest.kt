package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.os.Looper
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.FakeDevices
import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.Gear
import com.rumi.hermesvoice.core.TestAudio
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.headset.AudioEndpoint
import com.rumi.hermesvoice.core.headset.CommsLink
import com.rumi.hermesvoice.core.headset.HeadsetMicRoute
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.headset.HeadsetText
import com.rumi.hermesvoice.core.headset.LinkResult
import com.rumi.hermesvoice.core.headset.RecordingCommand
import com.rumi.hermesvoice.core.headset.RecordingCueTone
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). Owner policy F2: a Phone recording that is ALREADY open keeps its headset media control (and its
 * one MediaSession) while the app is hidden, so the headset can still finish that exact recording; a hidden app never opens a new
 * one; and when the control of a hidden headset-started recording cannot be kept, that recording (only) is ended and discarded.
 * Same production chain as [PhoneHeadsetRecordingFlowTest] (real [HeadsetMediaControl], real [PhoneViewModel], real claim /
 * [WavRecorder] / [RecordingCues]); only the leaves are fakes. Hiding the app is the same flag plus refresh that
 * `PhoneApp.onActivityStopped` performs. No physical key, headset, microphone, MediaSession delivery or lock screen (NOT_RUN).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class R9BackgroundControlTest {
    private lateinit var app: PhoneApp
    private lateinit var fake: FakeHermesDashboard
    private val store = ViewModelStore()

    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val cuePlaying = AtomicBoolean(false)
    private val ring: ByteArray = TestAudio.speechWav().let { it.copyOfRange(44, it.size) }
    private val created = AtomicInteger()

    private var devices = FakeDevices(listOf(Gear.speaker, Gear.wiredHeadset), listOf(Gear.wiredHeadsetMic))

    private inner class FakeRecord : RecordPort {
        private var position = 12_800
        override val initialized get() = true
        override fun setPreferredDevice(deviceId: Int) = true
        override fun routedDeviceId(): Int? = Gear.wiredHeadsetMic.id
        override fun start(): Boolean { events += "mic.start"; return true }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(5)
            if (cuePlaying.get()) {
                for (i in offset until offset + length step 2) { buffer[i] = (CUE and 0xFF).toByte(); buffer[i + 1] = (CUE shr 8).toByte() }
            } else {
                for (i in 0 until length) buffer[offset + i] = ring[(position + i) % ring.size]
                position = (position + length) % ring.size
            }
            return length
        }
        override fun stop() { events += "mic.stop" }
        override fun release() { events += "mic.release" }
    }

    private class FakePort : MediaSessionPort {
        val recording = mutableListOf<Boolean>()
        var released = 0
        override fun setRecording(recording: Boolean) { this.recording += recording }
        override fun release() { released++ }
    }

    private val callbacks = mutableListOf<MediaSession.Callback>()
    private val ports = mutableListOf<FakePort>()
    private val sessions = MediaSessionFactory { callback -> callbacks += callback; FakePort().also { ports += it } }
    private val liveSessions get() = ports.count { it.released == 0 }

    private class PlayedCue(val kind: Int, val done: (CueResult) -> Unit) { var closed = 0 }

    private val cuesPlayed = mutableListOf<PlayedCue>()
    private val cueOutput = CueOutput { pcm, _, target, done ->
        val kind = when {
            pcm.contentEquals(RecordingCueTone.pcm(1)) -> 1
            pcm.contentEquals(RecordingCueTone.pcm(2)) -> 2
            else -> 0
        }
        events += "cue:$kind@${target.id}"
        cuePlaying.set(true)
        val played = PlayedCue(kind, done)
        cuesPlayed += played
        AutoCloseable { played.closed++ }
    }

    private class DeferredLink : CommsLink {
        var onResult: ((LinkResult) -> Unit)? = null
        var closed = 0
        override fun acquire(input: AudioEndpoint, onResult: (LinkResult) -> Unit): AutoCloseable {
            this.onResult = onResult
            return AutoCloseable { closed++ }
        }
    }

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val info = mockk<CapabilityInfo>()
        every { info.nodes } returns emptySet()
        every { info.name } returns "hermes_voice_watch"
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } returns Tasks.forResult(info)
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } returns Tasks.forResult(1)
        val channels = mockk<ChannelClient>(relaxed = true)
        val data = mockk<DataClient>(relaxed = true)
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { Wearable.getDataClient(any<Context>()) } returns data
        every { Wearable.getDataClient(any<android.app.Activity>()) } returns data
    }

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun await(timeoutMs: Long = 8_000, done: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            idle(50)
            if (done()) return true
            if (System.nanoTime() > end) return false
            Thread.sleep(25)
        }
    }

    private fun substitute(name: String, value: Any) {
        val field = PhoneApp::class.java.getDeclaredField("$name\$delegate")
        field.isAccessible = true
        field.set(app, lazyOf(value))
    }

    private fun setVisible(visible: Boolean) {
        val field = PhoneApp::class.java.getDeclaredField("activityVisible")
        field.isAccessible = true
        field.setBoolean(app, visible)
        app.headsetMedia.refresh()
    }

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        app = RuntimeEnvironment.getApplication() as PhoneApp
        stubWearable()
        R9TestAdmission.install(app)
        fake = FakeHermesDashboard()
        app.settings.dashboardUrl = fake.baseUrl
        app.tokens.save(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        substitute("recordFactory", RecordFactory { created.incrementAndGet(); FakeRecord() })
        substitute("mediaSessions", sessions)
        substitute("cueOutput", cueOutput)
        useDevices(devices)
    }

    private fun useDevices(next: FakeDevices) {
        devices = next
        substitute("headset", HeadsetPolicy({ app.settings.useHeadset }, devices))
    }

    @After fun tearDown() {
        runCatching {
            val controller = PhoneApp::class.java.getMethod("getManualCapture").invoke(app)
            controller.javaClass.getMethod("cancel", String::class.java, Boolean::class.javaPrimitiveType).invoke(controller, "fixture ended", false)
        }
        runCatching { store.clear() }
        runCatching { fake.close() }
        unmockkAll()
    }

    private fun model(): PhoneViewModel {
        val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[PhoneViewModel::class.java]
        val owned = runBlocking { app.wiring().core.sessions.createConversation("Work", "work", "work things") }
        fake.scripts[owned.storedSessionId] = { emptyList() }
        model.open(owned)
        assertTrue(await { model.state.value.selected?.storedSessionId == owned.storedSessionId })
        return model
    }

    private fun ready(): PhoneViewModel {
        val model = model()
        model.setUseHeadset(true)
        setVisible(true)
        assertEquals(MediaControlStatus.REGISTERED, app.headsetMedia.status.value)
        return model
    }

    private var presses = 0L
    private fun press(index: Int = callbacks.lastIndex, code: Int = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
        idle(500)
        val down = 100_000L + ++presses * 10_000
        val callback = callbacks[index]
        callback.onMediaButtonEvent(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0)))
        callback.onMediaButtonEvent(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(down, down, KeyEvent.ACTION_UP, code, 0)))
        idle(50)
    }

    private fun finishCue(result: CueResult = CueResult.PLAYED) {
        cuePlaying.set(false)
        cuesPlayed.last().done(result)
        idle(50)
    }

    private fun starts() = events.count { it == "mic.start" }
    private fun stops() = events.count { it == "mic.stop" }
    private fun releases() = events.count { it == "mic.release" }
    private fun transcribes() = fake.timeline.count { it.startsWith("transcribe:") }
    private fun cueEvents() = events.filter { it.startsWith("cue:") }
    private fun recordSomeSpeech() { Thread.sleep(400); idle(50) }

    @Test fun R901_hiddenIdleKeepsLiveControlsAndAcceptsFreshStart() {
        val model = ready()
        setVisible(false)
        assertEquals("live opt-in controls survive HOME", 1, liveSessions)
        press()
        assertTrue(await { starts() == 1 })
        assertTrue(model.state.value.recording)
    }

    @Test fun R902_screenDisposalDoesNotOwnManualCaptureOrStopTarget() {
        ready()
        press()
        finishCue()
        recordSomeSpeech()
        setVisible(false)
        store.clear()
        idle(100)
        assertEquals("screen disposal must not close service capture", 0, stops())
        assertEquals(1, liveSessions)
        press()
        assertEquals(1, stops())
        assertTrue(await { transcribes() == 1 })
    }

    @Test fun R903_deniedAdmissionNeverOpensMic() {
        val model = ready()
        R9TestAdmission.admitted = false
        press()
        idle(200)
        assertEquals("actual admission denial closes start", 0, created.get())
        assertFalse(model.state.value.recording)
        assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
    }

    @Test fun R904_stopDuringPendingAdmissionFencesLateSuccess() {
        val model = ready()
        R9TestAdmission.admitted = null
        press(code = KeyEvent.KEYCODE_MEDIA_PLAY)
        assertEquals("pending admission is not permission to open", 0, created.get())
        press(code = KeyEvent.KEYCODE_MEDIA_PAUSE)
        R9TestAdmission.pending?.invoke(true)
        idle(200)
        assertEquals(0, created.get())
        assertFalse(model.state.value.recording)
        assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
    }

    @Test fun R905_missingDestinationRefusesInsteadOfInventingConversation() {
        val model = ready()
        app.selectedConversationId = null
        app.settings.routingEnabled = false
        press()
        assertEquals(0, created.get())
        assertFalse(model.state.value.recording)
    }

    @Test fun R906_hideDuringBluetoothPrepareDoesNotInvalidateAdmittedGeneration() {
        useDevices(FakeDevices(listOf(Gear.speaker, Gear.a2dp, Gear.scoOut), listOf(Gear.scoIn)))
        val link = DeferredLink()
        substitute("headsetMic", HeadsetMicRoute(app.headset, link))
        val model = ready()
        press()
        assertNotNull(link.onResult)
        setVisible(false)
        link.onResult!!.invoke(LinkResult.READY)
        idle(100)
        assertEquals(1, created.get())
        assertTrue(model.state.value.recording)
    }

    @Test fun R907_staleServiceStartCannotReplaceOrDestroyNewerAdmittedCapture() {
        val model = ready()
        press()
        val type = runCatching { Class.forName("com.rumi.hermesvoice.phone.PhoneManualService") }.getOrNull()
        assertNotNull("microphone service exists", type)
        @Suppress("UNCHECKED_CAST")
        val service = org.robolectric.Robolectric.buildService(type as Class<android.app.Service>).create().get()
        val field = Class.forName("com.rumi.hermesvoice.phone.PhoneManualCapture").getDeclaredField("generation").apply { isAccessible = true }
        val controller = PhoneApp::class.java.getMethod("getManualCapture").invoke(app)
        val token = field.getLong(controller)
        service.onStartCommand(Intent(app, type).setAction("com.rumi.hermesvoice.phone.MANUAL_START").putExtra("generation", token), 0, 1)
        service.onStartCommand(Intent(app, type).setAction("com.rumi.hermesvoice.phone.MANUAL_START").putExtra("generation", token - 1), 0, 2)
        assertEquals("stale delivery does not overwrite the live service generation", token,
            type.getDeclaredField("generation").apply { isAccessible = true }.getLong(service))
        assertEquals(0, stops())
        assertTrue(model.state.value.recording)
        service.onDestroy()
        idle(100)
        assertEquals(1, stops())
        assertFalse(model.state.value.recording)
    }

    @Test fun R908_noisySignalAfterStartCueEndsOnlyOwnedCaptureEvenWithStaleDeviceList() {
        val model = ready()
        val watch = app.audio.claimMicrophone(VoiceOrigin.WATCH)
        press(); finishCue(); recordSomeSpeech()
        app.sendBroadcast(Intent(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle(100)
        assertEquals("explicit noisy signal closes microphone without stale enumeration authority", 1, stops())
        assertFalse(model.state.value.recording)
        assertEquals(0, transcribes())
        assertTrue(watch.held)
        assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
        watch.release()
    }

    @Test fun R909_manualHandoffRequiresActualExclusiveRecognizerReleaseAndDoesNotTouchWatch() {
        ready()
        val watch = app.audio.claimMicrophone(VoiceOrigin.WATCH)
        val arbiter = PhoneApp::class.java.getMethod("getPhoneMic").invoke(app)
        val acquire = arbiter.javaClass.getMethod("acquire", Boolean::class.javaPrimitiveType, kotlin.jvm.functions.Function0::class.java)
        var releaseCount = 0
        lateinit var recognizer: AutoCloseable
        recognizer = acquire.invoke(arbiter, true, { releaseCount++; events.add("recognizer.release"); recognizer.close(); true }) as AutoCloseable
        press()
        assertEquals(1, releaseCount)
        assertTrue(events.indexOf("recognizer.release") < events.indexOf("mic.start"))
        assertEquals(1, created.get())
        assertEquals("no recognizer can share the Phone recorder", null, acquire.invoke(arbiter, true, { true }))
        assertTrue(watch.held)
        press()
        assertEquals(1, stops())
        watch.release()
    }

    @Test fun R910_serviceTeardownDuringStopCueCancelsTheOwnedCueButNotWatchOrSubmittedTurn() {
        ready()
        val watch = app.audio.claimMicrophone(VoiceOrigin.WATCH)
        press()
        val type = Class.forName("com.rumi.hermesvoice.phone.PhoneManualService")
        @Suppress("UNCHECKED_CAST")
        val service = org.robolectric.Robolectric.buildService(type as Class<android.app.Service>).create().get()
        val controller = PhoneApp::class.java.getMethod("getManualCapture").invoke(app)
        val token = controller.javaClass.getDeclaredField("generation").apply { isAccessible = true }.getLong(controller)
        service.onStartCommand(Intent(app, type).setAction("com.rumi.hermesvoice.phone.MANUAL_START").putExtra("generation", token), 0, 1)
        finishCue(); recordSomeSpeech(); press()
        val stopTone = cuesPlayed.last()
        assertEquals(1, stops())
        assertEquals(0, stopTone.closed)
        service.onDestroy()
        idle(100)
        assertEquals("service teardown cancels old-generation stop cue too", 1, stopTone.closed)
        assertTrue(watch.held)
        assertTrue(await { transcribes() == 1 })
        watch.release()
    }

    @Test fun R911_anEarlierSubmittedTurnCannotOverwriteTheCurrentRecordingStatus() {
        val model = ready()
        fake.transcribeDelayMs = 1_500
        press(); finishCue(); recordSomeSpeech(); press(); finishCue()
        assertTrue(await { transcribes() == 1 })
        press(); finishCue()
        assertTrue(model.state.value.recording)
        assertTrue(await { app.phoneTurns.value == 0 })
        assertTrue("an older turn outcome is not the current microphone status", model.state.value.voiceStatus.contains("Listening"))
        assertTrue(model.state.value.recording)
        assertEquals(2, starts())
        assertEquals(1, stops())
    }

    private companion object { const val CUE = 0x7F7F }

    private fun r11Transport(play: Boolean) {
        idle(500)
        if (play) callbacks.last().onPlay() else callbacks.last().onPause()
        idle(50)
    }
    private fun r11Pending(play: Boolean, bluetooth: Boolean) {
        val link = DeferredLink()
        if (bluetooth) {
            useDevices(FakeDevices(listOf(Gear.speaker, Gear.a2dp, Gear.scoOut), listOf(Gear.scoIn)))
            substitute("headsetMic", HeadsetMicRoute(app.headset, link))
        }
        ready()
        if (!bluetooth) R9TestAdmission.admitted = null
        val watch = app.audio.claimMicrophone(VoiceOrigin.WATCH)
        r11Transport(play)
        assertTrue("start is pending without opening microphone", app.manualCapture.busy)
        assertEquals(0, created.get())
        val admission = R9TestAdmission.pending
        if (bluetooth) assertNotNull(link.onResult) else assertNotNull(admission)
        r11Transport(play)
        assertFalse("fresh same callback cancels pending start", app.manualCapture.busy)
        if (bluetooth) {
            assertEquals(1, link.closed)
            link.onResult!!.invoke(LinkResult.READY)
        } else admission!!.invoke(true)
        idle(200)
        assertEquals("late success cannot resurrect the microphone", 0, created.get())
        assertFalse(app.manualCapture.state.value.recording)
        assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
        assertEquals(0, transcribes())
        assertTrue(cueEvents().isEmpty())
        assertTrue("Watch remains independent", watch.held)
        watch.release()
    }
    @Test fun R11_pendingAdmissionPlayPlay() = r11Pending(true, false)
    @Test fun R11_pendingAdmissionPausePause() = r11Pending(false, false)
    @Test fun R11_pendingBluetoothPlayPlay() = r11Pending(true, true)
    @Test fun R11_pendingBluetoothPausePause() = r11Pending(false, true)

    private fun r11Hidden(play: Boolean) {
        ready()
        r11Transport(play)
        assertEquals(1, starts())
        finishCue()
        recordSomeSpeech()
        val watch = app.audio.claimMicrophone(VoiceOrigin.WATCH)
        setVisible(false) // HOME/lock modeled with the production ownership flag.
        store.clear() // No screen target remains: Application capture owns Stop.
        idle(100)
        assertEquals(0, stops())
        assertEquals(1, liveSessions)
        r11Transport(play)
        assertEquals(1, stops())
        assertEquals(1, releases())
        assertFalse(app.manualCapture.busy)
        assertEquals(listOf("cue:1@11", "cue:2@11"), cueEvents())
        assertTrue(await { transcribes() == 1 })
        idle(500)
        assertEquals(1, transcribes())
        assertTrue(watch.held)
        watch.release()
    }
    @Test fun R11_hiddenDisposedPlayPlay() = r11Hidden(true)
    @Test fun R11_hiddenDisposedPausePause() = r11Hidden(false)
}
