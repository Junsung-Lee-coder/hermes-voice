package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.notify.ConversationPresence
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.notify.ReplyAlertLedger
import com.rumi.hermesvoice.core.notify.ReplyAlertPort
import com.rumi.hermesvoice.core.notify.ReplyAlertResult
import com.rumi.hermesvoice.core.notify.ReplyAlerts
import com.rumi.hermesvoice.core.notify.ReplyPreview
import com.rumi.hermesvoice.core.voice.PlaybackSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decision half of "opening a conversation clears its reply alerts": what is posted, suppressed and dismissed, and what dedupe survives. */
class ReplyAlertDismissalTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = InMemoryKeyValueStore()

    @After fun stop() = scope.cancel()

    private class Surface : ReplyAlertPort {
        val shown = ArrayList<String>()
        val dismissed = ArrayList<String>()
        var onShow: () -> Unit = {}
        override fun show(alert: ReplyAlert): Boolean = show(alert, null, false)
        override fun show(alert: ReplyAlert, preview: ReplyPreview?, quiet: Boolean): Boolean { shown += "${alert.identity}@${alert.storedSessionId}"; onShow(); return true }
        override fun dismissConversation(storedSessionId: String): Int { dismissed += storedSessionId; return 1 }
    }

    private fun reply(identity: String, session: String = "s-1", target: VoiceOrigin = VoiceOrigin.PHONE, sink: PlaybackSink? = null) =
        FinalReply(identity, session, target, heard = false, cancelled = false, source = FinalReplySource.OWN, sink = sink)

    @Test fun `opening a conversation asks the surface to dismiss exactly that conversation`() {
        val surface = Surface()
        val alerts = ReplyAlerts(ReplyAlertLedger(store), surface, scope)
        alerts.onConversationOpened("s-1")
        assertEquals(listOf("s-1"), surface.dismissed)
    }

    @Test fun `a surface without dismissal support keeps working (the default removes nothing)`() {
        val alerts = ReplyAlerts(ReplyAlertLedger(store), ReplyAlertPort { true }, scope)
        alerts.onConversationOpened("s-1")
        assertEquals(0, ReplyAlertPort { true }.dismissConversation("s-1"))
    }

    @Test fun `a reply for the conversation in front of the user is claimed but not posted, and the screen is told`(): Unit = runBlocking {
        val surface = Surface()
        val presence = ConversationPresence().also { it.setForeground(true); it.setShown("s-1") }
        val alerts = ReplyAlerts(ReplyAlertLedger(store), surface, scope, presence)
        assertEquals(ReplyAlertResult.SUPPRESSED_VISIBLE, alerts.dispatch(reply("t-1#reply")))
        assertTrue(surface.shown.isEmpty())
        assertEquals("a replay of the viewed identity never posts later", ReplyAlertResult.DUPLICATE, alerts.dispatch(reply("t-1#reply")))
        presence.setShown(null)
        assertEquals(ReplyAlertResult.DUPLICATE, alerts.dispatch(reply("t-1#reply")))
        assertTrue(surface.shown.isEmpty())
    }

    @Test fun `a reply for another conversation, or while not in front, is still posted`(): Unit = runBlocking {
        val surface = Surface()
        val presence = ConversationPresence().also { it.setForeground(true); it.setShown("s-1") }
        val alerts = ReplyAlerts(ReplyAlertLedger(store), surface, scope, presence)
        assertEquals(ReplyAlertResult.SHOWN_HERE, alerts.dispatch(reply("t-2#reply", session = "s-2")))
        presence.setForeground(false)
        assertEquals(ReplyAlertResult.SHOWN_HERE, alerts.dispatch(reply("t-3#reply", session = "s-1")))
        assertEquals(listOf("t-2#reply@s-2", "t-3#reply@s-1"), surface.shown)
    }

    @Test fun `a new distinct reply after leaving the conversation is posted again`(): Unit = runBlocking {
        val surface = Surface()
        val presence = ConversationPresence().also { it.setForeground(true); it.setShown("s-1") }
        val alerts = ReplyAlerts(ReplyAlertLedger(store), surface, scope, presence)
        assertEquals(ReplyAlertResult.SUPPRESSED_VISIBLE, alerts.dispatch(reply("t-1#reply")))
        presence.setShown(null)
        assertEquals(ReplyAlertResult.SHOWN_HERE, alerts.dispatch(reply("t-2#reply")))
        assertEquals(listOf("t-2#reply@s-1"), surface.shown)
    }

    @Test fun `a conversation that opens while the alert is being posted is dismissed afterwards`(): Unit = runBlocking {
        val surface = Surface()
        val presence = ConversationPresence()
        surface.onShow = { presence.setForeground(true); presence.setShown("s-1") }
        val alerts = ReplyAlerts(ReplyAlertLedger(store), surface, scope, presence)
        presence.onOpened = { alerts.onConversationOpened(it) }
        alerts.dispatch(reply("t-1#reply"))
        assertEquals(listOf("t-1#reply@s-1"), surface.shown)
        assertTrue("the open that raced the post removed it", surface.dismissed.contains("s-1"))
    }

    @Test fun `dismissing never forgets an identity, so a replay after the open is not posted again`(): Unit = runBlocking {
        val surface = Surface()
        val alerts = ReplyAlerts(ReplyAlertLedger(store), surface, scope)
        assertEquals(ReplyAlertResult.SHOWN_HERE, alerts.dispatch(reply("t-1#reply")))
        alerts.onConversationOpened("s-1")
        assertEquals(ReplyAlertResult.DUPLICATE, alerts.dispatch(reply("t-1#reply")))
        val restarted = ReplyAlerts(ReplyAlertLedger(store), surface, scope)
        assertEquals(ReplyAlertResult.DUPLICATE, restarted.dispatch(reply("t-1#reply")))
        assertEquals(1, surface.shown.size)
    }

    @Test fun `a reply for the Watch is still sent to the Watch while the Phone shows that conversation`(): Unit = runBlocking {
        val surface = Surface()
        val sent = ArrayList<String>()
        val sink = object : PlaybackSink {
            override suspend fun play(audio: SpokenAudio, cue: com.rumi.hermesvoice.core.voice.PlaybackCue) = Unit
            override suspend fun deliverReplyAlert(alert: ReplyAlert): Boolean { sent += alert.identity; return true }
        }
        val presence = ConversationPresence().also { it.setForeground(true); it.setShown("s-1") }
        val alerts = ReplyAlerts(ReplyAlertLedger(store), surface, scope, presence)
        assertEquals(ReplyAlertResult.SENT_TO_WATCH, alerts.dispatch(reply("t-1#final", target = VoiceOrigin.WATCH, sink = sink)))
        assertEquals(listOf("t-1#final"), sent)
        assertTrue(surface.shown.isEmpty())
    }

    @Test fun `the screen hears about a reply that arrived while it was in front`(): Unit = runBlocking {
        val presence = ConversationPresence().also { it.setForeground(true); it.setShown("s-1") }
        val alerts = ReplyAlerts(ReplyAlertLedger(store), Surface(), scope, presence)
        val seen = ArrayList<String>()
        presence.onArrival = { seen += it }
        alerts.dispatch(reply("t-1#reply"))
        assertEquals(listOf("s-1"), seen)
    }
}
