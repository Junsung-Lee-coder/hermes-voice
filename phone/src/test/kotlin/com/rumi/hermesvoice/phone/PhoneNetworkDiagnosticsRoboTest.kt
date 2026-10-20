package com.rumi.hermesvoice.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import com.rumi.hermesvoice.core.diag.DiagCause
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagConn
import com.rumi.hermesvoice.core.diag.DiagExportResult
import com.rumi.hermesvoice.core.diag.DiagModality
import com.rumi.hermesvoice.core.diag.DiagOrigin
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.diag.DiagTransport
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). Network diagnostics through the REAL PhoneApp wiring, PhoneViewModel, ChatService, the journal
 * and the exporter against the Hermes dashboard contract double. Simulated: the Data Layer and the platform connectivity service.
 * No device, no real network, no share-sheet recipient.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneNetworkDiagnosticsRoboTest : PhoneRoboBase() {
    @Before fun setUp() = baseSetUp()
    @After fun tearDown() = baseTearDown()

    private val log get() = app.diagnostics.log
    private fun events(code: DiagCode) = log.snapshot().filter { it.code == code }
    private fun journalFiles(): List<File> = File(app.noBackupFilesDir, "diag-journal").listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty()

    // The journal thread replaces the file atomically; on Windows a reader can briefly collide with that replace, so read until it settles.
    private fun readSettled(file: File): String {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            try { return file.readText() } catch (e: java.io.IOException) {
                if (System.nanoTime() > deadline) throw e
                Thread.sleep(20)
            }
        }
    }

    private fun exportJson(): JSONObject {
        val ready = runBlocking { app.diagnostics.export() } as DiagExportResult.Ready
        return JSONObject(ready.file.readText())
    }

    @Test fun anAttachmentThatCannotBeReadIsAPreparationEventAndTheScreenSaysSoInFixedWords() {
        val work = conversation("work")
        val model = open(work)
        model.addAttachment(Uri.parse("content://com.example.missing.provider/SECRET-photo-name.jpg"))
        assertTrue(await { events(DiagCode.REQUEST_FAILED).isNotEmpty() })
        val event = events(DiagCode.REQUEST_FAILED).single()
        assertEquals(DiagStage.PREPARE, event.stage)
        assertEquals(DiagModality.ATTACHMENT, event.modality)
        assertEquals(DiagOrigin.PHONE, event.origin)
        assertEquals(DiagCause.LOCAL, event.cause)
        assertTrue(await { model.state.value.status == "Couldn't read that file" })
        assertTrue("nothing was uploaded or sent", prompts().isEmpty())
        assertTrue(model.state.value.attachments.isEmpty())
        val text = exportJson().toString()
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("example.missing"))
    }

    @Test fun anEmptyFileIsRefusedBeforeAnyUploadWithAFixedMessage() {
        val work = conversation("work")
        val model = open(work)
        val empty = File(app.cacheDir, "SECRET-empty.txt").apply { writeText("") }
        model.addAttachment(Uri.fromFile(empty))
        assertTrue(await { model.state.value.status == "That file is empty, unreadable or too large to attach" })
        assertEquals(DiagStage.PREPARE, events(DiagCode.REQUEST_FAILED).single().stage)
        assertTrue(prompts().isEmpty())
    }

    @Test fun aRejectionByHermesIsLabelledNotAcceptedAndNeverNeverSent_andARetryIsAnotherAttempt() {
        val work = conversation("work")
        fake.turnBehavior = { "session_limit" }
        val model = open(work)
        model.setDraft("same words")
        model.send()
        assertTrue(await { model.state.value.sendsInFlight == 0 && events(DiagCode.REQUEST_FAILED).size == 1 })
        val firstId = app.textSends.ids.get()
        assertTrue(model.state.value.failedSends.isEmpty())
        assertTrue(model.state.value.pendingSends.isEmpty())
        assertEquals("Acceptance uncertain; check the conversation. Do not retry this request", model.state.value.status)
        assertEquals("one submission only", listOf("same words"), prompts())
        settle()
        assertEquals("no automatic resubmission", listOf("same words"), prompts())

        model.retryFailedSend(firstId)
        settle()
        assertEquals("unproved rejection grants no same-send Retry", listOf("same words"), prompts())
        model.setDraft("same words"); model.send() // independently authored NEW send, not Retry
        assertTrue(await { prompts().size == 2 && model.state.value.sendsInFlight == 0 && events(DiagCode.REQUEST_FAILED).size == 2 })
        assertTrue(model.state.value.failedSends.isEmpty())
        assertEquals("Acceptance uncertain; check the conversation. Do not retry this request", model.state.value.status)
        assertEquals(listOf("same words", "same words"), prompts())
        val failed = events(DiagCode.REQUEST_FAILED)
        assertEquals(2, failed.size)
        assertEquals("two attempts are two correlations", 2, failed.map { it.ref }.toSet().size)
        assertTrue(failed.all { it.ref != null && it.cause == DiagCause.REJECTED && it.stage == DiagStage.SUBMIT })
        assertTrue(failed.all { it.modality == DiagModality.TEXT })
        assertTrue("rejection is never presented as acceptance", events(DiagCode.REQUEST_ACCEPTED).isEmpty())
    }

    @Test fun aSendWhileTheSystemReportsNoNetworkStillReachesHermes_connectivityIsContextOnly() {
        val work = conversation("work")
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(manager).setActiveNetworkInfo(null)
        assertEquals(DiagConn.NONE, PhoneNetworkSnapshot.conn(app))
        val model = open(work)
        model.setDraft("offline but local")
        model.send()
        assertTrue("the send is never blocked by the snapshot", await { prompts() == listOf("offline but local") })
        assertTrue(model.state.value.failedSends.isEmpty())
    }

    @Test fun capabilityFlagsMapToBoundedCategoriesOnly() {
        val validated = PhoneNetworkSnapshot.map(hasInternet = true, validated = true, transports = setOf(DiagTransport.WIFI))
        assertEquals(DiagConn.VALIDATED, validated.conn)
        assertEquals(setOf(DiagTransport.WIFI), validated.transports)
        assertEquals(DiagConn.UNVALIDATED, PhoneNetworkSnapshot.map(true, false, setOf(DiagTransport.VPN)).conn)
        assertEquals(DiagConn.NONE, PhoneNetworkSnapshot.map(false, false, emptySet()).conn)
        assertEquals(setOf(DiagTransport.OTHER), PhoneNetworkSnapshot.map(true, true, emptySet()).transports)
    }

    @Test fun theExportCarriesVersionIdentityCoverageAndNoSecrets() {
        val work = conversation("work")
        fake.turnBehavior = { "not_owned" }
        val model = open(work)
        model.setDraft("SECRET-user-words")
        model.send()
        assertTrue(await { model.state.value.failedSends.size == 1 })
        val json = exportJson()
        assertEquals("hermes-voice-diagnostics", json.getString("kind"))
        val appJson = json.getJSONObject("app")
        assertTrue(appJson.getString("version_name").isNotEmpty())
        assertTrue(appJson.getInt("version_code") > 0)
        val status = appJson.getString("build_status")
        assertTrue(status in setOf("available", "unavailable", "unreadable"))
        if (status == "available") assertTrue(appJson.getString("build_sha256").matches(Regex("^[0-9a-f]{64}$"))) else assertFalse(appJson.has("build_sha256"))
        assertTrue(json.has("exported_utc"))
        val phone = json.getJSONObject("phone")
        val coverage = phone.getJSONObject("coverage")
        assertTrue(coverage.getInt("retained") >= 1)
        assertEquals(256, coverage.getInt("capacity"))
        assertEquals(24, coverage.getInt("retention_hours"))
        assertTrue(coverage.has("capacity_dropped") && coverage.has("retention_pruned") && coverage.has("persistence"))
        assertTrue(phone.getJSONObject("network").has("state"))
        val text = json.toString()
        for (forbidden in listOf("SECRET-user-words", work, fake.accessToken, fake.refreshToken, fake.baseUrl, app.applicationInfo.sourceDir, app.cacheDir.path, "Bearer", "127.0.0.1", "localhost")) {
            assertFalse("export must not contain $forbidden", text.contains(forbidden))
        }
    }

    @Test fun theJournalIsAppPrivateOutsideTheShareFoldersAndHoldsNoPlaintextIds() {
        val work = conversation("work")
        fake.turnBehavior = { "not_owned" }
        val model = open(work)
        model.setDraft("SECRET-journal-words")
        model.send()
        assertTrue(await { model.state.value.failedSends.size == 1 })
        app.diagnostics.flush()
        assertTrue("the failure reaches the journal file", await { journalFiles().isNotEmpty() })
        val dir = File(app.noBackupFilesDir, "diag-journal").canonicalPath
        assertFalse(dir.startsWith(app.cacheDir.canonicalPath))
        val text = journalFiles().joinToString { readSettled(it) }
        for (forbidden in listOf("SECRET-journal-words", work, fake.accessToken, fake.baseUrl, "Bearer")) assertFalse(forbidden, text.contains(forbidden))
        assertTrue(journalFiles().sumOf { it.length() } <= 96 * 1024)
    }

    @Test fun aFlushedLogComesBackWhenTheProcessIsRecreated() {
        val work = conversation("work")
        fake.turnBehavior = { "not_owned" }
        val model = open(work)
        model.setDraft("persist me")
        model.send()
        assertTrue(await { model.state.value.failedSends.size == 1 })
        app.diagnostics.flush()
        assertTrue(await { journalFiles().isNotEmpty() })
        Thread.sleep(200)
        val before = events(DiagCode.REQUEST_FAILED).size
        val reborn = PhoneDiagnostics(app)
        assertTrue("restore happens off the main thread", await { reborn.log.snapshot().count { it.code == DiagCode.REQUEST_FAILED } >= before })
        assertTrue(reborn.log.coverage().restored >= 1)
        val restored = reborn.log.snapshot().first { it.code == DiagCode.REQUEST_FAILED }
        assertNotNull(restored.ref)
        assertTrue(restored.opaque)
    }

    @Test fun clearingThisPhonesLogRemovesOnlyDiagnostics() {
        val work = conversation("work")
        fake.turnBehavior = { "not_owned" }
        val model = open(work)
        model.setDraft("keep my draft state")
        model.send()
        assertTrue(await { model.state.value.failedSends.size == 1 })
        app.diagnostics.flush()
        assertTrue(await { journalFiles().isNotEmpty() })
        val dashboard = app.settings.dashboardUrl
        assertTrue(runBlocking { app.diagnostics.clearLocalLog() })
        assertTrue(log.snapshot().isEmpty())
        val reborn = com.rumi.hermesvoice.core.diag.DiagLog(clock = { 10L })
        com.rumi.hermesvoice.core.diag.DiagJournal(reborn, com.rumi.hermesvoice.core.diag.FileDiagJournalStorage(File(app.noBackupFilesDir, "diag-journal")), java.util.concurrent.Executor { it.run() }).attach()
        assertTrue("nothing of the cleared log can be restored (a waiting flush may leave an empty journal, never the old events)", reborn.snapshot().isEmpty())
        assertEquals("settings are untouched", dashboard, app.settings.dashboardUrl)
        assertEquals("the conversation and its retry state are untouched", 1, model.state.value.failedSends.size)
        assertEquals(work, model.state.value.selected?.storedSessionId)
        assertTrue("the next event is recorded normally", run {
            log.record(DiagCode.STOP_ALL, DiagOrigin.PHONE)
            log.snapshot().size == 1
        })
    }

    @Test fun recordingWhileClearingFromOtherThreadsNeitherThrowsNorLosesTheLogItself() {
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(3) { worker ->
            pool.execute {
                try {
                    start.await()
                    repeat(300) { log.record(DiagCode.STOP_ALL, DiagOrigin.PHONE); if (it % 50 == 0) app.diagnostics.flush() }
                } catch (t: Throwable) { errors += t }
            }
        }
        pool.execute {
            try { start.await(); repeat(10) { runBlocking { app.diagnostics.clearLocalLog() } } } catch (t: Throwable) { errors += t }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        assertTrue(errors.joinToString { it.toString() }, errors.isEmpty())
        assertTrue(log.snapshot().size <= 256)
    }

    @Test fun theShareIntentGrantsOnlyTheOneReportFileReadOnly() {
        val ready = runBlocking { app.diagnostics.export() } as DiagExportResult.Ready
        val intent = app.diagnostics.shareIntent(app, ready.file) { f -> android.net.Uri.parse("content://${app.packageName}.diagshare/diag/${f.name}") }
        assertNotNull(intent)
        assertTrue(intent!!.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val inner = intent.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals("application/json", inner.type)
        assertNull("a journal file is never shareable", app.diagnostics.shareIntent(app, journalFiles().firstOrNull() ?: File(app.noBackupFilesDir, "diag-journal/x")) { android.net.Uri.parse("content://x/y") })
        assertNotEquals(0, ready.file.length())
    }

    private fun openWatchChannel(inputFailure: Exception, turnId: String) {
        val channels = io.mockk.mockk<com.google.android.gms.wearable.ChannelClient>()
        io.mockk.every { channels.getInputStream(any()) } returns com.google.android.gms.tasks.Tasks.forException(inputFailure)
        io.mockk.every { channels.close(any()) } returns com.google.android.gms.tasks.Tasks.forResult<Void>(null)
        io.mockk.every { com.google.android.gms.wearable.Wearable.getChannelClient(any<Context>()) } returns channels
        val channel = io.mockk.mockk<com.google.android.gms.wearable.ChannelClient.Channel>()
        io.mockk.every { channel.path } returns com.rumi.hermesvoice.core.watchlink.WatchLinkPaths.turnPath(turnId)
        io.mockk.every { channel.nodeId } returns "watch-node-SECRET"
        org.robolectric.Robolectric.setupService(PhoneWatchListenerService::class.java).onChannelOpened(channel)
    }

    @Test fun aWatchRecordingThatCannotBeReadFromTheChannelIsAWatchVoiceTransferFailureWithoutTheErrorText() {
        openWatchChannel(java.io.IOException("SECRET-channel-failure 10.0.0.9"), "turn-" + "a".repeat(12))
        assertTrue(await { events(DiagCode.REQUEST_FAILED).isNotEmpty() })
        val event = events(DiagCode.REQUEST_FAILED).first()
        assertEquals(DiagOrigin.WATCH, event.origin)
        assertEquals(DiagStage.TRANSFER, event.stage)
        assertEquals(DiagModality.VOICE, event.modality)
        assertEquals(DiagCause.DISCONNECT, event.cause)
        val text = exportJson().toString()
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("10.0.0.9"))
        assertFalse(text.contains("watch-node"))
    }

    @Test fun aCancelledWatchChannelReadIsNotRecordedAsAFailure() {
        openWatchChannel(kotlinx.coroutines.CancellationException("stopped"), "turn-" + "b".repeat(12))
        settle()
        assertTrue(events(DiagCode.REQUEST_FAILED).isEmpty())
    }
}
