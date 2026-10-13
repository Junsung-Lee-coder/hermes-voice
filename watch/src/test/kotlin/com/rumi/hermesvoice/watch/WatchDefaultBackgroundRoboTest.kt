package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.speech.SpeechRecognizer
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.ReaderSurface
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchPhase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Collections
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * SCRATCH HARNESS (never packaged). The ACTUAL Watch classes under Robolectric: WatchActivity (Compose UI with the real
 * readerSwipe / holdToTalk gestures as injected touch input, Back dispatcher, permission launchers, lifecycle),
 * WatchApp (haptic adapter, talk state), WatchVoiceRuntime + WakeController (platform SpeechRecognizer listener,
 * AudioRecord capture, background session ports) and WatchVoiceService (onStartCommand / foreground entry / onDestroy).
 *
 * Simulated platform inputs only: SpeechRecognizer callbacks, AudioRecord PCM (synthetic noise), Vibrator (records what
 * the app asked for; no DND/OS policy), permission answers, startForegroundService (Robolectric records the intent; the
 * test plays the platform by delivering it to the real service, late, or never), Wearable Data Layer (mockk). The task
 * move to the back is read from the app's own log, then played as the platform does (pause, stop).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchDefaultBackgroundRoboTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var app: WatchApp
    private var scenario: ActivityScenario<WatchActivity>? = null
    private val toPhone: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val node = mockk<Node>()
        every { node.id } returns PHONE
        every { node.isNearby } returns true
        every { node.displayName } returns "phone"
        val info = mockk<CapabilityInfo>()
        every { info.nodes } returns setOf(node)
        every { info.name } returns WatchLinkPaths.CAPABILITY_PHONE
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } returns Tasks.forResult(info)
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers { toPhone += secondArg<String>(); Tasks.forResult(1) }
        val channels = mockk<ChannelClient>()
        every { channels.openChannel(any(), any()) } answers {
            toPhone += "channel:" + secondArg<String>()
            val c = mockk<ChannelClient.Channel>()
            every { c.path } returns secondArg()
            every { c.nodeId } returns PHONE
            Tasks.forResult(c)
        }
        every { channels.getOutputStream(any()) } answers { Tasks.forResult(ByteArrayOutputStream()) }
        val data = mockk<DataClient>()
        every { data.getDataItems(any<Uri>()) } returns Tasks.forException<DataItemBuffer>(IllegalStateException("harness: no data item"))
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { Wearable.getDataClient(any<Context>()) } returns data
        every { Wearable.getDataClient(any<Activity>()) } returns data
    }

    /** The app's classes live on across tests in Robolectric's sandbox: a service instance of an earlier test is ended. */
    private fun endLeftoverService() {
        WatchVoiceService.running?.finish()
    }

    @Before
    fun setUp() {
        endLeftoverService()
        ShadowLog.stream = System.out
        RecordingVibratorShadow.events.clear()
        app = RuntimeEnvironment.getApplication() as WatchApp
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        stubWearable()
        settings(WakeLocation.WATCH)
    }

    @After
    fun tearDown() {
        scenario?.close()
        endLeftoverService()
        unmockkAll()
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private var revision = 0L

    private fun settings(mode: WakeLocation, haptics: Boolean = true, standby: Boolean = true) {
        revision += 1
        app.applySettings(WatchSettings(mode, "루미", hapticsEnabled = haptics, watchBackgroundWakeEnabled = standby, revision = revision).toJson())
        idle(50)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun log() = ShadowLog.getLogsForTag(TAG).map { it.msg }

    /** The app moved its task to the back (b38 logs the swipe action; the new code logs the move itself). */
    private fun hides() = log().count { it.endsWith("action=background") || it.contains("task moved to back") }

    private fun launch(): ActivityScenario<WatchActivity> = ActivityScenario.launch(WatchActivity::class.java).also { scenario = it; idle(500) }

    private fun swipeRight() { compose.onNodeWithTag("reader_root").performTouchInput { swipeRight() }; idle(200) }

    private fun swipeLeft() { compose.onNodeWithTag("reader_root").performTouchInput { swipeLeft() }; idle(200) }


    /** The platform delivers the pending startForegroundService intent to the REAL service; false if none was asked for. */
    private fun deliverService(): Boolean {
        val intent = shadowOf(app).nextStartedService ?: return false
        Robolectric.buildService(WatchVoiceService::class.java, intent).create().startCommand(0, 1)
        idle(200)
        return true
    }

    /** The platform after a task move to the back: the activity is paused and stopped (it stays alive). */
    private fun platformHides() { scenario!!.moveToState(Lifecycle.State.CREATED); idle(200) }

    /** The user opens the app again (task brought to the front): restart, start, resume. */
    private fun userReopens() {
        // Explicit launcher delivery, not an ambiguous passive lifecycle transition.
        scenario!!.onActivity { deliverNewIntent(it, launcherIntent()) }
        scenario!!.moveToState(Lifecycle.State.RESUMED); idle(500)
    }

    private fun deliverNewIntent(activity: WatchActivity, intent: Intent) {
        // Dispatch the real protected Android callback; no surrogate lifecycle authority.
        WatchActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, intent)
    }
    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setClass(app, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    private fun notificationStop() {
        WatchVoiceService.running!!.onStartCommand(Intent(app, WatchVoiceService::class.java).setAction(ACTION_STOP), 0, 2)
        idle(100)
    }

    /** A same-open pause/resume with no stop (e.g. a system dialog or the notification shade over the app). */
    private fun pauseResume() {
        scenario!!.moveToState(Lifecycle.State.STARTED); idle(50)
        scenario!!.moveToState(Lifecycle.State.RESUMED); idle(300)
    }

    private fun finishing(): Boolean { var f = false; scenario!!.onActivity { f = it.isFinishing }; return f }

    private fun lastPrompt(): ShadowActivity.PermissionsRequest? {
        var out: ShadowActivity.PermissionsRequest? = null
        scenario!!.onActivity { out = shadowOf(it).lastRequestedPermission }
        return out
    }

    private fun answer(request: ShadowActivity.PermissionsRequest, granted: Boolean) {
        request.requestedPermissions.forEach { if (granted) shadowOf(app).grantPermissions(it) else shadowOf(app).denyPermissions(it) }
        scenario!!.onActivity { a ->
            a.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                IntArray(request.requestedPermissions.size) { if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED })
        }
        idle(100)
    }

    /** The system permission dialog over the app (paused while it shows, resumed after), answered. */
    private fun dialog(request: ShadowActivity.PermissionsRequest, granted: Boolean) {
        scenario!!.moveToState(Lifecycle.State.STARTED); idle(50)
        answer(request, granted)
        scenario!!.moveToState(Lifecycle.State.RESUMED); idle(300)
    }

    private fun shownText(fragment: String) = compose.onAllNodesWithText(fragment, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()

    private fun assertNoBackgroundControl() {
        assertTrue("no background control", compose.onAllNodesWithTag("background").fetchSemanticsNodes().isEmpty())
        for (word in listOf("Background", "Tap to start", "Tap to stop", "BG ")) assertFalse("no '$word' shown", shownText(word))
    }

    private fun startNoisePcm() {
        val random = Random(7)
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, isBlocking: Boolean): Int {
                    for (i in 0 until sizeInBytes / 2) {
                        val s = random.nextInt(-40, 40)
                        audioData[offsetInBytes + 2 * i] = (s and 0xff).toByte()
                        audioData[offsetInBytes + 2 * i + 1] = (s shr 8 and 0xff).toByte()
                    }
                    Thread.sleep(5)
                    return sizeInBytes - sizeInBytes % 2
                }
            }
        }
    }

    private fun holdToTalk() { compose.onNodeWithTag("reader_root").performTouchInput { longClick(durationMillis = 1_300) }; idle(300) }

    private var seen = 0
    private fun newVibrations(): List<Pair<Long, Int?>> {
        val all = synchronized(RecordingVibratorShadow.events) { RecordingVibratorShadow.events.toList() }
        return all.drop(seen).also { seen = all.size }
    }

    private val readyPulse = 20L to VibrationAttributes.USAGE_HARDWARE_FEEDBACK

    private fun recognizer(): SpeechRecognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()!!
    private fun ready(r: SpeechRecognizer = recognizer()) { Shadow.extract<ShadowSpeechRecognizer>(r).triggerOnReadyForSpeech(Bundle()); idle(30) }
    private fun results(text: String) {
        Shadow.extract<ShadowSpeechRecognizer>(recognizer()).triggerOnResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) })
        idle(30)
    }

    /** The session is the default one of this open (b38 had none: there the test's own explicit start stands in). */
    private fun sessionOfThisOpen() {
        if (shadowOf(app).nextStartedService == null && !app.voice.backgroundStatus.value.running) app.voice.coordinator.start()
        idle(50)
    }

    // ── default background on open, no control in the app ─────────────────────────────────────

    @Test fun T01() { // opening the app starts background operation by itself; no background status or control on screen
        launch()
        assertTrue("asked the platform for the service on open, without a tap", deliverService())
        val status = app.voice.backgroundStatus.value
        assertTrue("$status", status.running && status.microphone && app.voice.presence.armed)
        assertNoBackgroundControl()
    }

    @Test fun T02() { // opened on a selected chat: same default start; chat and list show no background status/control
        app.seedReaderForQa(6, 6)
        launch()
        assertEquals(ReaderSurface.CHAT, app.reader.value.surface)
        assertTrue(deliverService())
        assertTrue(app.voice.backgroundStatus.value.running)
        assertNoBackgroundControl()
        swipeLeft()
        assertEquals(ReaderSurface.SESSIONS, app.reader.value.surface)
        assertEquals("left swipe is navigation only", 0, hides())
        assertNoBackgroundControl()
    }

    @Test fun T03() { // right swipe on home: task to the back, never finish or stop; the running session is reused
        launch()
        deliverService()
        val generation = app.voice.background.generation
        swipeRight()
        assertEquals(1, hides())
        assertFalse("never finish", finishing())
        assertTrue(app.voice.backgroundStatus.value.running)
        assertEquals(generation, app.voice.background.generation)
        assertNull("no second start", shadowOf(app).nextStartedService)
    }

    @Test fun T04() { // right swipe before the platform delivered the service: still hidden at once; truthful afterwards
        launch()
        assertNotNull("the open asked for the service", shadowOf(app).peekNextStartedService())
        swipeRight()
        assertEquals("not gated on the service", 1, hides())
        platformHides()
        assertTrue(deliverService())
        idle(500)
        val status = app.voice.backgroundStatus.value
        assertTrue("$status", status.running && !status.microphone && !app.voice.presence.armed)
    }

    @Test fun T05() { // right swipe from the sessions list and from a chat: hidden, selection kept
        app.seedReaderForQa(6, 6)
        launch()
        deliverService()
        val selected = app.reader.value.selectedSessionId
        swipeRight()
        assertEquals(1, hides())
        platformHides(); userReopens()
        swipeLeft()
        assertEquals(ReaderSurface.SESSIONS, app.reader.value.surface)
        swipeRight()
        assertEquals(2, hides())
        assertEquals(selected, app.reader.value.selectedSessionId)
        assertFalse(finishing())
    }

    @Test fun T06() { // microphone denied: asked once per open, never again by a resume or a swipe; still hides
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        launch()
        val first = lastPrompt()
        assertNotNull(first)
        assertTrue(first!!.requestedPermissions.contains(Manifest.permission.RECORD_AUDIO))
        dialog(first, granted = false)
        val afterResume = lastPrompt()
        assertTrue("no second microphone prompt in the same open",
            afterResume === first || afterResume?.requestedPermissions?.contains(Manifest.permission.RECORD_AUDIO) != true)
        val beforeSwipe = lastPrompt()
        swipeRight()
        assertSame("the swipe asks nothing", beforeSwipe, lastPrompt())
        assertEquals(1, hides())
        deliverService()
        assertFalse("never listening without the permission", app.voice.backgroundStatus.value.microphone)
    }

    @Test fun T07() { // notifications not allowed (Android 13+): asked once at the open; denied -> replies only, truthful
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        launch()
        val prompt = lastPrompt()
        assertTrue("asked for notifications at the open: ${prompt?.requestedPermissions?.toList()}",
            prompt?.requestedPermissions?.contains(Manifest.permission.POST_NOTIFICATIONS) == true)
        dialog(prompt!!, granted = false)
        assertSame("asked once", prompt, lastPrompt())
        deliverService()
        assertTrue(app.voice.backgroundStatus.value.running)
        assertFalse(app.voice.backgroundStatus.value.microphone)
    }

    @Test fun T08() { // Back moves the task to the back like the right swipe; it never finishes the activity
        launch()
        deliverService()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        idle(200)
        assertFalse("Back never finishes", finishing())
        assertEquals(1, hides())
        assertTrue(app.voice.backgroundStatus.value.running)
    }

    @Test fun T09() { // the notification's Stop holds through same-open resumes; only a real reopen starts it again
        launch()
        deliverService()
        WatchVoiceService.running!!.onStartCommand(Intent(app, WatchVoiceService::class.java).setAction(ACTION_STOP), 0, 2)
        idle(200)
        assertFalse(app.voice.backgroundStatus.value.running)
        pauseResume()
        swipeRight()
        assertNull("no restart by a resume or a swipe of the same open", shadowOf(app).nextStartedService)
        platformHides()
        userReopens()
        assertTrue("the next real open starts it again", deliverService())
        assertTrue(app.voice.backgroundStatus.value.running)
    }

    @Test fun T10() { // a service the system ended is not resurrected inside the open; the next real open starts it
        launch()
        deliverService()
        WatchVoiceService.running!!.onDestroy()
        idle(200)
        assertFalse(app.voice.backgroundStatus.value.running)
        pauseResume()
        swipeRight()
        assertNull(shadowOf(app).nextStartedService)
        platformHides()
        userReopens()
        assertTrue(deliverService())
    }

    @Test fun T11() { // activity recreation: no second start, same session
        launch()
        deliverService()
        val generation = app.voice.background.generation
        scenario!!.recreate()
        idle(500)
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(generation, app.voice.background.generation)
        assertTrue(app.voice.backgroundStatus.value.running)
    }

    @Test fun T12() { // push-to-talk under the admitted default session: the recording goes on with the app hidden
        startNoisePcm()
        launch()
        deliverService()
        holdToTalk()
        assertTrue(app.voice.capturing)
        swipeRight()
        platformHides()
        idle(500)
        assertTrue("neither sent nor dropped by the swipe", app.voice.capturing && app.talk.value.phase == WatchPhase.RECORDING)
    }

    @Test fun T13() { // recording with the Watch wake off (replies-only session): hiding cancels it unsent, as before
        // Kept standby=false on purpose: this case IS the replies-only session (no armed microphone), whose hide cancels the recording.
        // Foreground exclusion is covered separately (standby ON, location excluded) by WatchForegroundSeparationRoboTest.
        settings(WakeLocation.PHONE, standby = false)
        startNoisePcm()
        launch()
        deliverService()
        holdToTalk()
        assertTrue(app.voice.capturing)
        swipeRight()
        assertEquals(1, hides())
        platformHides()
        idle(500)
        assertFalse(app.voice.capturing)
        assertTrue("nothing uploaded", toPhone.none { it.startsWith("channel:") })
    }

    // ── haptics: the ready pulse only ───────────────────────────────────────────────────────

    @Test fun T14() { // one 20 ms ready pulse when listening starts; none on window rollovers hidden; none on a match
        launch()
        deliverService()
        sessionOfThisOpen()
        newVibrations()
        ready()
        assertEquals(listOf(readyPulse), newVibrations())
        platformHides()
        val off = app.getSystemService(PowerManager::class.java)
        shadowOf(off).setIsInteractive(false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); idle(100)
        repeat(3) {
            idle(31_000) // the background window expires and the next one opens
            ready()
        }
        assertTrue("no more ready pulses within the armed session", newVibrations().none { it == readyPulse })
        results("루미 불 꺼")
        idle(200)
        assertTrue("no pulse on the match itself (only the existing recording cues)", newVibrations().none { it.first == 20L })
    }

    @Test fun T15() { // haptics off by the Phone's setting: no ready pulse
        settings(WakeLocation.WATCH, haptics = false)
        launch()
        deliverService()
        ready()
        assertEquals(emptyList<Pair<Long, Int?>>(), newVibrations())
    }

    @Test fun T16() { // a late ready callback of a recognizer released by Stop never pulses
        launch()
        deliverService()
        sessionOfThisOpen()
        newVibrations()
        val old = recognizer()
        app.voice.stopBackground()
        platformHides()
        idle(200)
        ready(old)
        assertEquals(emptyList<Pair<Long, Int?>>(), newVibrations())
    }

    @Test fun T17() { // vertical drag and long press keep their meaning; neither hides
        startNoisePcm()
        app.seedReaderForQa(20, 20)
        launch()
        deliverService()
        compose.onNodeWithTag("reader_root").performTouchInput { swipeUp() }
        idle(500)
        assertEquals(0, hides())
        holdToTalk()
        assertTrue(app.voice.capturing)
        assertEquals(0, hides())
    }


    // FRESH INDEPENDENT adversarial tests; real entrypoints, shadow platform.
    @Test fun I18_StopThenScreenOffOnIsNotUserOpen() {
        launch(); assertTrue(deliverService())
        WatchVoiceService.running!!.onStartCommand(Intent(app, WatchVoiceService::class.java).setAction(ACTION_STOP), 0, 2)
        idle(100); assertFalse(app.voice.backgroundStatus.value.running)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        scenario!!.moveToState(Lifecycle.State.CREATED); idle(100)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        // Restores same task after OS screen-off; no launch, new intent or app tap.
        scenario!!.moveToState(Lifecycle.State.RESUMED); idle(300)
        assertNull("screen-on/lifecycle restart MUST NOT revoke Stop", shadowOf(app).nextStartedService)
    }

    @Test fun I19_FirstReadyAfterHidePulsesOnce() {
        launch(); assertTrue(deliverService()); newVibrations()
        platformHides(); ready()
        assertEquals(listOf(readyPulse), newVibrations())
        ready(); assertTrue(newVibrations().isEmpty())
    }

    @Test fun I20_VisibleNotificationStopCancelsActiveCapture() {
        startNoisePcm(); launch(); assertTrue(deliverService()); holdToTalk()
        assertTrue(app.voice.capturing)
        WatchVoiceService.running!!.onStartCommand(Intent(app, WatchVoiceService::class.java).setAction(ACTION_STOP), 0, 2)
        idle(100)
        assertFalse("system-facing Stop MUST cancel active capture even when Activity visible", app.voice.capturing)
        assertTrue("Stop never uploads capture", toPhone.none { it.startsWith("channel:") })
    }

    @Test fun I21_PermissionAnswerAfterStoppedDialogDoesNotRestart() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        launch(); val prompt = lastPrompt()!!; assertTrue(deliverService())
        scenario!!.moveToState(Lifecycle.State.CREATED); idle(100)
        WatchVoiceService.running!!.onStartCommand(Intent(app, WatchVoiceService::class.java).setAction(ACTION_STOP), 0, 2)
        answer(prompt, granted = true)
        scenario!!.moveToState(Lifecycle.State.RESUMED); idle(300)
        assertNull("answering stopped dialog is not a new user open", shadowOf(app).nextStartedService)
    }

    @Test fun I22_PendingServiceThenHideDenyRaceNeverArms() {
        launch(); val staleVisit=app.voice.coordinator.visit
        swipeRight(); platformHides()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        app.voice.onPermissionResult(); app.voice.onSettingsPulled(staleVisit)
        assertTrue(deliverService())
        assertFalse(app.voice.backgroundStatus.value.microphone)
        assertFalse(app.voice.presence.armed)
        assertFalse(finishing())
    }

    @Test fun I23_StopRecreateNeverStarts() {
        launch(); assertTrue(deliverService())
        WatchVoiceService.running!!.onStartCommand(Intent(app, WatchVoiceService::class.java).setAction(ACTION_STOP), 0, 2)
        idle(100); scenario!!.recreate(); idle(300)
        assertNull("recreation does not revoke Stop", shadowOf(app).nextStartedService)
    }


    // b40 repair tests. All platform/transport stimuli remain host shadows.
    @Test fun E24_ExplicitLauncherNewIntentReleasesStopOnlyWhenVisible() {
        launch(); assertTrue(deliverService()); notificationStop(); platformHides()
        scenario!!.onActivity { deliverNewIntent(it, launcherIntent()) }
        assertNull("hidden launch delivery alone cannot start", shadowOf(app).nextStartedService)
        scenario!!.moveToState(Lifecycle.State.RESUMED); idle(300)
        assertTrue("actual launcher entry restores default session", deliverService())
        assertTrue(app.voice.presence.armed)
    }

    @Test fun E25_PassiveRestartAfterDenialDoesNotResetPromptAttempt() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        launch(); val prompt = lastPrompt()!!; dialog(prompt, false)
        platformHides(); scenario!!.moveToState(Lifecycle.State.RESUMED); idle(300)
        assertSame("passive restart cannot re-ask a refused permission", prompt, lastPrompt())
    }

    @Test fun E26_VisibleStopRejectsStaleReadySettingsPermissionAndIdle() {
        launch(); assertTrue(deliverService()); val old = recognizer(); val visit = app.voice.coordinator.visit
        notificationStop(); newVibrations()
        app.voice.onPermissionResult(); app.voice.onSettingsPulled(visit)
        app.voice.coordinator.onIdle(); app.voice.coordinator.onRearmDue()
        pauseResume(); idle(32_000); ready(old)
        assertFalse("no old or fresh automatic wake after Stop", app.voice.wakeListening.value)
        assertTrue("no READY after Stop", newVibrations().none { it.first == 20L })
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(app.holds.held().isEmpty())
    }

    @Test fun E27_VisibleStopCancelsPendingPttHandoffButLaterExplicitPttAllowed() {
        startNoisePcm(); launch(); assertTrue(deliverService())
        assertTrue(app.voice.wakeListening.value)
        app.voice.onTalkPressed() // pending recognizer-to-PTT microphone handoff
        notificationStop(); idle(1000)
        assertFalse("old pending PTT never starts", app.voice.capturing)
        assertTrue(toPhone.none { it.startsWith("channel:") })
        app.voice.onTalkPressed(); idle(300)
        assertTrue("a subsequent explicit PTT remains usable", app.voice.capturing)
        app.voice.stopBackground(); idle(100)
        assertFalse(app.voice.capturing)
    }

    @Test fun E28_HiddenStopCancelsCaptureAndStaleCaptureCallbacks() {
        startNoisePcm(); launch(); assertTrue(deliverService()); holdToTalk()
        val oldId = app.talk.value.turnId!!
        swipeRight(); platformHides(); assertTrue(app.voice.capturing)
        notificationStop()
        val f = app.voice.javaClass.getDeclaredField("captures\$delegate").apply { isAccessible = true }
        val captures = (f.get(app.voice) as Lazy<*>).value as com.rumi.hermesvoice.core.watchlink.CaptureCoordinator
        captures.onLive(oldId); captures.onCalibrated(oldId)
        captures.stop(oldId, com.rumi.hermesvoice.core.watchlink.CaptureStop.TAP_SEND)
        idle(1000)
        assertFalse(app.voice.capturing)
        assertTrue(toPhone.none { it.startsWith("channel:") })
        assertTrue(app.holds.held().isEmpty())
    }

    @Test fun E29_VisibleStopCancelsPendingRecognizedUploadAndTransitClaim() {
        launch(); assertTrue(deliverService()); app.qaUploadDelayMs = 60_000
        val id = app.newTurn(com.rumi.hermesvoice.core.watchlink.TurnTrigger.WAKE_PHRASE)!!
        app.uploadRecognized(id, "synthetic request", "repair-transit-claim"); idle(100)
        notificationStop(); idle(100_000)
        val f = app.javaClass.getDeclaredField("transit").apply { isAccessible = true }
        assertFalse("claim renewal authority ended", (f.get(app) as com.rumi.hermesvoice.core.wake.WakeClaimTransit).active)
        assertTrue("pending upload withdrawn", toPhone.none { it.startsWith("channel:") })
        assertTrue(app.holds.held().isEmpty())
    }

    @Test fun E30_StopCancelsHeldWakeClaimAndLateVerdict() {
        settings(WakeLocation.BOTH); startNoisePcm(); launch(); assertTrue(deliverService())
        results("루미"); idle(100)
        assertTrue("wake episode waits for claim", app.voice.wakeEpisodePending)
        notificationStop(); app.voice.wake.wake.onClaimTimer(); idle(1000)
        assertFalse(app.voice.wakeEpisodePending); assertFalse(app.voice.capturing)
        assertTrue("claim released", log().any { it.contains("wake claim RELEASE") && it.contains("sent=true") })
        assertTrue(toPhone.none { it.startsWith("channel:") })
        assertTrue(app.holds.held().isEmpty())
    }

    @Test fun E31_RestoredActivityDoesNotReleaseStopEvenWithLauncherIntent() {
        launch(); assertTrue(deliverService()); notificationStop()
        scenario!!.recreate(); idle(500)
        assertFalse(app.voice.wakeListening.value)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun E32_HistoryIntentAndNonLaunchIntentCannotReleaseStop() {
        launch(); assertTrue(deliverService()); notificationStop(); platformHides()
        scenario!!.onActivity { deliverNewIntent(it, launcherIntent().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)) }
        scenario!!.moveToState(Lifecycle.State.RESUMED); idle(300)
        scenario!!.onActivity { activity ->
            val scenarioIdentity = activity.intent
            deliverNewIntent(activity, Intent(app, WatchActivity::class.java).setAction("passive.test"))
            // ActivityScenario's host monitor matches lifecycle events by the original Intent.
            // Restore only that fixture identity, WITHOUT a new onNewIntent/user-open callback.
            // Production's openPending result remains untouched for the following real resume.
            activity.intent = scenarioIdentity
        }
        pauseResume()
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(app.voice.wakeListening.value)
    }

    @Test fun E33_ExplicitLauncherReopenResetsRefusedPermissionAttempt() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        launch(); val old = lastPrompt()!!; dialog(old, false)
        platformHides(); userReopens()
        val fresh = lastPrompt()
        assertNotNull(fresh); assertTrue("new user open may ask again", old !== fresh)
        assertTrue(fresh!!.requestedPermissions.contains(Manifest.permission.RECORD_AUDIO))
    }


    @Test fun E34_VisibleStopCancelsPlaybackAndRejectsOldLaterReply() {
        launch(); assertTrue(deliverService())
        // Seed a prepared platform player; real Stop/ACK/tombstone path, no decoder/device assertion.
        val request = com.rumi.hermesvoice.core.watchlink.PlayRequest("old-play-turn", 0, "final", "audio/wav", byteArrayOf(1), later = true)
        val player = mockk<android.media.MediaPlayer>(relaxed = true)
        fun field(name: String, value: Any?) { app.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(app, value) }
        field("player", player); field("playing", request); field("playingNode", PHONE)
        app.holds.acquire(com.rumi.hermesvoice.core.background.HoldReason.PLAYBACK)
        notificationStop()
        io.mockk.verify(exactly = 1) { player.stop() }; io.mockk.verify(exactly = 1) { player.release() }
        assertNull(app.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(app))
        app.play(request, PHONE); idle(100)
        assertNull("old follow playback cannot return", app.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(app))
        assertTrue(app.holds.held().isEmpty())
    }

    @Test fun E35_StopCancelsAcceptedWakeHandoffBeforeRecorder() {
        startNoisePcm(); launch(); assertTrue(deliverService())
        results("루미"); notificationStop(); idle(1000)
        assertFalse(app.voice.wakeEpisodePending); assertFalse(app.voice.capturing)
        assertTrue(toPhone.none { it.startsWith("channel:") })
        assertTrue(app.holds.held().isEmpty())
    }

    @Test fun E36_StopRejectsServiceAdmissionAndSettingsOfOldGeneration() {
        launch(); val generation=app.voice.background.generation; val visit=app.voice.coordinator.visit
        // Stop before OS delivery, then deliver exactly that pending request.
        app.voice.stopBackground(); assertTrue(deliverService())
        app.voice.onServiceEntered(generation, true); app.voice.onSettingsPulled(visit); idle(100)
        assertFalse(app.voice.backgroundStatus.value.running); assertFalse(app.voice.presence.armed)
        assertFalse(app.voice.wakeListening.value); assertTrue(app.holds.held().isEmpty())
    }

    @Test fun E37_FreshNonLauncherActivityCannotRevokeExistingStop() {
        launch(); assertTrue(deliverService()); notificationStop(); scenario!!.close(); scenario=null
        scenario=ActivityScenario.launch(Intent(app, WatchActivity::class.java).setAction("passive.test"))
        idle(300)
        assertNull("fresh generic Activity creation is not user-launch authority", shadowOf(app).nextStartedService)
        assertFalse(app.voice.wakeListening.value)
    }

    @Test fun R38_RepeatedStopsNeverReauthorizeOldTurnPlayback() {
        launch(); assertTrue(deliverService())
        val oldId = app.newTurn(com.rumi.hermesvoice.core.watchlink.TurnTrigger.PUSH_TO_TALK)!!
        notificationStop()
        io.mockk.mockkConstructor(android.media.MediaPlayer::class)
        every { anyConstructed<android.media.MediaPlayer>().setAudioAttributes(any()) } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().setWakeMode(any(), any()) } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().setOnCompletionListener(any()) } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().setOnErrorListener(any()) } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().setOnPreparedListener(any()) } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().setDataSource(any<String>()) } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().prepareAsync() } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().stop() } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().release() } returns Unit
        val stale = com.rumi.hermesvoice.core.watchlink.PlayRequest(oldId, 0, "final", "audio/wav", byteArrayOf(1), later = true)
        app.play(stale, PHONE); idle(100)
        assertNull("positive control: first Stop rejects the old identity before cap overflow",
            app.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(app))
        repeat(16) {
            platformHides(); userReopens(); assertTrue(deliverService())
            assertNotNull(app.newTurn(com.rumi.hermesvoice.core.watchlink.TurnTrigger.PUSH_TO_TALK))
            notificationStop()
        }
        app.play(stale, PHONE); idle(100)
        assertNull("later reply for explicitly stopped old turn must stay rejected after 17 actual notification Stops",
            app.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(app))
    }

    @Test fun R39_StoppedOldReplyCannotCancelNewCurrentPlayback() {
        launch(); assertTrue(deliverService())
        val oldId = app.newTurn(com.rumi.hermesvoice.core.watchlink.TurnTrigger.PUSH_TO_TALK)!!
        notificationStop()
        platformHides(); userReopens(); assertTrue(deliverService())
        val newId = app.newTurn(com.rumi.hermesvoice.core.watchlink.TurnTrigger.PUSH_TO_TALK)!!
        val current = com.rumi.hermesvoice.core.watchlink.PlayRequest(newId, 0, "final", "audio/wav", byteArrayOf(1), later = true)
        val player = mockk<android.media.MediaPlayer>(relaxed = true)
        fun field(name: String, value: Any?) { app.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(app, value) }
        field("player", player); field("playing", current); field("playingNode", PHONE)
        app.holds.acquire(com.rumi.hermesvoice.core.background.HoldReason.PLAYBACK)
        app.onPhoneState(com.rumi.hermesvoice.core.watchlink.TurnStateMessage(newId, "responding", "", false))
        app.play(com.rumi.hermesvoice.core.watchlink.PlayRequest(oldId, 0, "final", "audio/wav", byteArrayOf(1), later = true), PHONE)
        idle(100)
        io.mockk.verify(exactly = 0) { player.stop() }
        io.mockk.verify(exactly = 0) { player.release() }
        assertSame("rejected old stopped identity must not replace a newer speaker", player,
            app.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(app))
    }

    // b41 boundary tests: real admission/Stop/state/callback entrypoints, mocked decoder/transport only.
    private fun appField(name: String): Any? = app.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(app)
    private fun setAppField(name: String, value: Any?) { app.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(app, value) }
    private fun playbackDeadlines(): Map<Any?, Any?> {
        val f = app.holds.javaClass.getDeclaredField("until").apply { isAccessible = true }
        return (f.get(app.holds) as Map<*, *>).toMap()
    }
    private val prepared = io.mockk.slot<android.media.MediaPlayer.OnPreparedListener>()
    private val completed = io.mockk.slot<android.media.MediaPlayer.OnCompletionListener>()
    private val errored = io.mockk.slot<android.media.MediaPlayer.OnErrorListener>()
    private fun mockPlayers() {
        io.mockk.mockkConstructor(android.media.MediaPlayer::class)
        every { anyConstructed<android.media.MediaPlayer>().setAudioAttributes(any()) } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().setWakeMode(any(), any()) } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().setOnCompletionListener(capture(completed)) } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().setOnErrorListener(capture(errored)) } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().setOnPreparedListener(capture(prepared)) } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().setDataSource(any<String>()) } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().prepareAsync() } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().start() } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().stop() } returns Unit
        every { anyConstructed<android.media.MediaPlayer>().release() } returns Unit
        shadowOf(app.getSystemService(android.media.AudioManager::class.java))
            .setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }
    private fun request(id: String, later: Boolean = true, seq: Int = 0) =
        com.rumi.hermesvoice.core.watchlink.PlayRequest(id, seq, "final", "audio/wav", byteArrayOf(1), later = later)
    private fun freshLocalRequest(): com.rumi.hermesvoice.core.watchlink.PlayRequest {
        val id = app.newTurn(com.rumi.hermesvoice.core.watchlink.TurnTrigger.PUSH_TO_TALK)!!
        app.onPhoneState(com.rumi.hermesvoice.core.watchlink.TurnStateMessage(id, "responding", "", false))
        return request(id)
    }
    private fun stopAndReopen(): String {
        val id = app.newTurn(com.rumi.hermesvoice.core.watchlink.TurnTrigger.PUSH_TO_TALK)!!
        notificationStop(); platformHides(); userReopens(); assertTrue(deliverService())
        return id
    }
    private fun seedCurrent(id: String): android.media.MediaPlayer {
        val mp = mockk<android.media.MediaPlayer>(relaxed = true)
        setAppField("player", mp); setAppField("playing", request(id)); setAppField("playingNode", PHONE)
        app.holds.acquire(com.rumi.hermesvoice.core.background.HoldReason.PLAYBACK)
        return mp
    }
    private fun interceptAcks(task: com.google.android.gms.tasks.Task<Int>? = null): MutableList<com.rumi.hermesvoice.core.watchlink.PlayedAck> {
        val acks = mutableListOf<com.rumi.hermesvoice.core.watchlink.PlayedAck>()
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            if (secondArg<String>() == WatchLinkPaths.PLAYED)
                acks += com.rumi.hermesvoice.core.watchlink.PlayedAck.decode(thirdArg<ByteArray>())!!
            task ?: Tasks.forResult(1)
        }
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        return acks
    }

    @Test fun R40_StoppedRefusalDoesNotRetimestampPlayerHoldOrFocus() {
        launch(); assertTrue(deliverService()); val old = stopAndReopen()
        val live = freshLocalRequest(); val mp = seedCurrent(live.turnId)
        // Near expiry makes ACK acquire observable even with WakeHolds' no-shortening rule.
        val f = app.holds.javaClass.getDeclaredField("until").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val deadlines = f.get(app.holds) as MutableMap<com.rumi.hermesvoice.core.background.HoldReason, Long>
        deadlines[com.rumi.hermesvoice.core.background.HoldReason.PLAYBACK] = android.os.SystemClock.elapsedRealtime() + 5000
        val before = playbackDeadlines(); val talk = app.talk.value; val ended = app.lastPlaybackEndedAtMs
        val audio = shadowOf(app.getSystemService(android.media.AudioManager::class.java))
        val abandoned = audio.lastAbandonedAudioFocusRequest
        val deferred = com.google.android.gms.tasks.TaskCompletionSource<Int>(); val acks = interceptAcks(deferred.task)
        app.play(request(old), PHONE); idle(10)
        assertEquals(before, playbackDeadlines()); assertEquals(talk, app.talk.value)
        assertEquals(ended, app.lastPlaybackEndedAtMs); assertSame(abandoned, audio.lastAbandonedAudioFocusRequest)
        assertSame(mp, appField("player")); assertEquals(1, acks.size)
        assertFalse(acks.single().ok); assertEquals("stopped on the watch", acks.single().error)
        deferred.setResult(1); idle(100)
        assertEquals(before, playbackDeadlines()); assertSame(mp, appField("player"))
    }

    @Test fun R41_NonLaterStoppedReplyAlsoCannotMutateLivePlayer() {
        launch(); assertTrue(deliverService()); val old = stopAndReopen()
        val live = freshLocalRequest(); val mp = seedCurrent(live.turnId); val before = playbackDeadlines()
        val acks = interceptAcks(); app.play(request(old, later = false), PHONE); idle(100)
        io.mockk.verify(exactly = 0) { mp.stop() }; io.mockk.verify(exactly = 0) { mp.release() }
        assertSame(mp, appField("player")); assertEquals(before, playbackDeadlines())
        assertEquals("stopped on the watch", acks.single().error); assertFalse(acks.single().ok)
    }

    @Test fun R42_BusyRefusalDoesNotTouchIndependentSpeakerOrHoldOnAckFailure() {
        launch(); assertTrue(deliverService())
        assertNotNull(app.newTurn(com.rumi.hermesvoice.core.watchlink.TurnTrigger.PUSH_TO_TALK))
        val mp = seedCurrent("authorized-phone-turn"); val before = playbackDeadlines(); val talk = app.talk.value
        val failed = com.google.android.gms.tasks.TaskCompletionSource<Int>(); val acks = interceptAcks(failed.task)
        app.play(request("another-phone-turn"), PHONE); idle(20)
        assertEquals(before, playbackDeadlines()); assertEquals(talk, app.talk.value)
        failed.setException(IllegalStateException("synthetic ACK transport failure")); idle(100)
        io.mockk.verify(exactly = 0) { mp.stop() }; io.mockk.verify(exactly = 0) { mp.release() }
        assertEquals(before, playbackDeadlines()); assertSame(mp, appField("player"))
        assertEquals(com.rumi.hermesvoice.core.watchlink.PlayedAck.BUSY_RECORDING, acks.single().error)
        assertFalse(acks.single().ok)
    }

    @Test fun R43_OlderRefusalAckCannotReleaseNewPlaybackAfterCompletion() {
        launch(); assertTrue(deliverService()); val old = stopAndReopen(); mockPlayers()
        val pending = com.google.android.gms.tasks.TaskCompletionSource<Int>(); val acks = interceptAcks(pending.task)
        app.play(request(old), PHONE); idle(20); assertNull(appField("player"))
        val live = freshLocalRequest(); app.play(live, PHONE)
        val mp = appField("player") as android.media.MediaPlayer; prepared.captured.onPrepared(mp)
        val before = playbackDeadlines(); pending.setResult(1); idle(100)
        assertSame(mp, appField("player")); assertEquals(before, playbackDeadlines())
        assertTrue(com.rumi.hermesvoice.core.background.HoldReason.PLAYBACK in app.holds.held())
        assertEquals(1, acks.size); assertFalse(acks.single().ok)
    }

    @Test fun R44_StopBeforePrepareRejectsAllOldPlayerCallbacks() {
        launch(); assertTrue(deliverService()); mockPlayers(); val acks = interceptAcks()
        val live = freshLocalRequest(); app.play(live, PHONE)
        val mp = appField("player") as android.media.MediaPlayer
        val ready = prepared.captured; val done = completed.captured; val bad = errored.captured
        notificationStop(); ready.onPrepared(mp); done.onCompletion(mp); bad.onError(mp, 1, 2); idle(100)
        io.mockk.verify(exactly = 0) { anyConstructed<android.media.MediaPlayer>().start() }
        assertNull(appField("player")); assertTrue(app.holds.held().isEmpty())
        assertEquals(1, acks.size); assertFalse(acks.single().ok)
        app.play(request(live.turnId, later = false), PHONE); idle(100)
        assertNull(appField("player")); assertFalse(acks.last().ok)
    }

    @Test fun R45_StopAfterPrepareEndsOnceNeverReportsPlayed() {
        launch(); assertTrue(deliverService()); mockPlayers(); val acks = interceptAcks()
        val live = freshLocalRequest(); app.play(live, PHONE)
        val mp = appField("player") as android.media.MediaPlayer
        val done = completed.captured; val bad = errored.captured
        prepared.captured.onPrepared(mp); notificationStop(); done.onCompletion(mp); bad.onError(mp, 3, 4); idle(100)
        io.mockk.verify(exactly = 1) { anyConstructed<android.media.MediaPlayer>().start() }
        assertNull(appField("player")); assertEquals(1, acks.size); assertFalse(acks.single().ok)
        assertTrue(app.holds.held().isEmpty())
    }

    @Test fun R46_ReplacedPlayerCallbacksCannotEndOrAckNewSpeaker() {
        launch(); assertTrue(deliverService()); mockPlayers(); val acks = interceptAcks()
        val first = freshLocalRequest(); app.play(first, PHONE)
        val oldMp = appField("player") as android.media.MediaPlayer
        val oldReady = prepared.captured; val oldDone = completed.captured; val oldBad = errored.captured
        app.play(request("authorized-phone-second"), PHONE)
        val newMp = appField("player") as android.media.MediaPlayer
        val before = playbackDeadlines(); val talk = app.talk.value; val count = acks.size
        oldReady.onPrepared(oldMp); oldDone.onCompletion(oldMp); oldBad.onError(oldMp, 1, 2); idle(100)
        assertSame(newMp, appField("player")); assertEquals(before, playbackDeadlines()); assertEquals(talk, app.talk.value)
        assertEquals(count, acks.size)
    }

    @Test fun R47_CompletionIsOnlySuccessfulAckAndFailureLeavesNoPlayerHold() {
        launch(); assertTrue(deliverService()); mockPlayers(); val acks = interceptAcks()
        app.play(freshLocalRequest(), PHONE); val first = appField("player") as android.media.MediaPlayer
        prepared.captured.onPrepared(first); assertTrue(acks.isEmpty())
        val done = completed.captured; done.onCompletion(first); done.onCompletion(first); idle(100)
        assertEquals(1, acks.size); assertTrue(acks.single().ok); assertTrue(app.holds.held().none { it == com.rumi.hermesvoice.core.background.HoldReason.PLAYBACK })
        app.play(request("authorized-phone-error"), PHONE); val next = appField("player") as android.media.MediaPlayer
        errored.captured.onError(next, 7, 8); idle(100)
        assertEquals(2, acks.size); assertFalse(acks.last().ok); assertNull(appField("player"))
        assertTrue(app.holds.held().none { it == com.rumi.hermesvoice.core.background.HoldReason.PLAYBACK })
    }

    @Test fun R48_FreshActualPttTurnAfterStopAdmitsPreparedPlayback() {
        // Unlike cancellation tests, this positive upload needs real worker reads of usable PCM.
        // Robolectric asks the provider on each read; keep the input cursor outside the returned source object.
        val samplesRead = java.util.concurrent.atomic.AtomicInteger(0)
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, isBlocking: Boolean): Int {
                    for (i in 0 until sizeInBytes / 2) {
                        val frame = (samplesRead.get() + i) / 320
                        val level = if (frame < 10) 100 else if (frame / 5 % 2 == 0) 6000 else 12000
                        val sample = if (i % 32 < 16) level else -level
                        audioData[offsetInBytes + 2 * i] = sample.toByte()
                        audioData[offsetInBytes + 2 * i + 1] = (sample shr 8).toByte()
                    }
                    samplesRead.addAndGet(sizeInBytes / 2)
                    Thread.sleep(5)
                    return sizeInBytes - sizeInBytes % 2
                }
            }
        }
        launch(); assertTrue(deliverService()); notificationStop()
        app.voice.onTalkPressed(); idle(300); assertTrue(app.voice.capturing)
        val id = app.talk.value.turnId!!
        val recorder = app.voice.javaClass.getDeclaredField("recorder").apply { isAccessible = true }.get(app.voice) as WatchCapture
        val deadline = System.nanoTime() + 2_000_000_000L
        while ((recorder.stats()?.pcmBytes ?: 0) < 32_000 && System.nanoTime() < deadline) {
            Thread.sleep(10); idle(10)
        }
        assertTrue("positive PCM bytes, not virtual-looper time alone", (recorder.stats()?.pcmBytes ?: 0) >= 16_000)
        assertTrue("usable synthetic amplitude", (recorder.stats()?.rms ?: 0) >= 5000)
        assertTrue("quiet onset + modulated PCM captured before send: ${recorder.stats()} logs=${log()}", (recorder.stats()?.pcmBytes ?: 0) >= 32_000)
        val loop = WatchCapture::class.java.getDeclaredField("loop").apply { isAccessible = true }.get(recorder) as com.rumi.hermesvoice.core.audio.PcmCaptureLoop
        assertEquals("fixture must pass the unchanged real input gate", com.rumi.hermesvoice.core.audio.AudioInputVerdict.USABLE,
            com.rumi.hermesvoice.core.audio.AudioInputGate.assess(com.rumi.hermesvoice.core.audio.PcmCaptureLoop.wav(loop.pcm())!!, "audio/wav"))
        // End through real PTT and real transport; then Phone admits that exact fresh turn.
        app.voice.onTalkPressed(); idle(300); assertFalse(app.voice.capturing)
        val uploadDeadline = System.nanoTime() + 2_000_000_000L
        while (!toPhone.any { it.startsWith("channel:") } && System.nanoTime() < uploadDeadline) { Thread.sleep(10); idle(10) }
        assertTrue("actual PTT channel handoff missing: stats=${recorder.stats()} talk=${app.talk.value} logs=${log()} transport=$toPhone", toPhone.any { it.startsWith("channel:") })
        app.onPhoneState(com.rumi.hermesvoice.core.watchlink.TurnStateMessage(id, "responding", "", false))
        mockPlayers(); val acks = interceptAcks(); app.play(request(id), PHONE)
        val mp = appField("player") as android.media.MediaPlayer; prepared.captured.onPrepared(mp)
        io.mockk.verify(exactly = 1) { anyConstructed<android.media.MediaPlayer>().start() }
        assertEquals(id, app.talk.value.speakingTurnId); assertTrue(acks.isEmpty())
        completed.captured.onCompletion(mp); idle(100); assertTrue(acks.single().ok)
    }

    @Test fun R49_AuthorizedPhoneOriginAndEarlierUnstoppedLocalTurnsRemainAllowed() {
        launch(); assertTrue(deliverService()); val local = freshLocalRequest()
        mockPlayers(); val acks = interceptAcks()
        app.play(request("phone-origin-not-local"), PHONE)
        var mp = appField("player") as android.media.MediaPlayer; prepared.captured.onPrepared(mp)
        assertEquals("phone-origin-not-local", app.talk.value.speakingTurnId)
        completed.captured.onCompletion(mp); idle(100); assertTrue(acks.single().ok)
        app.play(local, PHONE); mp = appField("player") as android.media.MediaPlayer
        prepared.captured.onPrepared(mp); completed.captured.onCompletion(mp); idle(100)
        assertTrue(acks.last().ok); assertEquals(local.turnId, acks.last().turnId)
    }

    @Test fun R50_MultipleOverflowCyclesRetainAllCancelledLocalIdentities() {
        launch(); assertTrue(deliverService()); val ids = mutableListOf<String>()
        repeat(40) {
            ids += app.newTurn(com.rumi.hermesvoice.core.watchlink.TurnTrigger.PUSH_TO_TALK)!!
            notificationStop(); platformHides(); userReopens(); assertTrue(deliverService())
        }
        mockPlayers(); val acks = interceptAcks()
        ids.forEachIndexed { i, id -> app.play(request(id, later = i % 2 == 0), PHONE); idle(1); assertNull("cancelled $i", appField("player")) }
        idle(100); assertEquals(40, acks.size); assertTrue(acks.all { !it.ok && it.error == "stopped on the watch" })
        val fresh = freshLocalRequest(); app.play(fresh, PHONE); assertNotNull(appField("player"))
    }

    @Test fun R51_StoppedStateAndWrongTurnStopDoNotMutateNewSpeaker() {
        launch(); assertTrue(deliverService()); val old = stopAndReopen()
        val live = freshLocalRequest(); val mp = seedCurrent(live.turnId)
        val talk = app.talk.value; val before = playbackDeadlines()
        app.onPhoneState(com.rumi.hermesvoice.core.watchlink.TurnStateMessage(old, "responding", "stale", false))
        app.onPhoneState(com.rumi.hermesvoice.core.watchlink.TurnStateMessage(old, "done", "stale", true))
        app.stopPlayback("stale phone stop", old); idle(100)
        assertSame(mp, appField("player")); assertEquals(talk, app.talk.value); assertEquals(before, playbackDeadlines())
        io.mockk.verify(exactly = 0) { mp.stop() }; io.mockk.verify(exactly = 0) { mp.release() }
    }

    @Test fun R52_DeferredAckFailureAfterNewStopCannotLoseFuturePlayerHold() {
        launch(); assertTrue(deliverService()); val old = stopAndReopen(); mockPlayers()
        val task = com.google.android.gms.tasks.TaskCompletionSource<Int>(); interceptAcks(task.task)
        app.play(request(old), PHONE); idle(10)
        val fresh = freshLocalRequest(); app.play(fresh, PHONE); notificationStop()
        platformHides(); userReopens(); assertTrue(deliverService())
        val next = freshLocalRequest(); app.play(next, PHONE); val mp = appField("player") as android.media.MediaPlayer
        val before = playbackDeadlines(); task.setException(IllegalStateException("late ACK failure")); idle(100)
        assertSame(mp, appField("player")); assertEquals(before, playbackDeadlines())
    }

    @Test fun R53_StopAlsoRevokesEarlierPendingLocalTurnNotJustLatestTalk() {
        launch(); assertTrue(deliverService()); val earlier = freshLocalRequest(); val latest = freshLocalRequest()
        assertTrue(earlier.turnId != latest.turnId); notificationStop()
        mockPlayers(); val acks = interceptAcks()
        app.play(earlier, PHONE); idle(100)
        assertNull("Stop revokes earlier local follow work too", appField("player"))
        assertEquals("stopped on the watch", acks.single().error)
        app.play(latest, PHONE); idle(100); assertNull(appField("player"))
        assertEquals(2, acks.size); assertTrue(acks.none { it.ok })
    }

    @Test fun R54_RealListenerStateAndStopFramesRespectNewPlayerIdentity() {
        launch(); assertTrue(deliverService()); val old = stopAndReopen(); val live = freshLocalRequest()
        val mp = seedCurrent(live.turnId); val before = playbackDeadlines(); val talk = app.talk.value
        val listener = Robolectric.buildService(WatchListenerService::class.java).create().get()
        for (path in listOf(WatchLinkPaths.STATE, WatchLinkPaths.STOP)) {
            val event = mockk<com.google.android.gms.wearable.MessageEvent>()
            every { event.path } returns path
            every { event.data } returns com.rumi.hermesvoice.core.watchlink.TurnStateMessage(old, "done", "old", true).encode()
            listener.onMessageReceived(event)
        }
        idle(100); assertSame(mp, appField("player")); assertEquals(before, playbackDeadlines()); assertEquals(talk, app.talk.value)
        io.mockk.verify(exactly = 0) { mp.stop() }; listener.onDestroy()
    }

    @Test fun R55_PrepareFailureAckDoesNotClaimSuccessfulPlayback() {
        launch(); assertTrue(deliverService()); mockPlayers(); val acks = interceptAcks()
        every { anyConstructed<android.media.MediaPlayer>().prepareAsync() } throws IllegalStateException("synthetic prepare error")
        app.play(freshLocalRequest(), PHONE); idle(100)
        assertNull(appField("player")); assertEquals(1, acks.size); assertFalse(acks.single().ok)
        assertEquals("IllegalStateException", acks.single().error)
        assertTrue(app.holds.held().none { it == com.rumi.hermesvoice.core.background.HoldReason.PLAYBACK })
    }

    @Test fun R56_FocusDenialRefusesNewAudioWithoutPlayerConstruction() {
        launch(); assertTrue(deliverService()); mockPlayers(); val acks = interceptAcks()
        shadowOf(app.getSystemService(android.media.AudioManager::class.java))
            .setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        app.play(freshLocalRequest(), PHONE); idle(100)
        assertNull(appField("player")); assertEquals("audio focus denied", acks.single().error); assertFalse(acks.single().ok)
        io.mockk.verify(exactly = 0) { anyConstructed<android.media.MediaPlayer>().prepareAsync() }
    }

    @Test fun R57_ActualLateWakeVerdictAfterStopCannotRestartCapture() {
        settings(WakeLocation.BOTH); startNoisePcm(); launch(); assertTrue(deliverService())
        val claims = mutableListOf<com.rumi.hermesvoice.core.wake.WakeClaimMessage>()
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            if (secondArg<String>() == WatchLinkPaths.WAKE_CLAIM)
                claims += com.rumi.hermesvoice.core.wake.WakeClaimMessage.decode(thirdArg<ByteArray>())!!
            Tasks.forResult(1)
        }
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        results("루미"); idle(100); assertTrue(app.voice.wakeEpisodePending)
        val claim = claims.first { it.op == com.rumi.hermesvoice.core.wake.WakeClaimMessage.Op.CLAIM }
        notificationStop()
        app.onWakeVerdict(PHONE, com.rumi.hermesvoice.core.wake.WakeVerdictMessage(claim.claimId,
            com.rumi.hermesvoice.core.wake.ClaimVerdict.GRANTED, epoch = 1).encode())
        idle(1000); assertFalse(app.voice.capturing); assertFalse(app.voice.wakeEpisodePending)
        assertTrue(toPhone.none { it.startsWith("channel:") }); assertTrue(app.holds.held().isEmpty())
    }

    @Test fun R58_TimeoutAndLateAckCompletionCannotMutateLivePlayback() {
        launch(); assertTrue(deliverService()); val old = stopAndReopen(); val live = freshLocalRequest()
        val mp = seedCurrent(live.turnId); val before = playbackDeadlines(); val talk = app.talk.value
        val pending = com.google.android.gms.tasks.TaskCompletionSource<Int>(); val acks = interceptAcks(pending.task)
        app.play(request(old), PHONE); idle(10_100)
        assertSame(mp, appField("player")); assertEquals(before, playbackDeadlines()); assertEquals(talk, app.talk.value)
        assertEquals(1, acks.size); assertFalse(acks.single().ok)
        assertTrue((appField("pendingPlayedAcks") as Set<*>).isEmpty())
        pending.setResult(1); idle(100)
        assertSame(mp, appField("player")); assertEquals(before, playbackDeadlines())
        io.mockk.verify(exactly = 0) { mp.stop() }; io.mockk.verify(exactly = 0) { mp.release() }
    }

    @Test fun R59_ActualDecodedChannelRejectsStoppedAudioWithoutTouchingCurrentPlayer() {
        launch(); assertTrue(deliverService()); val old = stopAndReopen(); val live = freshLocalRequest()
        val mp = seedCurrent(live.turnId); val before = playbackDeadlines(); val talk = app.talk.value
        val acks = interceptAcks(); val stale = request(old)
        val channel = mockk<ChannelClient.Channel>()
        every { channel.path } returns WatchLinkPaths.playPath(old, 0)
        every { channel.nodeId } returns PHONE
        val client = mockk<ChannelClient>()
        every { client.getInputStream(channel) } returns Tasks.forResult<java.io.InputStream>(java.io.ByteArrayInputStream(stale.toFrame().encode()))
        every { client.close(channel) } returns Tasks.forResult<Void>(null)
        every { Wearable.getChannelClient(any<Context>()) } returns client
        val listener = Robolectric.buildService(WatchListenerService::class.java).create().get()
        listener.onChannelOpened(channel)
        val deadline = System.nanoTime() + 2_000_000_000L
        while (acks.isEmpty() && System.nanoTime() < deadline) { Thread.sleep(10); idle(10) }
        assertEquals("actual channel decoded and refusal ACK sent", 1, acks.size)
        assertEquals(old, acks.single().turnId); assertEquals("stopped on the watch", acks.single().error)
        assertSame(mp, appField("player")); assertEquals(before, playbackDeadlines()); assertEquals(talk, app.talk.value)
        io.mockk.verify(exactly = 0) { mp.stop() }; io.mockk.verify(exactly = 0) { mp.release() }
        listener.onDestroy()
    }

    @Test fun R60_OverlappingAckTokensReleaseOnlyTheirIndependentHold() {
        launch(); assertTrue(deliverService())
        assertNotNull(app.newTurn(com.rumi.hermesvoice.core.watchlink.TurnTrigger.PUSH_TO_TALK))
        val mp = seedCurrent("live-phone-speaker"); val before = playbackDeadlines()
        val first = com.google.android.gms.tasks.TaskCompletionSource<Int>()
        val second = com.google.android.gms.tasks.TaskCompletionSource<Int>()
        val messages = mockk<MessageClient>(); var sent = 0
        every { messages.sendMessage(any(), any(), any()) } answers {
            assertEquals(WatchLinkPaths.PLAYED, secondArg<String>())
            assertFalse(com.rumi.hermesvoice.core.watchlink.PlayedAck.decode(thirdArg<ByteArray>())!!.ok)
            if (sent++ == 0) first.task else second.task
        }
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        app.play(request("busy-one"), PHONE); app.play(request("busy-two"), PHONE); idle(10)
        val f = app.javaClass.getDeclaredField("playedAckHolds\$delegate").apply { isAccessible = true }
        val ackHolds = (f.get(app) as Lazy<*>).value as com.rumi.hermesvoice.core.background.WakeHolds
        assertEquals(2, sent); assertEquals(2, (appField("pendingPlayedAcks") as Set<*>).size)
        first.setResult(1); idle(100)
        assertEquals(1, (appField("pendingPlayedAcks") as Set<*>).size)
        assertTrue(com.rumi.hermesvoice.core.background.HoldReason.PLAYBACK in ackHolds.held())
        assertSame(mp, appField("player")); assertEquals(before, playbackDeadlines())
        second.setException(IllegalStateException("synthetic second ACK failure")); idle(100)
        assertTrue((appField("pendingPlayedAcks") as Set<*>).isEmpty()); assertTrue(ackHolds.held().isEmpty())
        assertSame(mp, appField("player")); assertEquals(before, playbackDeadlines())
    }

    @Test fun R61_ChannelOpenedBeforeStopCannotAdmitUnknownPhoneAudioAfterNewSpeaker() {
        launch(); assertTrue(deliverService()); mockPlayers()
        val delayed = request("old-phone-channel-turn")
        val channel = mockk<ChannelClient.Channel>()
        every { channel.path } returns WatchLinkPaths.playPath(delayed.turnId, delayed.sequence)
        every { channel.nodeId } returns PHONE
        val input = com.google.android.gms.tasks.TaskCompletionSource<java.io.InputStream>()
        val client = mockk<ChannelClient>()
        every { client.getInputStream(channel) } returns input.task
        every { client.close(channel) } returns Tasks.forResult<Void>(null)
        every { Wearable.getChannelClient(any<Context>()) } returns client
        val listener = Robolectric.buildService(WatchListenerService::class.java).create().get()
        val acks = interceptAcks()
        listener.onChannelOpened(channel); idle(10)
        io.mockk.verify(exactly = 1) { client.getInputStream(channel) }
        notificationStop(); platformHides(); userReopens(); assertTrue(deliverService())
        val live = freshLocalRequest(); val mp = seedCurrent(live.turnId)
        val before = playbackDeadlines(); val talk = app.talk.value
        input.setResult(java.io.ByteArrayInputStream(delayed.toFrame().encode()))
        val deadline = System.nanoTime() + 2_000_000_000L
        while (acks.isEmpty() && System.nanoTime() < deadline) { Thread.sleep(10); idle(10) }
        assertEquals("pre-Stop channel gets an explicit negative ACK", 1, acks.size)
        assertFalse(acks.single().ok); assertEquals(delayed.turnId, acks.single().turnId)
        assertSame("old received Phone audio cannot replace the post-Stop speaker", mp, appField("player"))
        assertEquals(before, playbackDeadlines()); assertEquals(talk, app.talk.value)
        io.mockk.verify(exactly = 0) { mp.stop() }; io.mockk.verify(exactly = 0) { mp.release() }
        listener.onDestroy()
    }

    @Test fun R62_QueuedFocusLossForOldPlayerCannotStopReplacementSpeaker() {
        launch(); assertTrue(deliverService()); mockPlayers(); interceptAcks()
        app.play(freshLocalRequest(), PHONE)
        val old = appField("player") as android.media.MediaPlayer
        val f = app.javaClass.getDeclaredField("focusRequest").apply { isAccessible = true }
        val focusRequest = f.get(app) as android.media.AudioFocusRequest
        val loss = focusRequest.javaClass.getDeclaredMethod("getOnAudioFocusChangeListener")
            .apply { isAccessible = true }.invoke(focusRequest) as android.media.AudioManager.OnAudioFocusChangeListener
        // Android can report focus off the main thread; the real callback queues app-scope work.
        val worker = Thread { loss.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS) }
        worker.start(); worker.join(2000); assertFalse(worker.isAlive)
        app.play(request("new-phone-focus-owner"), PHONE)
        val current = appField("player") as android.media.MediaPlayer
        assertTrue(old !== current)
        val before = playbackDeadlines(); val talk = app.talk.value
        idle(100)
        assertSame("queued old focus callback cannot end the newer player", current, appField("player"))
        assertEquals(before, playbackDeadlines()); assertEquals(talk, app.talk.value)
    }

    @Test fun R63_DefaultAndAckAdaptersKeepDistinctTagsBoundedTimeoutAndReleaseOwnership() {
        // Use real Android adapters and SDK34 PowerManager shadows: mocking the final framework
        // class causes a JDK class-redefinition failure, not a product ownership failure.
        val reason = com.rumi.hermesvoice.core.background.HoldReason.PLAYBACK
        val playerHolds = com.rumi.hermesvoice.core.background.WakeHolds(AndroidWakeLocks(app), android.os.SystemClock::elapsedRealtime)
        val ackHolds = com.rumi.hermesvoice.core.background.WakeHolds(AndroidWakeLocks(app, "HermesVoice:ack"), android.os.SystemClock::elapsedRealtime)
        val before = android.os.SystemClock.elapsedRealtime()
        playerHolds.acquire(reason, Long.MAX_VALUE)
        val playerLock = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()!!
        val playerShadow = shadowOf(playerLock)
        ackHolds.acquire(reason, 10_000L)
        val ackLock = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()!!
        val ackShadow = shadowOf(ackLock)
        assertTrue(playerLock !== ackLock)
        assertEquals("HermesVoice:playback", playerShadow.tag)
        assertEquals("HermesVoice:ack:playback", ackShadow.tag)
        assertFalse(playerShadow.isReferenceCounted)
        assertFalse(ackShadow.isReferenceCounted)
        // Shadow's native bytecode was read back first: acquire(timeout) stores elapsedRealtime+timeout.
        fun deadline(shadow: org.robolectric.shadows.ShadowPowerManager.ShadowWakeLock): Long {
            val field = shadow.javaClass.getDeclaredField("timeoutTimestampList").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val times = field.get(shadow) as List<java.util.Optional<Long>>
            return times.single().get()
        }
        assertEquals(before + reason.maxMs, deadline(playerShadow))
        assertEquals(before + 10_000L, deadline(ackShadow))
        assertTrue(playerLock.isHeld); assertTrue(ackLock.isHeld)
        ackHolds.release(reason)
        assertFalse(ackLock.isHeld); assertTrue(playerLock.isHeld)
        assertEquals(setOf(reason), playerHolds.held())
        ackHolds.acquire(reason, 10_000L)
        idle(10_001)
        assertFalse("platform ACK timeout is bounded", ackLock.isHeld)
        assertTrue("ACK expiry cannot expire player", playerLock.isHeld)
        playerHolds.release(reason)
        assertFalse(playerLock.isHeld)
    }

    // b43: real app admission/completion/notification Stop, with decoder/transport boundaries mocked.
    private fun focusShadow() = shadowOf(app.getSystemService(android.media.AudioManager::class.java))
    private fun platformFocusRequest() = focusShadow().lastAudioFocusRequest.audioFocusRequest!!
    private fun focusListener(request: android.media.AudioFocusRequest) = request.javaClass
        .getDeclaredMethod("getOnAudioFocusChangeListener").apply { isAccessible = true }
        .invoke(request) as android.media.AudioManager.OnAudioFocusChangeListener
    private fun assertLiveUnchanged(mp: android.media.MediaPlayer, talk: com.rumi.hermesvoice.core.watchlink.WatchTalkState,
        deadlines: Map<Any?, Any?>, focus: android.media.AudioFocusRequest, ended: Any?) {
        assertSame("unrelated prepared/playing B stays owned", mp, appField("player"))
        assertEquals(talk, app.talk.value); assertEquals(deadlines, playbackDeadlines())
        assertSame(focus, platformFocusRequest()); assertEquals(ended, appField("lastPlaybackEndedAtMs"))
        assertEquals(talk.speakingTurnId, (appField("playing") as com.rumi.hermesvoice.core.watchlink.PlayRequest).turnId)
    }
    private fun completedPhoneThenStop(role: String): String {
        val id = "completed-phone-$role"
        val first = com.rumi.hermesvoice.core.watchlink.PlayRequest(id, 0, role, "audio/wav", byteArrayOf(1), later = false)
        app.playReceived(first, PHONE, app.playbackStopGeneration)
        val mp = appField("player") as android.media.MediaPlayer
        prepared.captured.onPrepared(mp); completed.captured.onCompletion(mp); idle(100)
        assertNull(appField("player")); assertNull(app.talk.value.turnId)
        notificationStop(); platformHides(); userReopens(); assertTrue(deliverService())
        return id
    }
    private fun completedIdentityRefusal(role: String, later: Boolean, ackFails: Boolean) {
        settings(WakeLocation.PHONE) // Watch standby on, location excludes the Watch: a visible app listens to nothing (no LISTEN hold).
        launch(); assertTrue(deliverService()); mockPlayers()
        val positive = interceptAcks(); val old = completedPhoneThenStop(role)
        assertTrue("completion positively ACKs the legitimate first utterance", positive.single().ok)
        app.playReceived(request("unrelated-live-B", later = false), PHONE, app.playbackStopGeneration)
        val mp = appField("player") as android.media.MediaPlayer; prepared.captured.onPrepared(mp)
        val talk = app.talk.value; val before = playbackDeadlines(); val focus = platformFocusRequest()
        val ended = appField("lastPlaybackEndedAtMs"); val abandoned = focusShadow().lastAbandonedAudioFocusRequest
        val pending = com.google.android.gms.tasks.TaskCompletionSource<Int>(); val acks = interceptAcks(pending.task)
        app.playReceived(request(old, later, seq = 1), PHONE, app.playbackStopGeneration); idle(10)
        assertLiveUnchanged(mp, talk, before, focus, ended)
        assertSame(abandoned, focusShadow().lastAbandonedAudioFocusRequest)
        assertEquals(1, acks.size); assertEquals(old, acks.single().turnId); assertFalse(acks.single().ok)
        assertEquals("stopped on the watch", acks.single().error)
        if (ackFails) pending.setException(IllegalStateException("b43 negative ACK failure")) else pending.setResult(1)
        idle(100); assertLiveUnchanged(mp, talk, before, focus, ended)
        assertSame(abandoned, focusShadow().lastAbandonedAudioFocusRequest)
        assertTrue((appField("pendingPlayedAcks") as Set<*>).isEmpty())
    }
    @Test fun R64_CompletedPhoneAckStopRejectsSameTurnFinalPreservingBAndAckCompletion() {
        completedIdentityRefusal("ack", later = false, ackFails = false)
    }
    @Test fun R65_CompletedPhoneFinalStopRejectsLaterFinalPreservingBAndAckFailure() {
        completedIdentityRefusal("final", later = true, ackFails = true)
    }
    @Test fun R66_ConsecutivePhoneUtterancesAndFreshPhoneAfterStopRemainAuthorized() {
        launch(); assertTrue(deliverService()); mockPlayers(); val acks = interceptAcks()
        repeat(2) { seq ->
            app.playReceived(request("normal-phone", later = false, seq = seq), PHONE, app.playbackStopGeneration)
            val mp = appField("player") as android.media.MediaPlayer
            prepared.captured.onPrepared(mp); completed.captured.onCompletion(mp); idle(100)
        }
        assertEquals(2, acks.size); assertTrue(acks.all { it.ok })
        notificationStop(); platformHides(); userReopens(); assertTrue(deliverService())
        app.playReceived(request("genuinely-new-phone", later = false), PHONE, app.playbackStopGeneration)
        val mp = appField("player") as android.media.MediaPlayer
        prepared.captured.onPrepared(mp); assertEquals("genuinely-new-phone", app.talk.value.speakingTurnId)
        completed.captured.onCompletion(mp); idle(100); assertTrue(acks.last().ok)
    }
    private fun delayedOldFocus(firstOffMain: Boolean, oldCompleted: Boolean) {
        settings(WakeLocation.PHONE) // Watch standby on, location excludes the Watch: a visible app listens to nothing (no LISTEN hold).
        launch(); assertTrue(deliverService()); mockPlayers(); interceptAcks()
        app.playReceived(request("focus-A", later = false), PHONE, app.playbackStopGeneration)
        val old = appField("player") as android.media.MediaPlayer; prepared.captured.onPrepared(old)
        val oldRequest = platformFocusRequest(); val loss = focusListener(oldRequest)
        if (oldCompleted) { completed.captured.onCompletion(old); idle(100); assertNull(appField("player")) }
        app.playReceived(request("focus-B", later = false), PHONE, app.playbackStopGeneration)
        val current = appField("player") as android.media.MediaPlayer; prepared.captured.onPrepared(current)
        assertTrue(old !== current)
        val before = playbackDeadlines(); val talk = app.talk.value; val focus = platformFocusRequest()
        val ended = appField("lastPlaybackEndedAtMs"); val abandoned = focusShadow().lastAbandonedAudioFocusRequest
        // FIRST receipt of A's actual saved platform listener is strictly AFTER B is installed.
        if (firstOffMain) {
            val worker = Thread { loss.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS) }
            worker.start(); worker.join(2000); assertFalse(worker.isAlive)
        } else loss.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idle(100); assertLiveUnchanged(current, talk, before, focus, ended)
        assertSame(abandoned, focusShadow().lastAbandonedAudioFocusRequest)
        assertTrue("each acquisition has its own platform request", oldRequest !== focus)
    }
    @Test fun R67_FirstOldListenerDeliveryAfterReplacementOnMainCannotStopB() { delayedOldFocus(false, false) }
    @Test fun R68_FirstOldListenerDeliveryAfterReplacementOffMainCannotStopB() { delayedOldFocus(true, false) }
    @Test fun R69_OldRequestFirstDeliveredAfterCompletionCannotStopB() { delayedOldFocus(false, true) }
    @Test fun R70_CurrentAcquisitionLossStillStopsBWithNegativeAckAndReleasesFocus() {
        launch(); assertTrue(deliverService()); mockPlayers(); val acks = interceptAcks()
        val current = request("current-focus-B", later = false); app.playReceived(current, PHONE, app.playbackStopGeneration)
        val mp = appField("player") as android.media.MediaPlayer; prepared.captured.onPrepared(mp)
        val focus = platformFocusRequest(); focusListener(focus).onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS)
        idle(100); assertNull(appField("player")); assertNull(app.talk.value.speakingTurnId)
        assertFalse(com.rumi.hermesvoice.core.background.HoldReason.PLAYBACK in app.holds.held())
        assertSame(focus, focusShadow().lastAbandonedAudioFocusRequest)
        assertEquals(current.turnId, acks.single().turnId); assertFalse(acks.single().ok)
        assertEquals("audio focus lost", acks.single().error)
    }
    @Test fun R71_FocusDenialAbandonsExactRequestAndConstructsNoPlayer() {
        settings(WakeLocation.PHONE) // Watch standby on, location excludes the Watch: a visible app listens to nothing (no LISTEN hold).
        launch(); assertTrue(deliverService()); mockPlayers(); val acks = interceptAcks()
        focusShadow().setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        app.playReceived(request("denied-focus", later = false), PHONE, app.playbackStopGeneration); idle(100)
        assertNull(appField("player")); assertSame(platformFocusRequest(), focusShadow().lastAbandonedAudioFocusRequest)
        assertEquals("audio focus denied", acks.single().error); assertFalse(acks.single().ok)
        io.mockk.verify(exactly = 0) { anyConstructed<android.media.MediaPlayer>().prepareAsync() }
        assertTrue(app.holds.held().isEmpty())
    }
    @Test @Config(shadows = [AcquisitionAudioManagerShadow::class])
    fun R72_FocusLossBeforeRequestReturnCannotAdmitPlayer() {
        settings(WakeLocation.PHONE) // Watch standby on, location excludes the Watch: a visible app listens to nothing (no LISTEN hold).
        launch(); assertTrue(deliverService()); mockPlayers(); val acks = interceptAcks()
        AcquisitionAudioManagerShadow.lossBeforeReturn = true
        app.playReceived(request("early-focus-loss", later = false), PHONE, app.playbackStopGeneration); idle(100)
        assertNull("lost acquisition cannot create a player after callback", appField("player"))
        assertSame(platformFocusRequest(), focusShadow().lastAbandonedAudioFocusRequest)
        assertEquals("audio focus denied", acks.single().error); assertFalse(acks.single().ok)
        io.mockk.verify(exactly = 0) { anyConstructed<android.media.MediaPlayer>().prepareAsync() }
        assertTrue(app.holds.held().isEmpty())
    }
    @Test fun R73_InvalidReceiveGenerationCannotAuthorizeIdentityForLaterStop() {
        launch(); assertTrue(deliverService()); mockPlayers(); val acks = interceptAcks()
        val generation = app.playbackStopGeneration; notificationStop()
        app.playReceived(request("invalid-generation-only", later = false), PHONE, generation); idle(100)
        assertNull(appField("player")); assertFalse(acks.single().ok)
        platformHides(); userReopens(); assertTrue(deliverService()); notificationStop()
        platformHides(); userReopens(); assertTrue(deliverService())
        app.playReceived(request("invalid-generation-only", later = false), PHONE, app.playbackStopGeneration)
        val mp = appField("player") as android.media.MediaPlayer
        prepared.captured.onPrepared(mp); completed.captured.onCompletion(mp); idle(100)
        assertTrue("rejected old receive did not grant pre-Stop response authority", acks.last().ok)
    }

    companion object {
        private const val TAG = "HermesVoiceWatch"
        private const val PHONE = "phone-node"
        private const val ACTION_STOP = "com.rumi.hermesvoice.watch.action.BACKGROUND_STOP"
    }
}

/** New-only SDK34 focus boundary shadow. It retains the real request and injects loss inside requestAudioFocus. */
@org.robolectric.annotation.Implements(android.media.AudioManager::class)
class AcquisitionAudioManagerShadow : org.robolectric.shadows.ShadowAudioManager() {
    @org.robolectric.annotation.Implementation
    override fun requestAudioFocus(request: android.media.AudioFocusRequest): Int {
        val result = super.requestAudioFocus(request)
        if (lossBeforeReturn) {
            request.javaClass.getDeclaredMethod("getOnAudioFocusChangeListener").apply { isAccessible = true }
                .invoke(request).let { (it as android.media.AudioManager.OnAudioFocusChangeListener)
                    .onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS) }
        }
        return result
    }
    companion object {
        @JvmField var lossBeforeReturn = false
        @JvmStatic @org.robolectric.annotation.Resetter fun resetBoundary() { lossBeforeReturn = false }
    }
}
