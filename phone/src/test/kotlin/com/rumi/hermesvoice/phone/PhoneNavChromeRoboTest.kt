package com.rumi.hermesvoice.phone

import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.voice.AssembledRoute
import com.rumi.hermesvoice.core.voice.DestinationEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The Phone frame without bottom tabs: the single Talk control at the bottom, the plus and gear
 * at the top right, Back from Chat and Settings to Conversations, and Back from Conversations. Real MainActivity, PhoneScreen,
 * PhoneChrome, TalkBar and PhoneViewModel. Simulated: window insets (dispatched to the decor view), the permission launcher
 * (Robolectric records the request), and touch input. Whether Android actually moves the task to the background cannot be observed here.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneNavChromeRoboTest : PhoneNavRoboBase() {

    private fun textNodes(exact: String) = rule.onAllNodes(SemanticsMatcher("text == $exact") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == exact } == true
    }).fetchSemanticsNodes().size

    private fun conversationsShown() = exists("new_conversation_button") && !exists("chat_list")

    private fun assertActionGeometry() {
        waitFor("actions are shown") { exists("settings_button") && exists("new_conversation_button") }
        val root = rootBounds()
        val plus = bounds("new_conversation_button")
        val gear = bounds("settings_button")
        for ((name, box) in listOf("plus" to plus, "gear" to gear)) {
            assertTrue("$name is at least 48dp wide: ${box.width}", box.width.value >= 47.9f)
            assertTrue("$name is at least 48dp tall: ${box.height}", box.height.value >= 47.9f)
            assertTrue("$name stays on screen", box.left.value >= 0f && box.right.value <= root.right.value + 0.5f)
            assertTrue("$name is in the top bar", box.top.value >= 0f && box.bottom.value <= 96f)
        }
        assertTrue("the plus is immediately left of the gear: ${plus.right} <= ${gear.left}", plus.right.value <= gear.left.value + 0.5f)
        assertTrue("the plus touches the gear's side, nothing between", gear.left.value - plus.right.value <= 8f)
        assertTrue("the gear is the far right: ${root.right.value - gear.right.value}", root.right.value - gear.right.value <= 16.5f)
        assertEqualsDp("the two share a centre line", plus.top.value + plus.height.value / 2, gear.top.value + gear.height.value / 2, 1f)
        rule.onNodeWithTag("settings_button").assertContentDescriptionEquals("Settings").assertHasClickAction()
        rule.onNodeWithTag("new_conversation_button").assertContentDescriptionEquals("New conversation").assertHasClickAction()
    }

    private fun navInset(dp: Int) {
        val activity = controller!!.get()
        val px = (dp * activity.resources.displayMetrics.density).toInt()
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px)).build()
        ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
        settle()
    }

    @Test fun N01_thereAreNoBottomTabsAndTheBottomHoldsOnlyTheTalkControl() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        for (tag in listOf("tab_conversations", "tab_chat", "tab_settings")) assertEquals("$tag is gone", 0, count(tag))
        for (label in listOf("Conversations", "Chat", "Settings")) assertEquals("no '$label' tab label", 0, textNodes(label))
        assertEquals("one Talk control", 1, count("talk"))
        assertEquals("one bottom bar", 1, count("bottom_bar"))
        val root = rootBounds()
        val bar = bounds("bottom_bar")
        assertTrue("the bar reaches the bottom edge: ${bar.bottom} vs ${root.bottom}", root.bottom.value - bar.bottom.value <= 1f)
        assertTrue("no tab-height spacer under Talk: ${root.bottom.value - bounds("talk").bottom.value}", root.bottom.value - bounds("talk").bottom.value <= 16f)
        assertTrue("the bar is only the Talk control: ${bar.height}", bar.height.value <= 72f)
    }

    @Test fun N02_plusAndGearSitAtTheTopRightInOrder() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        assertActionGeometry()
    }

    @Config(qualifiers = "w320dp-h1280dp")
    @Test fun N03_plusAndGearStayRightAlignedOnANarrowScreen() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        assertActionGeometry()
    }

    @Config(qualifiers = "w320dp-h1280dp")
    @Test fun N04_plusAndGearKeepTheirSizeAndPlaceWithADoubledFontScale() {
        RuntimeEnvironment.setFontScale(2.0f)
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        assertActionGeometry()
    }

    @Test fun N05_theGearOpensSettingsAndBackReturnsToTheList() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        rule.onNodeWithTag("settings_button").performClick()
        waitFor("settings are shown") { textNodes("Appearance") > 0 }
        assertTrue("a back arrow is offered", exists("back_button"))
        rule.onNodeWithTag("back_button").assertContentDescriptionEquals("Back").assertHasClickAction()
        assertFalse("the plus belongs to the list", exists("new_conversation_button"))
        assertNull("nothing was opened", model.state.value.selected)
        rule.onNodeWithTag("back_button").performClick()
        waitFor("the list is back") { conversationsShown() && exists("conversation_row_$alpha") }
        assertFalse(controller!!.get().isFinishing)
    }

    @Test fun N06_signedOutTheGearStillReachesTheConnectionSetupAndThereIsNoPlus() {
        val model = showing()
        model.signOut()
        waitFor("the sign-in screen is shown") { textNodes("Sign in to Hermes") > 0 }
        assertTrue("the gear stays reachable", exists("settings_button"))
        assertFalse("nothing to create while signed out", exists("new_conversation_button"))
        rule.onNodeWithTag("settings_button").performClick()
        waitFor("the connection setup is shown") { textNodes("Hermes dashboard") > 0 }
        rule.onNodeWithTag("back_button").performClick()
        waitFor("sign-in is back") { textNodes("Sign in to Hermes") > 0 }
    }

    @Test fun N07_theBackArrowReturnsFromChatKeepingTheSessionHistoryAndDraft() {
        val alpha = titled("Synthetic alpha", "alpha")
        seed(alpha, 6, "keep")
        val model = showWith(alpha)
        openCard(model, alpha)
        waitFor("history is shown") { exists("message_6") }
        model.setDraft("an unsent draft")
        val before = model.state.value
        val restBefore = fake.restLog.toList().count { it.startsWith("PATCH ") }
        val promptsBefore = prompts().size
        assertTrue("a back arrow is offered", exists("back_button"))
        rule.onNodeWithTag("back_button").performClick()
        waitFor("the list is back") { conversationsShown() && exists("conversation_row_$alpha") }
        val after = model.state.value
        assertEquals("the selected session is kept", before.selected, after.selected)
        assertEquals("the history is kept", before.history, after.history)
        assertEquals("the draft is kept", "an unsent draft", after.draft)
        assertEquals("routing is untouched", before.routingEnabled, after.routingEnabled)
        assertEquals("the playback destination is untouched", before.playbackDevice, after.playbackDevice)
        assertEquals("nothing was archived", restBefore, fake.restLog.toList().count { it.startsWith("PATCH ") })
        assertEquals("no request was sent or cancelled", promptsBefore, prompts().size)
        assertFalse(controller!!.get().isFinishing)
        openCard(model, alpha)
        waitFor("the composer shows the draft") { exists("composer") }
        rule.onNodeWithTag("composer").assertTextContains("an unsent draft")
        waitFor("the history is shown again") { exists("message_6") }
    }

    @Test fun N08_systemBackFromChatReturnsToTheListWithoutFinishing() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        openCard(model, alpha)
        systemBack()
        waitFor("the list is back") { conversationsShown() && exists("conversation_row_$alpha") }
        assertFalse("the activity was not finished", controller!!.get().isFinishing)
        assertEquals("the conversation stays selected", alpha, model.state.value.selected?.storedSessionId)
    }

    @Test fun N09_systemBackFromSettingsReturnsToTheListAndBackFromTheListDoesNotFinish() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        rule.onNodeWithTag("settings_button").performClick()
        waitFor("settings are shown") { textNodes("Appearance") > 0 }
        systemBack()
        waitFor("the list is back") { conversationsShown() }
        assertFalse(controller!!.get().isFinishing)
        systemBack()
        assertFalse("back from the list only sends the task to the background, it does not finish", controller!!.get().isFinishing)
        assertTrue("the list is still the screen", conversationsShown())
    }

    @Test fun N10_backWithTheCreationDialogOpenClosesOnlyTheDialog() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        rule.onNodeWithTag("new_conversation_button").performClick()
        waitFor("the dialog is open") { exists("create_dialog") }
        // A system Back goes to the topmost window first: the dialog's own dispatcher, not the activity's.
        (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed()
        settle()
        waitFor("the dialog closed") { !exists("create_dialog") }
        assertTrue(conversationsShown())
        assertFalse(controller!!.get().isFinishing)
    }

    @Test fun N11_aTappedAlertOpeningWhileInSettingsShowsThatChatAndBackGoesToTheList() {
        val work = conversation("work")
        val home = conversation("home")
        val model = showing(replyTap(work))
        waitFor("the alert's conversation is open") { model.state.value.selected?.storedSessionId == work && exists("chat_list") }
        rule.onNodeWithTag("back_button").performClick()
        waitFor("the list is shown") { conversationsShown() }
        settle()
        assertTrue("the old alert is not replayed into the chat", conversationsShown())
        rule.onNodeWithTag("settings_button").performClick()
        waitFor("settings are shown") { textNodes("Appearance") > 0 }
        controller!!.newIntent(replyTap(home))
        waitFor("the new alert opens its chat") { model.state.value.selected?.storedSessionId == home && exists("chat_list") }
        rule.onNodeWithTag("back_button").performClick()
        waitFor("back goes to the list, not Settings") { conversationsShown() }
    }

    @Test fun N12_aRoutedTurnAcceptedBeforeTheUserWentBackDoesNotPullThemIntoTheChat() {
        val work = conversation("work")
        val home = conversation("home")
        app.settings.routingEnabled = true
        app.settings.autoNavigateToRouted = true
        val model = showing(replyTap(work))
        waitFor("the chat is open") { model.state.value.selected?.storedSessionId == work && exists("chat_list") }
        app.routedNavigation.onAccepted("turn-before-back", VoiceOrigin.PHONE)
        rule.onNodeWithTag("back_button").performClick()
        waitFor("the list is shown") { conversationsShown() }
        app.routedNavigation.onDelivered(AssembledRoute("turn-before-back", VoiceOrigin.PHONE, "words", DestinationEntry("home", home), "ack"))
        settle()
        assertTrue("still on the list", conversationsShown())
        assertEquals("the selection did not move", work, model.state.value.selected?.storedSessionId)
        app.routedNavigation.onAccepted("turn-after-back", VoiceOrigin.PHONE)
        app.routedNavigation.onDelivered(AssembledRoute("turn-after-back", VoiceOrigin.PHONE, "words", DestinationEntry("home", home), "ack"))
        waitFor("a new routed request still opens its conversation") { model.state.value.selected?.storedSessionId == home && exists("chat_list") }
    }

    @Test fun N13_theTalkControlHasTheVisualHeightOfFortyAndATouchTargetOfFortyEight() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        assertEquals(1, count("talk"))
        val visualNodes = rule.onAllNodes(tagIs("talk_visual"), useUnmergedTree = true)
        assertEquals(1, visualNodes.fetchSemanticsNodes().size)
        val target = bounds("talk")
        val visual = rule.onNodeWithTag("talk_visual", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("touch target is at least 48dp: ${target.height}", target.height.value >= 47.9f)
        assertEqualsDp("the visible button is 40dp", 40f, visual.height.value, 0.6f)
        assertTrue("the visible button is inside the target", visual.top.value >= target.top.value - 0.5f && visual.bottom.value <= target.bottom.value + 0.5f)
        assertTrue("full width inside the safe margin", visual.width.value >= rootBounds().width.value - 32.5f)
    }

    @Test fun N14_theTalkControlSitsAboveTheNavigationBarInset() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        navInset(48)
        val root = rootBounds()
        val talk = bounds("talk")
        assertTrue("Talk is above the 48dp inset: bottom=${talk.bottom} root=${root.bottom}", talk.bottom.value <= root.bottom.value - 47.5f)
        assertTrue("and close to it, no tab-height gap: ${root.bottom.value - talk.bottom.value}", root.bottom.value - talk.bottom.value <= 48f + 16f)
        assertTrue("the list is still visible above Talk", bounds("bottom_bar").top.value > 64f)
    }

    @Test fun N15_tappingTalkAsksForTheMicrophoneExactlyOnceWhenItIsNotGranted() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val activity = controller!!.get()
        assertNull("nothing asked yet", shadowOf(activity).peekNextStartedActivityForResult())
        rule.onNodeWithTag("talk").performClick()
        settle()
        val first = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("the permission request was launched", first)
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", first.intent.action)
        assertNull("exactly once", shadowOf(activity).peekNextStartedActivityForResult())
    }

    @Test fun N16_tappingTalkWithTheMicrophoneGrantedDoesNotAskForPermission() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val activity = controller!!.get()
        rule.onNodeWithTag("talk").performClick()
        settle()
        assertNull("no permission request when it is granted", shadowOf(activity).peekNextStartedActivityForResult())
    }

    @Test fun N17_thePresenceFollowsTheChatNotTheSettingsOrTheList() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        openCard(model, alpha)
        assertEquals("shown while chatting", alpha, app.presence.visibleSession)
        rule.onNodeWithTag("back_button").performClick()
        waitFor("the list is shown") { conversationsShown() }
        assertNull("not shown once back on the list", app.presence.visibleSession)
    }
}
