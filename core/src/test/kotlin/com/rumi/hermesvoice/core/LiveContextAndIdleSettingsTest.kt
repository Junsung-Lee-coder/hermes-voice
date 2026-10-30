package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.LiveSessionContext
import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.settings.WatchSettings
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class LiveContextAndIdleSettingsTest {
    @Test fun everyIdleSecondRoundTripsWithTenDefaultAndStrictBounds() {
        assertEquals(10,WatchSettings.fromJson("{}").liveIdleSeconds)
        for(n in 1..60) assertEquals(n,WatchSettings.fromJson(WatchSettings(liveIdleSeconds=n).toJson()).liveIdleSeconds)
        for(v in listOf("0","61","-1","1.5","\"10\"","null")) assertNull(v,WatchSettings.parse("{\"live_idle_seconds\":$v}"))
    }
    @Test fun contextUsesVisibleProjectionAndNeverTreatsToolsOrControlsAsDialogue() {
        val rows=org.json.JSONArray()
            .put(JSONObject().put("id",1).put("role","user").put("content","human quote NO_REPLY"))
            .put(JSONObject().put("id",2).put("role","user").put("content","hidden carrier").put("display_kind","hidden"))
            .put(JSONObject().put("id",3).put("role","tool").put("content","private tool"))
            .put(JSONObject().put("id",4).put("role","assistant").put("content","NO_REPLY"))
        val page=HistoryPage.parse(JSONObject().put("session_id","a").put("messages",rows))
        val context=LiveSessionContext("a")
        val output=context.page(page,true)
        assertEquals(1,output.size);assertTrue(output.single().content.contains("human quote NO_REPLY"));assertFalse(output.single().commentary)
        assertTrue(context.page(page,false).isEmpty());assertEquals(4,page.rawIds.size)
    }
    @Test fun canonicalArrivalDedupAndCrossSessionGuardDoNotUseSpeechOrTextEquality() {
        val context=LiveSessionContext("a")
        val final=HistoryMessage(10,"assistant","same text",1.0,finishReason="stop")
        val first=context.page(HistoryPage("a",listOf(final),1),false)
        assertTrue(first.single().commentary);assertFalse(context.ownFinal(10));assertTrue(context.page(HistoryPage("a",listOf(final),1),false).isEmpty())
        assertTrue(context.page(HistoryPage("a",listOf(final.copy(rowId=11)),1),false).single().commentary)
        try { context.page(HistoryPage("b",listOf(final),1),false);fail("other session") } catch(_:IllegalArgumentException){}
    }
}
