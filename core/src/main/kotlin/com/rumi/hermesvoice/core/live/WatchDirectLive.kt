package com.rumi.hermesvoice.core.live

import org.json.JSONObject

/** Branch B (Watch-direct experiment): an ordinary Watch-origin call (Watch microphone and Watch speaker) is the Watch's own WebRTC peer on
 *  the Watch's OS default internet route; the Phone keeps the authenticated session request, settings and every policy decision. These are
 *  the only words both apps share for it. The offer, the answer and provider events cross the exact paired control only and are never logged. */
object WatchDirectLive {
    /** A Watch Start field: its own speaker and microphone are the ordinary route (no private or personal output), so direct media may be chosen. */
    const val START = "direct"
    /** The Phone's "open" body field choosing the Watch's own direct media instead of the Phone-owned relay. */
    const val OPEN = "direct"
    /** Exactly the provider event types the Phone acts on ([LiveDialogue.receive], [LiveWire.appended]); the Watch hands on no others. */
    val PHONE_EVENTS = setOf("session.started", "session.closed", "error", "session.input_transcript.delta", "session.output_transcript.delta",
        "session.delegation.created", "session.instructions.appended", "session.thinking.appended", "session.commentary.appended")
    fun forwarded(raw: String): Boolean = (try { JSONObject(raw).optString("type") } catch (_: Exception) { null }) in PHONE_EVENTS
    /** The Watch's SDP offer for the one session request: bounded text that is not the relay's version word. Never inspected further or logged. */
    fun offer(body: String): Boolean = body.startsWith("v=0") && body.toByteArray(Charsets.UTF_8).size <= WatchLiveWire.MAX_PAYLOAD
}
