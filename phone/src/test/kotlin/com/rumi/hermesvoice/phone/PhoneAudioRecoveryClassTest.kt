package com.rumi.hermesvoice.phone

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import com.rumi.hermesvoice.core.FakeDevices
import com.rumi.hermesvoice.core.Gear
import com.rumi.hermesvoice.core.HermesPlaybackException
import com.rumi.hermesvoice.core.HermesPlaybackTransientException
import com.rumi.hermesvoice.core.SpokenAudio
import com.rumi.hermesvoice.core.SpokenRole
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.headset.HeadsetText
import com.rumi.hermesvoice.core.voice.PlaybackCue
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
 * Which Phone private-output failures are worth asking again (nothing was heard and the output is still there) and which are final
 * (the route, focus or output was lost, the media volume is 0, the file is bad). A strict recording player stands behind [PlayerPort];
 * real Bluetooth, real audibility and Android 15 background focus are NOT_RUN here.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneAudioRecoveryClassTest {
    private class Player(var routed: Int?, val accepts: Boolean = true) : PlayerPort {
        val log = mutableListOf<String>()
        var routeListener: (() -> Unit)? = null
        var noisyListener: (() -> Unit)? = null
        var started: (() -> Unit)? = null
        var completed: (() -> Unit)? = null
        var errored: ((Int, Int) -> Unit)? = null
        val unmutes get() = log.count { it == "mute:false" }

        override fun setPreferredDevice(deviceId: Int): Boolean { log += "preferred:$deviceId"; return accepts }
        override fun routedDeviceId(): Int? = routed
        override fun setMuted(muted: Boolean) { log += "mute:$muted" }
        override fun watchRoute(onChange: () -> Unit, onNoisy: () -> Unit): AutoCloseable {
            routeListener = onChange
            noisyListener = onNoisy
            return AutoCloseable { log += "unwatch" }
        }
        override fun play(path: String, onStarted: () -> Unit, onCompleted: () -> Unit, onError: (Int, Int) -> Unit) {
            log += "play"
            started = onStarted
            completed = onCompleted
            errored = onError
        }
        override fun stop() { log += "stop" }
        override fun release() { log += "release" }
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private lateinit var devices: FakeDevices
    private val players = mutableListOf<Player>()
    private var accepts = true
    private var eligible = true
    private var routedByNext: Int? = null
    private lateinit var sink: PhoneSpeakerSink
    private lateinit var audio: AudioManager

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        val context = RuntimeEnvironment.getApplication()
        audio = context.getSystemService(AudioManager::class.java)
        devices = FakeDevices(listOf(Gear.speaker, Gear.a2dp))
        sink = PhoneSpeakerSink(context, devices, PlayerFactory { _: AudioAttributes -> Player(routedByNext, accepts).also { players += it } }, focusEligible = { eligible })
    }

    @After fun tearDown() = scope.cancel()

    private fun cue() = PlaybackCue("t-1", VoiceOrigin.PHONE, SpokenRole.FINAL, 1, "hello", VoiceOrigin.PHONE, later = true, headset = Gear.a2dp)

    private fun pump(what: String, until: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun start(finished: AtomicInteger = AtomicInteger()): Deferred<Result<Unit>> =
        scope.async { runCatching { sink.playConfirmed(SpokenAudio(ByteArray(64) { 1 }, "audio/mpeg"), cue()) { finished.incrementAndGet() } } }

    private fun Deferred<Result<Unit>>.failure(): Throwable {
        pump("the sink to end") { isCompleted }
        return runBlocking { await() }.exceptionOrNull() ?: throw AssertionError("expected a failure")
    }

    private fun playing(): Player {
        pump("play") { players.isNotEmpty() && players[0].started != null }
        return players.single()
    }

    private fun assertTransient(error: Throwable) {
        assertTrue("transient, was $error", error is HermesPlaybackTransientException)
    }

    private fun assertTerminal(error: Throwable) {
        assertTrue("a playback failure, was $error", error is HermesPlaybackException)
        assertFalse("terminal, was $error", error is HermesPlaybackTransientException)
    }

    @Test fun R01_aRouteThatIsNeverNamedInTimeIsWorthAnotherTry() {
        val finished = AtomicInteger()
        val run = start(finished)
        playing().started!!()
        advance(PhoneSpeakerSink.ROUTE_CONFIRM_MS + 300)
        assertTransient(run.failure())
        assertEquals(0, finished.get())
    }

    @Test fun R02_aPreferredDeviceRefusalBeforeAnythingWasHeardIsWorthAnotherTry() {
        accepts = false
        val run = start()
        assertTransient(run.failure())
        assertEquals(0, players.single().unmutes)
    }

    @Test fun R03_aClipThatEndsBeforeTheOutputWasProvenIsWorthAnotherTry() {
        val run = start()
        val player = playing()
        player.started!!()
        player.completed!!()
        assertTransient(run.failure())
        assertEquals(0, player.unmutes)
    }

    @Test fun R04_aDeniedAudioFocusRequestIsWorthAnotherTryAndNeverStartsThePlayer() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val run = start()
        assertTransient(run.failure())
        assertTrue("no native start without focus", players.none { "play" in it.log })
    }

    @Test fun R04b_aDeniedFocusWhileAndroidGivesNoFocusToABackgroundAppIsFinalAndSaysWhatToDo() {
        eligible = false
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val run = start()
        val error = run.failure()
        assertTerminal(error)
        assertEquals(HeadsetText.BACKGROUND_FOCUS, error.message)
        assertTrue("no native start without focus", players.none { "play" in it.log })
    }

    @Test fun R05_aGenericNativePlayerErrorIsWorthAnotherTryButAMalformedFileIsNot() {
        val first = start()
        val one = playing()
        one.errored!!(1, 0)
        assertTransient(first.failure())

        players.clear()
        val second = start()
        val two = playing()
        two.errored!!(1, -1007)
        assertTerminal(second.failure())
    }

    @Test fun R06_theOutputGoingAwayEndsItForGoodInsteadOfWaitingForAnotherTry() {
        val run = start()
        playing()
        devices.disconnectAll()
        val error = run.failure()
        assertTerminal(error)
        assertEquals(HeadsetText.PLAYBACK_LOST, error.message)
    }

    @Test fun R07_aRouteChangeAfterTheProofEndsItForGood() {
        routedByNext = Gear.a2dp.id
        val run = start()
        val player = playing()
        player.started!!()
        assertEquals(1, player.unmutes)
        player.routed = Gear.speaker.id
        player.routeListener!!()
        assertTerminal(run.failure())
    }

    @Test fun R08_theBecomingNoisyBroadcastEndsItForGood() {
        routedByNext = Gear.a2dp.id
        val run = start()
        val player = playing()
        player.started!!()
        player.noisyListener!!()
        assertTerminal(run.failure())
    }

    @Test fun R09_aMediaVolumeOfZeroIsNeverUnmutedAndIsReportedAsTheCauseNotAsSuccess() {
        devices.silent = true
        routedByNext = Gear.a2dp.id
        val finished = AtomicInteger()
        val run = start(finished)
        val player = playing()
        player.started!!()
        val error = run.failure()
        assertTerminal(error)
        assertEquals(HeadsetText.VOLUME_ZERO, error.message)
        assertEquals(0, player.unmutes)
        assertEquals(0, finished.get())
    }

    @Test fun R10_theNativeErrorTableSeparatesRetriableFromFinal() {
        assertTrue(PhoneSpeakerSink.transientPlayerError(1, 0))
        assertTrue(PhoneSpeakerSink.transientPlayerError(100, 0))
        assertFalse(PhoneSpeakerSink.transientPlayerError(1, -1007))
        assertFalse(PhoneSpeakerSink.transientPlayerError(1, -1010))
        assertFalse(PhoneSpeakerSink.transientPlayerError(200, 0))
    }
}
