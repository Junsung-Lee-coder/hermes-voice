package com.rumi.hermesvoice.core.wake

/**
 * The one budget of a screen activation. A standby wake whose device may not listen with the screen off waits for the
 * phrase only while this budget lasts. It starts when the device's own screen becomes interactive after not being so
 * (or the first time it is seen interactive) and on a real enable of the standby switch ([restart]); nothing else
 * (recognizer restarts, alarms, repeated screen-on or settings callbacks, the app moving to the background) restarts it.
 */
class ScreenActivationBudget(
    private val clock: () -> Long,
    private val budgetMs: Long = WakeContract.SCREEN_ON_BUDGET_MS,
) {
    private var interactive: Boolean? = null
    private var startMs = 0L

    /** Feeds the device's own screen fact. True when it is a new activation; a repeat of the same fact changes nothing. */
    @Synchronized
    fun observe(screenInteractive: Boolean): Boolean {
        val activated = screenInteractive && interactive != true
        interactive = screenInteractive
        if (activated) startMs = clock()
        return activated
    }

    /** The standby switch was really enabled while the screen is on: one explicitly bounded budget from now. */
    @Synchronized
    fun restart() {
        if (interactive == true) startMs = clock()
    }

    /** When the budget ends; null before the screen was seen or while it is not interactive. */
    @Synchronized
    fun endMs(): Long? = if (interactive == true) startMs + budgetMs else null

    /** What is left of the budget (zero or less: spent); null as for [endMs]. */
    @Synchronized
    fun remainingMs(): Long? = endMs()?.let { it - clock() }
}
