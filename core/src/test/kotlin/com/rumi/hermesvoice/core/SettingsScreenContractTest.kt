package com.rumi.hermesvoice.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level contract of the Phone settings screen (the Compose UI itself is not run on a device or emulator here): the ⓘ help
 * buttons, the dialog, the later-reply duration row and the Watch navigation switch are wired as specified. Evidence level:
 * source contract, below a rendered-UI test.
 */
class SettingsScreenContractTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(path: String) = File(root, path).readText()
    private val main = source("phone/src/main/kotlin/com/rumi/hermesvoice/phone/MainActivity.kt")
    private val model = source("phone/src/main/kotlin/com/rumi/hermesvoice/phone/PhoneViewModel.kt")

    private fun block(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("found: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        assertTrue("found end: $end", to > from)
        return text.substring(from, to)
    }

    @Test
    fun `the info button is an accessible icon button with a tagged option-specific description and local-only state`() {
        val button = block(main, "internal fun InfoHelpButton(topic: HelpTopic)", "private fun HelpDialog")
        assertTrue(button.contains("IconButton(onClick = { open = true }"))
        assertTrue(button.contains("Modifier.testTag(topic.buttonTag)"))
        assertTrue(button.contains("contentDescription = topic.contentDescription"))
        assertTrue("the glyph is hidden from accessibility so only the description is spoken", button.contains("clearAndSetSemantics"))
        assertTrue(button.contains("rememberSaveable(topic.id) { mutableStateOf(false) }"))
        assertFalse("opening help changes no setting", button.contains("model.") || button.contains("settings"))
        assertTrue(button.contains("if (open) HelpDialog(topic) { open = false }"))
    }

    @Test
    fun `the help dialog is dismissible with back or outside tap, scrollable, tagged and has a Close button`() {
        val dialog = block(main, "private fun HelpDialog(topic: HelpTopic", "@OptIn(ExperimentalLayoutApi::class)")
        assertTrue(dialog.contains("AlertDialog("))
        assertTrue(dialog.contains("onDismissRequest = onDismiss"))
        assertTrue(dialog.contains("verticalScroll(rememberScrollState())"))
        assertTrue(dialog.contains("testTag(topic.dialogTag)"))
        assertTrue(dialog.contains("testTag(\"help_close\")"))
        assertTrue(dialog.contains("topic.paragraphs.forEach"))
        assertFalse("no setting is touched by the dialog", dialog.contains("model.") || dialog.contains("settings"))
    }

    @Test
    fun `every settings topic has its info button on the screen and the switch rows pass their topic`() {
        val settings = block(main, "internal fun SettingsTab(", "private fun SwitchRow")
        for (call in listOf("SettingsHelp.spokenReplies()", "SettingsHelp.laterReplies(state.laterReplyWindowMinutes)", "SettingsHelp.routing()",
            "SettingsHelp.phoneNavigation()", "SettingsHelp.watchNavigation()", "SettingsHelp.watch()", "SettingsHelp.backgroundRelay()",
            "SettingsHelp.wakePhrase()", "SettingsHelp.wakePatterns()", "SettingsHelp.standby()", "SettingsHelp.vad()")) {
            assertEquals("exactly one ⓘ for $call", 1, Regex(Regex.escape(call)).findAll(main).count())
            assertTrue("$call is on the settings screen", settings.contains(call) || main.contains(call))
        }
        assertTrue("later-reply label shows the configured duration", main.contains("\"Speak later replies (\${LaterReplyWindow.describe(state.laterReplyWindowMinutes)})\""))
        assertFalse("no fixed 30-minute label", main.contains("Speak later replies (30 minutes)"))
        assertFalse("no fixed 30 minutes claim remains on the screen", Regex("within\\s+30 minutes|after 30 minutes").containsMatchIn(main))
    }

    @Test
    fun `the long explanations left the screen but the connection and status text stayed`() {
        for (gone in listOf("The routing acknowledgement and the final reply always play", "Hermes doesn't mark which request",
            "steady noise such as a fan", "Android's non-exact", "It uses more battery")) {
            assertFalse("moved to the help dialog: $gone", main.contains(gone))
        }
        assertTrue(main.contains("Enter your Hermes dashboard address. Sign-in finishes in the browser and returns here."))
        assertTrue(main.contains("This phone forgets its Hermes sign-in. Your conversations stay on the dashboard."))
        assertTrue(main.contains("Watch standby: requested; the Watch is not reachable, it applies this when it syncs"))
        assertTrue(main.contains("ColorScheme.error") || main.contains("colorScheme.error"))
    }

    @Test
    fun `the later-reply duration row has the specified controls and commits on Done, focus loss and unit or preset choice`() {
        val row = block(main, "private fun LaterReplyWindowRow(", "\n}\n")
        for (tag in listOf("later_reply_window_value", "later_reply_unit_", "later_reply_preset_", "later_reply_window_error")) {
            assertTrue("tag $tag", row.contains(tag))
        }
        assertTrue(row.contains("KeyboardType.Number") && row.contains("ImeAction.Done"))
        assertTrue("typing only changes a draft", Regex("onValueChange = \\{ value ->\\s+text = value.filter \\{ it.isDigit\\(\\) \\}").containsMatchIn(row))
        assertFalse("no save inside onValueChange", block(row, "onValueChange =", "label =").contains("commitLaterReplyWindow"))
        assertTrue(row.contains("KeyboardActions(onDone = { commit(); focus.clearFocus() })"))
        assertTrue(row.contains("if (wasFocused && !focusState.isFocused) commit()"))
        assertTrue(row.contains("model.commitLaterReplyWindow(text, choice)"))
        assertTrue(row.contains("model.setLaterReplyWindowMinutes(minutes)"))
        assertTrue("the error is shown without a dialog", row.contains("state.laterReplyWindowError?.let"))
        assertTrue(main.contains("LaterReplyWindowRow(state, model)"))
    }

    @Test
    fun `the view model validates, never stores a refused entry and never touches the later-reply consent`() {
        val commit = block(model, "fun commitLaterReplyWindow(", "fun setAutoNavigate")
        assertTrue(commit.contains("LaterReplyWindow.fromEntry(text, unit)"))
        assertTrue(commit.contains("LaterReplyWindow.validOrNull(minutes) ?: return"))
        assertTrue(commit.contains("app.settings.laterReplyWindowMinutes = valid"))
        assertTrue(commit.contains("laterReplyWindowError = entry.reason"))
        assertFalse("duration is not consent", commit.contains("laterConsent") || commit.contains("speakLater") || commit.contains("setSpeakLaterReplies"))
        assertTrue(commit.contains("fun setWatchAutoNavigate(on: Boolean)"))
        assertTrue(commit.contains("updateWatch(current.copy(watchAutoNavigateToRouted = on))"))
        assertTrue(model.contains("laterReplyWindowMinutes = app.settings.laterReplyWindowMinutes"))
    }

    @Test
    fun `the Watch navigation switch is separate from the phone switch and disabled while routing is off`() {
        val phone = block(main, "\"Open the routed conversation on this phone\"", "HorizontalDivider()")
        assertTrue(phone.contains("enabled = state.routingEnabled"))
        assertTrue(phone.contains("tag = \"auto_navigate\"") && phone.contains("model::setAutoNavigate"))
        assertTrue(phone.contains("\"Open the routed conversation on Watch\""))
        assertTrue(phone.contains("state.watch.watchAutoNavigateToRouted, enabled = state.routingEnabled"))
        assertTrue(phone.contains("tag = \"watch_auto_navigate\"") && phone.contains("model::setWatchAutoNavigate"))
        assertTrue(main.contains("SwitchRow(\"Route voice requests automatically\", state.routingEnabled, tag = \"routing_enabled\""))
    }
}
