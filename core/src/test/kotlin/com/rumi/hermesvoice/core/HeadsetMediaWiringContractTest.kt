package com.rumi.hermesvoice.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gates for amendment K and L that the JVM cannot run: the scoped MediaSession, the headset-only cue path, the manifest, and
 * that the Watch's recording and sending never consult the Phone's headset state. A text match proves a line exists, never runtime
 * timing; behavior is pinned in MediaButtonDecoderTest and the Phone Robolectric tests.
 */
class HeadsetMediaWiringContractTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(path: String) = File(root, path).takeIf { it.isFile }?.readText().orEmpty()

    private val phone = "phone/src/main/kotlin/com/rumi/hermesvoice/phone"
    private val watch = "watch/src/main/kotlin/com/rumi/hermesvoice/watch"

    @Test
    fun `the media buttons are taken by a scoped MediaSession - no fake player  service  accessibility hook or global key listener`() {
        val media = source("$phone/HeadsetMedia.kt")
        assertTrue(media.contains("MediaSession(") && media.contains("setCallback("))
        assertTrue("released", media.contains(".release()"))
        assertTrue(media.contains("PlaybackState.STATE_PAUSED") && media.contains("ACTION_PLAY_PAUSE"))
        for (forbidden in listOf("MediaPlayer", "AudioTrack", "ExoPlayer", "AccessibilityService", "MediaBrowserService", "MediaSessionService", "dispatchKeyEvent", "registerMediaButtonEventReceiver", "ToneGenerator", "startForeground")) {
            assertFalse("HeadsetMedia.kt must not use $forbidden", media.contains(forbidden))
        }
        val manifest = source("phone/src/main/AndroidManifest.xml")
        assertEquals("no service was added", 3, Regex("<service\\s").findAll(manifest).count())
        assertFalse(manifest.contains("MEDIA_BUTTON"))
        assertFalse(manifest.contains("BIND_ACCESSIBILITY_SERVICE"))
    }

    @Test
    fun `MEDIA_STOP and the other keys are not mapped and the keys come only from the decoder`() {
        val media = source("$phone/HeadsetMedia.kt")
        assertFalse(media.contains("KEYCODE_MEDIA_STOP"))
        assertFalse(media.contains("KEYCODE_MEDIA_NEXT") || media.contains("KEYCODE_MEDIA_PREVIOUS") || media.contains("KEYCODE_VOLUME"))
        assertTrue(media.contains("MediaButtonDecoder"))
        val decoder = source("core/src/main/kotlin/com/rumi/hermesvoice/core/headset/MediaButton.kt")
        assertFalse(decoder.contains("86 ->"))
    }

    @Test
    fun `the cue is generated locally and played only to the chosen headset - preferred device  readback  focus  no default tone path`() {
        val cues = source("$phone/RecordingCues.kt")
        assertTrue(cues.contains("AudioTrack") && cues.contains("setPreferredDevice(") && cues.contains("routedDevice"))
        assertTrue(cues.contains("requestAudioFocus(") && cues.contains("abandonAudioFocusRequest("))
        assertTrue(cues.contains("RecordingCueTone.pcm("))
        for (forbidden in listOf("ToneGenerator", "SoundPool", "MediaActionSound", "TextToSpeech", "playSoundEffect", "STREAM_SYSTEM", "STREAM_RING", "setStreamVolume", "adjustVolume", "setSpeakerphoneOn", "setMode(", "startBluetoothSco", "setCommunicationDevice")) {
            assertFalse("RecordingCues.kt must not use $forbidden", cues.contains(forbidden))
        }
        assertFalse("no network or backend", cues.contains("okhttp") || cues.contains("HermesDashboardClient") || cues.contains("api/audio"))
    }

    @Test
    fun `the view model owns the one recording controller path - the media command reaches toggleRecording's start and stop and the recorder keeps its admission gate`() {
        val vm = source("$phone/PhoneViewModel.kt")
        assertTrue(vm.contains("HeadsetRecordingTarget") && vm.contains("override fun onHeadsetCommand("))
        assertTrue("one recorder", Regex("WavRecorder\\(").findAll(vm).count() == 1)
        assertTrue(vm.contains("app.headsetMedia.attach(this)") && vm.contains("app.headsetMedia.detach(this)"))
        assertTrue(vm.contains("app.recordingCues"))
        val audio = source("$phone/PhoneAudio.kt")
        assertTrue(audio.contains("fun admit()") && audio.contains("admitted: Boolean"))
    }

    @Test
    fun `Watch recording and sending never consult the Phone's headset state or the private-output state`() {
        val capturing = listOf("WatchVoiceRuntime.kt", "WatchActivity.kt", "WatchCapture.kt", "WatchRecorder.kt")
            .map { source("$watch/$it") }.filter { it.isNotEmpty() }
        assertTrue("found the Watch capture sources", capturing.isNotEmpty())
        for (text in capturing) {
            assertFalse(text.contains("privateAudio") || text.contains("private_headset") || text.contains("useHeadset") || text.contains("HeadsetPolicy"))
        }
        val bridge = source("$phone/PhoneWatchBridge.kt")
        assertFalse("the Phone's headset media control never starts a Watch microphone", source("$phone/HeadsetMedia.kt").contains("WatchLink") || source("$phone/HeadsetMedia.kt").contains("PhoneWatchBridge"))
        assertTrue(bridge.isNotEmpty())
    }
}
