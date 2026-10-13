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
import com.rumi.hermesvoice.core.headset.HeadsetMediaText
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.headset.HeadsetText
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The headset media button (amendment K) through the PRODUCTION chain: the media callback the
 * REAL [HeadsetMediaControl] registers -> the REAL [PhoneViewModel] push-to-talk controller -> the REAL claim / mic route /
 * [WavRecorder] / [RecordingCues] -> the dashboard double. Only the leaves are fakes: the audio devices, the session factory, the
 * microphone and the cue output. No physical key, headset, microphone, sound or Android media routing is exercised (NOT_RUN).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneHeadsetRecordingFlowTest {
    private lateinit var app: PhoneApp
    private lateinit var fake: FakeHermesDashboard
    private val store = ViewModelStore()

    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val cuePlaying = AtomicBoolean(false)
    private val ring: ByteArray = TestAudio.speechWav().let { it.copyOfRange(44, it.size) }
    private val created = AtomicInteger()
    @Volatile private var starved = false
    @Volatile private var micWorks = true

    private val devices = FakeDevices(listOf(Gear.speaker, Gear.wiredHeadset), listOf(Gear.wiredHeadsetMic))

    private inner class FakeRecord : RecordPort {
        private var position = 12_800
        override val initialized get() = micWorks
        override fun setPreferredDevice(deviceId: Int) = true
        override fun routedDeviceId(): Int? = Gear.wiredHeadsetMic.id
        override fun start(): Boolean { events += "mic.start"; return true }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(5)
            if (starved) return 0
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

    private class PlayedCue(val kind: Int, val target: AudioEndpoint, val done: (CueResult) -> Unit) { var closed = 0 }

    private val cuesPlayed = mutableListOf<PlayedCue>()
    private val cueOutput = CueOutput { pcm, _, target, done ->
        val kind = when {
            pcm.contentEquals(RecordingCueTone.pcm(1)) -> 1
            pcm.contentEquals(RecordingCueTone.pcm(2)) -> 2
            else -> 0
        }
        events += "cue:$kind@${target.id}"
        cuePlaying.set(true)
        val played = PlayedCue(kind, target, done)
        cuesPlayed += played
        AutoCloseable { played.closed++ }
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
        substitute("headset", HeadsetPolicy({ app.settings.useHeadset }, devices))
        substitute("recordFactory", RecordFactory { created.incrementAndGet(); FakeRecord() })
        substitute("mediaSessions", sessions)
        substitute("cueOutput", cueOutput)
    }

    @After fun tearDown() {
        app.manualCapture.cancel("fixture ended")
        app.connectionCue.cancel()
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
    private fun press(code: Int = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
        idle(500)
        val down = 100_000L + ++presses * 10_000
        val callback = callbacks.last()
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
    private fun transcribes() = fake.timeline.count { it.startsWith("transcribe:") }
    private fun cueEvents() = events.filter { it.startsWith("cue:") }

    private fun samples(wav: ByteArray): Set<Int> {
        val out = HashSet<Int>()
        var i = 44
        while (i + 1 < wav.size) { out += (wav[i].toInt() and 0xFF) or (wav[i + 1].toInt() shl 8); i += 2 }
        return out
    }

    private fun assertSpeechOnly(wav: ByteArray) {
        assertTrue("some speech was kept", wav.size > 44 + 3_200)
        assertFalse("no cue sample reached the upload", CUE in samples(wav))
    }

    private fun recordSomeSpeech() { Thread.sleep(400); idle(50) }

    @Test fun K01_aPlayPausePressStartsOneRecordingAndOneDingToTheHeadsetOnlyAfterTheMicrophoneOpened() {
        val model = ready()
        press()
        assertEquals(1, starts())
        assertEquals(listOf("mic.start", "cue:1@${Gear.wiredHeadset.id}"), events.filter { it == "mic.start" || it.startsWith("cue:") })
        assertTrue(model.state.value.recording)
        assertFalse("still preparing, not listening", model.state.value.voiceStatus.contains("Listening"))
        assertEquals(listOf(true), ports.single().recording)
        finishCue()
        assertTrue(model.state.value.voiceStatus.contains("Listening"))
        assertEquals("one press, one recording", 1, created.get())
    }

    @Test fun K02_aSecondPressStopsAndSendsOneRequestWithTheStopCueAfterTheRecorderStoppedAndNoCueSoundInTheWav() {
        val model = ready()
        press()
        recordSomeSpeech()
        finishCue()
        recordSomeSpeech()
        press()
        assertEquals(1, stops())
        assertEquals(listOf("cue:1@11", "cue:2@11"), cueEvents())
        assertTrue("the stop cue plays after the recorder stopped", events.indexOf("mic.stop") < events.indexOf("cue:2@11"))
        assertFalse(model.state.value.recording)
        assertEquals(listOf(true, false), ports.single().recording)
        assertTrue(await { transcribes() == 1 })
        val wav = fake.lastTranscribedWav!!
        assertSpeechOnly(wav)
        assertTrue(await { fake.rawPrompts.size == 1 })
        idle(500)
        assertEquals("one request only", 1, transcribes())
        assertEquals(1, fake.rawPrompts.size)
    }

    @Test fun K03_explicitManualStartAndStopRemainIdempotent() {
        val model = ready()
        assertEquals(HeadsetOutcome.IGNORED, model.onHeadsetCommand(RecordingCommand.STOP)); idle(50)
        assertEquals("explicit stop while idle does nothing", 0, created.get())
        assertTrue(cueEvents().isEmpty())
        assertEquals(HeadsetOutcome.STARTED, model.onHeadsetCommand(RecordingCommand.START)); idle(50)
        assertEquals(1, starts())
        finishCue()
        recordSomeSpeech()
        assertEquals(HeadsetOutcome.IGNORED, model.onHeadsetCommand(RecordingCommand.START)); idle(50)
        assertEquals("explicit start while recording does nothing", 1, starts())
        assertEquals(listOf("cue:1@11"), cueEvents())
        assertEquals(HeadsetOutcome.STOPPED, model.onHeadsetCommand(RecordingCommand.STOP)); idle(50)
        assertEquals(1, stops())
        assertEquals(listOf("cue:1@11", "cue:2@11"), cueEvents())
        finishCue()
        assertEquals(HeadsetOutcome.IGNORED, model.onHeadsetCommand(RecordingCommand.STOP)); idle(50)
        assertEquals("explicit stop again does nothing", 1, stops())
        assertEquals(2, cueEvents().size)
    }

    @Test fun K04_keyPhasesRepeatsAndDuplicatesOfOnePressAreOneTransition() {
        ready()
        idle(500)
        val down = 777_000L
        val callback = callbacks.last()
        fun send(action: Int, repeat: Int) = callback.onMediaButtonEvent(
            Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(down, down, action, KeyEvent.KEYCODE_HEADSETHOOK, repeat)))
        send(KeyEvent.ACTION_DOWN, 0); send(KeyEvent.ACTION_DOWN, 1); send(KeyEvent.ACTION_DOWN, 2); send(KeyEvent.ACTION_UP, 0); send(KeyEvent.ACTION_DOWN, 0)
        idle(50)
        assertEquals(1, starts())
        assertEquals(1, created.get())
    }

    @Test fun K05_otherMediaKeysAndKeysWithTheSettingOffNeverRecord() {
        val model = model()
        setVisible(true)
        assertEquals("off: no session at all", 0, liveSessions)
        assertEquals(MediaControlStatus.OFF, app.headsetMedia.status.value)
        model.setUseHeadset(true)
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_VOLUME_UP)) press(code)
        assertEquals(0, created.get())
        assertTrue(cueEvents().isEmpty())
    }

    @Test fun K06_noHeadsetRefusesButHiddenLiveOutputCanStartAfterAdmission() {
        val model = ready(); devices.disconnectAll()
        assertEquals(0, liveSessions); assertEquals(HeadsetOutcome.REFUSED, model.onHeadsetCommand(RecordingCommand.TOGGLE))
        devices.connect(Gear.wiredHeadset, inputs = listOf(Gear.wiredHeadsetMic)); assertEquals(1, liveSessions)
        setVisible(false); assertEquals(1, liveSessions)
        assertEquals(HeadsetOutcome.STARTED, model.onHeadsetCommand(RecordingCommand.START)); idle(100)
        assertEquals(1, created.get()); assertTrue(model.state.value.recording)
        model.onHeadsetCommand(RecordingCommand.STOP); idle(100); assertEquals(1, stops())
    }

    @Test fun K07_withoutMicrophonePermissionNothingStartsAndTheScreenSaysSo() {
        val model = ready()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        press()
        assertEquals(0, created.get())
        assertTrue(cueEvents().isEmpty())
        assertFalse(model.state.value.recording)
        assertTrue(model.state.value.micNotice?.contains(HeadsetMediaText.PERMISSION) == true || model.state.value.voiceStatus.contains(HeadsetMediaText.PERMISSION))
        assertTrue(app.audio.microphoneClaimed(VoiceOrigin.PHONE).not())
    }

    @Test fun K08_aMicrophoneThatCannotOpenGivesNoCueNoRecordingStateAndNoClaim() {
        val model = ready()
        micWorks = false
        press()
        assertTrue(cueEvents().isEmpty())
        assertFalse(model.state.value.recording)
        assertTrue("no recording was announced to the media session", ports.single().recording.none { it })
        assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
        micWorks = true
        press()
        assertEquals("the next press works", listOf("cue:1@11"), cueEvents())
    }

    @Test fun K09_aStartCueThatCannotPlayStillRecordsAndTheScreenSaysTheToneWasSkipped() {
        val model = ready()
        press()
        finishCue(CueResult.FOCUS_DENIED)
        assertTrue(model.state.value.recording)
        assertEquals(HeadsetMediaText.CUE_SKIPPED, model.state.value.micNotice)
        assertTrue(model.state.value.voiceStatus.contains("Listening"))
        recordSomeSpeech()
        press()
        assertTrue(await { transcribes() == 1 })
        assertSpeechOnly(fake.lastTranscribedWav!!)
    }

    @Test fun K10_theOutputDisconnectingDuringTheStartCueEndsTheRecordingOnceWithNoStaleCue() {
        val model = ready()
        press()
        val first = cuesPlayed.single()
        devices.disconnectAll()
        idle(100)
        assertEquals("the cue was cancelled", 1, first.closed)
        assertEquals("the recorder ended exactly once", 1, stops())
        assertFalse(model.state.value.recording)
        assertEquals("no stop cue and no request", 1, cueEvents().size)
        idle(200)
        assertEquals(0, transcribes())
        devices.connect(Gear.wiredHeadset, inputs = listOf(Gear.wiredHeadsetMic))
        idle(500)
        assertEquals("one new private connection cue but no recording replay", 2, cueEvents().size)
        assertEquals(1, starts())
        assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
    }

    @Test fun K11_theHeadsetMicrophoneDisconnectingWhileRecordingEndsItOnceWithoutAStopCue() {
        val model = ready()
        press()
        finishCue()
        recordSomeSpeech()
        devices.set(listOf(Gear.speaker, Gear.wiredHeadset), emptyList())
        idle(100)
        assertEquals(1, stops())
        assertFalse(model.state.value.recording)
        assertEquals("no success sound for an ended-by-loss recording", listOf("cue:1@11"), cueEvents())
        assertEquals(HeadsetText.LOST, model.state.value.micNotice)
        assertTrue(await { transcribes() == 1 })
        idle(300)
        assertEquals(1, transcribes())
    }

    @Test fun K12_aRecordingTooShortToSendGivesNoStopCue() {
        val model = ready()
        starved = true
        press()
        finishCue()
        Thread.sleep(300)
        press()
        assertEquals(1, stops())
        assertEquals(listOf("cue:1@11"), cueEvents())
        assertEquals("Too short", model.state.value.voiceStatus)
        assertEquals(0, transcribes())
    }

    @Test fun K13_aWatchRecordingKeepsItsClaimAndAPendingRequestDoesNotBlockANewPhoneRecording() {
        val model = ready()
        val watch = app.audio.claimMicrophone(VoiceOrigin.WATCH)
        fake.transcribeDelayMs = 3_000
        press()
        finishCue()
        recordSomeSpeech()
        press()
        assertTrue(await { transcribes() == 1 })
        assertTrue("the first request is still being transcribed", model.state.value.voiceBusy)
        finishCue()
        press()
        assertEquals("a second recording starts while the first request is pending", 2, starts())
        assertTrue(watch.held)
        assertTrue("the Watch microphone is never touched", app.audio.microphoneClaimed(VoiceOrigin.WATCH))
        finishCue()
        press()
        assertTrue(watch.held)
        watch.release()
    }

    @Test fun K14_modeOffEndsAndDiscardsOnlyHeadsetOwnedCaptureWithNoStopCue() {
        val model = ready(); press(); finishCue(); recordSomeSpeech()
        model.setUseHeadset(false); idle(100)
        assertEquals(0, liveSessions); assertEquals(MediaControlStatus.OFF, app.headsetMedia.status.value)
        assertFalse(model.state.value.recording); assertEquals(1, stops())
        assertEquals(listOf("cue:1@11"), cueEvents()); assertEquals(0, transcribes())
    }

    @Test fun K15_theScreenButtonUsesTheSameCuesWhileTheHeadsetIsPrivateAndNoneWhenItIsOff() {
        val model = ready()
        model.toggleRecording()
        assertEquals(listOf("cue:1@11"), cueEvents())
        finishCue()
        recordSomeSpeech()
        model.toggleRecording()
        assertEquals(listOf("cue:1@11", "cue:2@11"), cueEvents())
        finishCue()
        assertTrue(await { transcribes() == 1 })
        model.setUseHeadset(false)
        model.toggleRecording()
        assertEquals("off: today's behaviour, no cue, listening at once", 2, cueEvents().size)
        assertTrue("the screen observes the shared controller asynchronously", await { model.state.value.recording && model.state.value.voiceStatus.contains("Listening") })
        recordSomeSpeech()
        model.toggleRecording()
        assertTrue(await { transcribes() == 2 })
        assertSpeechOnly(fake.lastTranscribedWav!!)
    }

    @Test fun K16_screenDisposalDoesNotCancelOwnedCaptureOrItsStartCueButExactStopDoes() {
        ready(); press(); val cue = cuesPlayed.single()
        store.clear(); idle(100)
        assertEquals(0, cue.closed); assertEquals(0, stops()); assertEquals(1, liveSessions)
        assertTrue(app.audio.microphoneClaimed(VoiceOrigin.PHONE)); assertEquals(0, transcribes())
        app.manualCapture.stop(); idle(100)
        assertEquals(1, cue.closed); assertEquals(1, stops()); assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
        assertEquals(0, transcribes())
    }

    @Test fun K17_aStartWhileTheStopCueIsStillPlayingCancelsThatCueAndStartsNormally() {
        ready()
        press()
        finishCue()
        recordSomeSpeech()
        press()
        val stopCue = cuesPlayed.last()
        press()
        assertEquals("the stop cue was replaced", 1, stopCue.closed)
        assertEquals(2, starts())
        assertEquals(listOf("cue:1@11", "cue:2@11", "cue:1@11"), cueEvents())
    }

    private fun foregroundActivity(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).get().also {
        // Attach the window's decor for superclass fallback; do not start unrelated Compose/wake lifecycles.
        it.window.decorView
    }

    // R8: foreground Activity dispatch is the OS priority route, not a fabricated session callback.
    // The Activity is attached but not created: avoids unrelated Compose/wake/UI/network setup.
    @Test fun R801_foregroundActivityPlayPauseStartsAndStopsTheRealPhoneControllerExactlyOnce() {
        val model = ready()
        val activity = foregroundActivity()
        val down = 900_000L
        val event = KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
        assertTrue("the foreground Activity must consume eligible headset play/pause before OS music fallback", activity.dispatchKeyEvent(event))
        idle(50)
        assertEquals(1, starts())
        assertTrue(model.state.value.recording)
        assertEquals(listOf("cue:1@11"), cueEvents())
        assertTrue(activity.dispatchKeyEvent(KeyEvent(down, down + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)))
        finishCue()
        recordSomeSpeech()
        idle(500)
        assertTrue(activity.dispatchKeyEvent(KeyEvent(down + 1000, down + 1000, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)))
        idle(50)
        assertEquals(1, stops())
        assertFalse(model.state.value.recording)
        assertEquals(listOf("cue:1@11", "cue:2@11"), cueEvents())
        assertTrue(await { transcribes() == 1 })
        assertSpeechOnly(fake.lastTranscribedWav!!)
    }

    @Test fun R802_foregroundAndSessionCopiesRepeatUpAndTransportCallbacksShareOneDecoder() {
        ready()
        val activity = foregroundActivity()
        val down = 910_000L
        val event = KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0)
        assertTrue("Activity priority route is missing", activity.dispatchKeyEvent(event))
        assertTrue(activity.dispatchKeyEvent(KeyEvent(down, down + 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 1)))
        assertTrue(activity.dispatchKeyEvent(KeyEvent(down, down + 2, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0)))
        callbacks.last().onMediaButtonEvent(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event))
        callbacks.last().onPlay()
        idle(50)
        assertEquals(1, starts())
        assertEquals(0, stops())
        assertEquals(listOf("cue:1@11"), cueEvents())
    }

    @Test fun R803_foregroundReleaseGatesAndUnrelatedMediaKeysFallThroughWithoutCapturing() {
        val model = ready()
        val activity = foregroundActivity()
        fun down(code: Int) = KeyEvent(920_000L, 920_000L, KeyEvent.ACTION_DOWN, code, 0)
        assertFalse(activity.dispatchKeyEvent(down(KeyEvent.KEYCODE_MEDIA_NEXT)))
        model.setUseHeadset(false)
        assertFalse(activity.dispatchKeyEvent(down(KeyEvent.KEYCODE_MEDIA_PLAY)))
        model.setUseHeadset(true)
        devices.disconnectAll()
        assertFalse(activity.dispatchKeyEvent(down(KeyEvent.KEYCODE_MEDIA_PLAY)))
        devices.connect(Gear.wiredHeadset, inputs = listOf(Gear.wiredHeadsetMic))
        setVisible(false)
        assertFalse(activity.dispatchKeyEvent(down(KeyEvent.KEYCODE_MEDIA_PLAY)))
        assertEquals(0, created.get())
        assertEquals("one permitted fresh connection cue, no recording cue", listOf("cue:1@11"), cueEvents())
    }

    @Test fun R804_releasedSessionCallbackCannotSuppressARealForegroundStartAfterReconnect() {
        ready()
        val old = callbacks.single()
        devices.disconnectAll()
        devices.connect(Gear.wiredHeadset, inputs = listOf(Gear.wiredHeadsetMic))
        assertEquals(2, callbacks.size)
        // No clock advance: stale callback and actual key arrive within the shared decoder's duplicate window.
        old.onPlay()
        val activity = foregroundActivity()
        assertTrue(activity.dispatchKeyEvent(KeyEvent(930_000L, 930_000L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0)))
        idle(50)
        assertEquals("released callback must not poison the shared decoder", 1, starts())
        assertEquals("connection then accepted recording start, both private", listOf("cue:1@11", "cue:1@11"), cueEvents())
        assertEquals(0, stops())
    }

    @Test fun R805_foregroundStartAndStopDoNotRequireAndroidToOpenAMediaSession() {
        substitute("mediaSessions", MediaSessionFactory { null })
        val model = model()
        model.setUseHeadset(true)
        setVisible(true)
        assertEquals(MediaControlStatus.UNAVAILABLE, app.headsetMedia.status.value)
        val activity = foregroundActivity()
        assertTrue(activity.dispatchKeyEvent(KeyEvent(940_000L, 940_000L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)))
        idle(50)
        assertEquals(1, starts())
        finishCue()
        recordSomeSpeech()
        idle(500)
        assertTrue(activity.dispatchKeyEvent(KeyEvent(941_000L, 941_000L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)))
        idle(50)
        assertEquals(1, stops())
        assertEquals(listOf("cue:1@11", "cue:2@11"), cueEvents())
        assertTrue(await { transcribes() == 1 })
        assertSpeechOnly(fake.lastTranscribedWav!!)
        assertTrue(ports.isEmpty())
    }

    @Test fun R806_hiddenHeadsetCaptureWithoutASessionIsDiscardedRatherThanLeavingAnUnstoppableMic() {
        substitute("mediaSessions", MediaSessionFactory { null })
        val model = model()
        model.setUseHeadset(true)
        setVisible(true)
        val watch = app.audio.claimMicrophone(VoiceOrigin.WATCH)
        val activity = foregroundActivity()
        assertTrue(activity.dispatchKeyEvent(KeyEvent(950_000L, 950_000L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)))
        idle(50)
        assertTrue(model.state.value.recording)
        finishCue()
        recordSomeSpeech()
        setVisible(false)
        idle(50)
        assertEquals("no session can finish the hidden headset-owned capture", 1, stops())
        assertFalse(model.state.value.recording)
        assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
        assertEquals("discard, not submit", 0, transcribes())
        assertEquals("no stop cue for a discarded orphan", listOf("cue:1@11"), cueEvents())
        assertTrue(watch.held)
        watch.release()
    }

    private companion object {
        const val CUE = 0x7F7F
    }

    // R11: same production entrypoints and capture as K/R8, synthetic leaves only.
    private fun r11Sequence(first: Int, second: Int, path: String) {
        val model = ready()
        val activity = if (path == "foreground") foregroundActivity() else null
        var down = 1_110_000L
        fun send(code: Int) {
            idle(500)
            down += 1000
            val event = KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0)
            when (path) {
                "foreground" -> assertTrue(activity!!.dispatchKeyEvent(event))
                "session-key" -> assertTrue(callbacks.last().onMediaButtonEvent(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event)))
                "transport" -> if (code == KeyEvent.KEYCODE_MEDIA_PLAY) callbacks.last().onPlay() else callbacks.last().onPause()
                else -> error("fixture path")
            }
            idle(50)
        }
        send(first)
        assertEquals("each accepted play OR pause starts while idle", 1, starts())
        assertTrue(app.manualCapture.state.value.recording)
        finishCue()
        recordSomeSpeech()
        send(second)
        assertEquals("each accepted play OR pause stops while busy", 1, stops())
        assertEquals(1, created.get())
        assertFalse(model.state.value.recording)
        assertFalse(app.manualCapture.busy)
        assertEquals(listOf("cue:1@11", "cue:2@11"), cueEvents())
        assertTrue(events.indexOf("mic.release") < events.indexOf("cue:2@11"))
        assertEquals(listOf(true, false), ports.single().recording)
        assertTrue(await { transcribes() == 1 })
        assertSpeechOnly(fake.lastTranscribedWav!!)
        finishCue()
        idle(500)
        assertEquals("one WAV only", 1, transcribes())
    }

    @Test fun R11_foreground_PlayPlay() = r11Sequence(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY, "foreground")

    @Test fun R11_foreground_PausePause() = r11Sequence(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, "foreground")

    @Test fun R11_foreground_PlayPause() = r11Sequence(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, "foreground")

    @Test fun R11_foreground_PausePlay() = r11Sequence(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, "foreground")

    @Test fun R11_session_key_PlayPlay() = r11Sequence(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY, "session-key")

    @Test fun R11_session_key_PausePause() = r11Sequence(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, "session-key")

    @Test fun R11_session_key_PlayPause() = r11Sequence(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, "session-key")

    @Test fun R11_session_key_PausePlay() = r11Sequence(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, "session-key")

    @Test fun R11_transport_PlayPlay() = r11Sequence(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY, "transport")

    @Test fun R11_transport_PausePause() = r11Sequence(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, "transport")

    @Test fun R11_transport_PlayPause() = r11Sequence(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, "transport")

    @Test fun R11_transport_PausePlay() = r11Sequence(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, "transport")

    @Test fun R11_samePressAcrossForegroundSessionAndTransportDoesNotToggleTwice() {
        ready()
        val activity = foregroundActivity()
        val down = 1_120_000L
        val event = KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
        assertTrue(activity.dispatchKeyEvent(event))
        callbacks.last().onMediaButtonEvent(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event))
        callbacks.last().onPlay()
        callbacks.last().onPause()
        assertTrue(activity.dispatchKeyEvent(KeyEvent(down, down + 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 1)))
        assertTrue(activity.dispatchKeyEvent(KeyEvent(down, down + 2, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0)))
        idle(50)
        assertEquals(1, starts())
        assertEquals(0, stops())
        assertEquals(listOf("cue:1@11"), cueEvents())
        finishCue()
        recordSomeSpeech()
        idle(500)
        assertTrue(activity.dispatchKeyEvent(event)) // Same downTime, even outside window.
        idle(50)
        assertEquals(0, stops())
        assertTrue(activity.dispatchKeyEvent(KeyEvent(down + 1000, down + 1000, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)))
        callbacks.last().onPause()
        idle(50)
        assertEquals(1, stops())
        assertTrue(await { transcribes() == 1 })
        assertSpeechOnly(fake.lastTranscribedWav!!)
    }
}
