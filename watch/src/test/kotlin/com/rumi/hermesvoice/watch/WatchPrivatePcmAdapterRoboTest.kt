package com.rumi.hermesvoice.watch

import com.rumi.hermesvoice.core.live.LivePcmFrame
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.RtpReceiver
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

/** Executes the production Watch remote-track adapter against the pinned Java API; JNI/OS media are mocked, not observed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WatchPrivatePcmAdapterRoboTest {
    private lateinit var media:WatchWebRtcMedia;private lateinit var module:JavaAudioDeviceModule
    private lateinit var track:AudioTrack;private lateinit var receiver:RtpReceiver
    private val sink=slot<AudioTrackSink>();private val frames=ArrayList<LivePcmFrame>()
    @Before fun setup(){
        media=WatchWebRtcMedia(RuntimeEnvironment.getApplication())
        module=mockk(relaxed=true);track=mockk(relaxed=true);receiver=mockk(relaxed=true)
        every{receiver.track()}returns track;every{track.addSink(capture(sink))}just Runs
        WatchWebRtcMedia::class.java.getDeclaredField("module").also{it.isAccessible=true}.set(media,module)
    }
    @After fun cleanup(){media.close();unmockkAll()}
    private fun attach(){WatchWebRtcMedia::class.java.getDeclaredMethod("attachRemote",RtpReceiver::class.java).also{it.isAccessible=true}.invoke(media,receiver)}
    @Test fun mutedWatchAdmStillRelaysCopiedRemotePcmThroughTheRealSupportedTrackSink(){
        media.privateOutput{frames+=it};media.output(true);attach()
        val bytes=byteArrayOf(1,2,3,4);sink.captured.onData(ByteBuffer.wrap(bytes),16,48000,1,2,0)
        bytes.fill(0);assertEquals(1,frames.size);assertArrayEquals(byteArrayOf(1,2,3,4),frames.single().pcm)
        verify(exactly=0){module.setSpeakerMute(false)};verify(atLeast=1){module.setSpeakerMute(true)}
        verify(exactly=0){module.setMicrophoneMute(any())}
        media.output(false);sink.captured.onData(ByteBuffer.allocate(4),16,48000,1,2,0);assertEquals(1,frames.size)
    }
    @Test fun duplicateTrackCallbacksDoNotInstallTwoSinksAndTeardownRevokesLatePcm(){
        media.privateOutput{frames+=it};media.output(true);attach();attach()
        verify(exactly=1){track.addSink(any())}
        val late=sink.captured;media.close();late.onData(ByteBuffer.allocate(4),16,48000,1,2,0)
        assertTrue(frames.isEmpty());verify(exactly=1){track.removeSink(any())}
    }
}
