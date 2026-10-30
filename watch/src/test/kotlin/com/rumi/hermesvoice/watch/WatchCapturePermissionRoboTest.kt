package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.live.WatchRelayAudio
import io.mockk.*
import kotlinx.coroutines.*
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Direct production capture boundary, not a replacement mediaFactory. Platform/transport doubles
 * carry synthetic PCM only. Runtime's upstream permission guard is deliberately not the subject. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchCapturePermissionRoboTest {
    private class Setup(message:String):IllegalStateException(message)
    private lateinit var app:WatchApp
    private lateinit var media:WatchChannelMedia
    private lateinit var record:AudioRecord
    private val builds=AtomicInteger();private val reads=AtomicInteger();private val failures=AtomicInteger()
    private val released=AtomicInteger();private val sourceClosed=AtomicInteger();private val sinkClosed=AtomicInteger();private val channelClosed=AtomicInteger()
    private val sourceRelease=CountDownLatch(1);private val captureRelease=CountDownLatch(1)
    private val microphone=java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
    private val uplink=ByteArrayOutputStream()
    private val pcm=ByteArray(WatchRelayAudio.FRAME_BYTES){(it%117).toByte()}
    @Volatile private var stopped=false
    @Volatile private var denyConstruction=false
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    private fun await(done:()->Boolean):Boolean {val end=System.nanoTime()+5_000_000_000;while(System.nanoTime()<end){idle();if(done())return true;Thread.sleep(10)};return done()}
    @Before fun setup(){
        app=RuntimeEnvironment.getApplication() as WatchApp
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val address=WatchRelayAudio.Address("permission-control","media","epoch","stream")
        mockkStatic(Wearable::class)
        val client=mockk<ChannelClient>(relaxed=true);val channel=mockk<ChannelClient.Channel>()
        every{channel.nodeId}returns "phone";every{channel.path}returns address.path()
        val source=object:InputStream(){override fun read():Int {sourceRelease.await(10,TimeUnit.SECONDS);return -1};override fun close(){sourceClosed.incrementAndGet();sourceRelease.countDown()}}
        val sink=object:OutputStream(){override fun write(b:Int){synchronized(uplink){uplink.write(b)}};override fun close(){sinkClosed.incrementAndGet()}}
        every{client.openChannel(any(),any())}returns Tasks.forResult(channel)
        every{client.getInputStream(channel)}returns Tasks.forResult(source as InputStream)
        every{client.getOutputStream(channel)}returns Tasks.forResult(sink as OutputStream)
        every{client.close(channel)}answers{channelClosed.incrementAndGet();Tasks.forResult(null)}
        every{Wearable.getChannelClient(any<android.content.Context>())}returns client
        Class.forName("android.media.AudioRecord")
        mockkStatic(AudioRecord::class);every{AudioRecord.getMinBufferSize(any(),any(),any())}returns 1920
        mockkConstructor(AudioRecord.Builder::class);record=mockk(relaxed=true)
        every{anyConstructed<AudioRecord.Builder>().setAudioSource(any())}answers{self as AudioRecord.Builder}
        every{anyConstructed<AudioRecord.Builder>().setAudioFormat(any())}answers{self as AudioRecord.Builder}
        every{anyConstructed<AudioRecord.Builder>().setBufferSizeInBytes(any())}answers{self as AudioRecord.Builder}
        every{anyConstructed<AudioRecord.Builder>().build()}answers{builds.incrementAndGet();if(denyConstruction)throw SecurityException("synthetic permission revoked during build");record}
        every{record.state}returns AudioRecord.STATE_INITIALIZED
        every{record.recordingState}returns AudioRecord.RECORDSTATE_RECORDING
        every{record.audioSessionId}returns 17
        every{record.read(any<ByteArray>(),any(),any(),any())}answers{
            if(reads.getAndIncrement()==0){pcm.copyInto(firstArg());pcm.size}
            else{captureRelease.await(10,TimeUnit.SECONDS);-3}
        }
        every{record.stop()}answers{stopped=true;captureRelease.countDown()}
        every{record.release()}answers{released.incrementAndGet();Unit}
        media=WatchChannelMedia(app,"phone",address,{true},{true},{failures.incrementAndGet();media.close()})
        media.observeMicrophone{microphone+=it}
    }
    @After fun cleanup(){
        if(::media.isInitialized)media.close();sourceRelease.countDown();captureRelease.countDown()
        idle();app.scope.coroutineContext[Job]?.cancel();unmockkAll()
    }
    private fun connect(signal:suspend(String)->String={"phone-relay-ready"})=runBlocking {media.connect(signal,{})}
    private fun refused(){
        assertTrue("capture edge must resolve",await{builds.get()>0||failures.get()==1})
        assertEquals(0,builds.get());assertTrue("capture refusal must reach terminal callback",await{failures.get()==1});assertEquals(0,reads.get());assertFalse(microphone.contains(true))
        assertTrue(microphone.contains(false));assertEquals(0,uplink.size())
        assertEquals(1,sourceClosed.get());assertEquals(1,sinkClosed.get());assertEquals(1,channelClosed.get())
        media.close();assertEquals(1,channelClosed.get())
    }
    @Test fun deniedAtDirectDefaultCaptureBoundaryNeverConstructsRecorderAndClosesTransport(){
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        if(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_DENIED)throw Setup("denied fixture not effective")
        connect();refused()
    }
    @Test fun permissionRevokedDuringChannelNegotiationIsRecheckedAtCaptureEffect(){
        connect {shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO);"phone-relay-ready"}
        refused()
    }
    @Test fun constructionTimeSecurityDenialAfterGrantIsGracefulAndNeverStartsCapture(){
        denyConstruction=true;connect()
        assertTrue(await{failures.get()==1});assertEquals(1,builds.get());assertEquals(0,reads.get())
        assertFalse(microphone.contains(true));assertEquals(0,uplink.size())
        verify(exactly=0){record.startRecording()};assertEquals(0,released.get())
        assertEquals(1,sourceClosed.get());assertEquals(1,sinkClosed.get());assertEquals(1,channelClosed.get())
    }
    @Test fun grantedPermissionStillCapturesWatchPcmAndReleasesRecorderOnClose(){
        connect();assertTrue("permitted default must emit actual adapter frame",await{synchronized(uplink){uplink.size()>=pcm.size+28}})
        val frame=WatchRelayAudio.read(DataInputStream(ByteArrayInputStream(synchronized(uplink){uplink.toByteArray()})),WatchRelayAudio.UPLINK)
        assertArrayEquals(pcm,frame.pcm);assertTrue(microphone.contains(true));assertEquals(1,builds.get());assertEquals(0,failures.get())
        media.close();assertTrue(await{released.get()==1});assertTrue(stopped);assertTrue(microphone.contains(false))
        verify(exactly=1){record.startRecording()};assertEquals(1,channelClosed.get())
    }
}
