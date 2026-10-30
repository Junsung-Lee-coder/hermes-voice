package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.sessions.ReceivedConversations
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class LiveWatchCustodyRepairTest {
    @Test fun originalWatchNodeSurvivesJournalReconstructionWithoutAnyPlaybackRoute()=runBlocking {
        val store=InMemoryKeyValueStore()
        val journal=AcceptedTurnStore(store)
        assertTrue(journal.begin(AcceptedTurn("send",AcceptedKind.LIVE,"session",TurnAcceptance("runtime",3,"epoch",0,"streaming"),System.currentTimeMillis(),"scope",VoiceOrigin.WATCH,"original-node")))
        journal.idle("send")
        val restored=AcceptedTurnStore(store).pending().single()
        assertEquals(VoiceOrigin.WATCH,restored.origin);assertEquals("original-node",restored.watchNode);assertEquals(AcceptedKind.LIVE,restored.kind)
    }
    @Test fun privateLiveOutputKeepsAQuietOriginalWatchNotificationObligationAcrossReload(){
        val store=InMemoryKeyValueStore();val received=ReceivedConversations(store){"scope"}
        assertTrue(received.expect("session",7,"original-node",true,watchReceipt=true))
        val row=HistoryMessage(7,"assistant","final",1800000000.0,messageUid="uid",finishReason="stop")
        assertTrue(received.ingest("session",listOf(row),true,false,null))
        val restored=ReceivedConversations(store){"scope"}
        val pending=restored.pending(true).single()
        assertTrue(pending.getBoolean("quiet"));assertFalse(pending.getBoolean("private_origin"));assertEquals("original-node",pending.getString("node"))
        assertTrue(restored.watchReceiptAllowed(pending.getString("identity"),"original-node"));assertFalse(restored.watchReceiptAllowed(pending.getString("identity"),"other-node"))
        assertFalse(pending.getBoolean("watch_done"))
    }
    @Test fun lateCanonicalOriginAfterHistoryCanAddWatchCustodyButCannotResetItsPostedReceipt(){
        val store=InMemoryKeyValueStore();val r=ReceivedConversations(store){"scope"}
        val row=HistoryMessage(7,"assistant","final",1800000000.0,messageUid="uid",finishReason="stop")
        assertTrue(r.ingest("session",listOf(row),false,true,"unrelated-legacy-node"))
        val phoneIdentity=r.pending().single().getString("identity")
        assertTrue(r.acknowledge(phoneIdentity,true)) // an inferred legacy route is not original Live custody
        assertTrue(r.acknowledge(phoneIdentity,false)) // real ordering: Phone posted before canonical origin enrichment
        assertEquals("",r.canonicalRow("session",7)!!.getString("text"))
        assertTrue(r.expect("session",7,"original-node",false,watchReceipt=true))
        assertTrue(r.ingest("session",listOf(row),false,false,null))
        val pending=r.pending(true).single();val id=pending.getString("identity")
        assertEquals("canonical requery restores the body for the newly owed Watch", "final",pending.getString("text"))
        assertTrue(r.acknowledge(id,true));assertTrue(r.expect("session",7,"original-node",false,watchReceipt=true))
        assertTrue("duplicate canonical handoff never invents a second post",r.pending(true).isEmpty())
        assertThrows(IllegalStateException::class.java){r.expect("session",7,"foreign-original-node",false,watchReceipt=true)}
        assertEquals("a proven original node cannot be redirected", "original-node",r.canonicalRow("session",7)!!.getString("node"))
        assertFalse("posting is not reading",r.canonicalRow("session",7)!!.getBoolean("read"))
    }
    @Test fun knownSameRowTerminalUpdateRemainsNewWhileOmittedHistoricFinalsStayContextOnly(){
        val c=LiveSessionContext("session")
        val historic=(1L..30L).map{HistoryMessage(it,"assistant","old-$it",1.0,finishReason="stop")}
        val pending=HistoryMessage(31,"assistant","working",2.0,finishReason="length")
        assertEquals(20,c.page(HistoryPage("session",historic+pending,31,(1L..31L).toList()),true).size)
        assertTrue(c.page(HistoryPage("session",historic,30,(1L..30L).toList()),false).isEmpty())
        val update=c.page(HistoryPage("session",listOf(pending.copy(text="done",finishReason="stop")),1,listOf(31)),false).single()
        assertTrue(update.commentary);assertEquals(31L,update.rowId)
    }
}
