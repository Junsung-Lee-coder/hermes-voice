package com.rumi.hermesvoice.phone

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The compact "New conversation" dialog behind the plus button, through the real MainActivity,
 * PhoneViewModel and AppSessionRepository: opening and cancelling create nothing, a valid submit creates exactly one conversation
 * and opens it, an invalid alias or a full list (100 active destinations) is refused with the existing error line.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneNewConversationRoboTest : PhoneNavRoboBase() {

    private fun textCount(exact: String) = rule.onAllNodes(SemanticsMatcher("text == $exact") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == exact } == true
    }).fetchSemanticsNodes().size

    private fun descriptionCount(exact: String) = rule.onAllNodes(SemanticsMatcher("description == $exact") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == exact } == true
    }).fetchSemanticsNodes().size

    private fun fieldText(tag: String) =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun openDialog() {
        rule.onNodeWithTag("new_conversation_button").performClick()
        waitFor("the dialog is open") { exists("create_dialog") }
    }

    private fun fill(title: String, alias: String, description: String) {
        rule.onNodeWithTag("create_title").performTextInput(title)
        rule.onNodeWithTag("create_alias").performTextInput(alias)
        rule.onNodeWithTag("create_description").performTextInput(description)
    }

    @Test fun W01_noCreationFormIsShownUntilThePlusIsTapped() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        for (tag in listOf("create_dialog", "create_title", "create_alias", "create_description", "create_submit")) assertEquals("$tag hidden", 0, count(tag))
        assertEquals("no Create button on the list", 0, textCount("Create"))
        assertEquals("no 'New conversation' heading or CTA text on the list", 0, textCount("New conversation"))
        assertEquals("a single New conversation control", 1, descriptionCount("New conversation"))
    }

    @Test fun W02_theDialogOffersTheTitleAliasAndDescriptionInputs() {
        val alpha = titled("Synthetic alpha", "alpha")
        showWith(alpha)
        openDialog()
        for (tag in listOf("create_title", "create_alias", "create_description", "create_submit", "create_cancel")) assertTrue("$tag shown", exists(tag))
        rule.onNodeWithTag("create_submit").assertIsNotEnabled()
        rule.onNodeWithTag("create_alias").performTextInput("beta")
        rule.onNodeWithTag("create_submit").assertIsEnabled()
    }

    @Test fun W03_openingAndCancellingCreateNothing() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        val rows = fake.rows.size
        val rpc = fake.rpcLog.size
        val restPosts = fake.restLog.toList().count { it.startsWith("POST ") }
        openDialog()
        fill("Never made", "never", "words")
        rule.onNodeWithTag("create_cancel").performClick()
        waitFor("the dialog closed") { !exists("create_dialog") }
        settle()
        assertEquals("no session was created", rows, fake.rows.size)
        assertEquals("no gateway call was made", rpc, fake.rpcLog.size)
        assertEquals("no REST post", restPosts, fake.restLog.toList().count { it.startsWith("POST ") })
        assertEquals(listOf(alpha), shown(model))
        assertNull("nothing was opened", model.state.value.selected)
        openDialog()
        assertEquals("the cancelled alias is not kept", "", fieldText("create_alias"))
        assertEquals("the cancelled title is not kept", "", fieldText("create_title"))
    }

    @Test fun W04_aValidSubmitCreatesExactlyOneConversationAndOpensIt() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        val rows = fake.rows.size
        openDialog()
        fill("Synthetic gamma", "gamma", "gamma things")
        rule.onNodeWithTag("create_submit").performClick()
        waitFor("the conversation is created and open") { model.state.value.selected?.alias == "gamma" }
        waitFor("the dialog closed") { !exists("create_dialog") }
        settle()
        assertEquals("exactly one session was created", rows + 1, fake.rows.size)
        val created = model.state.value.selected!!
        assertEquals("Synthetic gamma", created.title)
        assertEquals("gamma things", created.description)
        assertTrue("it is a voice destination", app.wiring().core.sessions.allowlist(null).entries.any { it.alias == "gamma" })
        waitFor("its chat is shown") { exists("chat_list") }
        rule.onNodeWithTag("back_button").performClick()
        waitFor("it is listed") { exists("conversation_row_${created.storedSessionId}") && exists("conversation_row_$alpha") }
        assertEquals("still one creation after navigating", rows + 1, fake.rows.size)
    }

    @Test fun W05_anInvalidAliasIsRefusedWithTheErrorLineAndTheDialogKeepsTheInput() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        val rows = fake.rows.size
        openDialog()
        fill("Bad", "bad alias!", "words")
        rule.onNodeWithTag("create_submit").performClick()
        waitFor("the error is shown") { model.state.value.status.startsWith("Couldn't do that") }
        settle()
        assertEquals("nothing was created", rows, fake.rows.size)
        assertTrue("the dialog stays so the input can be fixed", exists("create_dialog"))
        rule.onNodeWithTag("create_alias").assertTextContains("bad alias!")
        assertNull(model.state.value.selected)
    }

    @Test fun W06_aTakenAliasIsRefusedAndNothingIsCreated() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        val rows = fake.rows.size
        openDialog()
        fill("Duplicate", "alpha", "words")
        rule.onNodeWithTag("create_submit").performClick()
        waitFor("the error is shown") { model.state.value.status.startsWith("Couldn't do that") }
        settle()
        assertEquals(rows, fake.rows.size)
        assertEquals(listOf(alpha), shown(model))
    }

    @Test fun W07_aFullListOfOneHundredActiveDestinationsRefusesAnotherOne() {
        for (i in 1..100) marked("cap-%03d".format(i))
        val model = showing()
        waitFor("all one hundred are listed", 60_000) { shown(model).size == 100 }
        val rows = fake.rows.size
        openDialog()
        fill("One too many", "extra", "words")
        rule.onNodeWithTag("create_submit").performClick()
        waitFor("the refusal is shown") { model.state.value.status.startsWith("Couldn't do that") }
        settle()
        assertEquals("nothing was created", rows, fake.rows.size)
        assertEquals("still exactly one hundred", 100, shown(model).size)
        assertTrue("the dialog stays open", exists("create_dialog"))
    }

    @Test fun W08_aDoubleTapOnSubmitCreatesOnce() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        val rows = fake.rows.size
        openDialog()
        fill("Twice", "twice", "words")
        rule.onNodeWithTag("create_submit").performClick()
        runCatching { rule.onNodeWithTag("create_submit").performClick() }
        waitFor("created") { model.state.value.selected?.alias == "twice" }
        settle()
        assertEquals("exactly one session", rows + 1, fake.rows.size)
    }

    @Test fun W09_theDialogIsNotOfferedWhileSignedOut() {
        val model = showing()
        model.signOut()
        waitFor("signed out") { !model.state.value.signedIn }
        settle()
        assertFalse(exists("new_conversation_button"))
        assertFalse(exists("create_dialog"))
    }

    private fun enabled(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null

    private fun createRequests() = fake.rpcLog.toList().count { it.optString("method") == "session.create" }

    @Test fun W10_aSecondCreateTapAfterRecreationIsSettledAndTheDialogStaysDismissibleWhenTheFirstFails() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        val rows = fake.rows.size
        val requests = createRequests()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.createBehavior = { arrived.countDown(); release.await(30, TimeUnit.SECONDS); "rpc_error" }
        openDialog()
        fill("Slow", "slow", "words")
        rule.onNodeWithTag("create_submit").performClick()
        waitFor("the create reached the server") { arrived.count == 0L }
        controller!!.recreate()
        idle(300); settle()
        waitFor("the dialog is back with its input") { exists("create_dialog") && fieldText("create_alias") == "slow" }
        rule.onNodeWithTag("create_submit").performClick()
        waitFor("the refused second attempt is settled: Cancel is usable again") { enabled("create_cancel") }
        waitFor("Create is usable again") { enabled("create_submit") }
        release.countDown()
        waitFor("the first create failed") { model.state.value.status.startsWith("Couldn't do that") }
        settle()
        assertTrue("the dialog can still be cancelled", enabled("create_cancel"))
        rule.onNodeWithTag("create_cancel").performClick()
        waitFor("the dialog closed") { !exists("create_dialog") }
        assertEquals("nothing was created", rows, fake.rows.size)
        assertEquals("one create request in all", requests + 1, createRequests())
    }

    @Test fun W11_aCreatedConversationThatFailsToOpenIsReportedAsCreatedAndIsNeverCreatedAgain() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        val rows = fake.rows.size
        val requests = createRequests()
        val failOpen = AtomicBoolean(true)
        fake.messagesHook = { session, _, _ -> if (session != alpha && failOpen.get()) 503 else 0 }
        openDialog()
        fill("Synthetic gamma", "gamma", "gamma things")
        rule.onNodeWithTag("create_submit").performClick()
        waitFor("the status says it was created but could not be opened") {
            model.state.value.status.startsWith("Created 'Synthetic gamma'") && model.state.value.status.contains("couldn't open it")
        }
        waitFor("the dialog is closed") { !exists("create_dialog") }
        settle()
        assertEquals("exactly one session was created", rows + 1, fake.rows.size)
        assertEquals("one create request", requests + 1, createRequests())
        assertNull("nothing was shown as opened", model.state.value.selected)
        assertFalse("no chat is shown", exists("chat_list"))
        waitFor("the new card is listed") { shown(model).size == 2 }
        val created = shown(model).first { it != alpha }
        waitFor("its card is on screen") { exists("conversation_row_$created") }
        failOpen.set(false)
        openCard(model, created)
        assertEquals("gamma", model.state.value.selected?.alias)
        assertEquals("opening it did not create again", rows + 1, fake.rows.size)
        assertEquals(requests + 1, createRequests())
    }

    @Test fun W12_aCreateWhileAnotherIsInFlightIsRefusedAtOnceAndItsCallbackIsSettled() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        val rows = fake.rows.size
        val requests = createRequests()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.createBehavior = { arrived.countDown(); release.await(30, TimeUnit.SECONDS); "ok" }
        val first = AtomicReference<Boolean?>(null)
        val second = AtomicReference<Boolean?>(null)
        model.createConversation("One", "one", "words") { first.set(it) }
        waitFor("the first create reached the server") { arrived.count == 0L }
        model.createConversation("Two", "two", "words") { second.set(it) }
        waitFor("the second call is settled") { second.get() != null }
        assertEquals("the second call was refused", false, second.get())
        release.countDown()
        waitFor("the first create completed") { first.get() == true }
        settle()
        assertEquals("exactly one session was created", rows + 1, fake.rows.size)
        assertEquals(requests + 1, createRequests())
        waitFor("it is open") { model.state.value.selected?.alias == "one" }
    }
}
