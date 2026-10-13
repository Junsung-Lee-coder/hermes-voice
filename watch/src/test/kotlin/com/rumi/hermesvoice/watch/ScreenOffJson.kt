package com.rumi.hermesvoice.watch

import org.json.JSONObject

/** The Phone's snapshot with the Watch's screen-off recognition preference carried as ON, built through the wire format. */
internal fun watchScreenOffOn(json: String): String = JSONObject(json).put("watch_background_wake_screen_off_enabled", true).toString()
