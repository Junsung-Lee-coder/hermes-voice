package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val BUDGET = 5_000L
private const val CONTINUOUS = 30_000L

/**
 * A device driven the way its Android runtime drives it, on a fake monotonic clock: the real [com.rumi.hermesvoice.core.background.PhoneBackgroundWake]
 * or [com.rumi.hermesvoice.core.background.WatchVoiceCoordinator] with the real controller, presence, session and holds. Only the platform is
 * fake. [advanceTo] fires, in time order, the recognizer deadline and the single re-arm alarm the device scheduled, exactly as the platform would.
 */
internal abstract class Dev(val name: String) {
    abstract var now: Long
    abstract val wake: WakeDeviceController
    protected abstract fun newSchedules(): List<String>
    abstract fun listenCount(): Int
    abstract fun listenHold(): Boolean
    abstract fun handoffHold(): Boolean
    abstract fun sent(): List<String>
    abstract fun captured(): Int
    abstract fun captureCancelled(): Boolean

    /** The absolute time of the single pending re-arm alarm, or null. */
    var rearmAt: Long? = null
        private set

    /** (opened at, deadline at that moment) of every recognizer window, in order. */
    val windows = mutableListOf<Pair<Long, Long>>()
    private var seen = 0

    protected fun ev(block: () -> Unit) {
        block()
        for (e in newSchedules()) rearmAt = if (e == "cancel") null else now + e.substring(3).toLong()
        while (listenCount() > seen) { seen++; windows += now to wake.windowDeadlineMs() }
    }

    abstract fun boot()
    abstract fun bootVisible()
    abstract fun hide()
    abstract fun deactivate()
    abstract fun activate()
    abstract fun dupScreenOn()
    abstract fun eligibility()
    abstract fun replaySettings()
    abstract fun screenOffPref(on: Boolean)
    abstract fun busy(on: Boolean)
    abstract fun idle()
    abstract fun playbackBusy()
    abstract fun error(code: Int)
    abstract fun heard(text: String, final: Boolean = true)
    abstract fun handoffDue()
    abstract fun captureEnded()
    abstract fun standbyOff()
    abstract fun standbyOnHidden()
    protected abstract fun fireWindow()
    protected abstract fun fireRearm()

    /** A late alarm callback the platform delivers although nothing is scheduled (or one that is long gone). */
    fun staleRearm() = fireRearm()

    fun advanceTo(t: Long) {
        var guard = 0
        while (true) {
            if (++guard > 400) fail("$name: runaway timer loop at now=$now")
            val w = if (wake.listening) wake.windowDeadlineMs() else null
            val dueW = w?.takeIf { it <= t }
            val dueR = rearmAt?.takeIf { it <= t }
            when {
                dueW != null && (dueR == null || dueW <= dueR) -> { now = maxOf(now, dueW); fireWindow() }
                dueR != null -> { now = maxOf(now, dueR); rearmAt = null; fireRearm() }
                else -> break
            }
        }
        now = maxOf(now, t)
    }

    /** Booted, then the screen goes dark and a minute passes: the next activation is the next event. */
    fun settleDark(): Long {
        boot()
        deactivate()
        advanceTo(now + 60_000)
        return now
    }

    fun assertDark(why: String) {
        assertFalse("$name $why: a recognizer window is open", wake.listening)
        assertFalse("$name $why: a LISTEN hold is left", listenHold())
        assertFalse("$name $why: a HANDOFF hold is left", handoffHold())
        assertNull("$name $why: a re-arm alarm is pending", rearmAt)
    }

    fun assertListeningUntil(deadline: Long, why: String) {
        assertTrue("$name $why: no window is open", wake.listening)
        assertTrue("$name $why: no LISTEN hold", listenHold())
        assertEquals("$name $why: the window's deadline", deadline, wake.windowDeadlineMs())
    }
}

internal class PhoneDev(pref: Boolean, master: Boolean = true, other: Boolean = !pref) : Dev("phone") {
    val rig = ScreenOffPhoneTest.Rig(pref, master, WakeLocation.PHONE, other)
    override var now: Long
        get() = rig.now
        set(value) { rig.now = value }
    override val wake: WakeDeviceController get() = rig.wake
    private var scanned = 0

