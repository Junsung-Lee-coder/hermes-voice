package com.rumi.hermesvoice.core.net

import com.rumi.hermesvoice.core.HermesException
import com.rumi.hermesvoice.core.HermesReplyUnrecoverableException
import com.rumi.hermesvoice.core.HermesTurnFailedException
import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.SessionNotOwnedException
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.replyMayStillArrive
import com.rumi.hermesvoice.core.voice.RecipientEvent
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the gateway told this app when it accepted one prompt, enough to find that turn's events again: the runtime session, the
 * replay watermark just before the prompt was sent ([anchorSeq]), the server's replay epoch and how many earlier turns of that
 * session had to finish first ([skip]). No text, no audio.
 */
data class TurnAcceptance(val runtimeId: String, val anchorSeq: Long, val epoch: String, val skip: Int,
    /** Local admission provenance, not a server id. Missing/legacy metadata NEVER grants attribution. */
    val admission: String = "")

enum class AcceptedKind { TEXT, VOICE }

/** One accepted prompt whose reply this app still owes the user: [id] is the chat send id or the voice turn id. */
data class AcceptedTurn(val id: String, val kind: AcceptedKind, val storedSessionId: String, val acceptance: TurnAcceptance, val createdMs: Long, val namespace: String? = null)

/**
 * The accepted prompts whose final reply has not been delivered yet, kept on the device so a reply that finishes after the
 * connection or the app process was lost is still delivered once. A record ends when the reply was delivered, the user stopped
 * the turn, or the reply can never be matched to the request (see [HermesReplyUnrecoverableException]); it never causes a prompt
 * to be sent again. Bounded and expiring.
 */
