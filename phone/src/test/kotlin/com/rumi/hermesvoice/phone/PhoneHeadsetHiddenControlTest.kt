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
class PhoneHeadsetHiddenControlTest {
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

    @Test fun H01_aHeadsetStartedRecordingStillStopsFromTheHeadsetAfterTheAppIsHiddenAndSendsExactlyOnce() {
        val model = ready()
        press()
        finishCue()
        recordSomeSpeech()
        assertEquals(1, callbacks.size)
        setVisible(false)
        assertEquals("the one existing session is kept for the open recording", 1, liveSessions)
        assertEquals(MediaControlStatus.REGISTERED, app.headsetMedia.status.value)
        assertTrue(model.state.value.recording)
        assertEquals(0, stops())

        press()
        assertEquals("the hidden press reached the real capture controller", 1, stops())
        assertFalse(model.state.value.recording)
        assertEquals(listOf(true, false), ports.single().recording)
        assertEquals("a stop tone follows the real stop", listOf("cue:1@11", "cue:2@11"), cueEvents())
        assertTrue(await { transcribes() == 1 })
        assertTrue(await { fake.rawPrompts.size == 1 })
        assertEquals("the session ended with the recording because the app is hidden", 0, liveSessions)
        assertEquals(MediaControlStatus.APP_CLOSED, app.headsetMedia.status.value)
        assertEquals(1, releases())
        assertEquals("still one recording, one session", 1, created.get())
        assertEquals(1, callbacks.size)
        assertTrue("the claim goes with the request and ends with its answer", await { !app.audio.microphoneClaimed(VoiceOrigin.PHONE) })
    }

    @Test fun H02_aRepeatedOrStalePressAfterTheHiddenStopDoesNothingAndExactlyOneRequestIsSent() {
        ready()
        press()
        finishCue()
        recordSomeSpeech()
        setVisible(false)
        press()
        assertEquals(1, stops())
        press()
        press(index = 0, code = KeyEvent.KEYCODE_MEDIA_PAUSE)
        press(index = 0, code = KeyEvent.KEYCODE_MEDIA_PLAY)
        idle(300)
        assertEquals("nothing started again", 1, starts())
        assertEquals(1, stops())
        assertEquals(1, created.get())
        assertTrue(await { transcribes() == 1 })
        assertTrue(await { fake.rawPrompts.size == 1 })
        idle(500)
        assertEquals("exactly one send", 1, transcribes())
        assertEquals(1, fake.rawPrompts.size)
        assertEquals(listOf("cue:1@11", "cue:2@11"), cueEvents())
    }

    @Test fun H03_aHiddenAppRejectsANewStartFromTheHeadsetAndOpensNoRecordingNoCueAndNoSession() {
        val model = ready()
        setVisible(false)
        assertEquals(0, liveSessions)
        assertEquals(HeadsetOutcome.REFUSED, model.onHeadsetCommand(RecordingCommand.START))
        assertEquals(HeadsetOutcome.REFUSED, model.onHeadsetCommand(RecordingCommand.TOGGLE))
        assertEquals(HeadsetOutcome.REFUSED, model.onHeadsetCommand(RecordingCommand.STOP))
        press(code = KeyEvent.KEYCODE_MEDIA_PLAY)
        assertEquals(0, created.get())
        assertEquals(0, starts())
        assertTrue(cueEvents().isEmpty())
        assertFalse(model.state.value.recording)
        assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
        assertEquals(0, liveSessions)
    }

    @Test fun H04_aStartThatWasStillOpeningWhenTheAppWasHiddenOpensNoMicrophoneAndGivesBackTheClaimAndTheLink() {
        useDevices(FakeDevices(listOf(Gear.speaker, Gear.a2dp, Gear.scoOut), listOf(Gear.scoIn)))
        val link = DeferredLink()
        substitute("headsetMic", HeadsetMicRoute(app.headset, link))
        val model = ready()
        press()
        assertTrue("the Bluetooth link is still being prepared", link.onResult != null)
        assertTrue("the microphone is claimed while opening", app.audio.microphoneClaimed(VoiceOrigin.PHONE))
        assertEquals(0, created.get())
        setVisible(false)
        assertEquals("no recording yet, so no session survives the hide", 0, liveSessions)
        link.onResult!!.invoke(LinkResult.READY)
        idle(100)
        assertEquals("the open was abandoned: no microphone", 0, created.get())
        assertEquals(0, starts())
        assertTrue(cueEvents().isEmpty())
        assertFalse(model.state.value.recording)
        assertFalse("the claim was given back", app.audio.microphoneClaimed(VoiceOrigin.PHONE))
        assertEquals("the link lease was given back", 1, link.closed)
        assertEquals(0, liveSessions)
    }

    @Test fun H05_aHiddenStopWhileTheStartToneIsStillPlayingCancelsTheToneEndsTheRecordingOnceAndSendsNothingAdmitted() {
        val model = ready()
        press()
        val tone = cuesPlayed.single()
        setVisible(false)
        assertEquals(1, liveSessions)
        press()
        assertEquals(1, tone.closed)
        assertEquals(1, stops())
        assertFalse(model.state.value.recording)
        assertEquals("nothing but the start tone had been kept", 0, transcribes())
        finishCue()
        idle(300)
        assertFalse("a late tone callback admits nothing", model.state.value.voiceStatus.contains("Listening"))
        assertEquals(0, transcribes())
        assertEquals(0, liveSessions)
        assertEquals(1, releases())
        assertTrue(await { !app.audio.microphoneClaimed(VoiceOrigin.PHONE) })
    }