    override fun newSchedules(): List<String> {
        val fresh = rig.calls.drop(scanned)
        scanned = rig.calls.size
        return fresh.filter { it.startsWith("rearm_in:") || it == "rearm_cancel" }.map { if (it == "rearm_cancel") "cancel" else "in:" + it.substring(9) }
    }

    override fun listenCount() = rig.listened()
    override fun listenHold() = HoldReason.LISTEN in rig.held
    override fun handoffHold() = HoldReason.HANDOFF in rig.held
    override fun sent() = rig.calls.filter { it.startsWith("send:") }
    override fun captured() = rig.calls.count { it == "capture" }
    override fun captureCancelled() = rig.calls.indexOf("capture").let { at -> at >= 0 && rig.calls.drop(at).any { it.startsWith("capture_cancelled") || it.startsWith("cancel_capture") } }

    override fun boot() = ev { rig.startedAndHidden() }
    override fun bootVisible() = ev { rig.background.onAppShown(); rig.background.start(); rig.drain() }
    override fun hide() = ev { rig.background.onAppHidden(); rig.drain() }
    override fun deactivate() = ev { rig.screenOff() }
    override fun activate() = ev { rig.screenOnEvent() }
    override fun dupScreenOn() = ev { rig.background.onEligibilityChanged(); rig.drain() }
    override fun eligibility() = ev { rig.background.onEligibilityChanged(); rig.drain() }
    override fun replaySettings() = ev {
        rig.settings = rig.settings.copy(revision = rig.settings.revision + 1)
        rig.background.onEligibilityChanged(); rig.drain()
    }
    override fun screenOffPref(on: Boolean) = ev { rig.preference(on) }
    override fun busy(on: Boolean) = ev { rig.busy = on; if (on) rig.background.onBusy(); rig.drain() }
    override fun idle() = ev { rig.busy = false; rig.background.onIdle(); rig.drain() }
    override fun playbackBusy() = ev { rig.background.onPlaybackBusy(); rig.drain() }
    override fun error(code: Int) = ev { rig.wake.onError(rig.wake.generation, code); rig.drain() }
    override fun heard(text: String, final: Boolean) = ev {
        rig.wake.onResults(rig.wake.generation, listOf(text), final); rig.background.onRecognizerActivity(); rig.drain()
    }
    override fun handoffDue() = ev { if (rig.wake.onHandoffDue(captureIdle = true)) { rig.recording = true; rig.busy = true }; rig.drain() }
    override fun captureEnded() = ev { rig.recording = false; rig.busy = false; rig.wake.onRequestCaptureEnded(sent = true); rig.drain() }
    override fun standbyOff() = ev {
        rig.settings = rig.settings.copy(revision = rig.settings.revision + 1, phoneBackgroundWakeEnabled = false)
        rig.background.onEligibilityChanged()
        rig.background.onStandbyOff(); rig.drain()
    }
    override fun standbyOnHidden() = ev {
        rig.settings = rig.settings.copy(revision = rig.settings.revision + 1, phoneBackgroundWakeEnabled = true)
        rig.background.onEligibilityChanged()
        rig.background.onAppShown(); rig.background.start(); rig.background.onAppHidden(); rig.drain()
    }
    override fun fireWindow() = ev { rig.wake.onTimer(); rig.drain() }
    override fun fireRearm() = ev { rig.background.onRearmDue(); rig.drain() }
}

