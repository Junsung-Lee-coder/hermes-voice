package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import io.mockk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * R29: the Phone tells the Watch, in its existing control status, the moment Watch speech can reach the provider (started, verified
 * input, not muted) and when it no longer can. The real controller and PhoneLiveWatchControl; the Watch relay media is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveWatchListeningEnvelopeR29RoboTest : PhoneRoboBase() {
    private val replies = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
    private var mic: ((Boolean) -> Unit)? = null
    @Before fun setUp() {
        baseSetUp(); shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val watch = mockk<Node>(); every { watch.id } returns "watch-node"
        val info = mockk<CapabilityInfo>(); every { info.nodes } returns setOf(watch)
        val caps = mockk<CapabilityClient>(); every { caps.getCapability(any(), any()) } returns Tasks.forResult(info)
        every { Wearable.getCapabilityClient(any<Context>()) } returns caps
        val messages = mockk<MessageClient>(); every { messages.sendMessage(any(), any(), any()) } answers {
            if (secondArg<String>() == PhoneLiveWatchControl.STATE) runCatching { JSONObject(thirdArg<ByteArray>().toString(Charsets.UTF_8)) }.getOrNull()
                ?.takeIf { it.optInt("v") == 1 }?.let { replies += it }
            Tasks.forResult(1)
        }
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = false, watchLiveEnabled = true))
        app.live.admission = { app.live.admitted(it) }
        app.live.watchRouteFactory = { object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.watchTransportFactory = { _, _ -> object : LiveTransport {
            override fun observeMicrophone(listener: (Boolean) -> Unit) { mic = listener }
            override suspend fun connect(history: JSONArray, event: (String) -> Unit) { event("{\"type\":\"session.started\",\"session\":{\"id\":\"watch-r29\"}}") }
            override fun send(json: String) = true
            override fun mute(muted: Boolean) { if (muted) mic?.invoke(false) } // the relayed Watch input reports itself again on unmute
            override fun output(enabled: Boolean) {}
            override fun silence() { mic?.invoke(false) }
            override fun close() {}
        } }
        conversation("alpha")
    }
    @After fun tearDown() { app.live.end(); settle(); baseTearDown() }
    private fun control(op: String, extra: JSONObject.() -> Unit = {}) = app.liveWatch.receive("watch-node",
        JSONObject().put("v", 1).put("id", "watch-r29").put("op", op).put("probe", "probe-$op-${replies.size}").apply(extra).toString().toByteArray())
    private fun last() = replies.last()

    @Test fun theStatusSaysListeningOnlyOnceTheProviderStartedWithVerifiedUnmutedWatchInput() {
        control("start")
        assertTrue("the provider started and the complete catalogue arrived", await(20_000) { app.live.state.value.ready && mic != null }); settle()
        assertFalse("no verified Watch input yet: not listening", last().optBoolean("listening", true))
        mic!!(true)
        assertTrue("started with verified input: listening", await { last().optBoolean("listening") })
        control("mute") { put("muted", true) }
        assertTrue("muted: not listening", await { app.live.state.value.muted && !last().optBoolean("listening", true) })
        control("mute") { put("muted", false) }; settle()
        assertFalse("unmuted, but the Watch input has not reported itself again", last().optBoolean("listening", true))
        mic!!(true)
        assertTrue(await { last().optBoolean("listening") })
        control("end")
        assertTrue(await { !app.live.active && !last().optBoolean("active") })
        assertFalse("an ended call is never listening", last().optBoolean("listening", true))
        assertTrue(fake.prompts.isEmpty())
    }
}
