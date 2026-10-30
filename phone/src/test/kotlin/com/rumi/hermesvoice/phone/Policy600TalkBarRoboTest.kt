package com.rumi.hermesvoice.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.sessions.OwnedRole
import com.rumi.hermesvoice.core.sessions.OwnedSession
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.PhoneLiveState
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/**
 * UI-contract witnesses, not media/provider or runtime safety proof. Since the owner's UI contract of 2026-10-27 the Talk bar carries no
 * routing / "Phone Live:" guidance line: the one Talk control says what it does now, and a refused Talk says why beside it.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h900dp")
class Policy600TalkBarRoboTest {
    private val rule=createComposeRule()
    @get:Rule val chain:org.junit.rules.RuleChain=org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(rule)
    private val selected=OwnedSession("20260101_120000_selected",OwnedRole.CONVERSATION,"Selected A","alpha","",false,0)
    private val state=mutableStateOf(PhoneUiState(signedIn=true,routingEnabled=false,selected=selected,watch=WatchSettings(phoneLiveEnabled=true)))
    @Before fun setup()=ComposeDispatcherIsolation.reset()
    private fun show(){rule.setContent{MaterialTheme{TalkBar(state.value){}}};rule.waitForIdle()}
    private fun noGuidance(){rule.onAllNodesWithTag("routing_target").assertCountEquals(0)
        for(text in listOf("Phone Live","Routing off","bound conversation","name a conversation")) rule.onAllNodesWithText(text,substring=true).assertCountEquals(0)}
    private fun singleTalk(){rule.onAllNodesWithTag("talk").assertCountEquals(1)}
    @Test fun nextPhoneLiveDoesNotPromiseLegacySelectedA(){
        show();noGuidance()
        rule.onNodeWithTag("talk").assertTextContains("Talk")
        rule.onAllNodesWithText("Routing off: voice requests go to Selected A").assertCountEquals(0);singleTalk()
    }
    @Test fun admittedBoundPhoneLiveRemainsTruthfulWhenPreferenceTurnsOff(){
        state.value=state.value.copy(live=PhoneLiveState(active=true,ready=true,boundSessionId="20260101_120000_boundb"))
        show();noGuidance();rule.onNodeWithTag("talk").assertTextContains("End Live")
        state.value=state.value.copy(watch=WatchSettings(phoneLiveEnabled=false));rule.waitForIdle()
        noGuidance();rule.onNodeWithTag("talk").assertTextContains("End Live")
        rule.onAllNodesWithText("Routing off: voice requests go to Selected A").assertCountEquals(0);singleTalk()
        state.value=state.value.copy(live=PhoneLiveState());rule.waitForIdle()
        noGuidance();rule.onNodeWithTag("talk").assertTextContains("Talk");singleTalk()
    }
    @Test fun admittedLegacyCaptureKeepsItsGuidanceWhenNextModeChanges(){
        state.value=state.value.copy(recording=true)
        show();noGuidance()
        rule.onNodeWithTag("talk").assertTextContains("Stop & send");singleTalk()
    }
    @Test fun watchOwnedLiveIsNotPresentedAsAPhoneSelectedDestination(){
        state.value=state.value.copy(live=PhoneLiveState(active=true,origin=VoiceOrigin.WATCH,boundSessionId="20260101_120000_boundb"))
        show();noGuidance()
        rule.onNodeWithTag("talk").assertTextContains("Talk");rule.onAllNodesWithText("End Live").assertCountEquals(0);singleTalk()
    }
    @Test fun legacyOffUnselectedStillRequiresAnActualConversation(){
        state.value=state.value.copy(watch=WatchSettings(phoneLiveEnabled=false),selected=null)
        show();noGuidance();rule.onNodeWithTag("talk").assertTextContains("Talk");singleTalk()
    }
}