internal class WatchDev(
    pref: Boolean,
    master: Boolean = true,
    other: Boolean = !pref,
    mode: WakeLocation = WakeLocation.WATCH,
) : Dev("watch") {
    val w = ComposedWatch(mode, watchStandby = master, phoneStandby = false, watchScreenOff = pref, phoneScreenOff = other)
    override var now: Long
        get() = w.now
        set(value) { w.now = value }
    override val wake: WakeDeviceController get() = w.wake
    private var scanned = 0

    override fun newSchedules(): List<String> {
        val fresh = w.rearmLog.drop(scanned)
        scanned = w.rearmLog.size
        return fresh
    }

    override fun listenCount() = w.listens()
    override fun listenHold() = HoldReason.LISTEN in w.held()
    override fun handoffHold() = HoldReason.HANDOFF in w.held()
    override fun sent() = w.log.filter { it.startsWith("send:") }
    override fun captured() = w.log.count { it == "capture" }
    override fun captureCancelled() = w.log.indexOf("capture").let { at -> at >= 0 && w.log.drop(at).any { it.startsWith("cancel_capture") } }

    override fun boot() = ev { w.show(); w.start(); w.hide() }
    override fun bootVisible() = ev { w.show(); w.start() }
    override fun hide() = ev { w.hide() }
    override fun deactivate() = ev { w.screenOff() }
    override fun activate() = ev { w.screenOnEvent() }
    override fun dupScreenOn() = ev { w.coordinator.onScreenOn(); w.drain() }
    override fun eligibility() = ev { w.coordinator.onEligibilityChanged(); w.drain() }
    override fun replaySettings() = ev {
        w.settings = w.settings.copy(revision = w.settings.revision + 1)
        w.coordinator.onEligibilityChanged(); w.drain()
    }
    override fun screenOffPref(on: Boolean) = ev { w.screenOffPreference(watch = on) }
    override fun busy(on: Boolean) = ev { w.busy = on; if (on) w.coordinator.onBusy(); w.drain() }
    override fun idle() = ev { w.busy = false; w.coordinator.onIdle(); w.drain() }
    override fun playbackBusy() = ev { w.coordinator.presence.onPlaybackBusy(); w.coordinator.sync(); w.drain() }
    override fun error(code: Int) = ev { w.wake.onError(w.wake.generation, code); w.drain() }
    override fun heard(text: String, final: Boolean) = ev { w.heard(text, final) }
    override fun handoffDue() = ev { w.handoffIn = null; if (w.wake.onHandoffDue(captureIdle = !w.capturing)) w.busy = true; w.drain() }
    override fun captureEnded() = ev {
        w.capturing = false; w.busy = false; w.holds.release(HoldReason.CAPTURE); w.wake.onRequestCaptureEnded(sent = true); w.drain()
    }
    override fun standbyOff() = ev { w.standbyChange(watch = false) }
    override fun standbyOnHidden() = ev { w.standbyChange(watch = true); w.show(); w.hide() }
    fun enterAmbient() = ev { w.enterAmbient() }
    fun leaveAmbient() = ev { w.leaveAmbient() }
    fun reachability(value: Boolean?) = ev { w.reachabilityChanged(value) }
    override fun fireWindow() = ev { w.wake.onTimer(); w.drain() }
    override fun fireRearm() = ev { w.rearmIn = null; w.coordinator.onRearmDue(); w.drain() }
}

/**
 * The standby wake while the device's own screen-off recognition is OFF: it waits for the wake phrase only inside ONE screen-activation
 * budget of 5 s, through the real Phone and Watch classes. Expired, nothing listens, retries or holds until the next real activation;
 * with the preference ON the continuous background behaviour is unchanged.
 */
class ScreenOnFiveSecondBudgetTest {
    private fun both(pref: Boolean = false, master: Boolean = true): List<Dev> = listOf(PhoneDev(pref, master), WatchDev(pref, master))

    // ── the budget itself ────────────────────────────────────────────────────────────────────

    @Test
    fun `the first window after initialization is bounded to five seconds and nothing follows it`() {
        for (d in both()) {
            d.boot()
            val t0 = d.now
            d.assertListeningUntil(t0 + BUDGET, "initialization")
            d.advanceTo(t0 + 1_000)
            assertTrue("${d.name}: the LISTEN hold covers the open window", d.listenHold())
            d.advanceTo(t0 + BUDGET - 1)
            assertTrue("${d.name}: still inside the budget", d.wake.listening)
            d.advanceTo(t0 + BUDGET)
            d.assertDark("budget over")
            d.advanceTo(t0 + 3_600_000)
            d.assertDark("an hour later")
            assertEquals("${d.name}: exactly one window", 1, d.listenCount())
        }
    }

