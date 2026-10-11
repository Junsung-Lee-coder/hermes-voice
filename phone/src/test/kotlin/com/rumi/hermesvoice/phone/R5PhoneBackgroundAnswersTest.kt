package com.rumi.hermesvoice.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ServiceCompat
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.voice.UnplayedReplyStore
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator.UnplayedReply
import io.mockk.every
import io.mockk.mockkStatic
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * R5 F-01 / F-02 / F-03 through the production composables and actions: the opt-in tap in Settings and in the not-played banner reaches
 * the real PhoneApp relay (visible-activity guard, foreground service); "asked for" and "admitted by Android" are different states;
 * a refusal or a stop is shown truthfully; no other option changes; the banner passes the stored conversation to the replay and shows an
 * honest reason for a reply that cannot play here. Simulated: Android's service start (the test delivers the start intent), the Data
 * Layer; no audio hardware, so nothing here proves a headset is heard.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class R5PhoneBackgroundAnswersTest : PhoneRoboBase() {
    val rule = createComposeRule()
    @get:Rule val cleanupThenCompose: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(rule)

    override fun pump() { idle(50); rule.waitForIdle() }

    @Before fun setUp() {
        baseSetUp()
        app.askNotificationsOnce()
    }

    @After fun tearDown() {
        runCatching { app.stopRelay() }
        baseTearDown()
    }

    private fun anUnplayed(session: String = "session-1", playable: Boolean = true) =
        UnplayedReply("w1#final", "w1", VoiceOrigin.WATCH, session, playable)

    private fun deliverStart(): org.robolectric.android.controller.ServiceController<PhoneRelayService> {
        val intent = shadowOf(app).nextStartedService
        assertNotNull("the app asked Android to start the relay service", intent)
        assertEquals(PhoneRelayService::class.java.name, intent.component?.className)
        return Robolectric.buildService(PhoneRelayService::class.java, intent).create().startCommand(0, 1)
    }

    private fun settingsContent(model: PhoneViewModel) = rule.setContent {
        val state by model.state.collectAsState()
        MaterialTheme { SettingsTab(state.copy(androidSdk = 35), model, false) }
    }

    @Test fun settingsTapAsksForTheRelayOnlyAndAdmissionIsAReportedSeparately() {
        val model = PhoneViewModel(app)
        model.setUseHeadset(true)
        app.onActivityStarted()
        val settingsBefore = app.settings.watchSettings()
        settingsContent(model)
        rule.onNodeWithTag("headset_background_status", useUnmergedTree = true).performScrollTo().assertTextContains("background relay", substring = true)
        rule.onNodeWithTag("headset_background_action").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue("the relay was asked for", app.relay.status.wanted && app.relay.status.running)
        assertFalse("asked for is not admitted", app.relayForeground.value)
        assertFalse("no microphone", app.relay.status.microphone)
        assertEquals("no wake, listen-on or other option changed", settingsBefore, app.settings.watchSettings())
        rule.onNodeWithTag("headset_background_status").assertTextContains("waiting for Android", substring = true)
        rule.onNodeWithTag("headset_background_action").assertDoesNotExist()

        val service = deliverStart()
        assertTrue("Android admitted the foreground service", await { app.relayForeground.value })
        rule.waitForIdle()
        rule.onNodeWithTag("headset_background_status").assertTextContains("Not a guarantee", substring = true)
        rule.onNodeWithTag("headset_background_action").assertDoesNotExist()

        service.destroy()
        assertTrue("an ended service no longer counts", await { !app.relayForeground.value })
        rule.waitForIdle()
        rule.onNodeWithTag("headset_background_action").assertExists()
    }

    @Test fun aTapWhileTheAppIsNotVisibleIsRefusedStartsNothingAndOffersTryAgain() {
        val model = PhoneViewModel(app)
        model.setUseHeadset(true)
        app.onActivityStopped()
        settingsContent(model)
        rule.onNodeWithTag("headset_background_action").performScrollTo().performClick()
        rule.waitForIdle()
        assertNull("no service start was requested from a hidden app", shadowOf(app).nextStartedService)
        assertFalse(app.relayForeground.value)
        assertFalse(app.relay.status.running)
        rule.onNodeWithTag("headset_background_status").assertTextContains("try again", substring = true, ignoreCase = true)
        rule.onNodeWithTag("headset_background_action").assertExists()
    }

    @Test fun aPlatformRefusalOfTheForegroundServiceIsShownAndNeverCountedAsAdmitted() {
        val model = PhoneViewModel(app)
        model.setUseHeadset(true)
        app.onActivityStarted()
        mockkStatic(ServiceCompat::class)
        every { ServiceCompat.startForeground(any(), any(), any(), any()) } throws SecurityException("not allowed")
        settingsContent(model)
        rule.onNodeWithTag("headset_background_action").performScrollTo().performClick()
        rule.waitForIdle()
        deliverStart()
        settle()
        rule.waitForIdle()
        assertFalse(app.relayForeground.value)
        assertFalse(PhoneRelayService.foregroundActive)
        rule.onNodeWithTag("headset_background_action").assertExists()
        rule.onNodeWithTag("headset_background_status").assertTextContains("Android", substring = true)
    }

    @Test fun theBannerOffersTheSameOptInForTheUnplayedReplyAndItIsTheOnlyThingItTurnsOn() {
        val model = PhoneViewModel(app)
        model.setUseHeadset(true)
        app.onActivityStarted()
        val settingsBefore = app.settings.watchSettings()
        val state = PhoneUiState(signedIn = true, useHeadset = true, androidSdk = 35, unplayed = listOf(anUnplayed()))
        rule.setContent {
            val request = rememberBackgroundRelayRequest(model)
            MaterialTheme { TalkBar(state, onBackgroundAnswers = request) {} }
        }
        rule.onNodeWithTag("unplayed_background_status").assertExists()
        rule.onNodeWithTag("unplayed_background_action").performClick()
        rule.waitForIdle()
        assertTrue(app.relay.status.running)
        assertFalse(app.relayForeground.value)
        assertEquals(settingsBefore, app.settings.watchSettings())
    }

    @Test fun theBannerShowsNoSetupBeforeAndroid15AndNoneOnceAdmitted() {
        val model = PhoneViewModel(app)
        val before35 = PhoneUiState(signedIn = true, useHeadset = true, androidSdk = 34, unplayed = listOf(anUnplayed()))
        val shown = androidx.compose.runtime.mutableStateOf(before35)
        rule.setContent { MaterialTheme { TalkBar(shown.value, onBackgroundAnswers = {}) {} } }
        rule.onNodeWithTag("unplayed_0").assertExists()
        rule.onNodeWithTag("unplayed_background_status").assertDoesNotExist()
        rule.onNodeWithTag("unplayed_background_action").assertDoesNotExist()
        shown.value = before35.copy(androidSdk = 35)
        rule.waitForIdle()
        rule.onNodeWithTag("unplayed_background_action").assertExists()
        shown.value = before35.copy(androidSdk = 35, relayForeground = true)
        rule.waitForIdle()
        rule.onNodeWithTag("unplayed_background_status").assertDoesNotExist()
        rule.onNodeWithTag("unplayed_background_action").assertDoesNotExist()
        assertFalse("rendering alone never asks for the relay", app.relay.status.wanted)
        assertNotNull(model)
    }

    @Test fun aReplyThatCannotPlayHereShowsAnHonestReasonInsteadOfAPlayButton() {
        val state = PhoneUiState(signedIn = true, unplayed = listOf(anUnplayed(playable = false)))
        rule.setContent { MaterialTheme { TalkBar(state) {} } }
        rule.onNodeWithTag("unplayed_unavailable_0").assertExists()
        rule.onNodeWithTag("unplayed_play_0").assertDoesNotExist()
    }

    @Test fun thePlayAgainTapHandsTheWholeReplyWithItsStoredConversationToTheAction() {
        val tapped = mutableListOf<UnplayedReply>()
        val reply = anUnplayed(session = "stored-conversation")
        val state = PhoneUiState(signedIn = true, unplayed = listOf(reply))
        rule.setContent { MaterialTheme { TalkBar(state, onPlayAgain = { tapped += it }) {} } }
        rule.onNodeWithTag("unplayed_play_0").performClick()
        assertEquals(listOf(reply), tapped)
    }

    private fun storeKey(): String {
        val endpoint = HermesDashboardEndpoint.parse(app.settings.dashboardUrl)
        val digest = MessageDigest.getInstance("SHA-256").digest("$endpoint|${app.settings.profile}".toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        return UnplayedReplyStore.KEY + "_" + digest
    }

    private fun seedPrivateWatchReply(): String {
        val prefs = app.getSharedPreferences(UnplayedReplyStore.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        val identity = com.rumi.hermesvoice.core.notify.ReplyAlert.identityOf("turn-s", "final")
        UnplayedReplyStore(SharedPreferencesKeyValueStore(prefs), key = storeKey()).put(
            UnplayedReplyStore.Entry(identity, "turn-s", VoiceOrigin.WATCH, "watch-node", "stored-conversation", true, "Stored private answer.", System.currentTimeMillis()))
        return identity
    }

    @Test fun playAgainUsesTheStoredConversationNotTheOneOnScreen() {
        val identity = seedPrivateWatchReply()
        app.settings.useHeadset = true
        val onScreen = conversation("onscreen")
        val model = open(onScreen)
        assertTrue(await { app.unplayedReplies.value.any { it.identity == identity } })
        val reply = app.unplayedReplies.value.first { it.identity == identity }
        assertEquals("the banner metadata carries the stored conversation", "stored-conversation", reply.storedSessionId)
        assertTrue(reply.playableHere)

        model.playAgain(reply.copy(storedSessionId = "some-other-conversation"))
        assertTrue(await { model.state.value.voiceStatus == "That reply can't be played again now" })
        assertTrue("a refused replay keeps the reply", app.unplayedReplies.value.any { it.identity == identity })

        model.playAgain(reply)
        assertTrue("accepted and finished once, with no headset to hear it", await(30_000) { model.state.value.voiceStatus.startsWith("Reply still not played") })
        assertTrue("the same reply is kept", app.unplayedReplies.value.any { it.identity == identity })
        assertTrue("nothing was sent to Hermes", fake.rawPrompts.isEmpty())
    }
}
