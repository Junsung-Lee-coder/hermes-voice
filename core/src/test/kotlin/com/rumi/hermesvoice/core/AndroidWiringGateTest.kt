package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.DeviceLocalFlags
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Secondary source gates: the behaviour itself is tested on the pure cores the Android adapters
 * delegate to (see WatchAdaptersTest, and WatchVoiceCoordinatorTest for the Watch's background
 * session composed as its runtime composes it). These only check that the adapters still delegate
 * there and keep platform wiring the JVM cannot run (theme flags, manifest, focus order,
 * contexts). A text match proves that a line exists, and an order gate only that one call comes
 * before another in the source text; never the runtime timing.
 */
class AndroidWiringGateTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(path: String) = File(root, path).readText()

    private val watch = "watch/src/main/kotlin/com/rumi/hermesvoice/watch"
    private val phone = "phone/src/main/kotlin/com/rumi/hermesvoice/phone"

    @Test
    fun `right swipe and Back background the task and the system swipe-to-dismiss cannot finish it instead`() {
        val activity = source("$watch/WatchActivity.kt")
        assertTrue(activity.substringAfter("ReaderAction.BACKGROUND_APP ->").substringBefore("\n").contains("toBackground(\"swipe\")"))
        assertTrue(activity.substringAfter("override fun handleOnBackPressed()").substringBefore("\n").contains("toBackground(\"back\")"))
        // Always to the back: not gated on the service or a permission, never finish, never the session's Stop.
        val toBackground = activity.substringAfter("private fun toBackground(reason: String) {").substringBefore("\n    }")
        assertTrue(toBackground.contains("moveTaskToBack(true)") && toBackground.contains("voice.ensureBackground()"))
        for (never in listOf("finish", "stopBackground", "return", "launch(")) assertFalse(never, toBackground.contains(never))
        assertTrue(activity.contains("ReaderAction.TOGGLE_SURFACE -> {") && activity.contains("app.toggleReaderSurface()"))
        assertTrue(activity.contains(".readerSwipe(::onSwipe)"))
        assertTrue(source("watch/src/main/res/values/themes.xml").contains("<item name=\"android:windowSwipeToDismiss\">false</item>"))
    }

    @Test
    fun `both reader lists take bezel input through a focused list with scroll haptics`() {
        val ui = source("$watch/ReaderUi.kt")
        assertEquals(2, Regex("\\.rotaryScroll\\(listState, focusRequester, onScrollStep\\)").findAll(ui).count())
        assertTrue(ui.contains("onRotaryScrollEvent"))
        assertTrue(ui.contains("driver.offer(event.verticalScrollPixels)") && ui.contains("driver.drain("))
        assertTrue("focus sits on the list, not around buttons", ui.contains("}.focusRequester(focusRequester).focusable()"))
        val activity = source("$watch/WatchActivity.kt")
        assertTrue(activity.contains("sessionsFocus.requestFocus()") && activity.contains("chatFocus.requestFocus()"))
        assertTrue(activity.contains("app.haptic(HapticEvent.SCROLL_STEP)"))
    }

    @Test
    fun `the capture lifecycle and wake window delegate to the tested cores`() {
        val activity = source("$watch/WatchActivity.kt")
        val runtime = source("$watch/WatchVoiceRuntime.kt")
        val talkPressed = runtime.substringAfter("fun onTalkPressed(): Boolean").substringBefore("// ── capture")
        assertFalse("no haptic on button intent", talkPressed.contains("haptic"))
        assertTrue(runtime.contains("CaptureCoordinator(capturePort)"))
        assertTrue(runtime.substringAfter("override fun onLive()").substringBefore("override fun onCalibrated()").contains("captures.onLive(turnId)"))
        assertTrue(runtime.substringAfter("override fun onCalibrated()").substringBefore("override fun onEnd(").contains("captures.onCalibrated(turnId)"))
        // Leaving the screen goes to the tested presence rules, which end a recording unsent unless a background session is armed.
        assertTrue(activity.substringAfter("override fun onPause()").substringBefore("super.onPause()").contains("voice.onActivityPaused()"))
        assertTrue(runtime.substringAfter("fun onActivityPaused()").substringBefore("fun onPermissionResult()").contains("coordinator.onActivityPaused()"))
        assertTrue(runtime.substringAfter("override fun cancelCapture(reason: String)").substringBefore("}").contains("CaptureStop.LIFECYCLE"))
        assertTrue("handoff is cancellable and generation-bound (WakeDeviceController)",
            runtime.contains("wake.wake.onHandoffDue(captureIdle = captures.activeId == null)") && runtime.contains("removeCallbacks(handoffRunnable)"))
        assertFalse("no duration cap", runtime.contains("maxTurnSeconds") || source("$watch/WatchCapture.kt").contains("limitFor"))
        val capture = source("$watch/WatchCapture.kt")
        assertTrue("start is confirmed by the recorder state", capture.contains("recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING"))
        assertTrue("the shared recording loop", capture.contains("PcmCaptureLoop({ recorder.read(it, 0, it.size) }, limitBytes, endpoint, listener"))
        assertTrue("the silence snapshot the wake flow passed in",
            runtime.contains("SilenceEndpoint(sampleRate = WatchCapture.SAMPLE_RATE, silenceMs = silenceMs)") &&
                runtime.contains("startCapture(TurnTrigger.WAKE_PHRASE, silenceMs)"))
        assertTrue(runtime.contains("end(turnId, CaptureStop.of(reason))"))
        assertFalse("no buzz on phone stage updates or timers", source("$watch/WatchApp.kt").contains("fun buzz("))
        val app = source("$watch/WatchApp.kt")
        assertTrue("usage comes from the tested policy", app.contains("WatchHapticPolicy.usageFor(event)") &&
            app.contains("VibrationAttributes.USAGE_HARDWARE_FEEDBACK") && app.contains("VibrationAttributes.USAGE_TOUCH"))
        assertFalse("never bypasses Do Not Disturb or user settings", app.contains("FLAG_BYPASS"))
        assertFalse("the end reason is only the accepted stop's", runtime.contains("lastStop"))
        // The activity holds no voice logic of its own: no recorder, coordinator or wake controller is created per screen.
        for (owned in listOf("CaptureCoordinator(", "WatchCapture(", "WakeController(", "SpeechRecognizer")) assertFalse(owned, activity.contains(owned))
    }

    @Test
    fun `both wake adapters delegate to the shared device controller`() {
        for ((path, device) in listOf("$watch/WakeController.kt" to "WATCH", "$phone/PhoneWakeController.kt" to "PHONE")) {
            val wake = source(path)
            assertTrue(path, wake.contains("WakeDeviceController(VoiceOrigin.$device, recognizerPort, timerPort, port, SystemClock::elapsedRealtime, initial, claims)"))
            // Stale callbacks of a released recognizer are dropped by token, for errors and results alike.
            assertTrue(path, wake.contains("created.setRecognitionListener(listenerFor(generation, onDevice, guard.open()))"))
            assertTrue(path, wake.contains("if (!guard.isCurrent(token)) return") &&
                wake.contains("if (guard.isCurrent(token)) onRecognized(gen, results, final = true)") &&
                wake.contains("if (guard.isCurrent(token)) onRecognized(gen, partialResults, final = false)"))
            assertTrue(path, wake.substringAfter("private fun destroyRecognizer()").substringBefore("}").contains("guard.close()"))
            assertFalse("the fallback no longer tests only for a non-null recognizer", wake.contains("error in LANGUAGE_ERRORS && recognizer != null"))
            assertFalse(wake.contains("EXTRA_PREFER_OFFLINE"))
            assertTrue(wake.contains("wake.onResults(gen, heard, final)"))
            // Privacy: recognized words are never logged, only how many candidates there were.
            assertFalse(path, Regex("Log\\.[a-z]\\([^\n]*\\$\\{?heard(?!\\.size)").containsMatchIn(wake))
        }
        // The Phone's wake phrase stays bound to its visible activity: pausing closes the window.
        val phoneWake = source("$phone/PhoneWakeController.kt")
        assertTrue(phoneWake.contains("private val activity: ComponentActivity") && phoneWake.contains("override fun onPause(owner: LifecycleOwner) = wake.onPause()"))
        assertFalse("receiver never touches the microphone", phoneWake.substringAfter("override fun onReceive").substringBefore("override fun onStart")
            .contains("startListening"))
        // The Watch's belongs to the application runtime and follows the tested presence rules.
        val watchWake = source("$watch/WakeController.kt")
        assertFalse(watchWake.contains("ComponentActivity") || watchWake.contains("LifecycleObserver"))
        val runtime = source("$watch/WatchVoiceRuntime.kt")
        // The wake flow is built on the coordinator's port and bound to it: its decisions are the tested ones.
        assertTrue(runtime.contains("WakeController(app, coordinator.devicePort(wakePort), app.settings.value, claimPort)") &&
            runtime.contains("coordinator.attach(wake.wake)"))
        assertTrue(runtime.contains("fun onActivityResumed(): Long = coordinator.onActivityResumed()") &&
            runtime.contains("fun onSettingsPulled(visit: Long): Boolean = coordinator.onSettingsPulled(visit)"))
        assertTrue(runtime.contains("app.settings.collect { coordinator.onEligibilityChanged() }"))
        assertFalse("the screen receiver only posts a signal", runtime.substringAfter("private val screenReceiver").substringBefore("private val qaReceiver")
            .let { it.contains("startListening") || it.contains("startCapture") })
        assertTrue("the runtime reports platform facts only; presence is the coordinator's", runtime.contains("resumed = false,"))
        val watchActivity = source("$watch/WatchActivity.kt")
        assertTrue(watchActivity.substringAfter("override fun onResume()").substringBefore("override fun onPause()").let {
            it.indexOf("val visit = voice.onActivityResumed()") in 0 until it.indexOf("pullSettings()") && it.indexOf("pullSettings()") < it.indexOf("voice.onSettingsPulled(visit)")
        })
        assertTrue("the read of a show is cancelled when the app leaves the screen",
            watchActivity.substringAfter("override fun onPause()").substringBefore("voice.onActivityPaused()").contains("settingsPull?.cancel()"))
        val phoneActivity = source("$phone/MainActivity.kt")
        assertTrue(phoneActivity.contains("collect { phoneWake.wake.onSettings(it) }"))
        assertTrue("the Phone recorder gets the snapshot", phoneActivity.contains("model.startHandsFree(silenceMs, claimId, episodeMicrophone.take())"))
        val model = source("$phone/PhoneViewModel.kt")
        assertTrue(model.contains("SilenceEndpoint(sampleRate = PhoneCapture.SAMPLE_RATE, silenceMs = silenceMs)"))
        assertTrue("the same capture lifecycle as the Watch", model.contains("private val captures = CaptureCoordinator(") &&
            model.contains("captures.stop(id, CaptureStop.of(reason))"))
        assertTrue(source("$phone/PhoneCapture.kt").contains("PcmCaptureLoop({ recorder.read(it, 0, it.size) }, LIMIT_BYTES, endpoint, listener"))
    }

    @Test
    fun `both apps carry the wake claim with their requests and the phone answers claims from the transport's node`() {
        val bridge = source("$phone/PhoneWatchBridge.kt")
        assertTrue(bridge.contains("WatchLinkPaths.WAKE_CLAIM -> answerWakeClaim(event.sourceNodeId, event.data)"))
        assertTrue(bridge.contains("WakeClaimService.handle(wiring.core.wakeAdmission, nodeId, data)"))
        assertTrue(source("phone/src/main/AndroidManifest.xml").contains("android:path=\"/hv/v1/wake/claim\""))
        val phoneActivity = source("$phone/MainActivity.kt")
        assertTrue(phoneActivity.contains("model.startHandsFree(silenceMs, claimId, episodeMicrophone.take())") &&
            phoneActivity.contains("model.sendRecognizedRequest(request, claimId, episodeMicrophone.take())"))
        assertTrue(phoneActivity.contains("admission()?.claim(WakeClaim(claimId, VoiceOrigin.PHONE, \"\", settingsRevision, generation, epoch))"))
        val model = source("$phone/PhoneViewModel.kt")
        assertTrue(model.contains("submitVoice(wav, wakeTurn = true, wakeClaimId = claimId, microphone = microphone)") &&
            model.contains("wakeTurn = true, wakeClaimId = claimId, routing = routing, microphone = microphone)"))
        assertTrue("a busy phone says so instead of dropping the request", model.substringAfter("fun sendRecognizedRequest").substringBefore("recognizedClaimId = claimId")
            .contains("return onWakeClosed(\"unfinished_request\")"))
        val watchRuntime = source("$watch/WatchVoiceRuntime.kt")
        assertTrue(watchRuntime.contains("app.upload(captureId, trigger, wav, claimId)") && watchRuntime.contains("app.uploadRecognized(turnId, text, recognizedClaimId)"))
        assertTrue(watchRuntime.contains("wake.wake.onRequestCaptureEnded(sent = true)") && watchRuntime.contains("wake.wake.onRequestCaptureEnded(sent = false)"))
        val watchApp = source("$watch/WatchApp.kt")
        assertTrue("a verdict counts only from the node the claim went to", watchApp.contains("if (!claimSender.accepts(verdict.claimId, sourceNodeId))"))
        assertTrue(source("$watch/WatchListenerService.kt").contains("WatchLinkPaths.WAKE_VERDICT ->"))
    }

    @Test
    fun `the watch keeps its claim until the phone answers and sends every claim message through the one ordered sender`() {
        val watchApp = source("$watch/WatchApp.kt")
        assertTrue(watchApp.contains("fun sendWakeClaim(message: WakeClaimMessage) = claimSender.offer(message)"))
        assertEquals("claim messages leave the Watch in one place only", 1, Regex("WatchLinkPaths\\.WAKE_CLAIM").findAll(watchApp).count())
        assertTrue(watchApp.contains("WakeClaimSender(scope, ::phoneNode,"))
        val upload = watchApp.substringAfter("fun upload(turnId: String, trigger: TurnTrigger, wav: ByteArray").substringBefore("fun uploadRecognized")
        assertTrue("the claim is kept before the recording is handed to the link",
            upload.indexOf("claimId?.let { keepClaimInTransit(it, turnId, wav.size) }") in 0 until upload.indexOf("send(turnId, WatchTurnUpload("))
        assertTrue(watchApp.substringAfter("fun uploadRecognized").substringBefore("// ── wake arbitration").contains("wakeClaimId?.let { keepClaimInTransit(it, turnId, request.length) }"))
        assertTrue("kept by the application, not the activity, so leaving the screen does not drop it",
            watchApp.contains("transitJob = scope.launch {") && watchApp.contains("delay(WakeContract.CLAIM_RENEW_MS)") && watchApp.contains("transit.tick()"))
        assertTrue(watchApp.contains("transit.onTransferFailed(turnId)") && watchApp.contains("transit.onPhoneState(message.turnId, message.terminal)") &&
            watchApp.contains("transit.onVerdict(verdict.claimId, verdict.verdict)"))
        assertTrue("a claim lost on the way is shown", watchApp.contains("it.sendFailed(\"Couldn't confirm with the phone. Say it again\")"))
        // The Phone says a turn is accepted at once, before it waits its place in line.
        assertTrue(source("core/src/main/kotlin/com/rumi/hermesvoice/core/watchlink/PhoneWatchRelay.kt")
            .contains("states.trySend(TurnStateMessage(turnId, \"accepted\", \"\", false))"))
    }

    @Test
    fun `both apps share the phone's count of answered wake requests and read it before they listen`() {
        val app = source("$phone/PhoneApp.kt")
        assertTrue(app.contains("core.onWakeEpisode = { episode ->") && app.contains("publishWakeEpoch(WakeEpochItem(episode.epoch, episode.claimId))") &&
            app.contains("publishWakeEpoch(WakeEpochItem(core.wakeAdmission.epoch))"))
        assertTrue(source("$phone/PhoneWatchBridge.kt").contains("PutDataMapRequest.create(WatchLinkPaths.WAKE_EPOCH)"))
        val phoneActivity = source("$phone/MainActivity.kt")
        assertTrue(phoneActivity.contains("override fun epoch(): Long = admission()?.epoch ?: 0L") &&
            phoneActivity.contains("phoneWake.wake.onEpisodeAnswered(it.epoch, it.claimId)"))
        assertTrue(source("watch/src/main/AndroidManifest.xml").contains("android:path=\"/hv/v1/wake/epoch\""))
        assertTrue(source("$watch/WatchListenerService.kt").contains("WatchLinkPaths.WAKE_EPOCH -> app.scope.launch { app.applyWakeEpoch(json) }"))
        val watchActivity = source("$watch/WatchActivity.kt")
        val watchRuntime = source("$watch/WatchVoiceRuntime.kt")
        assertTrue(watchRuntime.contains("override fun epoch(): Long = app.wakeEpoch") &&
            watchRuntime.contains("app.sendWakeClaim(WakeClaimMessage(WakeClaimMessage.Op.CLAIM, claimId, settingsRevision, generation, epoch))") &&
            watchRuntime.contains("app.wakeEpochListener = { epoch, claimId -> wake.wake.onEpisodeAnswered(epoch, claimId) }"))
        val resume = watchActivity.substringAfter("override fun onResume()").substringBefore("override fun onPause()")
        assertTrue("the count is read before the Watch may listen",
            watchActivity.contains("pullItem(WatchLinkPaths.WAKE_EPOCH, app::applyWakeEpoch)") &&
                resume.indexOf("pullSettings()") in 0 until resume.indexOf("voice.onSettingsPulled(visit)"))
        assertTrue("the wake location changing mid-request is said on both devices",
            watchRuntime.contains("\"wake_mode_changed\" ->") && source("$phone/PhoneViewModel.kt").contains("\"wake_mode_changed\" ->"))
    }

    @Test
    fun `talk tapped in an open wake window releases the recognizer and waits the handoff pause on both devices`() {
        val phoneActivity = source("$phone/MainActivity.kt")
        val phoneTalk = phoneActivity.substringAfter("private fun onTalk()").substringBefore("override fun onCreate")
        assertTrue(phoneTalk.contains("phoneWake.wake.onBusy()") && phoneTalk.contains("postDelayed(talkAfterRelease, WakeContract.MIC_HANDOFF_MS)"))
        assertTrue(phoneActivity.contains("PhoneScreen(model, recognizerAvailable, onTalk = ::onTalk)"))
        val watchTalk = source("$watch/WatchVoiceRuntime.kt").substringAfter("fun onTalkPressed(): Boolean").substringBefore("// ── capture")
        assertTrue(watchTalk.contains("coordinator.onBusy()") && watchTalk.contains("postDelayed(talkAfterRelease, WakeContract.MIC_HANDOFF_MS)"))
        // On the Phone, leaving the screen (rotation included) cancels a hands-free recording unsent: the documented lifecycle contract.
        assertTrue(source("$phone/PhoneWakeController.kt").contains("override fun onPause(owner: LifecycleOwner) = wake.onPause()"))
    }

    private fun permissions(manifest: String) = Regex("uses-permission android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1].removePrefix("android.permission.") }.toList()

    @Test
    fun `the manifests declare exactly the background components that are used, and nothing that starts or wakes by itself`() {
        val phoneManifest = source("phone/src/main/AndroidManifest.xml")
        val watchManifest = source("watch/src/main/AndroidManifest.xml")
        assertEquals(listOf("INTERNET", "RECORD_AUDIO", "VIBRATE", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CONNECTED_DEVICE",
            "FOREGROUND_SERVICE_MICROPHONE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK", "CHANGE_NETWORK_STATE", "POST_NOTIFICATIONS", "WAKE_LOCK"),
            permissions(phoneManifest))
        assertEquals(listOf("RECORD_AUDIO", "VIBRATE", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MICROPHONE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "POST_NOTIFICATIONS", "WAKE_LOCK"), permissions(watchManifest))
        for (manifest in listOf(phoneManifest, watchManifest)) {
            assertTrue(manifest.contains("<action android:name=\"android.speech.RecognitionService\" />"))
            // No boot start, battery-optimization exemption, overlay, full-screen intent, exact alarm, accessibility or assistant role.
            for (forbidden in listOf("RECEIVE_BOOT_COMPLETED", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "SYSTEM_ALERT_WINDOW", "USE_FULL_SCREEN_INTENT",
                "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "BIND_ACCESSIBILITY_SERVICE", "BIND_VOICE_INTERACTION", "FOREGROUND_SERVICE_DATA_SYNC",
                "FOREGROUND_SERVICE_SPECIAL_USE", "<receiver", "dataSync", "shortService", "specialUse", "systemExempted")) {
                assertFalse(forbidden, manifest.contains(forbidden))
            }
        }
        // The foreground services are private to the app and typed for what they do. The relay never has the
        // microphone; only the Phone's separate, opt-in background listening service may.
        val phoneService = phoneManifest.substringAfter("android:name=\".PhoneRelayService\"").substringBefore("/>")
        assertTrue(phoneService.contains("android:exported=\"false\"") && phoneService.contains("android:foregroundServiceType=\"connectedDevice|mediaPlayback\""))
        val phoneWakeService = phoneManifest.substringAfter("android:name=\".PhoneWakeService\"").substringBefore("/>")
        assertTrue(phoneWakeService.contains("android:exported=\"false\"") && phoneWakeService.contains("android:foregroundServiceType=\"microphone|mediaPlayback\""))
        val watchService = watchManifest.substringAfter("android:name=\".WatchVoiceService\"").substringBefore("/>")
        assertTrue(watchService.contains("android:exported=\"false\"") && watchService.contains("android:foregroundServiceType=\"microphone|mediaPlayback\""))
        assertEquals("only the Data Layer listeners are exported", 1, Regex("<service[^>]*android:exported=\"true\"").findAll(phoneManifest).count())
        assertEquals(1, Regex("<service[^>]*android:exported=\"true\"").findAll(watchManifest).count())
        val relay = source("$phone/PhoneRelayService.kt")
        assertFalse(relay.substringBefore("class AndroidWakeLocks").let { it.contains("MICROPHONE") || it.contains("AudioRecord") || it.contains("SpeechRecognizer") })
    }

    @Test
    fun `background operation starts only from the visible app, is not restarted by the system and is stopped from its notification or the app`() {
        val watchActivity = source("$watch/WatchActivity.kt")
        val runtime = source("$watch/WatchVoiceRuntime.kt")
        val watchService = source("$watch/WatchVoiceService.kt")
        val watchApp = source("$watch/WatchApp.kt")
        val listener = source("$watch/WatchListenerService.kt")
        // On by default, from the visible app only: the activity's show and its move to the back ensure it (once per open,
        // WatchBackgroundDefaultTest); there is no control in the app, and nothing else starts it.
        assertTrue(runtime.contains("WatchVoiceCoordinator(app.localStore, KEY_BACKGROUND, servicePort, host, app.holds, SystemClock::elapsedRealtime)"))
        assertTrue(source("core/src/main/kotlin/com/rumi/hermesvoice/core/background/WatchVoiceCoordinator.kt")
            .contains("BackgroundSession(store, key, service, resumeWhenVisible = false)"))
        assertEquals(2, Regex("voice\\.ensureBackground\\(\\)").findAll(watchActivity).count())
        assertTrue(watchActivity.substringAfter("override fun onResume()").substringBefore("settingsPull?.cancel()").contains("voice.ensureBackground()"))
        assertTrue(runtime.contains("fun ensureBackground(): Boolean = coordinator.ensureDefault()"))
        assertFalse("no in-app start/stop control", watchActivity.contains("startBackground") || watchActivity.contains("voice.stopBackground()") ||
            source("$watch/ReaderUi.kt").contains("BackgroundText"))
        assertEquals("the runtime starts it only through the default", 1, Regex("coordinator\\.(start|ensureDefault)\\(").findAll(runtime).count())
        for (elsewhere in listOf(watchService, watchApp, listener)) {
            assertFalse(elsewhere.contains("ensureBackground(") || elsewhere.contains("background.start(") || elsewhere.contains("startForegroundService"))
        }
        // A start is a request: the service reports its entry, and only that arms a microphone (WatchBackgroundDefaultTest).
        assertTrue(runtime.contains("override val confirmsEntry: Boolean get() = true"))
        assertTrue(watchService.substringAfter("ServiceCompat.startForeground(this, NOTIFICATION_ID").substringBefore("}.onFailure")
            .contains("voice.onServiceEntered(generation, microphone)"))
        assertEquals("the service is started in one place", 1, Regex("startForegroundService").findAll(runtime).count())
        // The microphone type is requested only when the session asks for it, and a refusal is caught, reported and not retried.
        assertTrue(watchService.contains("(if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)"))
        assertTrue(watchService.contains("microphone && enter(false) -> voice.onMicrophoneRefused(generation)"))
        assertTrue(watchService.substringAfter("private fun enter(microphone: Boolean): Boolean = runCatching {").contains("}.onFailure {"))
        // When the microphone may be armed is decided and tested in WatchVoiceCoordinatorTest; here only: nothing else arms it.
        assertFalse(runtime.contains("background.onVisible(") || runtime.contains("background.onMicrophone") || runtime.contains("presence.onArmed("))
        // Both services: not sticky, no redelivery, the session's generation, a Stop action to the service itself.
        for ((service, stop, gone) in listOf(Triple(watchService, "voice.stopBackground()", "voice.onServiceGone(generation)"),
            Triple(source("$phone/PhoneRelayService.kt"), "app.stopRelay()", "onRelayServiceGone(generation)"),
            Triple(source("$phone/PhoneWakeService.kt"), "wake.stop()", "background.onServiceGone(generation)"))) {
            assertTrue(service.contains("return START_NOT_STICKY"))
            assertFalse(service.contains("START_STICKY") || service.contains("START_REDELIVER_INTENT"))
            assertTrue(service.substringAfter("ACTION_STOP -> {").substringBefore("ACTION_START -> {").contains(stop))
            assertTrue(service.substringAfter("override fun onDestroy()").contains(gone))
            assertTrue(service.contains(".addAction(0, \"Stop\", stop)") && service.contains(".setOngoing(true)"))
            assertTrue(service.contains("PendingIntent.FLAG_IMMUTABLE") && !service.contains("FLAG_MUTABLE"))
            assertFalse("no full-screen intent or screen wake", service.contains("setFullScreenIntent") || service.contains("ACQUIRE_CAUSES_WAKEUP"))
        }
        // Stop with the app hidden ends everything of the session: recording, pending upload, playback, wake locks.
        val stop = runtime.substringAfter("fun stopBackground()").substringBefore("fun onMicrophoneRefused")
        for (step in listOf("coordinator.stop()", "end(it, CaptureStop.LIFECYCLE)", "app.cancelPendingUploads(\"Stopped\")", "app.stopPlayback(\"stopped\")", "app.holds.releaseAll()")) {
            assertTrue(step, stop.contains(step))
        }
        assertTrue(watchApp.contains("if (request.turnId in stoppedTurns) return refusePlayback(request, nodeId, \"stopped on the watch\")"))
        assertTrue(watchApp.substringAfter("fun cancelPendingUploads(reason: String)").substringBefore("private fun send(").contains("job.cancel()"))
        // The Phone: the switch in the visible app, resumed only when an activity is started, turns stopped when closed.
        val phoneApp = source("$phone/PhoneApp.kt")
        assertTrue(phoneApp.contains("BackgroundSession(localStore, DeviceLocalFlags.KEY_RELAY, relayPort, resumeWhenVisible = true)"))
        assertTrue(phoneApp.contains("relay.start(visible = activityVisible, microphoneWanted = false, microphonePermission = false)"))
        assertTrue(phoneApp.substringAfter("fun onActivityStarted()").substringBefore("fun onActivityStopped()").contains("relay.onVisible(microphoneWanted = false, microphonePermission = false)"))
        assertTrue(source("$phone/MainActivity.kt").substringAfter("override fun onStart()").substringBefore("override fun onStop()").contains("PhoneApp.from(this).onActivityStarted()"))
        assertTrue(phoneApp.substringAfter("fun stopRelay()").substringBefore("fun onRelayServiceGone").let {
            it.contains("relay.stop()") && it.contains("if (!hidden) return") && it.contains("turns.keys.toList().forEach { it.cancel() }") && it.contains("holds.releaseAll()")
        })
        assertEquals(1, Regex("startForegroundService").findAll(phoneApp).count())
        assertFalse(source("$phone/PhoneWatchBridge.kt").let { it.contains("startForegroundService") || it.contains("startRelay(") })
        // The Phone's background listening: one start (the visible app's switch, which the session refuses unless the app
        // is on screen; PhoneBackgroundWakeTest), the microphone type only on request with the refusal reported, and
        // ON-DEVICE recognition only (never the system's default service, which may stream to a server).
        val phoneWakeService = source("$phone/PhoneWakeService.kt")
        val phoneRuntime = source("$phone/PhoneBackgroundRuntime.kt")
        assertTrue(phoneWakeService.contains("(if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)"))
        assertTrue(phoneWakeService.contains("microphone && enter(false) -> wake.background.onMicrophoneRefused(generation)"))
        assertEquals(1, Regex("startForegroundService").findAll(phoneRuntime).count())
        assertEquals(1, Regex("background\\.start\\(\\)").findAll(phoneRuntime).count())
        assertTrue(source("$phone/PhoneViewModel.kt").contains("if (on) app.phoneWake.start() else app.phoneWake.stop()"))
        assertTrue(phoneRuntime.contains("SpeechRecognizer.createOnDeviceSpeechRecognizer(app)") && phoneRuntime.contains("EXTRA_PREFER_OFFLINE"))
        assertFalse("no fallback to a server recognizer", phoneRuntime.contains("createSpeechRecognizer(app"))
        assertTrue(phoneApp.substringAfter("fun onActivityStarted()").substringBefore("fun onActivityStopped()").contains("phoneWake.onAppShown()"))
        assertFalse("the voice settings never carry or migrate the opt-in",
            source("core/src/main/kotlin/com/rumi/hermesvoice/core/settings/AppSettings.kt").let { it.contains("background") || it.contains("relay") })
    }

    @Test
    fun `wake locks are time-limited, the screen is never forced on, and the debug trigger exists only in debug builds`() {
        val sources = listOf("$watch/WatchApp.kt", "$watch/WatchVoiceRuntime.kt", "$watch/WatchVoiceService.kt", "$watch/WatchActivity.kt", "$watch/WakeController.kt",
            "$watch/WatchListenerService.kt", "$phone/PhoneApp.kt", "$phone/PhoneRelayService.kt", "$phone/PhoneAudio.kt", "$phone/MainActivity.kt",
            "$phone/PhoneViewModel.kt", "$phone/PhoneWatchBridge.kt").associateWith(::source)
        assertEquals("wake locks are created in one adapter per app", 2, sources.values.sumOf { Regex("newWakeLock\\(").findAll(it).count() })
        for ((path, text) in sources) {
            assertFalse("$path: every acquire has a timeout", Regex("\\.acquire\\(\\)").containsMatchIn(text))
            for (forbidden in listOf("FULL_WAKE_LOCK", "SCREEN_BRIGHT_WAKE_LOCK", "ACQUIRE_CAUSES_WAKEUP", "setTurnScreenOn", "FLAG_TURN_SCREEN_ON",
                "setShowWhenLocked", "ACTION_BOOT_COMPLETED", "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "setExact", "AlarmManager")) {
                assertFalse("$path: $forbidden", text.contains(forbidden))
            }
        }
        val watchLocks = sources.getValue("$watch/WatchApp.kt")
        assertTrue(watchLocks.contains("PowerManager.PARTIAL_WAKE_LOCK, \"\$tagPrefix:\${reason.name.lowercase()}\""))
        assertTrue(watchLocks.contains("private val tagPrefix: String = \"HermesVoice\""))
        assertTrue(watchLocks.contains("WakeHolds(AndroidWakeLocks(this), SystemClock::elapsedRealtime)"))
        assertTrue(watchLocks.contains("AndroidWakeLocks(this, \"HermesVoice:ack\")"))
        assertTrue(watchLocks.contains("lock.acquire(timeoutMs)") && watchLocks.contains("setReferenceCounted(false)"))
        val runtime = sources.getValue("$watch/WatchVoiceRuntime.kt")
        assertTrue("the screen is kept on for a recording or a foreground window, never for a whole background session",
            runtime.contains("_keepScreenOn.value = recorder != null || (_wakeListening.value && !presence.armed)"))
        // The session's holds (window, handoff, gap) are the coordinator's, tested in WatchVoiceCoordinatorTest; the runtime takes none of them.
        for (reason in listOf("LISTEN", "HANDOFF", "REARM")) assertFalse(reason, runtime.contains("HoldReason.$reason"))
        assertTrue("the recording's hold is let go after its successor (the transfer) took one",
            runtime.substringAfter("override fun upload(").substringBefore("override fun uploadRecognized").let {
                it.indexOf("app.upload(captureId, trigger, wav, claimId)") in 0 until it.indexOf("app.holds.release(HoldReason.CAPTURE)") })
        val qa = runtime.substringBefore("ContextCompat.registerReceiver(app, qaReceiver").substringAfterLast("\n")
        assertTrue("the QA receiver is registered only in a debuggable build",
            runtime.substringBefore("ContextCompat.registerReceiver(app, qaReceiver").trimEnd().endsWith("// Exported so `adb shell am broadcast` reaches it; never registered in a release build.") &&
                runtime.contains("if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {") && qa.isBlank())
        assertEquals(1, Regex("RECEIVER_EXPORTED").findAll(runtime.replace("RECEIVER_NOT_EXPORTED", "")).count())
        val phoneApp = sources.getValue("$phone/PhoneApp.kt")
        assertEquals(1, Regex("RECEIVER_EXPORTED").findAll(phoneApp).count())
        assertTrue(phoneApp.substringBefore("ContextCompat.registerReceiver(this, qaReceiver").trimEnd()
            .endsWith("if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {\n            // Exported so `adb shell am broadcast` reaches it; never registered in a release build."))
        assertTrue("the Phone's debug trigger can only stop", phoneApp.substringAfter("private val qaReceiver").substringBefore("// ── background relay").let {
            it.contains("if (intent.getStringExtra(QA_RELAY) != \"stop\") return") && !it.contains("startRelay") })
    }

    @Test
    fun `playback asks for audio focus and only a finished player acknowledges it as played`() {
        val watchApp = source("$watch/WatchApp.kt")
        assertTrue(watchApp.contains("mp.setOnCompletionListener { if (player === it) finishPlayback(ok = true, error = \"\") }"))
        assertEquals("nothing else reports a successful playback", 1, Regex("finishPlayback\\(ok = true").findAll(watchApp).count())
        assertFalse(watchApp.contains("sendPlayed(request, node, ok = true") || watchApp.contains("sendPlayed(request, nodeId, ok = true"))
        assertTrue(watchApp.contains("if (!requestFocus()) return refusePlayback(request, nodeId, \"audio focus denied\")"))
        assertTrue(watchApp.substringAfter("private fun finishPlayback(").substringBefore("private fun refusePlayback(").contains("abandonFocus()"))
        val phoneAudio = source("$phone/PhoneAudio.kt")
        assertTrue(phoneAudio.contains("AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK") && phoneAudio.contains("phone playback refused: audio focus denied") &&
            phoneAudio.contains("abandonAudioFocusRequest(it)"))
        for (text in listOf(watchApp, phoneAudio)) {
            assertFalse("volume and Do Not Disturb are left to the system", text.contains("setStreamVolume") || text.contains("adjustStreamVolume") ||
                text.contains("setInterruptionFilter") || text.contains("FLAG_BYPASS"))
        }
    }

    @Test
    fun `voice turns of both origins run in the application, not in a screen`() {
        val model = source("$phone/PhoneViewModel.kt")
        assertTrue(model.contains("app.launchTurn(turnId, phoneOrigin = true, microphone) {"))
        assertFalse("no turn is tied to the view model's scope", model.substringAfter("private fun runPhoneTurn(").substringBefore("// ── phone hands-free").contains("viewModelScope"))
        assertTrue(source("$phone/PhoneWatchBridge.kt").contains("app.launchTurn(turnId, phoneOrigin = false) {"))
        val app = source("$phone/PhoneApp.kt")
        assertTrue(app.substringAfter("fun launchTurn(").let { it.contains("appScope.launch(start = CoroutineStart.LAZY)") && it.contains("holds.acquire(HoldReason.TURN)") &&
            it.contains("if (turns.isEmpty()) holds.release(HoldReason.TURN)") })
        assertEquals("one core, one connector: the relay adds none", 1, Regex("HermesVoiceCore\\.connect\\(").findAll(app).count())
    }

    @Test
    fun `a conversation created by a voice turn is shown on the phone and the watch without asking`() {
        val app = source("$phone/PhoneApp.kt")
        assertTrue(app.contains("if (route.created) conversationsCreated.value += 1"))
        assertTrue(source("$phone/PhoneViewModel.kt").contains("app.conversationsCreated.collect { count -> if (count > 0 && _state.value.signedIn) refresh() }"))
        val watchApp = source("$watch/WatchApp.kt")
        assertTrue(watchApp.substringAfter("if (message.terminal && ownTurn) {").substringBefore("}").contains("loadSessions()"))
        assertFalse("the Watch never creates sessions or talks to Hermes", watchApp.contains("session.create") || watchApp.contains("OkHttp"))
        assertFalse("creation only goes through the session repository", source("$phone/PhoneViewModel.kt").contains("conversations.create("))
    }

    @Test
    fun `the phone's session data is saved with a confirmed commit, never a fire-and-forget apply`() {
        val stores = source("$phone/AndroidStores.kt")
        assertTrue(stores.contains("override fun commitString(key: String, value: String): Boolean = prefs.edit().putString(key, value).commit()"))
        val sessions = source("core/src/main/kotlin/com/rumi/hermesvoice/core/sessions/AppSessions.kt")
        assertFalse("registry, journal and migration marker never use the unconfirmed write", sessions.contains("putString("))
        assertTrue("and their writes run off the main thread", sessions.contains("private val io: CoroutineDispatcher = Dispatchers.IO") &&
            Regex("withContext\\(io\\) \\{ (journal|registry)\\.").findAll(sessions).count() >= 6)
        assertTrue(source("$phone/PhoneViewModel.kt").contains("wiring.core.sessions.updateDestination(session.storedSessionId, alias, description)"))
    }

    @Test
    fun `settings migrate at phone start and the watch validates what it receives`() {
        assertTrue(source("$phone/PhoneApp.kt").contains("settings.migrate()"))
        val app = source("$watch/WatchApp.kt")
        assertTrue(app.contains("val result = replica.offer(json)") && app.contains("if (result == ReplicaUpdate.APPLIED)"))
        assertFalse(app.contains("WatchSettings.fromJson(json)"))
    }

    @Test
    fun `listener services keep only the application context in app-scoped work`() {
        val bridge = source("$phone/PhoneWatchBridge.kt")
        assertFalse(bridge.contains("this@PhoneWatchListenerService"))
        assertTrue(bridge.contains("DataLayerWatchTransport(context, channel.nodeId)") && bridge.contains("val context = applicationContext"))
        assertTrue(bridge.contains("Wearable.getMessageClient(context.applicationContext)"))
        assertFalse(source("$watch/WatchListenerService.kt").contains("this@WatchListenerService"))
        assertTrue(bridge.contains("BoundedRead.readAtMost(input, BoundedRead.FRAME_LIMIT)"))
    }

    @Test
    fun `phone answers reader requests and the label follows the route`() {
        assertTrue(source("phone/src/main/AndroidManifest.xml").contains("android:path=\"/hv/v1/reader/request\""))
        val app = source("$phone/PhoneApp.kt")
        assertTrue(app.contains("route?.device ?: flowOf(null)"))
        assertFalse(app.contains("_playbackDevice.value = origin"))
        assertTrue(source("$phone/MainActivity.kt").contains("handleQaIntent(intent, restored = savedInstanceState != null)"))
    }

    /** A function's text, from [signature] to the next member. */
    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        check(start >= 0) { "missing $signature" }
        val ends = listOf("\n    fun ", "\n    private fun ", "\n    /**", "\n    override fun ").map { text.indexOf(it, start + signature.length) }.filter { it > 0 }
        return text.substring(start, ends.minOrNull() ?: text.length)
    }

    /** [first] occurs, and before [then]. */
    private fun assertBefore(where: String, text: String, first: String, then: String) {
        val a = text.indexOf(first)
        val b = text.indexOf(then)
        assertTrue("$where: '$first' present", a >= 0)
        assertTrue("$where: '$then' present", b >= 0)
        assertTrue("$where: '$first' comes before '$then'", a < b)
    }

    @Test
    fun `later replies are an opt-in that every Stop ends, with device-local consent never restored`() {
        val phoneApp = source("$phone/PhoneApp.kt")
        val model = source("$phone/PhoneViewModel.kt")
        val runtime = source("$phone/PhoneBackgroundRuntime.kt")
        // The relay's Stop (app open or not), background listening's Stop and the option switched off all end following;
        // a new core (dashboard or profile changed) ends the old one's.
        assertTrue(phoneApp.substringAfter("fun stopRelay()").substringBefore("if (!hidden) return").contains("stopLaterReplies()"))
        assertTrue(runtime.substringAfter("fun stop(): BackgroundStatus {").substringBefore("\n    }").contains("app.stopLaterReplies()"))
        assertTrue(model.substringAfter("fun setSpeakLaterReplies(on: Boolean)").substringBefore("\n    }").contains("if (!on) app.stopLaterReplies()"))
        assertBefore("wiring()", body(phoneApp, "fun wiring(): Wiring"), "wiring?.core?.orchestrator?.stopFollowing()", "HermesVoiceCore.connect(")
        // Consent: the real Android adapter reads it from the device-local file (excluded from backup and transfer), and
        // nothing reads the backed-up copy an earlier build wrote; that one is switched off at start.
        assertTrue(phoneApp.contains("val localStore by lazy { SharedPreferencesKeyValueStore(prefs(DeviceLocalFlags.PHONE_PREFERENCES)) }"))
        assertTrue(phoneApp.contains("val laterConsent: LaterReplyConsent by lazy { LaterReplyConsent(localStore) }"))
        assertTrue(phoneApp.contains("laterEnabled = { laterConsent.enabled }, ownership = audio,"))
        assertTrue(phoneApp.contains("LaterReplyConsent.dropLegacy(settingsStore)"))
        assertTrue(model.substringAfter("fun setSpeakLaterReplies(on: Boolean)").substringBefore("\n    }").contains("app.laterConsent.enabled = on"))
        assertTrue(model.contains("speakLater = app.laterConsent.enabled,"))
        assertFalse(source("core/src/main/kotlin/com/rumi/hermesvoice/core/settings/AppSettings.kt").contains("speak_later"))
        assertFalse(source("core/src/main/kotlin/com/rumi/hermesvoice/core/HermesVoiceCore.kt").contains("settings.speakLater"))
        val file = "path=\"${DeviceLocalFlags.PHONE_PREFERENCES}.xml\""
        val backup = source("phone/src/main/res/xml/backup_rules.xml")
        val extraction = source("phone/src/main/res/xml/data_extraction_rules.xml")
        assertTrue(backup.contains("<exclude domain=\"sharedpref\" $file />"))
        assertTrue(extraction.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>").contains("<exclude domain=\"sharedpref\" $file />"))
        assertTrue(extraction.substringAfter("<device-transfer>").substringBefore("</device-transfer>").contains("<exclude domain=\"sharedpref\" $file />"))
        val manifest = source("phone/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\"") && manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    @Test
    fun `every Phone capture path claims the microphone and opens it only once a later reply stopped`() {
        val model = source("$phone/PhoneViewModel.kt")
        val runtime = source("$phone/PhoneBackgroundRuntime.kt")
        // Push-to-talk: claim, then the recorder opens only inside the "speaker stopped" callback.
        val ptt = body(model, "fun toggleRecording()")
        assertBefore("push-to-talk", ptt, "app.audio.claimMicrophone(VoiceOrigin.PHONE)", "microphone.whenSpeakerStopped(viewModelScope) { stopped ->")
        assertBefore("push-to-talk", ptt, "microphone.whenSpeakerStopped(viewModelScope) { stopped ->", "runCatching { recorder.start() }")
        assertEquals("the recorder opens in one place only", 1, Regex("recorder\\.start\\(\\)").findAll(model).count())
        // Hands-free (foreground) and background capture: the episode's claim or a new one, then open*() only from the callback.
        val handsFree = body(model, "fun startHandsFree(")
        assertBefore("hands-free", handsFree, "val microphone = held ?: app.audio.claimMicrophone(VoiceOrigin.PHONE)", "PhoneCapture(")
        assertTrue(handsFree.contains("microphone.whenSpeakerStopped(viewModelScope) { stopped -> openHandsFree(id, capture, stopped) }"))
        assertFalse(handsFree.contains("capture.start()"))
        assertTrue(body(model, "private fun openHandsFree(").contains("!capture.start()"))
        assertEquals(1, Regex("capture\\.start\\(\\)").findAll(model).count())
        assertEquals(1, Regex("openHandsFree\\(id, capture, stopped\\)").findAll(model).count())
        val background = body(runtime, "private fun startCapture(")
        assertBefore("background", background, "val microphone = held ?: app.audio.claimMicrophone(VoiceOrigin.PHONE)", "PhoneCapture(")
        assertTrue(background.contains("microphone.whenSpeakerStopped(app.mainScope) { stopped -> openCapture(id, capture, stopped) }"))
        assertFalse(background.contains("capture.start()"))
        assertTrue(body(runtime, "private fun openCapture(").contains("!capture.start()"))
        assertEquals(1, Regex("capture\\.start\\(\\)").findAll(runtime).count())
        // The claim goes with the request (the orchestrator takes it over) and is given back if the turn never ran.
        assertEquals(2, Regex("routing = routing, microphone = microphone\\)").findAll(model).count())
        assertEquals(2, Regex("routing = routing, microphone = microphone\\)").findAll(runtime).count())
        assertTrue(model.contains("app.launchTurn(turnId, phoneOrigin = true, microphone) {"))
        assertTrue(runtime.contains("app.launchTurn(turnId, phoneOrigin = true, microphone) {"))
        assertTrue(source("$phone/PhoneApp.kt").contains("job.invokeOnCompletion { microphone?.release() }"))
        // Every capture that ends unsent gives the claim back.
        assertTrue(model.substringAfter("override fun discard(message: String) {").substringBefore("\n        }").contains("handsFreeMicrophone?.release()"))
        assertTrue(runtime.substringAfter("override fun discard(message: String) {").substringBefore("\n        }").contains("captureMicrophone?.release()"))
        assertTrue(model.substringAfter("override fun onCleared() {").substringBefore("\n    }").contains("pushToTalkMicrophone?.release()"))
    }

    @Test
    fun `an accepted wake phrase owns the microphone from before its cue until its recording or request, on both Phone flows`() {
        val controller = source("core/src/main/kotlin/com/rumi/hermesvoice/core/wake/WakeDevice.kt")
        val handoff = controller.substring(controller.indexOf("private fun onHandoff("), controller.indexOf("private fun beginClaim("))
        assertBefore("onHandoff", handoff, "holdMicrophone()", "port.wakeAccepted()")
        assertBefore("beginClaim", body(controller, "private fun beginClaim()"), "holdMicrophone()", "port.request(")
        for ((file, where) in listOf("$phone/MainActivity.kt" to "foreground", "$phone/PhoneBackgroundRuntime.kt" to "background")) {
            val port = source(file)
            assertTrue(where, port.contains("override fun holdMicrophone(held: Boolean) = episodeMicrophone.hold(held)"))
            assertTrue(where, Regex("startRequestCapture\\(silenceMs: Long, claimId: String\\?\\): Boolean =\\s+hasMic\\(\\) && \\w+\\.?\\w*\\(silenceMs, claimId, episodeMicrophone\\.take\\(\\)\\)")
                .containsMatchIn(port))
            assertTrue(where, port.contains("episodeMicrophone.take()") && Regex("episodeMicrophone\\.take\\(\\)").findAll(port).count() == 2)
        }
    }

    @Test
    fun `a later reply only signals the Phone speaker while it really holds it, and that closes a window but never an accepted episode`() {
        val phoneApp = source("$phone/PhoneApp.kt")
        val model = source("$phone/PhoneViewModel.kt")
        val activity = source("$phone/MainActivity.kt")
        val runtime = source("$phone/PhoneBackgroundRuntime.kt")
        val watchApp = source("$watch/WatchApp.kt")
        assertTrue(phoneApp.contains("laterSpeaker = { device, holding -> if (device == VoiceOrigin.PHONE) phoneSpeakerLater(holding) })"))
        assertTrue(phoneApp.contains("laterScope = appScope, laterWork = ::laterReplyHold,"))
        assertFalse("the CPU hold is not a speaker signal", body(phoneApp, "private suspend fun laterReplyHold(").contains("speakingLater"))
        assertTrue(phoneApp.contains("wakeListening = { device -> device == VoiceOrigin.PHONE && phoneWakeListening() }"))
        assertTrue(model.contains("app.foregroundWakeListening = open"))
        // Foreground: a later reply on the speaker -> onPlaybackBusy (window only); a voice turn -> onBusy.
        assertTrue(activity.contains("WakeBusy.SPEAKER -> phoneWake.wake.onPlaybackBusy()"))
        assertTrue(activity.contains("WakeBusy.VOICE -> phoneWake.wake.onBusy()"))
        assertTrue(activity.contains("phoneWake.wake.onHandoffDue(captureIdle = model.captureIdle())"))
        assertTrue(body(model, "fun voiceIdle()").contains("!app.speakingLater.value"))
        assertFalse(body(model, "fun captureIdle()").contains("speakingLater"))
        assertTrue(body(model, "fun sendRecognizedRequest(").contains("if (!captureIdle())"))
        // Background: the same split.
        assertTrue(runtime.contains("SPEAKER -> background.onPlaybackBusy()") && runtime.contains("BUSY -> background.onBusy()"))
        assertTrue(runtime.contains("talkIdle = !capturing && app.phoneTurns.value == 0 && !app.speakingLater.value"))
        // The Watch guards its own recordings and its request in transit, before anything that's playing is touched.
        assertTrue(watchApp.substringAfter("fun play(request: PlayRequest, nodeId: String) {").substringBefore("stopPlayback(\"superseded\")")
            .contains("LaterPlaybackGuard.refusal(request, recordingNow())"))
        assertTrue(watchApp.substringAfter("fun newTurn(trigger: TurnTrigger): String? {").substringBefore("\n    }")
            .contains("LaterPlaybackGuard.onRecordingStarted(playing)"))
        assertTrue(body(watchApp, "private fun recordingNow()").let { it.contains("_talk.value.phase == WatchPhase.SENDING") &&
            it.contains("voice.wakeEpisodePending") })
        assertTrue(source("$watch/WatchVoiceRuntime.kt").contains("val wakeEpisodePending: Boolean get() = wake.wake.episodePending"))
    }
    @Test
    fun `on device creation has a direct API guard and old foreground fallback without cloud background`() {
        for (path in listOf("$watch/WakeController.kt", "$phone/PhoneWakeController.kt")) {
            val creation = source(path).substringAfter("private fun startRecognizer(").substringBefore("recognizer = created")
            assertTrue(path, creation.contains("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && onDevice)"))
            assertTrue(path, creation.contains("else SpeechRecognizer.createSpeechRecognizer("))
        }
        val background = source("$phone/PhoneBackgroundRuntime.kt").substringAfter("override fun start(generation: Long)").substringBefore("val created =")
        assertTrue(background.contains("if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false"))
        assertFalse(source("$phone/PhoneBackgroundRuntime.kt").contains("createSpeechRecognizer(app"))
        assertTrue(source("watch/build.gradle.kts").contains("implementation(\"androidx.fragment:fragment:1.3.0\")"))
    }

}