    @Test
    fun `a genuine screen activation opens exactly one new budget, however many windows or restarts it has`() {
        for (d in both()) {
            val t = d.settleDark()
            val before = d.listenCount()
            d.activate()
            d.assertListeningUntil(t + BUDGET, "activation")
            assertEquals("${d.name}: one window", before + 1, d.listenCount())
            d.advanceTo(t + 1_000)
            d.deactivate()
            d.assertDark("screen off inside the budget")
            d.advanceTo(t + 2_000)
            d.activate()
            d.assertListeningUntil(t + 2_000 + BUDGET, "re-activation gets one fresh full budget")
            d.advanceTo(t + 2_000 + BUDGET)
            d.assertDark("second budget over")
            d.advanceTo(t + 60_000)
            d.deactivate()
            d.advanceTo(t + 70_000)
            d.activate()
            d.assertListeningUntil(t + 70_000 + BUDGET, "third activation")
            assertTrue("${d.name}: no window ever outlives its activation's budget", d.windows.all { (opened, deadline) -> deadline - opened <= BUDGET })
        }
    }

    @Test
    fun `a window that opens late in the budget gets only what is left of it, from 0 to 4999 ms and never at 5000`() {
        for (offset in listOf(0L, 1L, 2_500L, 4_999L, 5_000L, 5_001L, 20_000L)) for (d in both()) {
            val t = d.settleDark()
            d.busy(true)
            d.activate()
            assertFalse("${d.name} +$offset: busy, nothing listens", d.wake.listening)
            d.advanceTo(t + offset)
            d.idle()
            if (offset < BUDGET) {
                d.assertListeningUntil(t + BUDGET, "+$offset: the remaining budget only")
                d.advanceTo(t + BUDGET)
                d.assertDark("+$offset: budget over")
                assertEquals("${d.name} +$offset: one window", 1, d.windows.count { it.first >= t })
            } else {
                d.assertDark("+$offset: busy until after the budget")
                assertEquals("${d.name} +$offset: no window opened", 0, d.windows.count { it.first >= t })
            }
            d.advanceTo(t + 120_000)
            d.assertDark("+$offset: later")
        }
    }

    @Test
    fun `quiet or failed windows are followed again only while the budget lasts`() {
        for (d in both()) {
            val t = d.settleDark()
            d.activate()
            d.assertListeningUntil(t + BUDGET, "activation")
            d.advanceTo(t + 1_000)
            d.error(7)
            assertEquals("${d.name}: a quiet completion is followed by the normal gap", t + 1_300, d.rearmAt)
            d.advanceTo(t + 1_300)
            d.assertListeningUntil(t + BUDGET, "the follow-up window gets only what is left")
            d.advanceTo(t + 2_000)
            d.error(5)
            assertEquals("${d.name}: a failure backs off one second", t + 3_000, d.rearmAt)
            d.advanceTo(t + 3_000)
            d.assertListeningUntil(t + BUDGET, "the retry also gets only what is left")
            d.advanceTo(t + 4_500)
            d.error(5)
            assertNull("${d.name}: a retry that would land after the budget is not scheduled", d.rearmAt)
            d.advanceTo(t + 120_000)
            d.assertDark("after the budget")
            assertEquals("${d.name}: three windows, all inside the budget", 3, d.windows.count { it.first >= t })
            assertTrue("${d.name}: every window ended by the budget", d.windows.filter { it.first >= t }.all { it.second <= t + BUDGET })
        }
    }

    @Test
    fun `the gap after a quiet window that would end after the budget is dropped, not scheduled`() {
        for (d in both()) {
            val t = d.settleDark()
            d.activate()
            d.advanceTo(t + 4_800)
            d.error(7)
            assertNull("${d.name}: a 300 ms gap from 4800 would reopen after the budget", d.rearmAt)
            d.advanceTo(t + 60_000)
            d.assertDark("later")
            assertEquals("${d.name}: one window", 1, d.windows.count { it.first >= t })
        }
    }

    // ── nothing but a real activation reopens it ─────────────────────────────────────────────