    @Test fun H06_turningTheSettingOffWhileHiddenEndsAndDiscardsTheHeadsetStartedRecordingAndLeavesNothingLive() {
        val model = ready()
        press()
        finishCue()
        recordSomeSpeech()
        setVisible(false)
        model.setUseHeadset(false)
        idle(100)
        assertEquals(1, stops())
        assertFalse(model.state.value.recording)
        assertEquals(HeadsetText.LOST, model.state.value.micNotice)
        assertEquals(0, liveSessions)
        assertEquals(1, releases())
        assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
        assertEquals("a discarded orphan is never uploaded", 0, transcribes())
        idle(500)
        assertEquals(0, transcribes())
        assertEquals("no stop tone for a discarded capture", listOf("cue:1@11"), cueEvents())
    }

    @Test fun H07_theOutputDisconnectingWhileHiddenEndsAndDiscardsOnlyTheHeadsetStartedRecordingOnceAndAStaleToneCallbackIsInert() {
        val model = ready()
        press()
        val tone = cuesPlayed.single()
        setVisible(false)
        devices.set(listOf(Gear.speaker), listOf(Gear.wiredHeadsetMic))
        idle(100)
        assertEquals(1, tone.closed)
        assertEquals(1, stops())
        assertFalse(model.state.value.recording)
        assertEquals(0, liveSessions)
        assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
        finishCue(CueResult.LOST)
        idle(300)
        assertEquals("the late callback did not stop or send again", 1, stops())
        assertEquals(0, transcribes())
        devices.set(listOf(Gear.speaker, Gear.wiredHeadset), listOf(Gear.wiredHeadsetMic))
        idle(500)
        assertEquals("nothing replays or restarts on reconnect", 1, starts())
        assertEquals(0, liveSessions)
        assertEquals(1, cueEvents().size)
    }

    @Test fun H08_theSettingTurnedOffWhileVisibleKeepsTheRecordingAndHidingThenDiscardsItBecauseNothingCanStopItAnyMore() {
        val model = ready()
        press()
        finishCue()
        recordSomeSpeech()
        model.setUseHeadset(false)
        assertTrue("visible: the recording continues (unchanged)", model.state.value.recording)
        assertEquals(0, stops())
        setVisible(false)
        idle(100)
        assertEquals(1, stops())
        assertFalse(model.state.value.recording)
        assertEquals(0, transcribes())
        assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
        assertEquals(0, liveSessions)
    }

    @Test fun H09_aRecordingTheScreenStartedIsNeverDiscardedWhenTheHeadsetControlIsLostWhileHiddenAndStillStopsFromTheScreen() {
        val model = ready()
        model.toggleRecording()
        finishCue()
        recordSomeSpeech()
        setVisible(false)
        devices.set(listOf(Gear.speaker), listOf(Gear.wiredHeadsetMic))
        idle(200)
        assertTrue("not a headset-started capture: left running as before", model.state.value.recording)
        assertEquals(0, stops())
        assertEquals(0, liveSessions)
        setVisible(true)
        model.toggleRecording()
        assertEquals(1, stops())
        assertTrue(await { transcribes() == 1 })
    }

    @Test fun H10_aSessionRegisteredAfterAReconnectShowsTheTrueRecordingStateAndTheStaleOneIgnoresKeys() {
        val model = ready()
        press()
        finishCue()
        recordSomeSpeech()
        devices.set(listOf(Gear.speaker), listOf(Gear.wiredHeadsetMic))
        idle(100)
        assertTrue("visible: the recording survives losing the session", model.state.value.recording)
        assertEquals(0, liveSessions)
        devices.set(listOf(Gear.speaker, Gear.wiredHeadset), listOf(Gear.wiredHeadsetMic))
        idle(100)
        assertEquals(2, ports.size)
        assertEquals("the new session starts in the true state", listOf(true), ports[1].recording)
        press(index = 0, code = KeyEvent.KEYCODE_MEDIA_PAUSE)
        assertEquals("the released session's callback is stale", 0, stops())
        setVisible(false)
        assertEquals(1, liveSessions)
        press(index = 1)
        assertEquals(1, stops())
        assertEquals(listOf(true, false), ports[1].recording)
        assertTrue(await { transcribes() == 1 })
        assertEquals(0, liveSessions)
    }

    @Test fun H11_aWatchClaimAndAPendingRequestAreUntouchedByAHiddenHeadsetStop() {
        val model = ready()
        val watch = app.audio.claimMicrophone(VoiceOrigin.WATCH)
        fake.transcribeDelayMs = 2_500
        press()
        finishCue()
        recordSomeSpeech()
        press()
        assertTrue(await { transcribes() == 1 })
        assertTrue("a first request is pending", model.state.value.voiceBusy)
        finishCue()
        press()
        finishCue()
        recordSomeSpeech()
        setVisible(false)
        press()
        assertEquals(2, stops())
        assertTrue(watch.held)
        assertTrue(app.audio.microphoneClaimed(VoiceOrigin.WATCH))
        assertTrue("both requests are still sent", await(15_000) { transcribes() == 2 })
        assertTrue(watch.held)
        watch.release()
    }

    @Test fun H12_clearingTheScreenWhileHiddenAndRecordingStopsTheCaptureReleasesTheSessionAndTheClaim() {
        ready()
        press()
        finishCue()
        recordSomeSpeech()
        setVisible(false)
        store.clear()
        idle(100)
        assertEquals(1, stops())
        assertEquals(0, liveSessions)
        assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
        assertEquals(0, transcribes())
        press(index = 0)
        assertEquals("a key after teardown does nothing", 1, stops())
    }

    private companion object {
        const val CUE = 0x7F7F
    }
}
