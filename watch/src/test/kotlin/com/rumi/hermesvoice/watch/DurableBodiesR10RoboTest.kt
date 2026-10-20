package com.rumi.hermesvoice.watch

import android.content.Context
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.watchlink.*
import io.mockk.*
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[34],shadows=[RecordingVibratorShadow::class])
class DurableBodiesR10RoboTest {
    private lateinit var original: WatchApp
    private val requests=ConcurrentLinkedQueue<ReaderRequest>()
    private val namespace="a".repeat(64)
    private val id="20260101_120000_workwork"
    @Before fun setup() {
        original=RuntimeEnvironment.getApplication() as WatchApp
        mockkStatic(Wearable::class)
        val capabilities=mockk<CapabilityClient>()
        val node=mockk<Node>();every { node.id } returns "phone-node";every { node.isNearby } returns true
        val info=mockk<CapabilityInfo>();every { info.nodes } returns setOf(node)
        every { capabilities.addListener(any<CapabilityClient.OnCapabilityChangedListener>(),any<String>()) } returns Tasks.forResult(null)
        every { capabilities.getCapability(any(),any()) } returns Tasks.forResult(info)
        val messages=mockk<MessageClient>()
        every { messages.sendMessage(any(),any(),any()) } answers {
            if(secondArg<String>()==WatchLinkPaths.READER_REQUEST) ReaderRequest.decode(thirdArg<ByteArray>())?.let { requests+=it }
            Tasks.forResult(1)
        }
        every { Wearable.getCapabilityClient(any<Context>()) } returns capabilities
        every { Wearable.getMessageClient(any<Context>()) } returns messages
    }
    @After fun cleanup()=unmockkAll()
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    private fun cold()=WatchApp().also {
        ReflectionHelpers.callInstanceMethod<Void>(it,"attach",ClassParameter.from(Context::class.java,original.baseContext));it.onCreate()
    }
    private fun respond(app: WatchApp,response: ReaderResponse) { app.onReaderResponse("phone-node",response.encode());idle() }
    private fun waitFor(what: String,done: () -> Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(!done() && System.nanoTime()<end) { idle();Thread.yield() }
        assertTrue(what,done())
    }
    @Test fun B01_coldWatchOpenShowsSavedBodyBeforeUnansweredPhoneRead() {
        val app=cold();app.loadSessions();idle()
        respond(app,ReaderResponse(requests.last().reqId,ReaderKind.SESSIONS,true,scope=namespace,
            sessions=listOf(ReaderSessionRow(id,"Body","alias","",1.0))))
        app.selectSession(id);idle()
        val request=requests.last()
        respond(app,ReaderResponse(request.reqId,ReaderKind.HISTORY,true,scope=namespace,sessionId=id,
            messages=listOf(ReaderMessageRow(17,"assistant","canonical 한국어",false)),nextOffset=3,hasOlder=true))
        assertEquals("canonical 한국어",app.reader.value.selectedHistory!!.messages.single().text)
        waitFor("canonical reader body durably saved") {
            original.localStore.getString("reader_bodies_v1")?.contains("canonical 한국어")==true
        }
        val next=cold();next.selectSession(id);idle()
        assertEquals("saved body must precede held Phone read",listOf(17L),next.reader.value.selectedHistory!!.messages.map { it.rowId })
        assertEquals(3,next.reader.value.selectedHistory!!.nextOlderOffset)
        val pending=requests.last()
        respond(next,ReaderResponse(pending.reqId,ReaderKind.HISTORY,true,scope=namespace,sessionId=id,
            messages=listOf(ReaderMessageRow(17,"assistant","canonical 한국어",false),ReaderMessageRow(18,"assistant","new",false)),nextOffset=4))
        assertEquals(listOf(17L,18L),next.reader.value.selectedHistory!!.messages.map { it.rowId })
    }
    @Test fun B03_explicitRemovalEvictsBodyBeforeColdReopen() {
        val app=cold();app.loadSessions();idle()
        respond(app,ReaderResponse(requests.last().reqId,ReaderKind.SESSIONS,true,scope=namespace,
            sessions=listOf(ReaderSessionRow(id,"Body","alias","",1.0))))
        app.selectSession(id);idle()
        respond(app,ReaderResponse(requests.last().reqId,ReaderKind.HISTORY,true,scope=namespace,sessionId=id,
            messages=listOf(ReaderMessageRow(17,"assistant","revoked body",false)),nextOffset=1))
        waitFor("body saved") { original.localStore.getString("reader_bodies_v1")?.contains("revoked body")==true }
        app.loadSessions();idle()
        respond(app,ReaderResponse(requests.last().reqId,ReaderKind.SESSIONS,true,scope=namespace,
            removedSessionIds=listOf(id)))
        waitFor("removed body durably fenced") { original.localStore.getString("reader_bodies_v1")?.contains("revoked body")!=true }
        val next=cold();next.selectSession(id);idle()
        assertTrue(next.reader.value.selectedHistory!!.messages.isEmpty())
    }
}