    @Test
    fun `stale or duplicate signals neither extend a running budget nor reopen an expired one`() {
        for (d in both()) {
            val t = d.settleDark()
            d.activate()
            d.advanceTo(t + 2_000)
            d.replaySettings(); d.dupScreenOn(); d.eligibility(); d.idle(); d.staleRearm()
            d.assertListeningUntil(t + BUDGET, "duplicates inside the budget change nothing")
            assertEquals("${d.name}: no second window", 1, d.windows.count { it.first >= t })
            d.advanceTo(t + BUDGET)
            d.assertDark("expired")
            val ops = listOf<Pair<String, () -> Unit>>(
                "settings replay" to { d.replaySettings() },
                "equal snapshot" to { d.eligibility() },
                "duplicate SCREEN_ON" to { d.dupScreenOn() },
                "idle" to { d.idle() },
                "stale alarm" to { d.staleRearm() },
                "busy then idle" to { d.busy(true); d.idle() },
                "playback busy" to { d.playbackBusy() },
                "second settings replay" to { d.replaySettings() },
            )
            var at = t + 6_000
            for ((label, op) in ops) {
                d.advanceTo(at)
                op()
                d.assertDark(label)
                at += 7_000
            }
            d.advanceTo(t + 3_600_000)
            d.assertDark("an hour later")
            assertEquals("${d.name}: still only the one window", 1, d.windows.count { it.first >= t })
        }
    }

    @Test
    fun `an enable of the standby switch is a real start of one bounded window and a repeat of it is not`() {
        for (d in both()) {
            val t = d.settleDark()
            d.activate()
            d.advanceTo(t + BUDGET)
            d.assertDark("expired")
            d.advanceTo(t + 30_000)
            d.standbyOff()
            d.assertDark("standby off")
            d.advanceTo(t + 31_000)
            val enabled = d.now
            d.standbyOnHidden()
            d.assertListeningUntil(enabled + BUDGET, "standby turned on: one explicitly bounded window")
            d.advanceTo(enabled + BUDGET)
            d.assertDark("the enable's budget over")
            d.advanceTo(enabled + 10_000)
            d.replaySettings(); d.eligibility(); d.dupScreenOn(); d.idle()
            d.assertDark("identical refreshes of the enabled setting")
        }
    }

    // ── the foreground and the screen ────────────────────────────────────────────────────────

    @Test
    fun `hiding the app without a new screen activation grants no fresh background budget`() {
        // A selected Watch listens on screen (its own foreground selector, see the next tests), so the dark-on-screen premise holds for the Phone only.
        for (d in listOf<Dev>(PhoneDev(pref = false))) {
            d.bootVisible()
            val t0 = d.now
            d.advanceTo(t0 + 10_000)
            d.assertDark("visible")
            d.hide()
            d.assertDark("hidden ten seconds after the screen came on")
            d.advanceTo(t0 + 120_000)
            d.assertDark("later")
            assertEquals("${d.name}: nothing ever listened", 0, d.listenCount())
        }
    }

    @Test
    fun `hiding the app inside the activation's budget listens only for what is left of it`() {
        for (d in both()) {
            d.bootVisible()
            val t0 = d.now
            d.advanceTo(t0 + 2_000)
            d.hide()
            d.assertListeningUntil(t0 + BUDGET, "hidden two seconds in")
            d.advanceTo(t0 + BUDGET)
            d.assertDark("budget over")
        }
    }

    @Test
    fun `a selected Watch keeps its foreground windows and stops listening in the standby once the budget is spent`() {
        val d = WatchDev(pref = false, mode = WakeLocation.WATCH)
        d.bootVisible()
        val t0 = d.now
        d.advanceTo(t0 + 20_000)
        assertTrue("the foreground location alone decides on screen, unlimited by the budget", d.wake.listening)
        assertTrue("windows followed one another on screen", d.listenCount() >= 2)
        d.hide()
        d.assertDark("hidden with the budget spent")
        d.advanceTo(t0 + 120_000)
        d.assertDark("later")
    }

    @Test
    fun `an excluded foreground selector still never listens on screen`() {
        val d = WatchDev(pref = false, mode = WakeLocation.PHONE)
        d.bootVisible()
        d.advanceTo(d.now + 20_000)
        assertEquals(0, d.listenCount())
    }

    // ── what the preference ON and the master OFF keep ───────────────────────────────────────

