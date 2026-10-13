package com.rumi.hermesvoice.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Native source-contract substitute for OS admission/provenance which Robolectric cannot establish. */
class R9ManualWiringTest {
    private val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val phone = "phone/src/main/kotlin/com/rumi/hermesvoice/phone/"
    private fun source(name: String) = File(root, phone + name).takeIf { it.isFile }?.readText().orEmpty()
    @Test fun applicationOwnsOneManualControllerAndViewModelObservesIt() {
        assertTrue(source("PhoneApp.kt").contains("val manualCapture"))
        assertTrue(source("PhoneViewModel.kt").contains("app.manualCapture.state.collect"))
        assertFalse(source("PhoneViewModel.kt").contains("WavRecorder(records"))
        assertFalse(source("PhoneViewModel.kt").contains("app.headsetMedia.detach(this)"))
    }
    @Test fun actualMicrophoneTypeAdmissionPrecedesRecorderAndHasExactStop() {
        val service = source("PhoneManualService.kt")
        assertTrue(service.contains("ServiceCompat.startForeground") && service.contains("FOREGROUND_SERVICE_TYPE_MICROPHONE"))
        assertTrue(service.contains("START_NOT_STICKY") && service.contains("EXTRA_GENERATION"))
        assertTrue(service.indexOf("ServiceCompat.startForeground") < service.indexOf("onAdmission(generation, true)"))
        val manifest = File(root, "phone/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains(".PhoneManualService") && manifest.contains("foregroundServiceType=\"microphone\""))
    }
    @Test fun recognizerAndRecordersHaveCommonExclusiveHandoff() {
        assertTrue(source("PhoneManualCapture.kt").contains("app.phoneMic.takeManual"))
        for (file in listOf("PhoneWakeController.kt", "PhoneBackgroundRuntime.kt")) assertTrue(file, source(file).contains("app.phoneMic"))
        assertTrue(source("PhoneViewModel.kt").contains("app.phoneMic.acquire"))
    }
    @Test fun realStorageCapEndsResourcesNotJustReadLoop() {
        assertTrue(source("PhoneAudio.kt").contains("onTerminal"))
        assertTrue(source("PhoneManualCapture.kt").contains("HoldReason.CAPTURE"))
        assertTrue(source("PhoneManualCapture.kt").contains("onTerminal"))
    }
    @Test fun honestSessionNeverCallsCapturingMusicPlayingAndConnectionUsesPrivateCue() {
        val media = source("HeadsetMedia.kt")
        assertFalse(media.contains("if (recording) PlaybackState.STATE_PLAYING"))
        assertTrue(source("PhoneApp.kt").contains("connectionCue"))
        assertTrue(source("ConnectionCueController.kt").contains("CueKind.CONNECTION"))
        val cue = source("RecordingCues.kt")
        assertTrue(cue.contains("CONNECTION(1)"))
        assertTrue(cue.contains("routed == target.id") && cue.contains("made.setMuted(true)"))
    }
}
