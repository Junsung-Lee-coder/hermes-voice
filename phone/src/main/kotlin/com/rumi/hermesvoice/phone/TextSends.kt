package com.rumi.hermesvoice.phone

import android.util.Log
import com.rumi.hermesvoice.core.ChatSendResult
import com.rumi.hermesvoice.core.ChatService
import com.rumi.hermesvoice.core.chat.ComposerDispatch
import com.rumi.hermesvoice.core.chat.DispatchedSend
import com.rumi.hermesvoice.core.chat.FailedSend
import com.rumi.hermesvoice.core.chat.PendingSend
import com.rumi.hermesvoice.core.diag.DiagClassify
import com.rumi.hermesvoice.core.notify.FinalReply
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The text requests being sent and answered, owned by the application and not by the screen that sent them: closing or recreating
 * the screen only detaches what it observes ([Screen]); the wait for the answer, its delivery (alert or merge into the open
 * conversation) and the failure of a request go on here, and a screen created later is shown what is still waiting and what failed.
 * The accepted request itself is also kept on disk by the core (AcceptedTurnStore), for a process restart.
 */
class TextSends(private val scope: CoroutineScope) {
    interface Screen {
        fun accepted(send: DispatchedSend, origin: PhoneApp.AsyncActionOrigin)
        fun finished(send: DispatchedSend, result: ChatSendResult, origin: PhoneApp.AsyncActionOrigin)
    }

    class Snapshot(val active: List<PendingSend>, val failed: List<FailedSend>)

    /** Request ids of this process, shared by every screen. */
    val ids = AtomicLong(0)

    private val lock = Any()
    private val active = LinkedHashMap<Long, PendingSend>()
    private var failed: List<FailedSend> = emptyList()
    // Custody belongs to the initiating auth connection, not the screen or the session id.
    private val origins = HashMap<Long, PhoneApp.AsyncActionOrigin>()
    private var screen: Screen? = null

    /** What waits and what failed now, and from now on [screen] hears of every change (the one screen at a time). */
    fun attach(screen: Screen, current: (PhoneApp.AsyncActionOrigin) -> Boolean): Snapshot = synchronized(lock) {
        this.screen = screen
        fun visible(id: Long) = origins[id]?.let { it.scope != null && current(it) } == true
        Snapshot(active.values.filter { visible(it.send.id) }, failed.filter { visible(it.send.id) })
    }

    fun detach(screen: Screen) = synchronized(lock) {
        if (this.screen === screen) this.screen = null
    }

    fun dismissFailed(id: Long) = synchronized(lock) {
        failed = failed.filterNot { it.send.id == id }
        if (!active.containsKey(id)) origins.remove(id)
    }

    fun start(chat: ChatService, pending: PendingSend, origin: PhoneApp.AsyncActionOrigin) {
        val send = pending.send
        synchronized(lock) { active[send.id] = pending; origins[send.id] = origin }
        dispatch(chat, pending, origin)
    }

    /** The application, not a possibly stale screen, owns proof that this exact send is still
     * definitively unsent. Claim once before dispatch; accepted/possibly-sent and active sends
     * have no such authority. A newly composed message has a fresh id and uses [start]. */
    fun retry(chat: ChatService, pending: PendingSend, origin: PhoneApp.AsyncActionOrigin): Boolean {
        val send = pending.send
        val owner = synchronized(lock) {
            if (active.containsKey(send.id) || failed.none { it.send === send }) return false
            val original = origins[send.id] ?: return false
            if (original.scope == null || original.scope != origin.scope || original.wiring !== origin.wiring ||
                original.connection != origin.connection || original.url != origin.url || original.profile != origin.profile) return false
            failed = failed.filterNot { it.send.id == send.id }
            active[send.id] = pending
            original
        }
        dispatch(chat, pending, owner)
        return true
    }

    private fun dispatch(chat: ChatService, pending: PendingSend, origin: PhoneApp.AsyncActionOrigin) {
        val send = pending.send
        scope.launch {
            val result = try {
                chat.send(send.sessionId, send.text, send.attachments, onAccepted = { accepted(send, origin) })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val classified = DiagClassify.of(error)
                Log.w(TAG, "send failed cause=${classified.cause.wire}")
                ChatSendResult.Failed("Acceptance uncertain; check the conversation. Do not retry this request", cause = classified.cause,
                    delivery = com.rumi.hermesvoice.core.SendDelivery.POSSIBLY_SENT)
            }
            finished(send, result, origin)
        }
    }

    private fun accepted(send: DispatchedSend, origin: PhoneApp.AsyncActionOrigin) {
        val listener = synchronized(lock) {
            active[send.id]?.let { active[send.id] = it.copy(delivered = true) }
            screen
        }
        listener?.accepted(send, origin)
    }

    private fun finished(send: DispatchedSend, result: ChatSendResult, origin: PhoneApp.AsyncActionOrigin) {
        val listener = synchronized(lock) {
            active.remove(send.id)
            ComposerDispatch.failure(send, result)?.let { failed = ComposerDispatch.withFailed(failed.filterNot { f -> f.send.id == send.id }, it) }
            origins.keys.retainAll(active.keys + failed.map { it.send.id })
            screen
        }
        listener?.finished(send, result, origin)
    }

    private companion object {
        const val TAG = "HermesVoice"
    }
}

/** What the open conversation on screen can say about itself to the reply alerts. */
interface ConversationDisplay {
    /** The visible conversation was refreshed from the server and now shows [reply] itself, and is still in front of the user. */
    suspend fun confirmArrival(reply: FinalReply): Boolean

    /** The visible conversation's newest history was read again successfully and it is still in front of the user. */
    suspend fun confirmOpened(storedSessionId: String): Boolean
}
