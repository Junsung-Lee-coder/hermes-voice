package com.rumi.hermesvoice.phone

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Looper
import com.rumi.hermesvoice.core.FakeDevices
import com.rumi.hermesvoice.core.Gear
import com.rumi.hermesvoice.core.HermesPlaybackException
import com.rumi.hermesvoice.core.SpokenAudio
import com.rumi.hermesvoice.core.SpokenRole
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.headset.AudioEndpoint
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * SCRATCH HARNESS (never packaged). Owner rule J / F3: a privately bound answer is silent until Android's readback has positively
 * named EXACTLY the intended personal output; it never sounds on the speaker first; and every other end (no route, wrong route,
 * route change, removal, focus loss, timeout, completion before proof, error, cancel) mutes, stops and cleans up without claiming
 * it played. The platform player is a strict recording fake behind [PlayerPort]; the real [AndroidPlayer] adapter is exercised
 * separately over Robolectric's MediaPlayer shadow. Physical audible behaviour and real Bluetooth routing are NOT_RUN.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhonePrivateSpeakerRouteTest {
    private class StrictPlayer(var routed: Int?, val accepts: Boolean = true) : PlayerPort {
        val log = mutableListOf<String>()
        var silenced = false
        var mutedAtPlay: Boolean? = null
        val unmutedWhileRoutedTo = mutableListOf<Int?>()
        var routeListener: (() -> Unit)? = null
        var noisyListener: (() -> Unit)? = null
        var noisyDuringWatch = false
        var watchClosed = 0
        var started: (() -> Unit)? = null
        var completed: (() -> Unit)? = null
        var errored: ((Int, Int) -> Unit)? = null
        var path: String? = null
        var failMute = false
        var failWatch = false
        val released get() = log.count { it == "release" }
        val unmutes get() = log.count { it == "mute:false" }

        override fun setPreferredDevice(deviceId: Int): Boolean { log += "preferred:$deviceId"; return accepts }
        override fun routedDeviceId(): Int? = routed
        override fun setMuted(muted: Boolean) {
            if (failMute) throw IllegalStateException("no volume")
            this.silenced = muted
            log += "mute:$muted"
            if (!muted) unmutedWhileRoutedTo += routed
        }
        override fun watchRoute(onChange: () -> Unit, onNoisy: () -> Unit): AutoCloseable {
            if (failWatch) throw IllegalStateException("no routing callbacks")
            log += "watch"
            routeListener = onChange
            noisyListener = onNoisy
            if (noisyDuringWatch) onNoisy()
            return AutoCloseable { watchClosed++; log += "unwatch" }
        }
        override fun play(path: String, onStarted: () -> Unit, onCompleted: () -> Unit, onError: (Int, Int) -> Unit) {
            mutedAtPlay = silenced
            this.path = path
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
    private val players = mutableListOf<StrictPlayer>()
    private var routedByNext: Int? = null
    private var configure: (StrictPlayer) -> Unit = {}
    private lateinit var sink: PhoneSpeakerSink
    private lateinit var audio: AudioManager

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        val context = RuntimeEnvironment.getApplication()
        audio = context.getSystemService(AudioManager::class.java)
        devices = FakeDevices(listOf(Gear.speaker, Gear.a2dp))
        sink = PhoneSpeakerSink(context, devices, PlayerFactory { _: AudioAttributes ->
            StrictPlayer(routedByNext).also { configure(it); players += it }
        })
    }

    @After fun tearDown() = scope.cancel()

    private fun cue(headset: AudioEndpoint?, seq: Int = 1) =
        PlaybackCue("t-$seq", VoiceOrigin.PHONE, SpokenRole.FINAL, seq, "hello", VoiceOrigin.PHONE, later = true, headset = headset)

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

    private fun start(cue: PlaybackCue, finished: AtomicInteger = AtomicInteger()): Deferred<Result<Unit>> =
        scope.async { runCatching { sink.playConfirmed(SpokenAudio(ByteArray(64) { 1 }, "audio/mpeg"), cue) { finished.incrementAndGet() } } }

    private fun Deferred<Result<Unit>>.result(): Result<Unit> {
        pump("the sink to end") { isCompleted }
        return runBlocking { await() }
    }

    private fun playing(): StrictPlayer {
        pump("play") { players.isNotEmpty() && players[0].started != null }
        return players.single()
    }

    private fun focus(change: Int) = shadowOf(audio).lastAudioFocusRequest!!.listener.onAudioFocusChange(change)

    private fun assertQuietEnd(player: StrictPlayer, message: String?, finished: AtomicInteger, run: Deferred<Result<Unit>>) {
        val failure = run.result().exceptionOrNull()
        assertTrue("a playback failure, not a success: $failure", failure is HermesPlaybackException)
        if (message != null) assertEquals(message, failure!!.message)
        assertEquals("never claimed played", 0, finished.get())
        assertEquals("muted before anything else at the end", "mute:true", player.log.last { it.startsWith("mute") })
        assertTrue("stopped", "stop" in player.log)
        assertEquals("released once", 1, player.released)
        assertEquals("route watch closed", 1, player.watchClosed)
        assertEquals("device watch closed", 0, devices.watching)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        assertFalse("file removed", java.io.File(player.path ?: "/none").exists())
    }

    @Test fun P01_aPrivateClipIsMutedBeforeTheNativePlayerStartsAndUnmutedOnlyAfterTheExactOutputIsNamed() {
        routedByNext = null
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        assertEquals(listOf("mute:true", "preferred:${Gear.a2dp.id}", "watch", "play"), player.log)
        assertEquals("silent at the moment of the native start", true, player.mutedAtPlay)
        player.started!!()
        assertEquals("started but not proven: still silent", 0, player.unmutes)
        player.routed = Gear.a2dp.id
        player.routeListener!!()
        assertEquals(listOf("mute:true", "preferred:${Gear.a2dp.id}", "watch", "play", "mute:false"), player.log)
        assertEquals(listOf<Int?>(Gear.a2dp.id), player.unmutedWhileRoutedTo)
        player.completed!!()
        assertTrue(run.result().isSuccess)
        assertEquals(1, finished.get())
        assertEquals(1, player.released)
        assertEquals(1, player.watchClosed)
        assertEquals(0, devices.watching)
    }

    @Test fun P02_aPlayerThatNamesNoRouteNeverSoundsAndFailsQuietlyAtTheBound() {
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        player.started!!()
        advance(PhoneSpeakerSink.ROUTE_CONFIRM_MS - 100)
        assertFalse("still waiting inside the bound", run.isCompleted)
        advance(300)
        assertQuietEnd(player, HeadsetText.PLAYBACK_UNROUTED, finished, run)
        assertEquals("never unmuted", 0, player.unmutes)
        assertTrue(player.unmutedWhileRoutedTo.isEmpty())
    }

    @Test fun P03_aWrongOutputBeforeTheStartNeverSoundsAndEndsAtOnce() {
        routedByNext = Gear.speaker.id
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        player.started!!()
        assertQuietEnd(player, HeadsetText.PLAYBACK_UNROUTED, finished, run)
        assertEquals(0, player.unmutes)
    }

    @Test fun P04_aRouteNamedOnlyAfterTheStartIsFoundByThePollAndThenUnmutedOnceWithoutAnotherSnapshot() {
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        player.started!!()
        advance(250)
        assertEquals(0, player.unmutes)
        player.routed = Gear.a2dp.id
        advance(PhoneSpeakerSink.ROUTE_POLL_MS + 50)
        assertEquals(1, player.unmutes)
        advance(PhoneSpeakerSink.ROUTE_CONFIRM_MS + 500)
        assertFalse("the bound no longer applies after proof", run.isCompleted)
        player.completed!!()
        assertTrue(run.result().isSuccess)
        assertEquals(1, finished.get())
        assertEquals(1, player.unmutes)
    }

    @Test fun P05_aRouteChangeAfterTheProofMutesStopsAndFailsWithoutFinishing() {
        routedByNext = Gear.a2dp.id
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        player.started!!()
        assertEquals(1, player.unmutes)
        player.routed = Gear.speaker.id
        player.routeListener!!()
        assertQuietEnd(player, HeadsetText.PLAYBACK_UNROUTED, finished, run)
        assertEquals("only ever unmuted while on the personal output", listOf<Int?>(Gear.a2dp.id), player.unmutedWhileRoutedTo)
    }

    @Test fun P06_theOutputRemovedBeforeTheProofNeverSoundsAndOneRemovedAfterItMutesAndStops() {
        val before = AtomicInteger()
        val run = start(cue(Gear.a2dp), before)
        val player = playing()
        devices.disconnectAll()
        assertQuietEnd(player, HeadsetText.PLAYBACK_LOST, before, run)
        assertEquals(0, player.unmutes)
        assertEquals("a late start callback after the end changes nothing", 1, player.released)
        player.started!!()
        player.completed!!()
        pump("late callbacks") { true }
        assertEquals(0, player.unmutes)
        assertEquals(1, player.released)
        assertEquals(0, before.get())

        devices.set(listOf(Gear.speaker, Gear.a2dp), emptyList())
        players.clear()
        routedByNext = Gear.a2dp.id
        val after = AtomicInteger()
        val second = start(cue(Gear.a2dp, seq = 2), after)
        val playerTwo = playing()
        playerTwo.started!!()
        assertEquals(1, playerTwo.unmutes)
        devices.disconnectAll()
        assertQuietEnd(playerTwo, HeadsetText.PLAYBACK_LOST, after, second)
    }

    @Test fun P07_aBecomingNoisyStyleRouteCallbackWithTheOutputGoneEndsItMuted() {
        routedByNext = Gear.a2dp.id
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        player.started!!()
        devices.set(listOf(Gear.speaker), emptyList())
        player.routed = Gear.speaker.id
        player.routeListener?.invoke()
        assertQuietEnd(player, null, finished, run)
    }

    @Test fun P08_focusLostOrTransientlyLostBeforeOrAfterTheProofEndsMutedAndALateCallbackIsInert() {
        for (change in listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
            players.clear()
            routedByNext = null
            val before = AtomicInteger()
            val run = start(cue(Gear.a2dp, seq = change + 10), before)
            val player = playing()
            player.started!!()
            focus(change)
            assertQuietEnd(player, "phone playback stopped: audio focus lost", before, run)
            assertEquals(0, player.unmutes)
            focus(change)
            player.started!!()
            pump("late callback") { true }
            assertEquals(1, player.released)

            players.clear()
            routedByNext = Gear.a2dp.id
            val after = AtomicInteger()
            val second = start(cue(Gear.a2dp, seq = change + 20), after)
            val playerTwo = playing()
            playerTwo.started!!()
            assertEquals(1, playerTwo.unmutes)
            focus(change)
            assertQuietEnd(playerTwo, "phone playback stopped: audio focus lost", after, second)
        }
    }

    @Test fun P09_aClipThatNeverStartsEndsUnroutedAtTheBoundWithNothingAudible() {
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        advance(PhoneSpeakerSink.ROUTE_CONFIRM_MS + 200)
        assertQuietEnd(player, HeadsetText.PLAYBACK_UNROUTED, finished, run)
        assertEquals(0, player.unmutes)
    }

    @Test fun P10_completionBeforeTheOutputWasEverProvenIsNotAPlayedClaim() {
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        player.started!!()
        player.completed!!()
        assertQuietEnd(player, HeadsetText.PLAYBACK_UNROUTED, finished, run)
        assertEquals(0, player.unmutes)
        players.clear()
        val neverStarted = AtomicInteger()
        val second = start(cue(Gear.a2dp, seq = 2), neverStarted)
        val playerTwo = playing()
        playerTwo.completed!!()
        assertQuietEnd(playerTwo, HeadsetText.PLAYBACK_UNROUTED, neverStarted, second)
    }

    @Test fun P11_duplicateStartAndCompletionCallbacksUnmuteAndFinishExactlyOnce() {
        routedByNext = Gear.a2dp.id
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        player.started!!()
        player.started!!()
        player.routeListener!!()
        player.routeListener!!()
        assertEquals(1, player.unmutes)
        player.completed!!()
        player.completed!!()
        assertTrue(run.result().isSuccess)
        pump("duplicates") { true }
        assertEquals(1, finished.get())
        assertEquals(1, player.released)
        assertEquals(1, player.watchClosed)
    }

    @Test fun P12_cancellingBeforeOrAfterTheProofMutesStopsAndLetsGoOfEverything() {
        val first = start(cue(Gear.a2dp))
        val player = playing()
        player.started!!()
        first.cancel()
        pump("cancelled") { first.isCompleted }
        pump("released") { player.released >= 1 && devices.watching == 0 }
        assertEquals("mute:true", player.log.last { it.startsWith("mute") })
        assertTrue("stop" in player.log)
        assertEquals(0, player.unmutes)
        assertEquals(1, player.watchClosed)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)

        players.clear()
        routedByNext = Gear.a2dp.id
        val second = start(cue(Gear.a2dp, seq = 2))
        val playerTwo = playing()
        playerTwo.started!!()
        assertEquals(1, playerTwo.unmutes)
        second.cancel()
        pump("cancelled") { second.isCompleted }
        pump("released") { playerTwo.released >= 1 && devices.watching == 0 }
        assertEquals("mute:true", playerTwo.log.last { it.startsWith("mute") })
        assertEquals(1, playerTwo.released)
        assertEquals(1, playerTwo.watchClosed)
    }

    @Test fun P13_aPlayerErrorBeforeTheProofEndsAsAnErrorWithoutEverUnmuting() {
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        player.errored!!(1, 2)
        val failure = run.result().exceptionOrNull()
        assertTrue(failure is HermesPlaybackException)
        assertEquals("phone playback error 1/2", failure!!.message)
        assertEquals(0, player.unmutes)
        assertEquals(0, finished.get())
        assertEquals(1, player.released)
        assertEquals(1, player.watchClosed)
        assertEquals(0, devices.watching)
    }

    @Test fun P14_aPlayerThatCannotBeMutedOrWatchedNeverPlaysAndEndsQuietly() {
        configure = { it.failMute = true }
        val muteFailed = start(cue(Gear.a2dp)).result().exceptionOrNull()
        assertEquals(HeadsetText.PLAYBACK_UNROUTED, muteFailed!!.message)
        assertTrue("play never called", players.single().path == null)
        assertEquals(0, devices.watching)

        players.clear()
        configure = { it.failWatch = true }
        val watchFailed = start(cue(Gear.a2dp, seq = 2)).result().exceptionOrNull()
        assertEquals(HeadsetText.PLAYBACK_UNROUTED, watchFailed!!.message)
        assertTrue("play never called", players.single().path == null)
        assertEquals(1, players.single().released)
        assertEquals(0, devices.watching)
    }

    // r4 owner-directed supersession of the r3 P15 ("a null route after the proof is not a change"): once the personal output was
    // proven, Android naming no route any more is missing authority, so the clip is muted, stopped and fails quiet even while the
    // device list still shows the headset. A route event naming the SAME device stays harmless (P18).
    @Test fun P15_aNullRouteAfterTheProofIsMissingAuthorityAndEndsMutedWhileTheHeadsetStillListsAsConnected() {
        routedByNext = Gear.a2dp.id
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        player.started!!()
        assertEquals(1, player.unmutes)
        assertTrue("the headset is still listed", devices.outputs().any { it.sameDevice(Gear.a2dp) })
        player.routed = null
        player.routeListener!!()
        assertQuietEnd(player, HeadsetText.PLAYBACK_UNROUTED, finished, run)
        assertEquals("only ever unmuted while on the personal output", listOf<Int?>(Gear.a2dp.id), player.unmutedWhileRoutedTo)
        assertEquals(1, player.unmutes)
    }

    @Test fun P18_aRouteEventNamingTheSameProvenOutputKeepsPlayingAndCompletesAsPlayed() {
        routedByNext = Gear.a2dp.id
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        player.started!!()
        repeat(3) { player.routeListener!!() }
        advance(PhoneSpeakerSink.ROUTE_POLL_MS * 3)
        assertFalse("still playing", run.isCompleted)
        assertEquals(1, player.unmutes)
        assertEquals("never re-silenced by a harmless route event", 1, player.log.count { it == "mute:true" })
        player.completed!!()
        assertTrue(run.result().isSuccess)
        assertEquals(1, finished.get())
        assertEquals(1, player.released)
    }

    @Test fun P19_aTerminalLossAfterTheProofCannotBeResurrectedByALateRouteStartCompletionFocusOrNoisyCallback() {
        routedByNext = Gear.a2dp.id
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        player.started!!()
        player.routed = null
        player.routeListener!!()
        assertQuietEnd(player, HeadsetText.PLAYBACK_UNROUTED, finished, run)
        val before = player.log.toList()
        player.routed = Gear.a2dp.id
        player.routeListener!!()
        player.started!!()
        player.noisyListener!!()
        player.completed!!()
        focus(AudioManager.AUDIOFOCUS_LOSS)
        focus(AudioManager.AUDIOFOCUS_GAIN)
        advance(PhoneSpeakerSink.ROUTE_CONFIRM_MS + 500)
        assertEquals("nothing happens to the player after the end", before, player.log)
        assertEquals(1, player.unmutes)
        assertEquals(0, finished.get())
    }

    @Test fun P20_aBecomingNoisySignalAfterTheProofMutesAndStopsEvenThoughRouteAndDevicesStillNameTheExactHeadset() {
        routedByNext = Gear.a2dp.id
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        player.started!!()
        assertEquals(1, player.unmutes)
        assertEquals(Gear.a2dp.id, player.routedDeviceId())
        assertTrue(devices.outputs().any { it.sameDevice(Gear.a2dp) })
        player.noisyListener!!()
        assertQuietEnd(player, HeadsetText.PLAYBACK_LOST, finished, run)
        assertEquals(listOf<Int?>(Gear.a2dp.id), player.unmutedWhileRoutedTo)
        val before = player.log.toList()
        player.noisyListener!!()
        player.routeListener!!()
        player.completed!!()
        advance(PhoneSpeakerSink.ROUTE_CONFIRM_MS + 500)
        assertEquals("nothing happens to the player after the end", before, player.log)
        assertEquals(1, player.unmutes)
        assertEquals(0, finished.get())
    }

    @Test fun P21_aBecomingNoisySignalBeforeTheProofOrBeforeTheStartCallbackNeverUnmutesLater() {
        routedByNext = Gear.a2dp.id
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val player = playing()
        assertEquals("route already names the headset but the start is not reported yet", 0, player.unmutes)
        player.noisyListener!!()
        assertQuietEnd(player, HeadsetText.PLAYBACK_LOST, finished, run)
        player.started!!()
        player.routeListener!!()
        advance(PhoneSpeakerSink.ROUTE_CONFIRM_MS + 500)
        assertEquals("the positive-confirmation race cannot unmute after the loss", 0, player.unmutes)
        assertTrue(player.unmutedWhileRoutedTo.isEmpty())
    }

    @Test fun P22_aBecomingNoisySignalDeliveredWhileTheRouteWatchIsBeingRegisteredStillEndsQuietlyAndNeverPlays() {
        configure = { it.noisyDuringWatch = true }
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        val failure = run.result().exceptionOrNull()
        assertTrue(failure is HermesPlaybackException)
        assertEquals(HeadsetText.PLAYBACK_LOST, failure!!.message)
        val player = players.single()
        assertNull("the native player was never started", player.path)
        assertEquals(0, player.unmutes)
        assertEquals(0, finished.get())
        assertEquals(1, player.released)
        assertEquals(1, player.watchClosed)
        assertEquals(0, devices.watching)
        assertEquals("mute:true", player.log.last { it.startsWith("mute") })
    }

    // The sink wired to the production AndroidPlayer's route/noisy registration: the real dynamic receiver gets the real broadcast.
    private class HybridPlayer(val strict: StrictPlayer, val real: AndroidPlayer) : PlayerPort by strict {
        override fun watchRoute(onChange: () -> Unit, onNoisy: () -> Unit): AutoCloseable = real.watchRoute(onChange, onNoisy)
        override fun release() { strict.release(); real.release() }
    }

    @Test fun P23_aRealBecomingNoisyBroadcastThroughTheProductionAdapterMutesAndStopsTheSinkWhileReadbackIsStale() {
        val app = RuntimeEnvironment.getApplication()
        val stricts = mutableListOf<StrictPlayer>()
        val hybridSink = PhoneSpeakerSink(app, devices, PlayerFactory { attributes: AudioAttributes ->
            val strict = StrictPlayer(Gear.a2dp.id).also { stricts += it }
            HybridPlayer(strict, AndroidPlayer(app, attributes))
        })
        val finished = AtomicInteger()
        val run = scope.async { runCatching { hybridSink.playConfirmed(SpokenAudio(ByteArray(64) { 1 }, "audio/mpeg"), cue(Gear.a2dp)) { finished.incrementAndGet() } } }
        pump("play") { stricts.isNotEmpty() && stricts[0].started != null }
        val player = stricts.single()
        player.started!!()
        assertEquals(1, player.unmutes)
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        val failure = run.result().exceptionOrNull()
        assertTrue(failure is HermesPlaybackException)
        assertEquals(HeadsetText.PLAYBACK_LOST, failure!!.message)
        assertEquals("mute:true", player.log.last { it.startsWith("mute") })
        assertTrue("stop" in player.log)
        assertEquals(1, player.released)
        assertEquals(0, finished.get())
        assertEquals(0, devices.watching)
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("a later broadcast changes nothing", 1, player.released)
        assertEquals(1, player.unmutes)
    }

    @Test fun P16_anUnboundClipIsExactlyTheLegacyPlaybackWithNoMuteNoRouteNoWatch() {
        val finished = AtomicInteger()
        val run = start(cue(null), finished)
        val player = playing()
        assertEquals(listOf("play"), player.log)
        assertEquals(0, devices.watching)
        devices.disconnectAll()
        devices.connect(Gear.wiredHeadphones)
        player.started!!()
        player.completed!!()
        assertTrue(run.result().isSuccess)
        assertEquals(1, finished.get())
        assertEquals(listOf("play", "release"), player.log)
        advance(PhoneSpeakerSink.ROUTE_CONFIRM_MS + 500)
        assertEquals(listOf("play", "release"), player.log)
    }

    @Test fun P17_aRefusedPreferredOutputOrDeniedFocusNeverPlays() {
        configure = { }
        val refusing = PhoneSpeakerSink(RuntimeEnvironment.getApplication(), devices, PlayerFactory { _: AudioAttributes ->
            StrictPlayer(null, accepts = false).also { players += it }
        })
        val run = scope.async { runCatching { refusing.playConfirmed(SpokenAudio(ByteArray(64), "audio/mpeg"), cue(Gear.a2dp)) {} } }
        assertEquals(HeadsetText.PLAYBACK_UNROUTED, run.result().exceptionOrNull()!!.message)
        assertNull(players.single().path)
        assertEquals(listOf("mute:true", "preferred:${Gear.a2dp.id}", "mute:true", "stop", "release"), players.single().log)

        players.clear()
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val denied = start(cue(Gear.a2dp, seq = 2)).result().exceptionOrNull()
        assertTrue(denied!!.message!!.contains("audio focus denied"))
        assertTrue(players.all { it.path == null && it.unmutes == 0 })
    }

    // ── the production adapter over Robolectric's MediaPlayer shadow ─────────────────────────

    private fun inner(player: AndroidPlayer): MediaPlayer {
        val field = AndroidPlayer::class.java.getDeclaredField("player")
        field.isAccessible = true
        return field.get(player) as MediaPlayer
    }

    private fun attributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    @Test fun A01_theAndroidPlayerAdapterMutesThroughTheRealMediaPlayerVolumeAndRestoresIt() {
        val player = AndroidPlayer(RuntimeEnvironment.getApplication(), attributes())
        val shadow = shadowOf(inner(player))
        player.setMuted(true)
        assertEquals(0f, shadow.leftVolume, 0f)
        assertEquals(0f, shadow.rightVolume, 0f)
        player.setMuted(false)
        assertEquals(1f, shadow.leftVolume, 0f)
        assertEquals(1f, shadow.rightVolume, 0f)
        player.release()
    }

    @Test fun A02_theAndroidPlayerAdapterRefusesAnOutputAndNamesNoRouteWhenAndroidHasNone() {
        val player = AndroidPlayer(RuntimeEnvironment.getApplication(), attributes())
        assertFalse(player.setPreferredDevice(Gear.a2dp.id))
        assertNull(player.routedDeviceId())
        player.release()
    }

    // r4: the becoming-noisy broadcast now reaches the distinct onNoisy callback (not the ordinary onChange one).
    @Test fun A03_theAndroidPlayerAdapterWatchRouteHearsABecomingNoisyBroadcastAsTheDistinctNoisySignalUntilClosed() {
        val context = RuntimeEnvironment.getApplication()
        val player = AndroidPlayer(context, attributes())
        val changed = AtomicInteger()
        val noisy = AtomicInteger()
        val handle = player.watchRoute({ changed.incrementAndGet() }, { noisy.incrementAndGet() })
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the broadcast is the noisy signal", 1, noisy.get())
        assertEquals("and not an ordinary route change", 0, changed.get())
        handle.close()
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("closed: no further callbacks", 1, noisy.get())
        assertEquals(0, changed.get())
        handle.close()
        player.release()
    }

    @Test fun A04_theAndroidPlayerAdapterPlaysThroughTheRealPreparedAndCompletionCallbacks() {
        val player = AndroidPlayer(RuntimeEnvironment.getApplication(), attributes())
        val file = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "adapter-smoke.mp3").apply { writeBytes(ByteArray(16)) }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.absolutePath), ShadowMediaPlayer.MediaInfo(1000, -1))
        val events = mutableListOf<String>()
        player.setMuted(true)
        player.play(file.absolutePath, { events += "started" }, { events += "completed" }, { what, extra -> events += "error:$what/$extra" })
        val shadow = shadowOf(inner(player))
        assertEquals("muted before the native start", 0f, shadow.leftVolume, 0f)
        shadow.invokePreparedListener()
        assertEquals(listOf("started"), events)
        assertEquals("the start did not unmute", 0f, shadow.leftVolume, 0f)
        shadow.invokeCompletionListener()
        assertEquals(listOf("started", "completed"), events)
        player.release()
        file.delete()
    }
}
