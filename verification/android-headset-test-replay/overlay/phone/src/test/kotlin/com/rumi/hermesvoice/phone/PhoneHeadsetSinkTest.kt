package com.rumi.hermesvoice.phone

import android.media.AudioAttributes
import android.media.AudioManager
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

/**
 * [PhoneSpeakerSink] with a fake player (the platform MediaPlayer is not run here): a headset-bound cue plays on THAT output
 * only, is checked against Android's endpoints and readback, ends with an error (never a speaker fallback) when the headset
 * goes, and always lets go of the player, focus, device watch and file. Nothing here touches the audio mode.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneHeadsetSinkTest {
    private class FakePlayer(var routed: Int?, var accepts: Boolean = true) : PlayerPort {
        var preferred: Int? = null
        var path: String? = null
        var stopped = 0
        var released = 0
        var onStarted: (() -> Unit)? = null
        var onCompleted: (() -> Unit)? = null
        var onError: ((Int, Int) -> Unit)? = null

        override fun setPreferredDevice(deviceId: Int): Boolean { preferred = deviceId; return accepts }
        override fun routedDeviceId(): Int? = routed
        override fun setMuted(muted: Boolean) = Unit
        override fun watchRoute(onChange: () -> Unit, onNoisy: () -> Unit): AutoCloseable = AutoCloseable { }
        override fun play(path: String, onStarted: () -> Unit, onCompleted: () -> Unit, onError: (Int, Int) -> Unit) {
            this.path = path
            this.onStarted = onStarted
            this.onCompleted = onCompleted
            this.onError = onError
        }
        override fun stop() { stopped++ }
        override fun release() { released++ }
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private lateinit var devices: FakeDevices
    private lateinit var players: MutableList<FakePlayer>
    private var routedByNext: Int? = null
    private lateinit var sink: PhoneSpeakerSink
    private lateinit var audio: AudioManager

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        val context = RuntimeEnvironment.getApplication()
        audio = context.getSystemService(AudioManager::class.java)
        devices = FakeDevices(listOf(Gear.speaker, Gear.a2dp))
        players = mutableListOf()
        sink = PhoneSpeakerSink(context, devices, PlayerFactory { _: AudioAttributes -> FakePlayer(routedByNext).also { players += it } })
    }

    @After fun tearDown() = scope.cancel()

    private fun cue(headset: AudioEndpoint?, seq: Int = 1) = PlaybackCue("t-$seq", VoiceOrigin.PHONE, SpokenRole.FINAL, seq, "hello", VoiceOrigin.PHONE, later = true, headset = headset)

    private fun pump(what: String, until: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun start(cue: PlaybackCue, finished: AtomicInteger = AtomicInteger()): Deferred<Result<Unit>> =
        scope.async { runCatching { sink.playConfirmed(SpokenAudio(ByteArray(64) { 1 }, "audio/mpeg"), cue) { finished.incrementAndGet() } } }

    private fun Deferred<Result<Unit>>.result(): Result<Unit> {
        pump("the sink to end") { isCompleted }
        return runBlocking { await() }
    }

    @Test
    fun `no headset - the existing speaker playback  no routing request  finished once on completion  everything released`() {
        val finished = AtomicInteger()
        val run = start(cue(null), finished)
        pump("a player") { players.isNotEmpty() && players[0].onStarted != null }
        val player = players.single()
        assertNull("no preferred device is asked for", player.preferred)
        assertEquals(0, devices.watching)
        player.onStarted!!()
        player.onCompleted!!()
        assertTrue(run.result().isSuccess)
        assertEquals(1, finished.get())
        assertEquals(1, player.released)
        assertEquals(0, devices.watching)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        assertFalse("the file is gone", java.io.File(player.path!!).exists())
    }

    @Test
    fun `a bound cue asks for exactly that output  confirms with the readback  and finishes on it`() {
        routedByNext = Gear.a2dp.id
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        pump("a player") { players.isNotEmpty() && players[0].onStarted != null }
        val player = players.single()
        assertEquals(Gear.a2dp.id, player.preferred)
        assertEquals("watching only while it plays", 1, devices.watching)
        player.onStarted!!()
        player.onCompleted!!()
        assertTrue(run.result().isSuccess)
        assertEquals(1, finished.get())
        assertEquals(0, devices.watching)
        assertEquals(1, player.released)
        assertEquals("the audio mode was never touched", AudioManager.MODE_NORMAL, audio.mode)
    }

    @Test
    fun `a headset that is not connected when playback starts fails at once with no player and no speaker`() {
        devices.disconnectAll()
        val failure = start(cue(Gear.a2dp)).result().exceptionOrNull()
        assertTrue(failure is HermesPlaybackException)
        assertEquals(HeadsetText.PLAYBACK_LOST, failure!!.message)
        assertTrue(players.isEmpty())
        assertEquals(0, devices.watching)
    }

    @Test
    fun `the headset disconnecting while it plays stops the player  releases focus and the watch  and finishes nothing`() {
        routedByNext = Gear.a2dp.id
        val finished = AtomicInteger()
        val run = start(cue(Gear.a2dp), finished)
        pump("a player") { players.isNotEmpty() && players[0].onStarted != null }
        val player = players.single()
        player.onStarted!!()
        devices.disconnectAll()
        val failure = run.result().exceptionOrNull()
        assertEquals(HeadsetText.PLAYBACK_LOST, failure!!.message)
        assertEquals(0, finished.get())
        assertTrue(player.stopped >= 1)
        assertEquals(1, player.released)
        assertEquals(1, players.size)
        assertEquals(0, devices.watching)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `a refused routing request never plays`() {
        val refusing = PhoneSpeakerSink(RuntimeEnvironment.getApplication(), devices, PlayerFactory { _: AudioAttributes -> FakePlayer(null, accepts = false).also { players += it } })
        val run = scope.async { runCatching { refusing.playConfirmed(SpokenAudio(ByteArray(64), "audio/mpeg"), cue(Gear.a2dp)) {} } }
        val failure = run.result().exceptionOrNull()
        assertEquals(HeadsetText.PLAYBACK_UNROUTED, failure!!.message)
        assertNull("play was never called", players.single().path)
        assertEquals(1, players.single().released)
        assertEquals(0, devices.watching)
    }

    @Test
    fun `a readback naming another output is not accepted as the headset`() {
        routedByNext = Gear.speaker.id
        val run = start(cue(Gear.a2dp))
        pump("a player") { players.isNotEmpty() && players[0].onStarted != null }
        val player = players.single()
        player.onStarted!!()
        val failure = run.result().exceptionOrNull()
        assertEquals(HeadsetText.PLAYBACK_UNROUTED, failure!!.message)
        assertEquals(1, player.released)
    }

    @Test
    fun `cancelling a headset playback stops the player and gives everything back`() {
        routedByNext = Gear.a2dp.id
        val run = start(cue(Gear.a2dp))
        pump("a player") { players.isNotEmpty() && players[0].onStarted != null }
        val player = players.single()
        player.onStarted!!()
        run.cancel()
        pump("cancelled") { run.isCompleted }
        pump("released") { player.released >= 1 && devices.watching == 0 }
        assertTrue(player.stopped >= 1)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `focus denied fails the headset playback without routing or playing`() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val failure = start(cue(Gear.a2dp)).result().exceptionOrNull()
        assertTrue(failure is HermesPlaybackException)
        assertTrue(failure!!.message!!.contains("audio focus denied"))
        assertTrue(players.all { it.path == null })
        assertEquals(0, devices.watching)
    }

    @Test
    fun `an unbound cue is untouched by headset state - a connected headset does not reroute it`() {
        val run = start(cue(null))
        pump("a player") { players.isNotEmpty() && players[0].onStarted != null }
        val player = players.single()
        assertNull(player.preferred)
        devices.disconnectAll()
        devices.connect(Gear.wiredHeadphones)
        player.onStarted!!()
        player.onCompleted!!()
        assertTrue(run.result().isSuccess)
    }
}
