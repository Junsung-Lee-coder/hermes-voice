package com.rumi.hermesvoice.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.rumi.hermesvoice.core.FakeDevices
import com.rumi.hermesvoice.core.Gear
import com.rumi.hermesvoice.core.headset.AudioEndpoint
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.headset.RecordingCueTone
import java.time.Duration
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
 * SCRATCH HARNESS (never packaged). Owner finding F4: the recording cue is a real audio-focus participant. The production
 * [AndroidCueOutput] (its real focus listener, registered with the platform AudioManager shadow) runs over a strict recording
 * [CueTrackPort]; the real [RecordingCues] wraps it for the end-to-end cases. Loss of focus, denied focus, a vanished or wrong
 * output, a timeout and every late callback end the cue exactly once, muted and released, never on another output. The real
 * [AndroidCueTrack] adapter is smoke-tested over the AudioTrack shadow. Audible behaviour, real focus arbitration and Bluetooth
 * routing are NOT_RUN.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneCueFocusRouteTest {
    private class Track(var routed: Int?, val accepts: Boolean = true, val writes: Boolean = true) : CueTrackPort {
        val log = mutableListOf<String>()
        var silenced = false
        var mutedAtPlay: Boolean? = null
        val unmutedWhileRoutedTo = mutableListOf<Int?>()
        var written: ByteArray? = null
        var routeListener: (() -> Unit)? = null
        var routeClosed = 0
        var marker: (() -> Unit)? = null
        var markerFrames = -1
        val released get() = log.count { it == "release" }
        val unmutes get() = log.count { it == "mute:false" }

        override fun setPreferredDevice(deviceId: Int): Boolean { log += "preferred:$deviceId"; return accepts }
        override fun write(pcm: ByteArray): Boolean { log += "write"; written = pcm; return writes }
        override fun setMuted(muted: Boolean) {
            this.silenced = muted
            log += "mute:$muted"
            if (!muted) unmutedWhileRoutedTo += routed
        }
        override fun routedDeviceId(): Int? = routed
        override fun watchRoute(onChange: () -> Unit, handler: Handler): AutoCloseable {
            log += "watch"
            routeListener = onChange
            return AutoCloseable { routeClosed++; log += "unwatch" }
        }
        override fun onMarker(frames: Int, handler: Handler, reached: () -> Unit) { log += "marker"; markerFrames = frames; marker = reached }
        override fun play() { mutedAtPlay = silenced; log += "play" }
        override fun stop() { log += "stop" }
        override fun release() { log += "release" }
    }

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var audio: AudioManager
    private val tracks = mutableListOf<Track>()
    private var routedByNext: Int? = null
    private var build: (Track) -> Track = { it }
    private var created = 0
    private var createHook: () -> Unit = {}
    private var failCreate = false
    private lateinit var output: AndroidCueOutput
    private val results = mutableListOf<CueResult>()
    private val target = Gear.wiredHeadset

    @Before fun setUp() {
        audio = app.getSystemService(AudioManager::class.java)
        output = AndroidCueOutput(app, CueTrackFactory { _: AudioAttributes, _: Int, _: Int ->
            created++
            if (failCreate) throw IllegalStateException("no track")
            createHook()
            build(Track(routedByNext)).also { tracks += it }
        })
    }

    private fun idle(ms: Long = 20) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun focus(change: Int) = shadowOf(audio).lastAudioFocusRequest!!.listener.onAudioFocusChange(change)

    private fun cue(pcm: ByteArray = RecordingCueTone.pcm(1), endpoint: AudioEndpoint = target): AutoCloseable =
        output.play(pcm, RecordingCueTone.SAMPLE_RATE, endpoint) { results += it }

    private fun assertCleaned(track: Track) {
        assertEquals("muted last", "mute:true", track.log.last { it.startsWith("mute") })
        assertTrue("stopped", "stop" in track.log)
        assertEquals("released once", 1, track.released)
        assertEquals("route watch closed once", 1, track.routeClosed)
        assertNotNull("focus given back", shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun C01_theCueStartsMutedAndSoundsOnlyOnceAndroidNamesExactlyTheHeadsetAndThenReportsPlayedOnce() {
        cue()
        idle()
        val track = tracks.single()
        assertEquals(listOf("mute:true", "preferred:${target.id}", "write", "watch", "marker", "play"), track.log.take(6))
        assertEquals("muted when the native track started", true, track.mutedAtPlay)
        assertEquals(RecordingCueTone.pcm(1).size / 2, track.markerFrames)
        assertTrue(RecordingCueTone.pcm(1).contentEquals(track.written!!))
        assertEquals(0, track.unmutes)
        track.routed = target.id
        track.routeListener!!()
        assertEquals(listOf<Int?>(target.id), track.unmutedWhileRoutedTo)
        track.routeListener!!()
        assertEquals("a repeated route report does not unmute again", 1, track.unmutes)
        track.marker!!()
        idle()
        assertEquals(listOf(CueResult.PLAYED), results)
        assertCleaned(track)
        track.marker!!()
        track.routeListener!!()
        idle()
        assertEquals("late callbacks are inert", listOf(CueResult.PLAYED), results)
        assertEquals(1, track.released)
    }

    @Test fun C02_aWrongOutputNeverSoundsAndEndsUnroutedOnceWithoutAFallback() {
        routedByNext = Gear.speaker.id
        cue()
        idle()
        val track = tracks.single()
        assertEquals(listOf(CueResult.UNROUTED), results)
        assertEquals(0, track.unmutes)
        assertCleaned(track)
    }

    @Test fun C03_noRouteEverNamedStaysMutedAndEndsUnroutedAtTheMarker() {
        cue()
        idle()
        val track = tracks.single()
        track.routeListener!!()
        track.marker!!()
        idle()
        assertEquals(listOf(CueResult.UNROUTED), results)
        assertEquals(0, track.unmutes)
        assertCleaned(track)
    }

    @Test fun C04_aRouteChangeAfterTheProofMutesAndEndsUnroutedOnce() {
        routedByNext = target.id
        cue()
        idle()
        val track = tracks.single()
        assertEquals("proved at once", 1, track.unmutes)
        track.routed = Gear.speaker.id
        track.routeListener!!()
        idle()
        assertEquals(listOf(CueResult.UNROUTED), results)
        assertCleaned(track)
        track.marker!!()
        idle()
        assertEquals(listOf(CueResult.UNROUTED), results)
    }

    @Test fun C05_focusLostOrTransientlyLostWhileWaitingForTheRouteEndsTheCueMutedAndInertAfterwards() {
        for (change in listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
            tracks.clear()
            results.clear()
            cue()
            idle()
            val track = tracks.single()
            assertEquals(0, track.unmutes)
            focus(change)
            idle()
            assertEquals(listOf(CueResult.FOCUS_LOST), results)
            assertCleaned(track)
            focus(change)
            track.routed = target.id
            track.routeListener!!()
            track.marker!!()
            idle()
            assertEquals("one ending only", listOf(CueResult.FOCUS_LOST), results)
            assertEquals("never sounded after the loss", 0, track.unmutes)
            assertEquals(1, track.released)
        }
    }

    @Test fun C06_focusLostAfterTheProofStopsTheSoundingCueOnceAndAMarkerLaterDoesNotReportPlayed() {
        routedByNext = target.id
        cue()
        idle()
        val track = tracks.single()
        assertEquals(1, track.unmutes)
        focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idle()
        assertEquals(listOf(CueResult.FOCUS_LOST), results)
        assertCleaned(track)
        track.marker!!()
        idle()
        assertEquals(listOf(CueResult.FOCUS_LOST), results)
    }

    @Test fun C07_deniedFocusBuildsNoTrackAndReportsDeniedOnce() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val handle = cue()
        idle()
        assertEquals(0, created)
        assertEquals(listOf(CueResult.FOCUS_DENIED), results)
        handle.close()
        idle()
        assertEquals(listOf(CueResult.FOCUS_DENIED), results)
    }

    @Test fun C08_closingTheHandleCancelsSilentlyWithNoResultAndALaterFocusLossIsInert() {
        val handle = cue()
        idle()
        val track = tracks.single()
        handle.close()
        assertCleaned(track)
        handle.close()
        focus(AudioManager.AUDIOFOCUS_LOSS)
        track.marker!!()
        idle()
        assertTrue("a silent cancel reports nothing", results.isEmpty())
        assertEquals(1, track.released)
    }

    @Test fun C09_focusLostWhileTheTrackWasBeingBuiltNeverPreferredNeverWrittenNeverPlayed() {
        createHook = { focus(AudioManager.AUDIOFOCUS_LOSS) }
        cue()
        idle()
        val track = tracks.single()
        assertEquals(listOf(CueResult.FOCUS_LOST), results)
        assertFalse("never played", "play" in track.log)
        assertFalse("never asked for an output", track.log.any { it.startsWith("preferred") })
        assertEquals(0, track.unmutes)
        assertEquals(1, track.released)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun C10_aRefusedOutputAFailedWriteOrNoTrackAtAllEndsQuietlyWithoutPlaying() {
        build = { Track(null, accepts = false) }
        cue()
        idle()
        assertEquals(listOf(CueResult.UNROUTED), results)
        assertFalse("play" in tracks.single().log)
        assertEquals(0, tracks.single().unmutes)
        assertEquals(1, tracks.single().released)

        tracks.clear(); results.clear()
        build = { Track(null, writes = false) }
        cue()
        idle()
        assertEquals(listOf(CueResult.FAILED), results)
        assertFalse("play" in tracks.single().log)
        assertEquals(1, tracks.single().released)

        tracks.clear(); results.clear()
        failCreate = true
        cue()
        idle()
        assertEquals(listOf(CueResult.FAILED), results)
        assertTrue(tracks.isEmpty())
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    // ── through the real RecordingCues: disconnect, timeout, and the one-start / two-stop tones ─────────────

    private fun recordingCues(devices: FakeDevices): RecordingCues = RecordingCues(HeadsetPolicy({ true }, devices), output)

    private val wired get() = FakeDevices(listOf(Gear.speaker, Gear.wiredHeadset), listOf(Gear.wiredHeadsetMic))

    @Test fun C11_throughRecordingCuesTheStartAndStopTonesAreOneAndTwoDingsAndOnlyTheirOwnPcmIsWritten() {
        val cues = recordingCues(wired)
        val ends = mutableListOf<CueResult>()
        cues.play(CueKind.START) { ends += it }
        idle()
        val start = tracks.single()
        assertTrue(RecordingCueTone.pcm(1).contentEquals(start.written!!))
        start.routed = target.id
        start.routeListener!!()
        start.marker!!()
        idle()
        assertEquals(listOf(CueResult.PLAYED), ends)
        cues.play(CueKind.STOP) { ends += it }
        idle()
        val stop = tracks.last()
        assertTrue(RecordingCueTone.pcm(2).contentEquals(stop.written!!))
        assertFalse(RecordingCueTone.pcm(1).contentEquals(RecordingCueTone.pcm(2)))
        assertEquals(CueKind.START.dings, 1)
        assertEquals(CueKind.STOP.dings, 2)
    }

    @Test fun C12_throughRecordingCuesAFocusLossReportsFocusLostOnceAndALateOutputLossOrTimeoutAddsNothing() {
        val devices = wired
        val cues = recordingCues(devices)
        val ends = mutableListOf<CueResult>()
        cues.play(CueKind.START) { ends += it }
        idle()
        val track = tracks.single()
        focus(AudioManager.AUDIOFOCUS_LOSS)
        idle()
        assertEquals(listOf(CueResult.FOCUS_LOST), ends)
        assertCleaned(track)
        devices.disconnectAll()
        idle(RecordingCues.CUE_TIMEOUT_MS + 500)
        assertEquals("one ending only", listOf(CueResult.FOCUS_LOST), ends)
        assertEquals(1, track.released)
        assertEquals(0, track.unmutes)
    }

    @Test fun C13_throughRecordingCuesTheOutputDisconnectingMidCueCancelsTheTrackOnceAsLostAndNothingReplays() {
        val devices = wired
        val cues = recordingCues(devices)
        val ends = mutableListOf<CueResult>()
        cues.play(CueKind.START) { ends += it }
        idle()
        val track = tracks.single()
        devices.disconnectAll()
        idle()
        assertEquals(listOf(CueResult.LOST), ends)
        assertCleaned(track)
        assertEquals(0, track.unmutes)
        devices.set(listOf(Gear.speaker, Gear.wiredHeadset), listOf(Gear.wiredHeadsetMic))
        idle(500)
        focus(AudioManager.AUDIOFOCUS_LOSS)
        track.marker!!()
        idle()
        assertEquals(listOf(CueResult.LOST), ends)
        assertEquals("no second track on reconnect", 1, tracks.size)
    }

    @Test fun C14_throughRecordingCuesAnUnconfirmedRouteEndsAtTheTimeoutMutedWithNoPlayedClaim() {
        val cues = recordingCues(wired)
        val ends = mutableListOf<CueResult>()
        cues.play(CueKind.START) { ends += it }
        idle()
        val track = tracks.single()
        idle(RecordingCues.CUE_TIMEOUT_MS + 200)
        assertEquals(listOf(CueResult.TIMEOUT), ends)
        assertCleaned(track)
        assertEquals(0, track.unmutes)
        focus(AudioManager.AUDIOFOCUS_LOSS)
        track.marker!!()
        idle()
        assertEquals(listOf(CueResult.TIMEOUT), ends)
    }

    @Test fun C15_aNewCueCancelsTheRunningOneWithoutAFocusReplayOrASecondEndingOfTheFirst() {
        val cues = recordingCues(wired)
        val first = mutableListOf<CueResult>()
        val second = mutableListOf<CueResult>()
        cues.play(CueKind.START) { first += it }
        idle()
        val a = tracks.single()
        cues.play(CueKind.STOP) { second += it }
        idle()
        assertEquals(listOf(CueResult.CANCELLED), first)
        assertCleaned(a)
        assertEquals(2, tracks.size)
        val b = tracks.last()
        b.routed = target.id
        b.routeListener!!()
        b.marker!!()
        idle()
        assertEquals(listOf(CueResult.PLAYED), second)
        assertEquals(listOf(CueResult.CANCELLED), first)
    }

    // ── r4: the cue stays fail-quiet AFTER the initial route proof, and hears an explicit becoming-noisy signal ───────

    private fun noisyIntent() = Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)

    private fun noisyReceivers() = shadowOf(app).getReceiversForIntent(noisyIntent())

    private fun noisyNow() = noisyReceivers().forEach { it.onReceive(app, noisyIntent()) }

    private fun noisyBroadcast() {
        app.sendBroadcast(noisyIntent())
        idle()
    }

    @Test fun C16_aNullRouteAfterTheProofEndsTheSoundingCueMutedAndUnroutedOnceAndALaterMarkerOrFocusGainIsInert() {
        routedByNext = target.id
        cue()
        idle()
        val track = tracks.single()
        assertEquals(1, track.unmutes)
        track.routed = null
        track.routeListener!!()
        idle()
        assertEquals(listOf(CueResult.UNROUTED), results)
        assertCleaned(track)
        track.routed = target.id
        track.routeListener!!()
        track.marker!!()
        focus(AudioManager.AUDIOFOCUS_GAIN)
        focus(AudioManager.AUDIOFOCUS_LOSS)
        idle(RecordingCues.CUE_TIMEOUT_MS + 500)
        assertEquals("no PLAYED and no second ending from stale callbacks", listOf(CueResult.UNROUTED), results)
        assertEquals("never unmuted again", 1, track.unmutes)
        assertEquals(1, track.released)
        assertEquals("submitted once, no second track", 1, created)
    }

    @Test fun C17_aBecomingNoisyBroadcastWhileTheTrackStillReportsTheExactHeadsetMutesStopsAndEndsLostOnce() {
        routedByNext = target.id
        val base = noisyReceivers().size
        cue()
        idle()
        val track = tracks.single()
        assertEquals(1, track.unmutes)
        assertEquals(target.id, track.routedDeviceId())
        noisyBroadcast()
        assertEquals(listOf(CueResult.LOST), results)
        assertCleaned(track)
        assertEquals(listOf<Int?>(target.id), track.unmutedWhileRoutedTo)
        assertEquals("the receiver is given back", base, noisyReceivers().size)
        noisyBroadcast()
        track.routeListener!!()
        track.marker!!()
        focus(AudioManager.AUDIOFOCUS_LOSS)
        idle(RecordingCues.CUE_TIMEOUT_MS + 500)
        assertEquals("one ending only", listOf(CueResult.LOST), results)
        assertEquals(1, track.released)
        assertEquals("no fallback track anywhere", 1, created)
    }

    @Test fun C18_aBecomingNoisyBroadcastBeforeAnyProofNeverLetsALaterRouteReportUnmute() {
        cue()
        idle()
        val track = tracks.single()
        assertEquals(0, track.unmutes)
        noisyBroadcast()
        assertEquals(listOf(CueResult.LOST), results)
        assertCleaned(track)
        track.routed = target.id
        track.routeListener!!()
        track.marker!!()
        idle()
        assertEquals(listOf(CueResult.LOST), results)
        assertEquals(0, track.unmutes)
    }

    @Test fun C19_theNoisyReceiverExistsOnlyForALiveCueAndIsGivenBackOnceWhateverEndsIt() {
        val base = noisyReceivers().size
        val endings = listOf<Pair<String, (AutoCloseable) -> Unit>>(
            "played" to { _: AutoCloseable -> tracks.single().let { it.routed = target.id; it.routeListener!!(); it.marker!!() } },
            "focus" to { _: AutoCloseable -> focus(AudioManager.AUDIOFOCUS_LOSS) },
            "cancel" to { handle: AutoCloseable -> handle.close() },
            "wrong output" to { _: AutoCloseable -> tracks.single().let { it.routed = Gear.speaker.id; it.routeListener!!() } },
            "noisy" to { _: AutoCloseable -> noisyBroadcast() },
        )
        for ((name, end) in endings) {
            tracks.clear(); results.clear(); routedByNext = null
            val handle = cue()
            idle()
            assertEquals("$name: exactly one receiver while the cue lives", base + 1, noisyReceivers().size)
            end(handle)
            idle()
            assertEquals("$name: receiver given back", base, noisyReceivers().size)
            handle.close()
            idle()
            assertEquals("$name: still given back after a second close", base, noisyReceivers().size)
        }

        tracks.clear(); results.clear()
        build = { Track(null, accepts = false) }
        cue(); idle()
        assertEquals("refused output", base, noisyReceivers().size)

        tracks.clear(); results.clear()
        build = { Track(null, writes = false) }
        cue(); idle()
        assertEquals("failed write", base, noisyReceivers().size)

        tracks.clear(); results.clear(); build = { it }
        failCreate = true
        cue(); idle()
        assertEquals("track creation exception", base, noisyReceivers().size)
        assertEquals(listOf(CueResult.FAILED), results)
        failCreate = false

        results.clear()
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        cue(); idle()
        assertEquals("denied focus", base, noisyReceivers().size)
        assertEquals(listOf(CueResult.FOCUS_DENIED), results)
    }

    @Test fun C20_aBecomingNoisySignalWhileTheTrackIsBeingBuiltNeverPrefersWritesOrPlaysIt() {
        createHook = { noisyNow() }
        val base = noisyReceivers().size
        cue()
        idle()
        val track = tracks.single()
        assertEquals(listOf(CueResult.LOST), results)
        assertFalse("never played", "play" in track.log)
        assertFalse("never asked for an output", track.log.any { it.startsWith("preferred") })
        assertEquals(0, track.unmutes)
        assertEquals(1, track.released)
        assertEquals("the receiver is given back", base, noisyReceivers().size)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun C21_throughRecordingCuesABecomingNoisyBroadcastEndsTheCueLostWithTheDevicesStillListedAndNothingReplays() {
        val devices = wired
        val cues = recordingCues(devices)
        val ends = mutableListOf<CueResult>()
        val base = noisyReceivers().size
        cues.play(CueKind.START) { ends += it }
        idle()
        val track = tracks.single()
        track.routed = target.id
        track.routeListener!!()
        assertEquals(1, track.unmutes)
        assertTrue("devices still list the exact headset", devices.outputs().any { it.sameDevice(target) })
        noisyBroadcast()
        assertEquals(listOf(CueResult.LOST), ends)
        assertCleaned(track)
        assertEquals(base, noisyReceivers().size)
        devices.set(listOf(Gear.speaker, Gear.wiredHeadset), listOf(Gear.wiredHeadsetMic))
        idle(RecordingCues.CUE_TIMEOUT_MS + 500)
        track.marker!!()
        focus(AudioManager.AUDIOFOCUS_LOSS)
        idle()
        assertEquals("one ending only", listOf(CueResult.LOST), ends)
        assertEquals("no second track or speaker fallback", 1, tracks.size)
        assertEquals(1, track.unmutes)
    }

    @Test fun C22_throughRecordingCuesATimeoutGivesTheNoisyReceiverBackToo() {
        val cues = recordingCues(wired)
        val ends = mutableListOf<CueResult>()
        val base = noisyReceivers().size
        cues.play(CueKind.START) { ends += it }
        idle()
        assertEquals(base + 1, noisyReceivers().size)
        idle(RecordingCues.CUE_TIMEOUT_MS + 200)
        assertEquals(listOf(CueResult.TIMEOUT), ends)
        assertEquals(base, noisyReceivers().size)
        assertCleaned(tracks.single())
    }

    @Test fun C23_repeatedRouteEventsNamingTheSameProvenHeadsetKeepTheCueSoundingAndItEndsPlayedOnce() {
        routedByNext = target.id
        cue()
        idle()
        val track = tracks.single()
        repeat(3) { track.routeListener!!() }
        idle()
        assertTrue("still live", results.isEmpty())
        assertEquals(1, track.unmutes)
        track.marker!!()
        idle()
        assertEquals(listOf(CueResult.PLAYED), results)
        assertCleaned(track)
    }

    private class NoReceiverContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? = throw SecurityException("no receivers")
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, flags: Int): Intent? = throw SecurityException("no receivers")
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, broadcastPermission: String?, scheduler: Handler?): Intent? =
            throw SecurityException("no receivers")
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, broadcastPermission: String?, scheduler: Handler?, flags: Int): Intent? =
            throw SecurityException("no receivers")
    }

    @Test fun C24_aCueWhoseBecomingNoisyReceiverCannotBeRegisteredNeverBuildsOrPlaysATrack() {
        val unprotected = AndroidCueOutput(NoReceiverContext(app), CueTrackFactory { _: AudioAttributes, _: Int, _: Int ->
            created++
            build(Track(routedByNext)).also { tracks += it }
        })
        routedByNext = target.id
        unprotected.play(RecordingCueTone.pcm(1), RecordingCueTone.SAMPLE_RATE, target) { results += it }
        idle()
        assertEquals(listOf(CueResult.FAILED), results)
        assertTrue("no track was built, so nothing could sound", tracks.isEmpty() || tracks.all { "play" !in it.log && it.unmutes == 0 })
        assertNotNull("focus given back", shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    // ── the production AudioTrack adapter over the platform shadow ───────────────────────────

    @Test fun A05_theAndroidCueTrackAdapterBuildsAStaticTrackMutesWritesWatchesAndReleases() {
        val track = AndroidCueTrack.factory(app).create(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            RecordingCueTone.SAMPLE_RATE,
            RecordingCueTone.pcm(1).size,
        )
        assertFalse("no such output", track.setPreferredDevice(Gear.wiredHeadset.id))
        assertNull(track.routedDeviceId())
        track.setMuted(true)
        track.setMuted(false)
        assertTrue(track.write(RecordingCueTone.pcm(1)))
        val handle = track.watchRoute({}, Handler(Looper.getMainLooper()))
        handle.close()
        track.release()
    }
}