    @Test
    fun `with screen-off recognition on the background stays continuous`() {
        for (d in both(pref = true)) {
            d.boot()
            val t0 = d.now
            // A selected Watch's visit window (5 s) outlives the hide; the continuous background takes over after it.
            if (d is WatchDev) d.advanceTo(t0 + BUDGET) else d.assertListeningUntil(t0 + CONTINUOUS, "first window")
            d.advanceTo(t0 + 120_000)
            assertTrue("${d.name}: still listening two minutes on", d.wake.listening || d.rearmAt != null)
            assertTrue("${d.name}: windows followed one another", d.listenCount() >= 3)
            d.deactivate()
            d.advanceTo(d.now + 120_000)
            assertTrue("${d.name}: and with the screen off", d.wake.listening || d.rearmAt != null)
        }
    }

    @Test
    fun `a device follows only its own pair of flags, never the other device's screen-off preference`() {
        for (d in listOf<Dev>(PhoneDev(pref = false, other = true), WatchDev(pref = false, other = true))) {
            d.boot()
            d.assertListeningUntil(d.now + BUDGET, "own preference off, the other device's on")
        }
        for (d in listOf<Dev>(PhoneDev(pref = true, other = false), WatchDev(pref = true, other = false))) {
            d.boot()
            if (d is WatchDev) {
                d.advanceTo(d.now + BUDGET)
                assertTrue("watch: continuous after its visit window", d.wake.listening || d.rearmAt != null)
            } else d.assertListeningUntil(d.now + CONTINUOUS, "own preference on, the other device's off")
        }
    }

    @Test
    fun `turning the preference on lifts an expired budget and turning it off ends continuous listening whose activation is old`() {
        for (d in both()) {
            val t = d.settleDark()
            d.activate()
            d.advanceTo(t + 20_000)
            d.assertDark("expired")
            d.screenOffPref(true)
            assertTrue("${d.name}: the continuous background is back", d.wake.listening)
            assertEquals("${d.name}: a continuous window", d.now + CONTINUOUS, d.wake.windowDeadlineMs())
        }
        for (d in both(pref = true)) {
            d.boot()
            d.advanceTo(d.now + 60_000)
            assertTrue("${d.name}: continuous", d.wake.listening || d.rearmAt != null)
            d.screenOffPref(false)
            d.assertDark("preference turned off a minute after the screen came on")
        }
    }

    @Test
    fun `with the standby off nothing listens on any screen event`() {
        for (d in both(master = false)) {
            d.boot()
            val visit = d.listenCount()
            d.advanceTo(d.now + 10_000)
            d.deactivate(); d.advanceTo(d.now + 10_000); d.activate(); d.dupScreenOn(); d.eligibility(); d.idle()
            d.advanceTo(d.now + 120_000)
            assertEquals("${d.name}: nothing listened beyond the selected Watch's own visit window", visit, d.listenCount())
            d.assertDark("master off")
        }
    }

    // ── what an accepted wake phrase keeps ───────────────────────────────────────────────────

    @Test
    fun `a leading wake phrase heard near the deadline keeps the window for its own inactivity, then nothing follows`() {
        for (d in both()) {
            val t = d.settleDark()
            d.activate()
            d.advanceTo(t + 4_900)
            d.heard("루미 내일 일정", final = false)
            d.assertListeningUntil(t + 4_900 + WakeContract.PENDING_INACTIVITY_MS, "a partial leading phrase extends the window")
            d.advanceTo(t + BUDGET + 1)
            assertTrue("${d.name}: still listening past the budget for the pending phrase", d.wake.listening)
            d.advanceTo(t + 4_900 + WakeContract.PENDING_INACTIVITY_MS)
            d.assertDark("the unfinished request ended its window and the budget is spent")
            d.advanceTo(t + 120_000)
            d.assertDark("later")
        }
    }

    @Test
    fun `a request recognized in full after the budget is sent complete, and the reply's idle reopens nothing`() {
        for (d in both()) {
            val t = d.settleDark()
            d.activate()
            d.advanceTo(t + 4_900)
            d.heard("루미 내일 일정", final = false)
            d.advanceTo(t + 9_000)
            d.heard("루미 내일 일정 알려줘", final = true)
            assertEquals("${d.name}: the whole final request, not cut at 5 s", listOf("send:내일 일정 알려줘"), d.sent())
            d.advanceTo(t + 20_000)
            d.idle()
            d.assertDark("the reply finished after the budget")
        }
    }