class AcceptedTurnStore(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val max: Int = MAX_ENTRIES,
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    private val live = HashSet<String>()
    private val reconciling = HashSet<String>()

    // A change the file refused is kept here, so this process still owes (or has settled) exactly what it knew, and is written again later.
    private val unsaved = LinkedHashMap<String, AcceptedTurn>()
    private val unsettled = HashSet<String>()

    /** Records the accepted turn. True when the file took it; false when it did not: the turn is then owed by this process only, until a later write succeeds. */
    suspend fun begin(turn: AcceptedTurn): Boolean {
        synchronized(this) { live += turn.id }
        return withContext(Dispatchers.IO) {
            synchronized(this@AcceptedTurnStore) {
                unsettled -= turn.id
                unsaved.remove(turn.id)
                unsaved[turn.id] = turn
                write(view().takeLast(max))
            }
        }
    }

    /** The collecting coroutine of [id] has ended in this process (the record stays until [settle]). */
    @Synchronized fun idle(id: String) {
        live -= id
    }

    /** Ends the record of [id]. True when the file took the change; false when it did not: this process then treats the turn as settled and writes it again later. */
    @Synchronized fun settle(id: String): Boolean {
        live -= id
        unsaved.remove(id)
        val all = view()
        if (all.none { it.id == id }) return true
        unsettled += id
        return write(view())
    }

    /** Records still owed, oldest first; expired ones are dropped. */
    @Synchronized fun pending(): List<AcceptedTurn> {
        val all = view()
        val now = clock()
        val fresh = all.filter { now - it.createdMs in 0..maxAgeMs }
        if (fresh.size != all.size || unsaved.isNotEmpty() || unsettled.isNotEmpty()) write(fresh)
        return fresh
    }

    private fun view(): List<AcceptedTurn> {
        val stored = read().filter { it.id !in unsaved && it.id !in unsettled }
        return stored + unsaved.values
    }

    private fun write(turns: List<AcceptedTurn>): Boolean {
        val saved = store.commitString(KEY, encode(turns))
        if (saved) {
            unsaved.clear()
            unsettled.clear()
        }
        return saved
    }

    /** True once for an id nobody in this process is collecting or reconciling. */
    @Synchronized fun claimForReconcile(id: String): Boolean = id !in live && reconciling.add(id)

    @Synchronized fun reconciled(id: String) {
        reconciling -= id
    }

    private fun read(): List<AcceptedTurn> {
        val raw = store.getString(KEY) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val acceptance = TurnAcceptance(o.optString("rt"), o.optLong("an", -1L), o.optString("ep"), o.optInt("sk", -1), o.optString("ap"))
                if (o.optString("id").isEmpty() || acceptance.runtimeId.isEmpty() || acceptance.anchorSeq < 0 || acceptance.skip < 0) return@mapNotNull null
                val kind = runCatching { AcceptedKind.valueOf(o.optString("kd")) }.getOrNull() ?: return@mapNotNull null
                AcceptedTurn(o.getString("id"), kind, o.optString("ss"), acceptance, o.optLong("at"), o.optString("ns").ifBlank { null })
            }
        } catch (_: org.json.JSONException) {
            emptyList()
        }
    }

    private fun encode(turns: List<AcceptedTurn>): String = JSONArray().also { array ->
        turns.forEach { t ->
            array.put(JSONObject().put("id", t.id).put("kd", t.kind.name).put("ss", t.storedSessionId).put("rt", t.acceptance.runtimeId)
                .put("an", t.acceptance.anchorSeq).put("ep", t.acceptance.epoch).put("sk", t.acceptance.skip).put("ap", t.acceptance.admission).put("at", t.createdMs).put("ns",t.namespace))
        }
    }.toString()

    companion object {
        const val KEY = "accepted_turns_v1"
        const val MAX_ENTRIES = 32
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * Finds the final reply of each accepted prompt that no coroutine of this process is waiting for (the app was restarted, or the wait
 * ended when the connection was lost) by re-attaching to the same runtime session and reading the server's replay of the events after
 * the prompt was accepted. The prompt is never sent again. A reply found is handed to the same final-reply decision as a live one, as
 * not heard (it is never spoken after the fact), and the alert ledger keeps that decision to one per identity.
 */
class AcceptedTurnReconciler(
    private val turns: AcceptedTurnStore,
    private val port: HermesConversationPort,
    private val finalReply: (FinalReply) -> Unit,
    private val quiet: () -> Boolean,
    private val scope: CoroutineScope,
    private val replyTimeoutMs: Long = REPLY_TIMEOUT_MS,
    /** Hands a reply or notice to the surface and says whether it is taken care of; when null [finalReply] is used and counts as done unless it throws. */
    private val deliver: (suspend (FinalReply) -> Boolean)? = null,
    private val namespace: () -> String? = { null },
) {
    fun reconcile() {
        for (turn in turns.pending()) {
            if (!turns.claimForReconcile(turn.id)) continue
            scope.launch {
                try {
                    reconcileOne(turn)
                } finally {
                    turns.reconciled(turn.id)
                }
            }
        }
    }

    private suspend fun reconcileOne(turn: AcceptedTurn) {
        if (turn.namespace != namespace()) return
        val reattached = try {
            port.reattach(turn.storedSessionId, turn.acceptance)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HermesReplyUnrecoverableException) {
            return noticeAndSettle(turn, NOTICE_NOT_FOUND)
        } catch (_: SessionNotOwnedException) {
            return noticeAndSettle(turn, NOTICE_NOT_OWNED)
        } catch (_: HermesException) {
            return
        } catch (_: IOException) {
            return
        }
        var complete: RecipientEvent.Complete? = null
        try {
            reattached.collect(replyTimeoutMs) { event -> if (event is RecipientEvent.Complete) complete = event }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: HermesException) {
            if (!error.replyMayStillArrive()) noticeAndSettle(turn, if (error is HermesTurnFailedException) NOTICE_FAILED else NOTICE_NOT_FOUND)
            return
        } catch (_: IOException) {
            return
        } finally {
            reattached.release()
        }
        val reply = complete
        if (reply != null && (reply.status == null || reply.status == "complete") && SilentControl.isWholeResponse(reply.text)) {
            turns.settle(turn.id)
            return
        }
        if (reply == null || reply.text.isBlank() || !(reply.status == null || reply.status == "complete")) return noticeAndSettle(turn, NOTICE_NO_ANSWER)
        if (handOff(turn, "reply", "final", reply.text, null, reply.finalRowId)) turns.settle(turn.id)
    }

    private suspend fun noticeAndSettle(turn: AcceptedTurn, text: String) {
        if (handOff(turn, "notice", "notice", text, NOTICE_TITLE)) turns.settle(turn.id)
    }

    private suspend fun handOff(turn: AcceptedTurn, textPart: String, voicePart: String, text: String, noticeTitle: String?, finalRowId:Long?=null): Boolean {
        val isText = turn.kind == AcceptedKind.TEXT
        val reply = FinalReply(ReplyAlert.identityOf(turn.id, if (isText) textPart else voicePart), turn.storedSessionId, VoiceOrigin.PHONE,
            heard = false, cancelled = false, source = if (isText) FinalReplySource.TEXT else FinalReplySource.OWN,
            // This journal has no original output policy. A current missing headset cannot
            // positively authorize sound for a recovered answer or its recovery notice.
            text = text, quiet = true, noticeTitle = noticeTitle, canonicalRowId=finalRowId, namespace=turn.namespace)
        return try {
            val handler = deliver
            if (handler != null) handler(reply) else { finalReply(reply); true }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val REPLY_TIMEOUT_MS = 15 * 60_000L
        const val NOTICE_TITLE = "Reply not received"
        const val NOTICE_NOT_FOUND = "Your message reached Hermes, but its reply could not be found again after the connection was lost. Open the conversation to check whether it arrived."
        const val NOTICE_NOT_OWNED = "Your message reached Hermes, but its reply could not be checked here: the conversation is archived, no longer used by this app, or open in another Hermes window. Open the conversation to read it."
        const val NOTICE_FAILED = "Your message reached Hermes, but Hermes ended the turn with an error and no answer."
        const val NOTICE_NO_ANSWER = "Your message reached Hermes, but the turn ended without a complete answer."
    }
}
