package com.rumi.hermesvoice.phone

import android.Manifest
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import kotlinx.coroutines.awaitCancellation
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The real Phone Live controller's startup order. The catalog read is held at the dashboard contract double; the audio route and
 * media are synthetic adapters that record when the controller acquires them. No platform audio, provider or device.
 * R29: an intentional call's catalog is read beside its route and media, not in front of them; choosing a conversation ([ready]) still
 * waits for the complete catalog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveStartupRouteOrderRoboTest : PhoneRoboBase() {
    private val steps = java.util.Collections.synchronizedList(ArrayList<String>())
    private val routes = ArrayList<Route>(); private val media = ArrayList<Media>()
    private var startsSession = true
    private val held = CountDownLatch(1); private val release = CountDownLatch(1)
    private inner class Route(val lost: () -> Unit) : LiveRoute {
        var closed = false; var observedAtClose = -1
        override fun open(): Boolean { steps += "open with ${app.live.state.value.choices.size} catalog rows"; return true }
        override fun valid() = !closed
        override fun close() { if (!closed) observedAtClose = liveSetupEvents().size; closed = true }
    }
    private inner class Media : LiveTransport {
        var silenced = false
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) {
            if (startsSession) event("{\"type\":\"session.started\",\"session\":{\"id\":\"route-order\"}}") else awaitCancellation()
        }
        override fun send(json: String) = true
        override fun mute(muted: Boolean) {}
        override fun output(enabled: Boolean) {}
        override fun silence() { silenced = true }
        override fun close() {}
    }
    @Before fun setUp() {
        baseSetUp()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = true, watchLiveEnabled = true))
        app.live.routeFactory = { lost -> steps += "route"; Route(lost).also { routes += it } }
        app.live.transportFactory = { steps += "transport"; Media().also { media += it } }
        app.live.watchRouteFactory = { steps += "watch route"; object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.watchTransportFactory = { _, _ -> steps += "watch transport"; Media().also { media += it } }
        app.live.admission = { app.live.admitted(it) }
        conversation("alpha")
    }
    @After fun tearDown() { release.countDown(); app.live.end(); settle(); baseTearDown() }
    private fun liveSetupEvents() = app.diagnostics.log.snapshot().filter { it.code.wire == "live_setup_failed" }
    private fun holdCatalog() { fake.listPageHook = { _, _ -> held.countDown(); release.await(10, TimeUnit.SECONDS) } }
    private fun startHeld(origin: VoiceOrigin = VoiceOrigin.PHONE) {
        assertTrue(app.live.start(origin, if (origin == VoiceOrigin.WATCH) "watch-node" else null))
        assertTrue("the Live catalog read is held at the dashboard", await { held.count == 0L }); settle()
        assertTrue(app.live.active); assertFalse(app.live.state.value.ready)
    }

    @Test fun heldCatalogNoLongerHoldsBackTheRouteAndTransportAndItsReleaseAddsNoSecondRoute() {
        holdCatalog(); startHeld()
        val acquired = listOf("route", "open with 0 catalog rows", "transport")
        assertTrue("the route and media are acquired while the catalog is still held", await { steps.toList() == acquired })
        assertFalse("no conversation can be chosen before the complete catalog", app.live.state.value.ready)
        release.countDown()
        assertTrue(await { app.live.state.value.ready })
        assertTrue("the held catalog lists the conversation", app.live.state.value.choices.isNotEmpty())
        assertEquals("exactly one fresh route and transport", acquired, steps.toList())
        assertEquals(1, routes.size); assertTrue(liveSetupEvents().isEmpty())
    }
    private fun endWhileCatalogHeld(stop: () -> Unit, status: String) {
        holdCatalog(); startHeld()
        assertTrue("the route and media were acquired beside the held catalog", await { media.size == 1 })
        val acquired = steps.toList()
        stop(); assertFalse(app.live.active)
        release.countDown(); settle(); idle(1_000); settle()
        assertEquals("no late Phone route or media after the call ended during the catalog", acquired, steps.toList())
        assertEquals(1, routes.size); assertTrue("its route is released", routes.single().closed)
        assertEquals(1, media.size); assertTrue("its audio stopped at once", media.single().silenced)
        assertTrue(app.live.state.value.status, app.live.state.value.status.startsWith(status))
        assertTrue("an intentional end is not a setup failure", liveSetupEvents().isEmpty())
    }
    @Test fun stopWhileCatalogIsHeldOpensNoLateRouteOrMedia() = endWhileCatalogHeld({ app.live.end() }, "Live ended")
    @Test fun modeOffWhileCatalogIsHeldOpensNoLateRouteOrMedia() =
        endWhileCatalogHeld({ app.settings.saveWatchSettings(app.settings.watchSettings().copy(phoneLiveEnabled = false)); app.live.settingsChanged() }, "Live mode off")
    @Test fun logoutWhileCatalogIsHeldOpensNoLateRouteOrMedia() =
        endWhileCatalogHeld({ app.tokens.clear(); app.invalidateSessionTitles() }, "Sign-in or connection scope changed")
    @Test fun catalogFailureEndsWithItsCatalogObservationAndReleasesWhateverRouteItAcquired() {
        fake.failListFromOffset = 0
        assertTrue(app.live.start()); assertTrue(await { !app.live.active })
        assertTrue("at most the call's one route, released", routes.size <= 1 && routes.all { it.closed })
        assertTrue("any media it reached stopped at once", media.size <= 1 && media.all { it.silenced })
        assertTrue(app.live.state.value.status, app.live.state.value.status.startsWith("Live conversation catalog could not be verified"))
        assertEquals(1, liveSetupEvents().size); assertEquals("live_catalog", liveSetupEvents().single().stage?.wire)
    }
    @Test fun currentPreReadyRouteLossIsObservedOnceAsUnknownSetupBeforeEndAndNeverReacquires() {
        startsSession = false
        assertTrue(app.live.start()); assertTrue("media setup reached with the route held", await { media.size == 1 })
        assertFalse(app.live.state.value.ready)
        val route = routes.single()
        route.lost(); route.lost() // two platform signals for the same loss
        assertTrue(await { !app.live.active })
        assertTrue(app.live.state.value.status, app.live.state.value.status.startsWith("Live audio route/focus lost; no speaker fallback"))
        assertTrue(media.single().silenced)
        assertEquals("one Live setup observation", 1, liveSetupEvents().size); val e = liveSetupEvents().single()
        assertEquals("phone", e.origin?.wire); assertEquals("unknown", e.fail?.wire); assertEquals("unknown", e.cause?.wire)
        assertEquals("live_input", e.stage?.wire); assertNull(e.status); assertNull(e.ref)
        assertEquals("observed before the call released its route", 1, route.observedAtClose)
        idle(61_000); settle()
        assertEquals("the cancelled media setup and the setup deadline add nothing", 1, liveSetupEvents().size)
        assertEquals("never reacquired", 1, routes.size); assertEquals(1, media.size)
    }
    @Test fun routeLossAfterReadyStaysRuntimeWithoutASetupObservation() {
        assertTrue(app.live.start()); assertTrue(await { app.live.state.value.ready })
        routes.single().lost()
        assertTrue(await { !app.live.active })
        assertTrue(app.live.state.value.status, app.live.state.value.status.startsWith("Live audio route/focus lost; no speaker fallback"))
        settle(); assertTrue(liveSetupEvents().isEmpty()); assertEquals(1, routes.size)
    }
    @Test fun lossCallbacksAfterStopOrFromAnOldCallAreSilentAndCannotEndTheNextCall() {
        assertTrue(app.live.start()); assertTrue(await { app.live.state.value.ready })
        val old = routes.single(); app.live.end(); assertFalse(app.live.active)
        old.lost(); settle()
        assertTrue(app.live.state.value.status, app.live.state.value.status.startsWith("Live ended"))
        startsSession = false; assertTrue(app.live.start()); assertTrue(await { media.size == 2 })
        old.lost(); settle()
        assertTrue("an old call's route callback cannot end the next preparing call", app.live.active)
        assertTrue(liveSetupEvents().isEmpty()); assertEquals(2, routes.size)
    }
    @Test fun watchOriginOpensItsRelayRouteAndTransportWithoutWaitingForTheCatalogAndNeverThePhoneRoute() {
        holdCatalog(); startHeld(VoiceOrigin.WATCH)
        assertTrue("the relay route and transport are acquired while the catalog is held", await { steps.toList() == listOf("watch route", "watch transport") })
        assertFalse(app.live.state.value.ready)
        release.countDown(); assertTrue(await { app.live.state.value.ready })
        assertEquals(listOf("watch route", "watch transport"), steps.toList()); assertTrue(routes.isEmpty())
    }
}
