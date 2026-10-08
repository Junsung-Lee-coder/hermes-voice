package com.rumi.hermesvoice.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gates for what the JVM cannot run: that the one "Use headset" setting reaches BOTH the playback controller and every
 * Phone capture path, that nothing toggles global audio for playback alone, and the manifest. The behavior itself is pinned in
 * Headset*Test (core) and the Phone Robolectric tests. A text match proves a line exists, never runtime timing.
 */
class HeadsetWiringContractTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(path: String) = File(root, path).readText()

    private val phone = "phone/src/main/kotlin/com/rumi/hermesvoice/phone"

    @Test
    fun `the manifest declares the audio-mode and Bluetooth permissions the microphone link needs  and no new service`() {
        val manifest = source("phone/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.MODIFY_AUDIO_SETTINGS"))
        assertTrue(manifest.contains("android.permission.BLUETOOTH_CONNECT"))
        assertEquals("no headset service was added to the baseline's three", 3, Regex("<service\\s").findAll(manifest).count())
        assertFalse(manifest.contains("BLUETOOTH_SCAN"))
    }

    @Test
    fun `one preference feeds the policy  the playback core and the microphone route`() {
        val app = source("$phone/PhoneApp.kt")
        assertTrue(app.contains("HeadsetPolicy({ settings.useHeadset }, headsetDevices)"))
        assertTrue(app.contains("HeadsetMicRoute(headset, AndroidCommsLink(this))"))
        assertTrue("playback: the core gets the policy and the Phone sink", app.contains("headset = headset, phoneSink = PhoneSpeakerSink(this, headsetDevices)"))
        assertTrue("one lifecycle object reports the private state to the core and the Watch", app.contains("PrivateAudioHub(settings, headset"))
        assertTrue("the Watch's receipt reaches it", source("$phone/PhoneWatchBridge.kt").contains("WatchLinkPaths.PRIVATE_AUDIO ->") &&
            source("$phone/PhoneWatchBridge.kt").contains("privateAudio.onReceipt("))
    }

    @Test
    fun `the toggle writes the Phone setting only  and is a Phone-only row`() {
        val vm = source("$phone/PhoneViewModel.kt")
        val setter = vm.substringAfter("fun setUseHeadset(on: Boolean) {").substringBefore("\n    }")
        assertTrue(setter.contains("app.settings.useHeadset = on"))
        assertTrue("the private-output state follows the toggle at once", setter.contains("app.privateAudio.refresh()"))
        assertFalse("the Watch snapshot is published by the private-audio object, not built here", setter.contains("WatchSettingsSync") || setter.contains("saveWatchSettings"))
        assertTrue(source("$phone/MainActivity.kt").contains("SwitchRow(\"Use headset\", state.useHeadset, tag = \"use_headset\""))
    }

    @Test
    fun `every Phone capture path prepares a microphone plan from the route and watches its loss`() {
        val vm = source("$phone/PhoneViewModel.kt")
        val runtime = source("$phone/PhoneBackgroundRuntime.kt")
        assertEquals("push-to-talk and hands-free prepare a plan", 2, Regex("app\\.headsetMic\\.prepare \\{").findAll(vm).count())
        assertEquals("both watch for an input loss", 2, Regex("app\\.headsetMic\\.watchLoss\\(plan\\)").findAll(vm).count())
        assertTrue("the post-wake request recording prepares one too", runtime.contains("app.headsetMic.prepare { plan ->"))
        assertTrue(runtime.contains("app.headsetMic.watchLoss(plan) {"))
    }

    @Test
    fun `the recorders ask for the preferred device  read back the routed one  and release the plan on every end`() {
        val audio = source("$phone/PhoneAudio.kt")
        val capture = source("$phone/PhoneCapture.kt")
        for (recorder in listOf(audio, capture)) {
            assertTrue(recorder.contains("setPreferredDevice("))
            assertTrue(recorder.contains("routedDeviceId()"))
            assertTrue(Regex("""\.report\(\w*\.?routedDeviceId\(\)\)""").containsMatchIn(recorder))
            assertTrue(recorder.contains("release()"))
        }
    }

    @Test
    fun `playback alone never touches the audio mode or Bluetooth SCO`() {
        val sink = source("$phone/PhoneAudio.kt").substringAfter("class PhoneSpeakerSink")
        for (forbidden in listOf("startBluetoothSco", "setCommunicationDevice", "MODE_IN_COMMUNICATION", "audio.mode", "setMode(", "isSpeakerphoneOn", "setSpeakerphoneOn", "setStreamVolume", "adjustVolume")) {
            assertFalse(forbidden, sink.contains(forbidden))
        }
    }

    @Test
    fun `the communication link is bounded  mode-restoring  and used only by the microphone route`() {
        val android = source("$phone/HeadsetAndroid.kt")
        assertTrue(android.contains("LINK_TIMEOUT_MS = 4_000L") && android.contains("main.postDelayed(timeout, timeoutMs)"))
        assertTrue(android.contains("audio.mode != AudioManager.MODE_NORMAL") && android.contains("savedMode?.let"))
        assertTrue(android.contains("Build.VERSION_CODES.S"))
        for (file in File(root, phone).listFiles { f -> f.extension == "kt" }!!) {
            if (file.name == "HeadsetAndroid.kt") continue
            assertFalse("${file.name} starts SCO itself", file.readText().contains("startBluetoothSco"))
        }
    }

    @Test
    fun `devices are watched by a callback around one playback or capture  never polled`() {
        val android = source("$phone/HeadsetAndroid.kt")
        assertTrue(android.contains("registerAudioDeviceCallback") && android.contains("unregisterAudioDeviceCallback"))
        val sink = source("$phone/PhoneAudio.kt").substringAfter("class PhoneSpeakerSink")
        assertFalse(sink.contains("Thread.sleep") || sink.contains("postDelayed") && sink.contains("outputs()") && sink.contains("while ("))
    }

    @Test
    fun `the wake recognizer is not claimed to be routed - the help and the code say only the recording follows`() {
        val help = com.rumi.hermesvoice.core.settings.SettingsHelp.useHeadset().paragraphs.joinToString(" ")
        assertTrue(help.contains("wake phrase itself is heard by Android's speech recognizer"))
        assertTrue(help.contains("only the recording that follows it uses the headset microphone"))
    }
}
