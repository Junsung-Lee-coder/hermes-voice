package com.rumi.hermesvoice.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Secondary source gates: the behaviour itself is tested on the pure cores the Android adapters
 * delegate to (see WatchAdaptersTest). These only check that the adapters still delegate there and
 * keep platform wiring the JVM cannot run (theme flags, manifest, focus order, contexts).
 */
class AndroidWiringGateTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(path: String) = File(root, path).readText()

    private val watch = "watch/src/main/kotlin/com/rumi/hermesvoice/watch"
    private val phone = "phone/src/main/kotlin/com/rumi/hermesvoice/phone"

    @Test
    fun `right swipe backgrounds the task and the system swipe-to-dismiss cannot finish it instead`() {
        val activity = source("$watch/WatchActivity.kt")
        val background = activity.substringAfter("ReaderAction.BACKGROUND_APP ->").substringBefore("}")
        assertTrue(background.contains("moveTaskToBack(true)"))
        assertFalse(background.contains("finish"))
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
        val talkPressed = activity.substringAfter("private fun onTalkPressed()").substringBefore("// ── capture")
        assertFalse("no haptic on button intent", talkPressed.contains("haptic"))
        assertTrue(activity.contains("CaptureCoordinator(capturePort)"))
        assertTrue(activity.substringAfter("override fun onLive()").substringBefore("override fun onCalibrated()").contains("captures.onLive(turnId)"))
        assertTrue(activity.substringAfter("override fun onCalibrated()").substringBefore("override fun onEnd(").contains("captures.onCalibrated(turnId)"))
        assertTrue(activity.substringAfter("override fun onPause()").substringBefore("super.onPause()").contains("CaptureStop.LIFECYCLE"))
        assertTrue("handoff is cancellable and generation-bound (WakeDeviceController)",
            activity.contains("wake.wake.onHandoffDue(captureIdle = captures.activeId == null)") && activity.contains("removeCallbacks(handoffRunnable)"))
        assertFalse("no duration cap", activity.contains("maxTurnSeconds") || source("$watch/WatchCapture.kt").contains("limitFor"))
        val capture = source("$watch/WatchCapture.kt")
        assertTrue("start is confirmed by the recorder state", capture.contains("recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING"))
        assertTrue("the shared recording loop", capture.contains("PcmCaptureLoop({ recorder.read(it, 0, it.size) }, limitBytes, endpoint, listener"))
        assertTrue("the silence snapshot the wake flow passed in",
            activity.contains("SilenceEndpoint(sampleRate = WatchCapture.SAMPLE_RATE, silenceMs = silenceMs)") &&
                activity.contains("startCapture(TurnTrigger.WAKE_PHRASE, silenceMs)"))
        assertTrue(activity.contains("end(turnId, CaptureStop.of(reason))"))
        assertFalse("no buzz on phone stage updates or timers", source("$watch/WatchApp.kt").contains("fun buzz("))
        val app = source("$watch/WatchApp.kt")
        assertTrue("usage comes from the tested policy", app.contains("WatchHapticPolicy.usageFor(event)") &&
            app.contains("VibrationAttributes.USAGE_HARDWARE_FEEDBACK") && app.contains("VibrationAttributes.USAGE_TOUCH"))
        assertFalse("never bypasses Do Not Disturb or user settings", app.contains("FLAG_BYPASS"))
        assertFalse("the end reason is only the accepted stop's", activity.contains("lastStop"))
    }

    @Test
    fun `both wake adapters delegate to the shared device controller, foreground only`() {
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
            assertFalse("receiver never touches the microphone", wake.substringAfter("override fun onReceive").substringBefore("override fun onStart")
                .contains("startListening"))
            assertFalse(wake.contains("EXTRA_PREFER_OFFLINE"))
            assertTrue("pause closes the window", wake.contains("override fun onPause(owner: LifecycleOwner) = wake.onPause()"))
            assertTrue(wake.contains("wake.onResults(gen, heard, final)"))
        }
        assertTrue("the Watch waits for its synced settings", source("$watch/WakeController.kt").contains("wake.onResume(settingsPending = true)"))
        val watchActivity = source("$watch/WatchActivity.kt")
        assertTrue(watchActivity.contains("app.settings.collect { wake.wake.onSettings(it) }") && watchActivity.contains("wake.wake.onSettingsCurrent()"))
        val phoneActivity = source("$phone/MainActivity.kt")
        assertTrue(phoneActivity.contains("collect { phoneWake.wake.onSettings(it) }"))
        assertTrue("the Phone recorder gets the snapshot", phoneActivity.contains("model.startHandsFree(silenceMs, claimId)"))
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
        assertTrue(phoneActivity.contains("model.startHandsFree(silenceMs, claimId)") && phoneActivity.contains("model.sendRecognizedRequest(request, claimId)"))
        assertTrue(phoneActivity.contains("admission()?.claim(WakeClaim(claimId, VoiceOrigin.PHONE, \"\", settingsRevision, generation, epoch))"))
        val model = source("$phone/PhoneViewModel.kt")
        assertTrue(model.contains("submitVoice(wav, wakeTurn = true, wakeClaimId = claimId)") && model.contains("wakeTurn = true, wakeClaimId = claimId)"))
        assertTrue("a busy phone says so instead of dropping the request", model.substringAfter("fun sendRecognizedRequest").substringBefore("recognizedClaimId = claimId")
            .contains("return onWakeClosed(\"unfinished_request\")"))
        val watchActivity = source("$watch/WatchActivity.kt")
        assertTrue(watchActivity.contains("app.upload(captureId, trigger, wav, claimId)") && watchActivity.contains("app.uploadRecognized(turnId, text, recognizedClaimId)"))
        assertTrue(watchActivity.contains("wake.wake.onRequestCaptureEnded(sent = true)") && watchActivity.contains("wake.wake.onRequestCaptureEnded(sent = false)"))
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
        assertTrue(watchActivity.contains("override fun epoch(): Long = app.wakeEpoch") &&
            watchActivity.contains("app.sendWakeClaim(WakeClaimMessage(WakeClaimMessage.Op.CLAIM, claimId, settingsRevision, generation, epoch))") &&
            watchActivity.contains("app.wakeEpochListener = { epoch, claimId -> wake.wake.onEpisodeAnswered(epoch, claimId) }"))
        val resume = watchActivity.substringAfter("override fun onResume()").substringBefore("override fun onDestroy()")
        assertTrue("the count is read before the Watch may listen",
            watchActivity.contains("pullItem(WatchLinkPaths.WAKE_EPOCH, app::applyWakeEpoch)") &&
                resume.indexOf("pullSettings()") in 0 until resume.indexOf("wake.wake.onSettingsCurrent()"))
        assertTrue("the wake location changing mid-request is said on both devices",
            watchActivity.contains("\"wake_mode_changed\" ->") && source("$phone/PhoneViewModel.kt").contains("\"wake_mode_changed\" ->"))
    }

    @Test
    fun `talk tapped in an open wake window releases the recognizer and waits the handoff pause on both devices`() {
        val phoneActivity = source("$phone/MainActivity.kt")
        val phoneTalk = phoneActivity.substringAfter("private fun onTalk()").substringBefore("override fun onCreate")
        assertTrue(phoneTalk.contains("phoneWake.wake.onBusy()") && phoneTalk.contains("postDelayed(talkAfterRelease, WakeContract.MIC_HANDOFF_MS)"))
        assertTrue(phoneActivity.contains("PhoneScreen(model, recognizerAvailable, onTalk = ::onTalk)"))
        val watchTalk = source("$watch/WatchActivity.kt").substringAfter("private fun onTalkPressed()").substringBefore("// ── capture")
        assertTrue(watchTalk.contains("wake.wake.onBusy()") && watchTalk.contains("postDelayed(talkAfterRelease, WakeContract.MIC_HANDOFF_MS)"))
        // Leaving the screen (rotation included) cancels a hands-free recording unsent: the documented lifecycle contract.
        assertTrue(source("$phone/PhoneWakeController.kt").contains("override fun onPause(owner: LifecycleOwner) = wake.onPause()"))
    }

    @Test
    fun `no new permission or background component, and the recognizer is visible on Android 11+`() {
        for (path in listOf("phone/src/main/AndroidManifest.xml", "watch/src/main/AndroidManifest.xml")) {
            val manifest = source(path)
            assertTrue(path, manifest.contains("<action android:name=\"android.speech.RecognitionService\" />"))
            assertFalse(path, manifest.contains("FOREGROUND_SERVICE") || manifest.contains("RECEIVE_BOOT_COMPLETED") || manifest.contains("WAKE_LOCK"))
        }
        val phonePermissions = Regex("uses-permission android:name=\"([^\"]+)\"").findAll(source("phone/src/main/AndroidManifest.xml"))
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("android.permission.INTERNET", "android.permission.RECORD_AUDIO"), phonePermissions)
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
}
