package com.rumi.hermesvoice.watch

import android.Manifest
import android.os.Looper
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.settings.WatchSettings
import io.mockk.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.*
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Real runtime DEFAULT WatchChannelMedia; only Google Tasks/streams are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchOpenCancellationRoboTest {
    private class Setup(message:String):IllegalStateException(message)
    private lateinit var app:WatchApp;private lateinit var live:WatchLiveVoiceRuntime
    private lateinit var client:ChannelClient
    private val opening=TaskCompletionSource<ChannelClient.Channel>()
    private val incoming=TaskCompletionSource<InputStream>();private val outgoing=TaskCompletionSource<OutputStream>()
    private val sent=ConcurrentLinkedQueue<ByteArray>();private val closes=ConcurrentLinkedQueue<ChannelClient.Channel>()
    private val inputs=AtomicInteger();private val outputs=AtomicInteger();private val opened=AtomicInteger()
    private var id="";private var sequence=0L;private var path="";private var mediaId="media"
    private val channel=mockk<ChannelClient.Channel>()
    private val sourceClosed=AtomicInteger();private val sinkClosed=AtomicInteger()
    private val sourceRelease=java.util.concurrent.CountDownLatch(1)
    private val source=object:InputStream(){override fun read():Int {sourceRelease.await(10,java.util.concurrent.TimeUnit.SECONDS);return -1};override fun close(){sourceClosed.incrementAndGet();sourceRelease.countDown()}}
    private val sink=object:OutputStream(){override fun write(b:Int){};override fun close(){sinkClosed.incrementAndGet()}}
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    private fun await(done:()->Boolean):Boolean{val end=System.nanoTime()+5_000_000_000;while(System.nanoTime()<end){idle();if(done())return true;Thread.sleep(10)};return done()}
    @Before fun setup(){
        app=RuntimeEnvironment.getApplication() as WatchApp
        app.localStore.putString("live_control_id","");app.localStore.putString("live_control_node","")
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.applySettings(WatchSettings(watchLiveEnabled=true,revision=100).toJson());idle()
        mockkStatic(Wearable::class)
        val messages=mockk<MessageClient>();every{messages.sendMessage(any(),any(),any())}answers{sent+=thirdArg<ByteArray>();Tasks.forResult(1)}
        every{Wearable.getMessageClient(any<android.content.Context>())}returns messages
        client=mockk(relaxed=true)
        every{channel.nodeId}returns "phone";every{channel.path}answers{path}
        every{client.openChannel(any(),any())}answers{path=secondArg();opened.incrementAndGet();opening.task}
        every{client.getInputStream(any())}answers{inputs.incrementAndGet();incoming.task}
        every{client.getOutputStream(any())}answers{outputs.incrementAndGet();outgoing.task}
        every{client.close(any())}answers{closes+=firstArg<ChannelClient.Channel>();Tasks.forResult(null)}
        every{Wearable.getChannelClient(any<android.content.Context>())}returns client
        live=WatchLiveVoiceRuntime(app){"phone"};live.now={1000L};live.admission={live.admitted(it)}
        live.routeFactory={object:WatchLiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        // No replacement of the production mediaFactory.
    }
    @After fun cleanup(){live.end();idle();app.scope.coroutineContext[Job]?.cancel();unmockkAll()}
    private fun packet(op:String,body:String="")=live.onStatus("phone",WatchLiveWire.packet(id,mediaId,"epoch",++sequence,op,body))
    private fun start(){
        live.toggle();idle();id=sent.map{JSONObject(it.toString(Charsets.UTF_8))}.first{it.optString("op")=="start"}.getString("id")
        packet("open",JSONObject().put("stream","held-stream").put("relay",true).put("muted",true).put("private",false).toString())
        if(!await{opened.get()==1})throw Setup("default media did not await real open Task")
    }
    private fun noRevival(){repeat(5){idle()};assertFalse(live.state.value.active);assertFalse(live.state.value.inputActive);assertEquals(0,sent.mapNotNull{WatchLiveWire.parse(it)}.count{it.op=="offer"||it.op=="microphone"&&it.body=="true"});assertTrue(closes.all{it===channel})}
    /** Completion on the test/main thread resumes Main.immediate inline, which is already a
     * successful handoff, not a prompt-cancellation dispatch gap. Hold the real main dispatcher
     * paused while the actual Google Task completes on another thread instead. */
    private fun completeBeforeMainDispatch(action:()->Unit){
        val error=java.util.concurrent.atomic.AtomicReference<Throwable>()
        val worker=Thread{try{action()}catch(t:Throwable){error.set(t)}}
        worker.start();worker.join(5000)
        if(worker.isAlive||error.get()!=null)throw Setup("off-main Task completion failed: ${error.get()}")
    }
    private fun lateClosed(){opening.setResult(channel);assertTrue("late successful default open needs exactly one close owner",await{closes.size==1});assertEquals(1,closes.size);assertEquals(0,inputs.get());assertEquals(0,outputs.get());noRevival()}
    @Test fun stopBeforeOpenSuccessClosesLateChannelExactlyOnce(){start();live.end();lateClosed()}
    @Test fun offBeforeOpenSuccessClosesLateChannelExactlyOnce(){start();app.applySettings(WatchSettings(watchLiveEnabled=false,revision=101).toJson());live.settingsChanged();lateClosed()}
    @Test fun successBeforeDispatchThenStopCannotLoseChannel(){start();completeBeforeMainDispatch{opening.setResult(channel)};assertEquals("continuation must still be queued on paused Main",0,inputs.get());live.end();assertTrue("prompt cancellation after Task success must close the undelivered result",await{closes.size==1});assertEquals(0,inputs.get());assertEquals(0,outputs.get());noRevival()}
    @Test fun defaultOpenTimeoutStillOwnsLateSuccess(){
        start();val connection=live.javaClass.getDeclaredField("connection").apply{isAccessible=true}.get(live) as Job
        if(!await{connection.isCompleted})throw Setup("real default 5s open timeout did not fire")
        live.end();lateClosed()
    }
    @Test fun lateFailedTaskHasNoChannelOrMediaEffects(){start();live.end();opening.setException(IOException("synthetic open failure"));repeat(5){idle()};assertTrue(closes.isEmpty());assertEquals(0,inputs.get());noRevival()}
    @Test fun cancelledInputAcquisitionClosesLateStreamAndExactChannel(){
        start();opening.setResult(channel);if(!await{inputs.get()==1})throw Setup("input Task not awaited")
        live.end();incoming.setResult(source);assertTrue("late input needs a close owner",await{sourceClosed.get()==1});assertEquals(1,closes.size);assertEquals(0,outputs.get());noRevival()
    }
    @Test fun inputSuccessBeforeDispatchCancellationCannotLoseStream(){
        start();opening.setResult(channel);if(!await{inputs.get()==1})throw Setup("input Task not awaited")
        completeBeforeMainDispatch{incoming.setResult(source)};assertEquals("continuation must still be queued on paused Main",0,outputs.get());live.end();assertTrue("undelivered input must be closed",await{sourceClosed.get()==1});assertEquals(0,outputs.get());assertEquals(1,closes.size);noRevival()
    }
    @Test fun cancelledOutputAcquisitionClosesBothStreamsOnce(){
        start();opening.setResult(channel);incoming.setResult(source);if(!await{outputs.get()==1})throw Setup("output Task not awaited")
        live.end();outgoing.setResult(sink);assertTrue("late output needs a close owner",await{sinkClosed.get()==1});assertEquals(1,sourceClosed.get());assertEquals(1,closes.size);noRevival()
    }
    @Test fun oldLateChannelCannotClearNewDefaultMedia(){
        start();live.end()
        val newer=mockk<ChannelClient.Channel>();var newerPath="";every{newer.nodeId}returns "phone";every{newer.path}answers{newerPath}
        every{client.openChannel(any(),any())}answers{newerPath=secondArg();Tasks.forResult(newer)}
        live.toggle();idle();id=sent.map{JSONObject(it.toString(Charsets.UTF_8))}.last{it.optString("op")=="start"}.getString("id");sequence=0
        mediaId="new-media";packet("open",JSONObject().put("stream","new-stream").put("relay",true).put("muted",true).toString())
        if(!await{inputs.get()==1})throw Setup("new default media missing")
        opening.setResult(channel);assertTrue("old late resource must close independently",await{closes.contains(channel)})
        assertFalse(closes.contains(newer));assertTrue(live.state.value.active)
        live.end();assertEquals(1,closes.count{it===newer});assertEquals(1,closes.count{it===channel})
    }
    @Test fun concurrentCloseAndSuccessfulOpenHaveExactlyOneOwner()=runBlocking{
        repeat(24){
            val task=TaskCompletionSource<ChannelClient.Channel>();val entered=java.util.concurrent.CountDownLatch(1)
            val address=com.rumi.hermesvoice.core.live.WatchRelayAudio.Address("c","m","e","s");path=address.path()
            every{client.openChannel(any(),any())}answers{entered.countDown();task.task}
            val media=WatchChannelMedia(app,"phone",address,{true},{true},{})
            val job=launch(Dispatchers.Default){try{media.connect({"phone-relay-ready"},{})}catch(_:Exception){/* concurrent terminal admission is allowed; exact resource custody is asserted below */}}
            if(!entered.await(5,java.util.concurrent.TimeUnit.SECONDS))throw Setup("race did not enter actual default open")
            val gate=java.util.concurrent.CountDownLatch(1)
            val complete=Thread{gate.await();task.setResult(channel)};complete.start();gate.countDown();media.close();job.cancelAndJoin();complete.join()
        }
        assertTrue(await{closes.size==24});assertEquals("close/open handoff must neither leak nor double-close",24,closes.size);assertEquals(0,outputs.get())
    }
    @Test fun delayedSuccessfulOpenStillConnectsAndNormalCloseOwnsAllResources(){
        start();opening.setResult(channel);incoming.setResult(source);outgoing.setResult(sink)
        if(!await{sent.mapNotNull{WatchLiveWire.parse(it)}.any{it.op=="offer"}})throw Setup("normal default offer missing")
        packet("answer","phone-relay-ready")
        assertTrue("a valid delayed open must connect, not fail all starts",await{live.state.value.active&&!live.state.value.pending})
        assertTrue(live.state.value.muted);assertEquals(1,inputs.get());assertEquals(1,outputs.get());assertTrue(closes.isEmpty())
        live.end();assertEquals(1,closes.size);assertEquals(1,sourceClosed.get());assertEquals(1,sinkClosed.get())
    }
}
