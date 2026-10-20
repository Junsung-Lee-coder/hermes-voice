package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.sessions.OwnedRole
import com.rumi.hermesvoice.core.sessions.OwnedSession
import com.rumi.hermesvoice.core.watchlink.PhoneReaderService
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real repository/client/HTTP contract double. The test models the Dashboard's default filtering,
 * not a nonexistent include_children/include_subagents flag. All identifiers/metadata are synthetic. */
class RegisteredCreatedSourceR9hTest {
    private val h=CoreHarness()
    private val repo get()=h.core.sessions
    private val original=h.fake.server.dispatcher
    private val omitted=HashSet<String>()
    private var detailStatus: Int?=null
    private var detailRewrite: ((JSONObject)->Unit)?=null
    private var detailHook: (() -> Unit)?=null
    private val metadata=java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    init {
        h.fake.server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path=request.requestUrl!!.encodedPath
                val response=original.dispatch(request)
                if(request.method!="GET" || response.status.substringAfter(' ').substringBefore(' ')!="200") return response
                if(path=="/api/sessions") {
                    val body=JSONObject(response.getBody()!!.clone().readUtf8())
                    val rows=body.getJSONArray("sessions");val kept=JSONArray();var dropped=0
                    for(i in 0 until rows.length()) {
                        val row=rows.getJSONObject(i).put("_delegate_from","synthetic-parent-preserved")
                        if(row.getString("id") in omitted) dropped++ else kept.put(row)
                    }
                    return response.setBody(body.put("sessions",kept).put("total",body.getInt("total")-dropped).toString())
                }
                if(path.startsWith("/api/sessions/") && path.removePrefix("/api/sessions/").contains('/').not()) {
                    detailHook?.invoke()
                    detailStatus?.let { return MockResponse().setResponseCode(it).setBody("{\"error\":\"synthetic detail failure\"}") }
                    val body=JSONObject(response.getBody()!!.clone().readUtf8()).put("_delegate_from","synthetic-parent-preserved")
                    detailRewrite?.invoke(body);metadata+=JSONObject(body.toString())
                    return response.setBody(body.toString())
                }
                return response
            }
        }
    }
    @After fun close() { scope.cancel();h.fake.server.dispatcher=original;h.close() }
    private fun known(id: String="known", archived: Boolean=false) {
        h.fake.addForeignRow(id,"discord",archived,"Title $id")
        h.fake.setCreatedSource(id,AppSources.CONVERSATION)
        h.registry.put(OwnedSession(id,OwnedRole.CONVERSATION,"Title $id",id,"",archived,1))
        omitted+=id
    }
    private fun listing(archived: Boolean=false)=runBlocking { repo.listConversations(archived) }
    private fun ids(archived: Boolean=false)=listing(archived).map { it.stored.id }
    private fun noMetadataWrites() {
        assertTrue(h.fake.restLog.toString(),h.fake.restLog.all { it.startsWith("GET ") })
        assertTrue(h.fake.rpcLog.isEmpty())
        assertTrue(h.fake.restLog.none { it.contains("include_children") || it.contains("include_subagents") })
        assertTrue(metadata.all { it.getString("_delegate_from")=="synthetic-parent-preserved" })
    }
    @Test fun L01_completedFilteredListIncludesRegisteredMarkedDelegate() {
        known();val before=h.registry.all();val listed=listing()
        assertEquals("registered marker-valid row must survive complete default-list omission",listOf("known"),listed.map { it.stored.id })
        assertTrue(listed.complete);assertEquals(0,listed.scanned);assertEquals(0,listed.total)
        assertEquals(before,h.registry.all());assertEquals("discord",h.fake.rows.getValue("known").source)
        assertEquals(AppSources.CONVERSATION,h.fake.rows.getValue("known").createdSource)
        assertEquals(1,metadata.size);noMetadataWrites()
    }
    @Test fun L02_archivedFilteredRegisteredDelegateIsVisibleOnlyInArchivedView() {
        known(archived=true)
        assertEquals(listOf("known"),ids(true));assertTrue(ids(false).isEmpty())
        assertTrue(h.registry.find("known")!!.archived);noMetadataWrites()
    }
    @Test fun L03_WatchSharedReaderKeepsRegisteredDelegateWithoutAdoptingUnknown()=runBlocking {
        known();h.fake.addForeignRow("unknown-marked","discord");h.fake.setCreatedSource("unknown-marked",AppSources.CONVERSATION)
        omitted+="unknown-marked"
        val result=ReaderResponse.decode(PhoneReaderService(repo).handle(ReaderRequest("req-r9h-0001",ReaderKind.SESSIONS).encode())!!)!!
        assertTrue(result.ok);assertEquals(listOf("known"),result.sessions.map { it.id })
        assertNull(h.registry.find("unknown-marked"));noMetadataWrites()
    }
    @Test fun L04_failedExactLookupReportsDegradedNotAuthoritativeEmptyComplete() {
        known();detailStatus=503
        val listed=listing()
        assertFalse("unverified omitted registered row must degrade completion",listed.complete)
        assertTrue(listed.isEmpty());assertNotNull(listed.error);assertNotNull(h.registry.find("known"))
        assertTrue(listed.removedIds.isEmpty());noMetadataWrites()
    }
    @Test fun L05_exactLookupRealRejectedRefreshStillSignalsAuthLoss() {
        known();detailStatus=401;h.fake.refreshAccepted=false
        try { listing();fail("omitted registered lookup must expose genuine auth loss") }
        catch(expected: HermesAuthRequiredException) { assertNull(h.tokens.load());assertEquals(1,h.fake.refreshes) }
    }
    @Test fun L06_filteredExactLookupCanBeCancelledWithoutRegistryRevocation() {
        known();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        detailHook={ entered.countDown();check(release.await(30,TimeUnit.SECONDS)) }
        val scan=scope.async { repo.listConversations(false) }
        try {
            assertTrue("filtered scan must request registered exact metadata",entered.await(10,TimeUnit.SECONDS))
            scan.cancel();release.countDown();runBlocking { withTimeout(20_000) { scan.join() } }
            assertTrue(scan.isCancelled);assertNotNull(h.registry.find("known"))
        } finally { release.countDown();detailHook=null;runBlocking { withTimeout(20_000) { scan.join() } } }
    }
    @Test fun C01_returnedMarkedDelegateNeedsNoDirectLookupAndIsNotDuplicated() {
        known();omitted.clear()
        assertEquals(listOf("known"),ids());assertTrue(metadata.isEmpty());noMetadataWrites()
    }
    @Test fun C02_registrySourceOrDelegateNeverAuthorizesBadOrMissingMarker() {
        for((i,marker) in listOf(null,"","discord",AppSources.ROUTER).withIndex()) {
            val id="bad-$i";known(id);h.fake.setCreatedSource(id,marker)
        }
        detailRewrite={ if(it.getString("id")=="bad-0") it.remove("created_source") }
        assertTrue(ids().isEmpty())
        for(i in 0..3) assertTrue(runBlocking { h.core.chat.send("bad-$i","not permitted") } is ChatSendResult.Failed)
        assertTrue(h.fake.prompts.isEmpty())
        assertTrue(h.fake.rows.values.all { it.source=="discord" });noMetadataWrites()
    }
    @Test fun C03_wrongExactIdentityAndAuthoritativeDeletionStayHiddenAndUnsendable() {
        known("wrong");known("deleted");h.fake.rows.remove("deleted")
        detailRewrite={ it.put("id","different-id") }
        assertTrue(ids().isEmpty())
        assertTrue(runBlocking { h.core.chat.send("wrong","not permitted") } is ChatSendResult.Failed)
        assertTrue(runBlocking { h.core.chat.send("deleted","not permitted") } is ChatSendResult.Failed)
        assertTrue(h.fake.prompts.isEmpty());noMetadataWrites()
    }
    @Test fun C04_unreturnedUnknownMarkedIdsCannotBeEnumeratedAndUnmarkedRowsStayHidden() {
        h.fake.addForeignRow("unknown","discord");h.fake.setCreatedSource("unknown",AppSources.CONVERSATION);omitted+="unknown"
        h.fake.addForeignRow("unmarked","discord")
        assertTrue(ids().isEmpty());assertTrue(h.registry.all().isEmpty());assertTrue(metadata.isEmpty());noMetadataWrites()
    }
    @Test fun C05_pagedMarkerStillAdoptsRegardlessOfDelegateOrMutableSource() {
        h.fake.addForeignRow("new-marked","tui");h.fake.setCreatedSource("new-marked",AppSources.CONVERSATION)
        assertEquals(listOf("new-marked"),ids());assertEquals(1,h.registry.all().size)
        assertEquals("tui",h.fake.rows.getValue("new-marked").source);noMetadataWrites()
    }
    @Test fun C06_incompleteDefaultPaginationRetainsItsExistingExactLookupFallback() {
        known();for(i in 1..101) h.fake.addForeignRow("noise-$i","cron")
        h.fake.failListFromOffset=100
        val listed=listing()
        assertFalse(listed.complete);assertEquals(listOf("known"),listed.map { it.stored.id })
        assertNotNull(listed.error);assertEquals(1,metadata.size);noMetadataWrites()
    }
}
