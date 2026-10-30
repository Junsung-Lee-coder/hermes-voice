package com.rumi.hermesvoice.core.live

import com.rumi.hermesvoice.core.net.SilentControl
import org.json.JSONObject
import java.util.UUID

/** Provider metadata is NOT a task or a destination. [targetSpeech] is only the user's own words that may name a conversation. */
data class LiveTextMessage(val role: String, val text: String)
data class LiveRequest(val delegationId: String, val text: String, val context: String, val targetSpeech: String = "")
data class LiveBinding(val callId: String, val delegationId: String, val sessionId: String, val text: String, val context: String)
sealed class LiveEvent {
    data class Transcript(val input: Boolean, val text: String) : LiveEvent()
    data class Confirmation(val request: LiveRequest) : LiveEvent()
    data class Started(val providerId: String) : LiveEvent()
    object Closed : LiveEvent()
    object Failure : LiveEvent()
}

/** Bounded, call-scoped transcript and one-at-a-time delegation custody. Only the user's own words may name a conversation; no speech is approval. */
class LiveDialogue(private val callId: String, private val scope: String, private var initialUserText: String? = null) {
    init { require(initialUserText == null || initialUserText!!.length <= 6000) }
    private data class Segment(val input: Boolean, val text: String, val start: Long, val end: Long, val order: Long)
    private val segments = ArrayList<Segment>()
    private val transcriptEvents = HashSet<String>()
    private var transcriptOrder = 0L
    private val contextWake = initialUserText
    /** Partial text context only: not finalized turns, playback evidence, or input to any authority-bearing reducer. */
    @Synchronized fun textContext(): List<LiveTextMessage> {
        if (closed) return emptyList()
        val runs = ArrayList<LiveTextMessage>()
        for (s in segments) {
            val role = if (s.input) "user" else "assistant"
            if (runs.lastOrNull()?.role == role) runs[runs.lastIndex] = LiveTextMessage(role, runs.last().text + s.text)
            else runs += LiveTextMessage(role, s.text)
        }
        return listOfNotNull(contextWake?.takeIf { it.isNotBlank() }?.let { LiveTextMessage("user", it) }) + runs.filter { it.text.isNotBlank() }
    }
    private val seen = HashSet<String>()
    private var pending: LiveRequest? = null
    private var closed = false
    private var consumedThrough = -1L
    private var pendingOffset = -1L
    private var targetFrom = -1L
    @Synchronized fun receive(raw: String): LiveEvent? {
        if (closed || raw.length > 65536) return null
        val j = try { JSONObject(raw) } catch (_: Exception) { return null }
        return when (j.optString("type")) {
            "session.started" -> LiveEvent.Started(j.optJSONObject("session")?.optString("id").orEmpty())
            "session.closed" -> { closed = true; pending = null; LiveEvent.Closed }
            "error" -> LiveEvent.Failure
            "session.input_transcript.delta", "session.output_transcript.delta" -> {
                val start = (j.opt("start_ms") as? Number)?.toLong() ?: return null
                val end = (j.opt("end_ms") as? Number)?.toLong() ?: return null
                val text = j.opt("delta") as? String ?: return null
                val input = j.getString("type") == "session.input_transcript.delta"
                // Reject a whole invalid/oversize delta rather than clipping JSON, Unicode or genuine repetition.
                if (start < 0 || end < start || text.isEmpty() || text.length > 6000 ||
                    !Charsets.UTF_8.newEncoder().canEncode(text) || text.toByteArray(Charsets.UTF_8).size > 8192 ||
                    (!input && SilentControl.isWholeResponse(text))) return null
                val identity = if (j.has("event_id")) (j.opt("event_id") as? String)?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return null else null
                // Official events carry event_id. Legacy peers without one cannot be safely content-deduped:
                // the same words/interval can be genuine speech, so retain their arrival identity instead.
                if (identity != null && (identity in transcriptEvents || transcriptEvents.size >= 4096)) return null
                if (identity != null) transcriptEvents += identity
                segments += Segment(input, text, start, end, transcriptOrder++)
                segments.sortWith(compareBy<Segment> { it.start }.thenBy { it.end }.thenBy { it.order })
                while (segments.size > 256 || segments.sumOf { it.text.length } > 12000) segments.removeAt(0)
                LiveEvent.Transcript(input, segments.filter { it.input == input }.joinToString("") { it.text }.takeLast(6000))
            }
            "session.delegation.created" -> {
                val delegation = j.optJSONObject("delegation") ?: return null
                if (delegation.optString("type") != "delegation" || delegation.optString("target") != "client") return null
                val id = delegation.optString("id").takeIf { it.isNotBlank() && it.length <= 200 } ?: return null
                if (id in seen || seen.size >= 128) return null
                seen += id
                // A later delegation supersedes a request the app could not bind yet (a clarification turn); a confirmed one is never replayed.
                val offset = (j.opt("offset_ms") as? Number)?.toLong() ?: return null
                val eligible = segments.filter { it.end <= offset }
                val spoken = eligible.filter { it.input && it.end > consumedThrough }.joinToString("") { it.text }.trim()
                val text = listOfNotNull(initialUserText?.takeIf { it.isNotBlank() }, spoken.takeIf { it.isNotBlank() }).joinToString("\n")
                if (text.isBlank()) return null
                pendingOffset = offset
                LiveRequest(id, text.takeLast(6000), (initialUserText?.let { "User (final wake recognition): $it\n" }.orEmpty() + eligible.joinToString("\n") { "${if (it.input) "User" else "Assistant"}: ${it.text}" }).takeLast(6000),
                    targetSpeech(eligible).takeLast(6000)).also { pending = it }.let { LiveEvent.Confirmation(it) }
            }
            else -> null // append ACKs never imply read, heard, played or work success.
        }
    }
    /** User input after any clarification, without runs that only repeat assistant speech (echo or a quote is never a choice). */
    private fun targetSpeech(eligible: List<Segment>): String {
        val from = maxOf(consumedThrough, targetFrom)
        val assistant = LiveTargetChoice.normalize(segments.filter { !it.input }.joinToString("") { it.text })
        val runs = ArrayList<StringBuilder>(); var open = false
        for (s in eligible) {
            if (!s.input || s.end <= from) { open = false; continue }
            if (!open) { runs += StringBuilder(); open = true }
            runs.last().append(s.text)
        }
        // Quoted material remains intact in the actual request/context, but is not a
        // destination command. In particular, the short-title exception must not turn
        // the assistant's `Beta` -> user's `"Beta"` echo into effectful selection.
        fun unquoted(text:String)=Regex("\"[^\"]*\"|“[^”]*”|‘[^’]*’|'[^']*'|「[^」]*」|『[^』]*』").replace(text," ")
        val own = runs.map { unquoted(it.toString()) }.filter { run -> LiveTargetChoice.normalize(run).let { it.isNotEmpty() && (it.length < 8 || !assistant.contains(it)) } }
        return (listOfNotNull(initialUserText?.takeIf { targetFrom < 0 }?.let(::unquoted)) + own).joinToString("\n")
    }
    /** The app could not bind the pending request: keep it, but only speech after this point may name its conversation. */
    @Synchronized fun clarify(delegationId: String) { if (pending?.delegationId == delegationId) targetFrom = pendingOffset }
    @Synchronized fun confirm(delegationId: String, currentScope: String, sessionId: String, request: String): LiveBinding? {
        val p = pending ?: return null
        if (closed || currentScope != scope || p.delegationId != delegationId || sessionId.isBlank() ||
            request.isBlank() || request.length > 6000) return null
        pending = null // BEFORE a suspend/effect: never replay on unknown acceptance or a double tap.
        consumedThrough = pendingOffset; initialUserText = null; targetFrom = -1L
        return LiveBinding(callId, p.delegationId, sessionId, request.trim(), p.context)
    }
    @Synchronized fun dismiss() { pending = null; consumedThrough = pendingOffset; initialUserText = null; targetFrom = -1L }
    @Synchronized fun close() { closed = true; pending = null; segments.clear(); initialUserText = null }
}

