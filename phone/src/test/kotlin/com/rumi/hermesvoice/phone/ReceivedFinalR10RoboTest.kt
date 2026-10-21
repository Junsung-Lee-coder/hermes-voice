package com.rumi.hermesvoice.phone
import io.mockk.*
import org.json.JSONObject
import androidx.compose.ui.test.onAllNodesWithText

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import com.rumi.hermesvoice.core.FakeHermesDashboard
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

/** Production list, submit, failure and NotificationManager callers. Synthetic fixture only. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h1280dp")
class ReceivedFinalR10RoboTest : PhoneNavRoboBase() {
    private fun received(id: String,text: String,time: Double,uid: String) {
        fake.injectFinalRow(id,text,time,uid)
    }
    private fun weight(title: String): FontWeight? {
        val node=rule.onNodeWithText(title,useUnmergedTree=true).fetchSemanticsNode()
        val layouts=mutableListOf<TextLayoutResult>()
        rule.runOnUiThread { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) }
        return layouts.single().layoutInput.style.fontWeight
    }
    private fun answers()=app.getSystemService(NotificationManager::class.java).activeNotifications.filter {
        it.notification.channelId in setOf("reply_arrival","reply_arrival_quiet") && !it.tag.endsWith("#notice")
    }
    @Test fun R01_onlyGenuineReceiveOrdersListNotOutgoingOrRefresh() {
        val a=titled("First","first");val b=titled("Second","second")
        received(a,"older",100.0,"a-old");received(b,"newer",200.0,"b-new")
        val vm=showing()
        waitFor("received timestamp controls compact list order") { shown(vm)==listOf(b,a) }
        fake.injectRow(a,"user","outgoing must not reorder")
        vm.refresh();waitFor("refresh finishes") { vm.state.value.status.isEmpty() }
        assertEquals(listOf(b,a),shown(vm))
        received(a,"latest",300.0,"a-new")
        vm.refresh();waitFor("actual new receipt reorders") { shown(vm)==listOf(a,b) }
        vm.refresh();waitFor("old history replay finishes") { vm.state.value.status.isEmpty() }
        assertEquals(listOf(a,b),shown(vm));assertTrue(fake.prompts.isEmpty())
    }
    @Test fun R02_incomingIsBoldUntilActualForegroundContentDisplay() {
        val a=titled("Unread target","unread")
        val vm=showWith(a)
        // Actual open establishes a baseline, not a fabricated receive on cache hydration.
        openCard(vm,a);systemBack()
        received(a,"unread answer",400.0,"unread-new")
        vm.refresh()
        waitFor("incoming title is bold on list") { weight("Unread target")==FontWeight.Bold }
        vm.refresh();waitFor("refresh leaves unread bold") { vm.state.value.status.isEmpty() }
        assertEquals(FontWeight.Bold,weight("Unread target"))
        openCard(vm,a)
        waitFor("applicable incoming body mounted") { vm.state.value.history.any { it.text=="unread answer" } }
        systemBack()
        waitFor("only actual foreground display clears bold") { weight("Unread target")!=FontWeight.Bold }
        assertTrue(fake.prompts.isEmpty())
    }
    @Test fun R04_visibleLengthLimitedReplyIsReceivedButInternalAndEmptyRowsAreNotFinalAlerts() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val a=titled("Partial receive","partial");val b=titled("Internal row","internal")
        val vm=showing()
        fake.injectFinalRow(a,"visible length-limited content",500.0,"partial-receive",reason="length")
        fake.injectFinalRow(b,"internal verification must not reorder",600.0,"verification-not-received",reason="verification_required")
        vm.refresh()
        waitFor("genuine partial incoming content orders the list, not internal process output") { shown(vm)==listOf(a,b) }
        assertEquals(FontWeight.Bold,weight("Partial receive"))
        assertNotEquals(FontWeight.Bold,weight("Internal row"))
        fake.injectFinalRow(b,"",700.0,"empty-not-received")
        vm.refresh();waitFor("empty row refresh completed") { vm.state.value.status.isEmpty() }
        assertEquals(listOf(a,b),shown(vm));assertNotEquals(FontWeight.Bold,weight("Internal row"))
        assertTrue("incomplete/internal/empty content is not an authoritative final",answers().isEmpty())
        assertTrue(fake.rawPrompts.isEmpty())
    }

    @Test fun N01_firstAcceptedWaitFailureDoesNotEndLaterRetainedFinalDetectionOrResend() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val a=titled("Recovery","recovery");val other=titled("Other","other")
        val vm=showWith(a,other);openCard(vm,other);systemBack();openCard(vm,a)
        fake.scripts[a]={listOf(FakeHermesDashboard.complete("retained later final"))}
        fake.turnBehavior={"hold"}
        vm.setDraft("exactly one prompt");vm.send()
        waitFor("actual accepted first request") { fake.heldTurns.containsKey(a) }
        fake.replayEpoch="second-epoch";fake.dropGateway()
        waitFor("accepted wait failed honestly") { vm.state.value.status.contains("reply",ignoreCase=true) && vm.state.value.sendsInFlight==0 }
        assertEquals("ONE prompt must be checked even if later alert assertions fail",listOf("exactly one prompt"),fake.rawPrompts.map { it.second })
        fake.finishHeldTurn(a, "recovered-1")
        received(other,"other distinct final",500.0,"other-1")
        systemBack();vm.refresh()
        waitFor("both genuine retained finals reach actual OS post after failed request") {
            val text=answers().map { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString() }
            text.any { it.contains("retained later final") } && text.any { it.contains("other distinct final") }
        }
        val initial=answers().map { it.tag }.toSet()
        vm.refresh();waitFor("replayed refresh completed") { vm.state.value.status.isEmpty() }
        assertEquals(initial,answers().map { it.tag }.toSet())
        assertEquals(listOf("exactly one prompt"),fake.rawPrompts.map { it.second })
    }

    @Test fun N02_deniedFinalsRemainPendingThenBothPostWithoutResendOrHistoryReplayAlerts() {
        val a=titled("Denied", "denied")
        val vm=showWith(a); openCard(vm,a); systemBack()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        received(a,"first pending final",600.0,"denied-1")
        received(a,"second pending final",601.0,"denied-2")
        fake.injectFinalRow(a,"verification is not final",602.0,"interim","verification_required")
        vm.refresh(); waitFor("denied scan settles") { !vm.state.value.status.contains("Reading") }
        assertTrue(answers().isEmpty()); assertTrue(fake.prompts.isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        vm.refresh()
        waitFor("both pending finals recover on permitted execution") { answers().size==2 }
        val keys=answers().map { it.tag }.toSet()
        vm.refresh();waitFor("duplicate scan settles") { vm.state.value.status.isEmpty() }
        assertEquals(keys,answers().map { it.tag }.toSet()); assertTrue(fake.prompts.isEmpty())
        assertFalse(answers().any { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("verification") })
    }

    @Test fun N04_disconnectedWatchObligationSurvivesTransportAndExportsActualProductionWire() {
        val id=titled("Wire source","wire");val vm=showWith(id)
        val captures=java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        val reachable=java.util.concurrent.atomic.AtomicBoolean(false)
        mockkStatic(com.google.android.gms.wearable.Wearable::class)
        try {
            val client=mockk<com.google.android.gms.wearable.MessageClient>()
            every { com.google.android.gms.wearable.Wearable.getMessageClient(any<android.content.Context>()) } returns client
            every { client.sendMessage(any(),any(),any()) } answers {
                if(secondArg<String>()=="/hv/v1/finals/intake" && reachable.get()) {
                    assertEquals("watch-node",firstArg<String>());captures+=thirdArg<ByteArray>().copyOf()
                    com.google.android.gms.tasks.Tasks.forResult(1)
                } else com.google.android.gms.tasks.Tasks.forException<Int>(java.io.IOException("synthetic disconnected Watch"))
            }
            val core=app.wiring().core
            val transport=mockk<com.rumi.hermesvoice.core.watchlink.WatchTransport>(relaxed=true)
            every { transport.nodeId } returns "watch-node"
            core.orchestrator.playbackRoute.accept(com.rumi.hermesvoice.core.voice.PlaybackTarget(com.rumi.hermesvoice.core.VoiceOrigin.WATCH,
                com.rumi.hermesvoice.core.watchlink.WatchPlaybackSink(transport,core.watchAcks)))
            vm.refresh();waitFor("baseline source list") { vm.state.value.status.isEmpty() && vm.state.value.conversations.size==1 }
            fake.injectFinalRow(id,"synthetic R10 Phone to Watch final",9000.0,"phone-watch-wire-uid")
            vm.refresh()
            fun durableRow():JSONObject? {
                val journal=app.localStore.getString("received_conversations_v1")?:return null
                val rows=JSONObject(journal).optJSONObject("sessions")?.optJSONObject(id)?.optJSONObject("rows")?:return null
                return rows.keys().asSequence().map { rows.getJSONObject(it) }.firstOrNull { it.optString("uid")=="phone-watch-wire-uid" }
            }
            waitFor("disconnected Watch stays durably owed") { durableRow()?.let { it.optBoolean("watch") && !it.optBoolean("watch_done") }==true }
            assertTrue(captures.isEmpty());reachable.set(true);vm.refresh()
            waitFor("real Phone production sender emits the retained final after reconnect") { captures.isNotEmpty() }
            val bytes=captures.first();val packet=JSONObject(String(bytes));val row=packet.getJSONArray("finals").getJSONObject(0)
            val bodyScope=app.javaClass.methods.firstOrNull { it.name=="bodyScope" && it.parameterCount==0 }?.invoke(app)?:app.sessionListScope()
            assertEquals(bodyScope,packet.getString("scope"));assertEquals(id,row.getString("session_id"))
            assertFalse("sendMessage success is not a Watch post receipt",durableRow()!!.getBoolean("watch_done"))
            assertTrue("no prompt is submitted by discovery or reconnect",fake.prompts.isEmpty())
            val temp=java.io.File(System.getenv("TEMP")?:error("owned native TEMP missing"))
            assertTrue("task-owned serial native slot only",Regex("""\\rvdev\\runs\\mr-r(?:10-20261019-r10(?:c1|b)|11-20261020-r11|12-20261021-r12|9-20261012-hv21)-""").containsMatchIn(temp.canonicalPath.lowercase()))
            java.nio.file.Files.write(java.io.File(temp,"r10-phone-watch-wire.json").toPath(),bytes,
                java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE)
            println("R10_PHONE_WATCH_WIRE_BASE64="+java.util.Base64.getEncoder().encodeToString(bytes))
        } finally { unmockkAll() }
    }

    @Test fun N05_failedReceiveCommitCannotSpendCursorOrPostAndThenRecovers() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val local=app.localStore
        val fail=java.util.concurrent.atomic.AtomicBoolean(false)
        val attempts=java.util.concurrent.atomic.AtomicInteger()
        mockkObject(local)
        try {
            every { local.commitString("received_conversations_v1",any()) } answers {
                if(fail.get()) { attempts.incrementAndGet();false } else callOriginal()
            }
            val a=titled("Commit source","commit");val vm=showWith(a)
            vm.refresh();waitFor("baseline receive checkpoint committed") { local.getString("received_conversations_v1")?.contains(a)==true }
            val baseline=local.getString("received_conversations_v1")
            fail.set(true);received(a,"recover after failed durable commit",4000.0,"commit-final")
            vm.refresh();waitFor("real receive commit was refused") { attempts.get()>0 }
            assertTrue("an uncommitted discovery cannot reach OS posting",answers().isEmpty())
            assertEquals("failed writes cannot spend durable cursors",baseline,local.getString("received_conversations_v1"))
            waitFor("storage recovery availability is visible") { rule.onAllNodesWithText("storage",substring=true).fetchSemanticsNodes().isNotEmpty() }
            fail.set(false);vm.refresh()
            waitFor("retained final is recovered after permitted storage resumes") { answers().size==1 }
            val tags=answers().map { it.tag };vm.refresh();waitFor("replay completed") { vm.state.value.status.isEmpty() }
            assertEquals(tags,answers().map { it.tag });assertTrue(fake.prompts.isEmpty())
        } finally { fail.set(false);unmockkAll() }
    }

    @Test fun N06_historicalMetadataEnrichmentIsNotANewFinalOrAReadReceipt() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // A server/registry conversation predating this receive journal, not a freshly created
        // conversation whose empty frontier this installation already observed.
        val a=fake.addStoredSession(com.rumi.hermesvoice.core.sessions.AppSources.CONVERSATION,"Historical source",false) { emptyList() }
        app.wiring().core.sessions.registry.put(com.rumi.hermesvoice.core.sessions.OwnedSession(a,com.rumi.hermesvoice.core.sessions.OwnedRole.CONVERSATION,"Historical source","historic","Historic fixture",false,0L))
        fake.injectFinalRow(a,"old canonical content",100.0,"historic-final",reason="")
        val vm=showWith(a);vm.refresh()
        waitFor("historical checkpoint") { app.localStore.getString("received_conversations_v1")?.contains("historic-final")==true }
        assertTrue("bootstrap is not a new arrival",answers().isEmpty())
        val messages=fake.rows.getValue(a).messages
        synchronized(messages) {
            val index=messages.indexOfFirst { it.optString("message_uid")=="historic-final" }
            messages[index]=JSONObject(messages[index].toString()).put("finish_reason","stop")
        }
        vm.refresh();waitFor("metadata reconciliation finishes") { vm.state.value.status.isEmpty() }
        assertTrue("enriching an old canonical row must not invent a new alert",answers().isEmpty())
        val rows=JSONObject(app.localStore.getString("received_conversations_v1")!!).getJSONObject("sessions").getJSONObject(a).getJSONObject("rows")
        val saved=rows.keys().asSequence().map { rows.getJSONObject(it) }.first { it.optString("uid")=="historic-final" }
        assertFalse("historical bootstrap is not proof of foreground display",saved.getBoolean("read"))
        assertTrue("unknown historic attention is represented separately",saved.getBoolean("baseline"))
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun N07_firstPostAcceptanceLookupFailureBeforeReceiveBootstrapStillRecoversWithoutResend() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store=androidx.lifecycle.ViewModelStore()
        val allowance=java.util.concurrent.atomic.AtomicInteger(1)
        try {
            val a=titled("First lookup","firstlookup")
            fake.messagesHook={ _,_,_ -> if(allowance.getAndDecrement()>0) 0 else 503 }
            fake.reconciliationMessagesHook=fake.messagesHook
            val vm=androidx.lifecycle.ViewModelProvider(store,androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app))[PhoneViewModel::class.java]
            vm.open(app.wiring().core.sessions.registry.find(a)!!)
            waitFor("empty conversation really opened before submission") { vm.state.value.selected?.storedSessionId==a && !vm.state.value.opening }
            fake.scripts[a]={listOf(FakeHermesDashboard.complete("first-lookup retained final"))};fake.turnBehavior={"hold"}
            vm.setDraft("only first-lookup prompt");vm.send()
            waitFor("first real prompt accepted") { fake.heldTurns.containsKey(a) }
            fake.replayEpoch="first-lookup-new-epoch";fake.dropGateway()
            waitFor("accepted wait fails while all subsequent history reads fail") { vm.state.value.sendsInFlight==0 }
            assertEquals("ONE prompt must execute before the final notification assertion",listOf("only first-lookup prompt"),fake.rawPrompts.map { it.second })
            fake.finishHeldTurn(a,"first-lookup-uid");fake.messagesHook=null;fake.reconciliationMessagesHook=null
            vm.refresh()
            waitFor("new retained final must not become a silent historic bootstrap") { answers().any { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("first-lookup retained final") } }
            assertEquals(1,fake.rawPrompts.size)
        } finally { fake.messagesHook=null;fake.reconciliationMessagesHook=null;store.clear() }
    }

    @Test fun N09_existingConversationFailedInitialHistoryKeepsHistoricBaselineButRecoversLaterFinal() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store=androidx.lifecycle.ViewModelStore()
        val failed=java.util.concurrent.atomic.AtomicInteger()
        try {
            val a=fake.addStoredSession(com.rumi.hermesvoice.core.sessions.AppSources.CONVERSATION,"Existing lookup gap",false) { emptyList() }
            received(a,"old final before tracking",1_700_000_090.0,"old-before-tracking")
            fake.messagesHook={ _,_,_ -> failed.incrementAndGet();503 }
            fake.reconciliationMessagesHook=fake.messagesHook
            val vm=androidx.lifecycle.ViewModelProvider(store,androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app))[PhoneViewModel::class.java]
            vm.refresh()
            waitFor("existing marked session initial body lookup really failed") { failed.get()>0 && app.wiring().core.sessions.registry.find(a)!=null }
            received(a,"new final after failed initial lookup",1_700_000_110.0,"new-after-tracking")
            fake.messagesHook=null;fake.reconciliationMessagesHook=null;vm.refresh()
            waitFor("initial history failure cannot silently bootstrap away a later genuine final") {
                answers().any { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("new final after failed initial lookup") }
            }
            assertFalse(answers().any { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("old final before tracking") })
            assertTrue("read recovery never submits a prompt",fake.rawPrompts.isEmpty())
        } finally { fake.messagesHook=null;fake.reconciliationMessagesHook=null;store.clear() }
    }

    @Test fun N10_finalityMetadataCannotUndoActualForegroundReadOfSameCanonicalContent() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val a=titled("Already read content","readmetadata");val vm=showWith(a)
        fun stored():JSONObject? = runCatching {
            val rows=JSONObject(app.localStore.getString("received_conversations_v1")!!).getJSONObject("sessions").getJSONObject(a).getJSONObject("rows")
            rows.keys().asSequence().map { rows.getJSONObject(it) }.first { it.optString("uid")=="read-before-finality" }
        }.getOrNull()
        fake.injectFinalRow(a,"same canonical content already read",750.0,"read-before-finality",reason="")
        vm.refresh();waitFor("incoming unknown-finality content is unread") { weight("Already read content")==FontWeight.Bold }
        openCard(vm,a)
        waitFor("the opened conversation shows the canonical body") { vm.state.value.history.any { it.text=="same canonical content already read" } }
        systemBack()
        waitFor("actual foreground placement really read this canonical body") { stored()?.optBoolean("read")==true && weight("Already read content")!=FontWeight.Bold }
        synchronized(fake.rows[a]!!.messages) { fake.rows[a]!!.messages.last().put("finish_reason","stop") }
        vm.refresh()
        waitFor("first authoritative final is posted separately from the existing read proof") { stored()?.let { it.optBoolean("final")&&it.optBoolean("phone") }==true }
        assertTrue("finality metadata must not undo an actual read of identical canonical content",stored()!!.optBoolean("read"))
        assertTrue("read and final notification acknowledgments remain separate",answers().isNotEmpty())
        assertTrue(fake.rawPrompts.isEmpty())
    }

    @Test fun N08_canonicalCancellationDoesNotResurrectOrMuteTheNextGenuineFinal() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val a=titled("Cancel source","cancel");val vm=showWith(a)
        received(a,"canonical final before cancel",1000.0,"cancel-one");vm.refresh()
        waitFor("actual OS final before exact cancellation") { answers().size==1 }
        val identity=answers().single().tag
        val rowId=fake.rows.getValue(a).messages.first { it.optString("message_uid")=="cancel-one" }.getLong("id")
        val reply=com.rumi.hermesvoice.core.notify.FinalReply(identity,a,com.rumi.hermesvoice.core.VoiceOrigin.PHONE,false,true,
            com.rumi.hermesvoice.core.notify.FinalReplySource.TEXT,text="canonical final before cancel")
        // Optional fields let these exact regression bytes still compile/run against the immutable
        // predecessor; actual current core callers populate them from an owned gateway receipt.
        if(reply.javaClass.declaredFields.any { it.name=="canonicalRowId" }) org.robolectric.util.ReflectionHelpers.setField(reply,"canonicalRowId",rowId)
        if(reply.javaClass.declaredFields.any { it.name=="namespace" }) org.robolectric.util.ReflectionHelpers.setField(reply,"namespace",app.javaClass.methods.firstOrNull { it.name=="bodyScope" && it.parameterCount==0 }?.invoke(app)?:app.sessionListScope())
        kotlinx.coroutines.runBlocking { app.replyAlerts.deliver(reply) }
        vm.refresh();waitFor("cancel replay reconciled") { vm.state.value.status.isEmpty() }
        assertTrue("exact cancellation removes and fences its own arrival",answers().isEmpty())
        received(a,"next distinct final remains eligible",1001.0,"cancel-two");vm.refresh()
        waitFor("cancellation is not a blanket session mute") { answers().size==1 }
        assertNotEquals(identity,answers().single().tag)
        assertTrue(answers().single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("next distinct final"))
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun N03_liveCanonicalFinalAndRetainedReplayShareOneDeviceIdentity() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val a=titled("Canonical", "canonical")
        val vm=showWith(a);openCard(vm,a)
        fake.scripts[a]={
            listOf(FakeHermesDashboard.complete("one canonical live final").let { (kind,payload) ->
                kind to payload!!.put("message_uid","live-canonical-1").put("persisted_turn",org.json.JSONObject()
                    .put("final_assistant_row_id",fake.rows.getValue(a).messages.size+1).put("complete",true))
            })
        }
        vm.setDraft("canonical once");vm.send()
        waitFor("attributable live request completed") { fake.rawPrompts.size==1 && vm.state.value.sendsInFlight==0 }
        assertEquals(listOf("canonical once"),fake.rawPrompts.map { it.second })
        systemBack();vm.refresh();waitFor("canonical retained reconciliation finished") { vm.state.value.status.isEmpty() }
        waitFor("canonical final really posted") { answers().isNotEmpty() }
        assertEquals("live and history intake are the SAME final, not two alerts",1,answers().size)
        val tags=answers().map { it.tag }.toSet()
        vm.refresh();waitFor("second replay finishes") { vm.state.value.status.isEmpty() }
        assertEquals(tags,answers().map { it.tag }.toSet())
        assertEquals(listOf("canonical once"),fake.rawPrompts.map { it.second })
    }

    @Test fun R03_backgroundSelectionAndRecreationDoNotReadIncomingContent() {
        val a=titled("Persistent unread","persist")
        val vm=showWith(a);openCard(vm,a);systemBack()
        received(a,"unread across recreation",700.0,"persistent-new")
        vm.refresh();waitFor("new final bold") { weight("Persistent unread")==FontWeight.Bold }
        vm.openOrResume(vm.state.value.conversations.single().owned)
        waitFor("background history fetched") { vm.state.value.history.any { it.text=="unread across recreation" } }
        assertEquals(FontWeight.Bold,weight("Persistent unread"))
        controller!!.pause().stop().destroy();controller=null
        val next=showing();waitFor("recreated list stays unread") { weight("Persistent unread")==FontWeight.Bold }
        openCard(next,a);waitFor("foreground mounting clears only actual content") { next.state.value.history.any { it.text=="unread across recreation" } }
        systemBack();waitFor("display acknowledgment persists") { weight("Persistent unread")!=FontWeight.Bold }
        assertTrue(fake.prompts.isEmpty())
    }
}