    @Test
    fun `a phrase-only handoff accepted at the last moment records past the budget and the screen, and nothing reopens after it`() {
        for (d in both()) {
            val t = d.settleDark()
            d.activate()
            d.advanceTo(t + 4_990)
            d.heard("루미", final = true)
            assertFalse("${d.name}: the recognizer is released for the recorder", d.wake.listening)
            d.advanceTo(t + 4_990 + WakeContract.MIC_HANDOFF_MS)
            d.handoffDue()
            assertEquals("${d.name}: the request recording started", 1, d.captured())
            d.advanceTo(t + 20_000)
            assertFalse("${d.name}: the budget's end never cuts the recording", d.captureCancelled())
            d.deactivate()
            d.advanceTo(t + 21_000)
            d.activate()
            assertFalse("${d.name}: neither does a screen change", d.captureCancelled())
            d.advanceTo(t + 30_000)
            assertEquals(1, d.captured())
            d.captureEnded()
            d.idle()
            d.assertDark("the recording ended after the second activation's budget")
            d.advanceTo(t + 120_000)
            d.assertDark("later")
        }
    }

    @Test
    fun `a push-to-talk recording on the Watch is not touched by the budget ending`() {
        val d = WatchDev(pref = false)
        val t = d.settleDark()
        d.activate()
        d.w.pttRecording = true
        d.busy(true)
        d.advanceTo(t + 30_000)
        assertFalse("nothing cancelled the recording", d.w.log.any { it.startsWith("cancel_capture") })
        assertFalse(d.w.log.contains("stopService"))
        assertTrue("the session goes on for replies", d.w.status.session.running)
        d.w.pttRecording = false
        d.idle()
        d.assertDark("the recording ended long after the budget")
    }

    // ── Watch-only facts ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the always-on display is not the screen, and leaving it is a real activation`() {
        val d = WatchDev(pref = false)
        val t = d.settleDark()
        d.activate()
        d.assertListeningUntil(t + BUDGET, "screen on")
        d.advanceTo(t + 1_000)
        d.enterAmbient()
        d.assertDark("ambient")
        d.advanceTo(t + 3_000)
        d.leaveAmbient()
        d.assertListeningUntil(t + 3_000 + BUDGET, "leaving ambient is a real activation: one fresh budget")
        d.advanceTo(t + 3_000 + BUDGET)
        d.assertDark("over")
        d.advanceTo(t + 60_000)
        d.enterAmbient()
        d.advanceTo(t + 100_000)
        d.assertDark("ambient again")
    }

    @Test
    fun `an unreachable Phone defers nothing past the budget, and recovery uses only what is left`() {
        val d = WatchDev(pref = false)
        val t = d.settleDark()
        d.w.reachable = false
        d.activate()
        assertFalse(d.wake.listening)
        assertNull("${d.name}: the 15 s retry would land after the budget", d.rearmAt)
        d.advanceTo(t + 3_000)
        d.reachability(true)
        d.assertListeningUntil(t + BUDGET, "recovered at 3 s")
        d.advanceTo(t + BUDGET)
        d.assertDark("over")

        val late = WatchDev(pref = false)
        val u = late.settleDark()
        late.w.reachable = false
        late.activate()
        late.advanceTo(u + 6_000)
        late.reachability(true)
        late.assertDark("recovered after the budget")
        late.advanceTo(u + 120_000)
        late.assertDark("later")
    }

    @Test
    fun `a playback cooldown is waited out only inside the budget`() {
        val inside = WatchDev(pref = false)
        val t = inside.settleDark()
        inside.w.cooldownUntil = t + 3_000
        inside.activate()
        assertEquals(t + 3_100, inside.rearmAt)
        inside.advanceTo(t + 3_100)
        inside.assertListeningUntil(t + BUDGET, "the cooldown ended inside the budget")

        val outside = WatchDev(pref = false)
        val u = outside.settleDark()
        outside.w.cooldownUntil = u + 7_000
        outside.activate()
        assertNull("${outside.name}: a cooldown that ends after the budget schedules nothing", outside.rearmAt)
        outside.advanceTo(u + 120_000)
        outside.assertDark("later")
        assertEquals(0, outside.windows.count { it.first >= u })
    }
}