object LiveWire {
    fun close(): String = JSONObject().put("type", "session.close").put("event_id", UUID.randomUUID().toString()).toString()
    fun instructions(text: String): String = append("instructions", null, text).first()
    /** The provider's acknowledgement that appended context reached the session timeline: never speech, playback or a read. */
    fun appended(raw: String): String? {
        if (raw.length > 65536) return null
        val j = try { JSONObject(raw) } catch (_: Exception) { return null }
        if (j.optString("type") !in setOf("session.instructions.appended", "session.thinking.appended", "session.commentary.appended")) return null
        return (j.opt("client_event_id") as? String)?.takeIf { it.isNotBlank() && it.length <= 200 }
    }
    /** At most 480 UTF-8 bytes <= 480 byte tokens, conservatively below each append's 500-token limit. */
    fun append(kind: String, delegationId: String?, text: String): List<String> {
        require(kind in setOf("instructions", "thinking", "commentary"))
        if (text.isEmpty() || SilentControl.isWholeResponse(text)) return emptyList()
        val pieces = ArrayList<String>(); val current = StringBuilder(); var bytes = 0
        text.codePoints().forEachOrdered { cp ->
            val s = String(Character.toChars(cp)); val size = s.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 480) { pieces += current.toString(); current.setLength(0); bytes = 0 }
            current.append(s); bytes += size
        }
        if (current.isNotEmpty()) pieces += current.toString()
        return pieces.map { JSONObject().put("type", "session.$kind.append").put("event_id", UUID.randomUUID().toString())
            .put("delegation_id", delegationId ?: JSONObject.NULL).put("content", it).toString() }
    }
}
