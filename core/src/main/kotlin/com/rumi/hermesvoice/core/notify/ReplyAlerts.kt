package com.rumi.hermesvoice.core.notify

import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.PlaybackSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class FinalReplySource { OWN, LATER, TEXT }

/**
 * One actual final assistant answer the app received, with what became of its audio. [text] is that answer itself, used ONLY
 * for this device's own notification preview: it never reaches the identity, the ledger, the Watch alert or any log.
 * [identity] is the positive terminal identity (turn + position, or a chat send): never derived from the body.
 */
class FinalReply(
    val identity: String,
    val storedSessionId: String,
    /** The device the answer was meant for: the playback target at hand-off (the Phone for a text reply). */
    val target: VoiceOrigin,
    /** Its audio was confirmed played to the end (or the text was already heard through spoken interim text). */
    val heard: Boolean,
    /** The user's Stop (or switching later replies off) ended it: never an alert. */
    val cancelled: Boolean,
    val source: FinalReplySource,
    /** The target device's link, when it is another device (the Watch); null for the Phone itself. */
    val sink: PlaybackSink? = null,
    /** The delivered final answer, exactly as received (null when unknown): never an interim, a progress line or the request. */
    val text: String? = null,
    /** The answer was meant for the private headset only: its alert is visual here, never a sound and never handed to the Watch. */
    val quiet: Boolean = false,
    /** Set for an honest notice that no answer arrived for this send (then [text] is the notice itself, not an answer). */
    val noticeTitle: String? = null,
    val canonicalRowId: Long? = null,
    val namespace: String? = null,
    val sourceGeneration: Long? = null,
)

/** What an alert carries to ANOTHER device (the Watch): identity and the conversation to open, nothing of the answer. */
data class ReplyAlert(val identity: String, val storedSessionId: String) {
    init {
        require(IDENTITY.matches(identity) && DestinationAllowlist.isValidSessionId(storedSessionId)) { "invalid reply alert" }
    }

    companion object {
        private val IDENTITY = Regex("^[A-Za-z0-9._:#-]{1,160}$")
        fun isValidIdentity(identity: String): Boolean = IDENTITY.matches(identity)

        /** "[turnId]#[part]", or a digest of the turn id when it holds characters the notification wire does not carry. */
        fun identityOf(turnId: String, part: String): String {
            val id = "$turnId#$part"
            if (IDENTITY.matches(id)) return id
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(turnId.toByteArray(Charsets.UTF_8))
            return "t" + digest.take(16).joinToString("") { "%02x".format(it) } + "#" + part
        }
    }
}

/** The platform notification surface of THIS device. [show] never throws and reports whether the system accepted it. */
fun interface ReplyAlertPort {
    fun show(alert: ReplyAlert): Boolean

    /** Shows [alert] with a preview of the answer where this surface can; the default is the content-free alert. */
    fun show(alert: ReplyAlert, preview: ReplyPreview?): Boolean = show(alert)

    /** As above; [quiet] asks for a visual-only alert (no sound) where this surface can; the default ignores it. */
    fun show(alert: ReplyAlert, preview: ReplyPreview?, quiet: Boolean): Boolean = show(alert, preview)

    /**
     * Removes every reply alert this surface has stacked for [storedSessionId] (and no other notification), returning how many it
     * removed. It works from what the platform still shows, so it also finds alerts posted before a process restart. The default
     * has nothing to remove.
     */
    fun dismissConversation(storedSessionId: String): Int = 0
}

/** Hands a reply (or an honest notice) to the surface; [done] is told, once, whether it is now taken care of (shown, suppressed or already handled). It is never called when the attempt was cancelled or failed. */
typealias ReplyDelivery = (FinalReply, (Boolean) -> Unit) -> Unit

enum class ReplyAlertResult { SUPPRESSED_HEARD, SUPPRESSED_CANCELLED, DUPLICATE, INVALID, SHOWN_HERE, NOT_SHOWN_HERE, SENT_TO_WATCH, SUPPRESSED_VISIBLE, DURABLE_INTAKE }

/**
 * A bounded durable set of the alert identities already handled, newest last. An identity is handled only after its alert was shown
 * (or knowingly suppressed): [begin] reserves it for one attempt, [commit] spends it, [release] gives it back when the attempt failed.
 */
class ReplyAlertLedger(private val store: KeyValueStore, private val max: Int = MAX_ENTRIES) {
    init {
        require(max > 0)
    }

    private val seen = LinkedHashSet<String>().also { set ->
        store.getString(KEY)?.split(' ')?.filter { it.isNotEmpty() }?.takeLast(max)?.let(set::addAll)
    }

    /** True once [identity] was shown or knowingly suppressed (in this process or an earlier one). */
    @Synchronized
    fun isHandled(identity: String): Boolean = identity in seen

    private val attempting = HashSet<String>()
    private var unsaved = false

    /** True the first time [identity] is claimed (saved before returning); false when it was handled before. */
    @Synchronized
    fun claim(identity: String): Boolean {
        if (!begin(identity)) return false
        commit(identity)
        return true
    }

    /** True when [identity] was neither handled nor is being attempted now; the caller then owes [commit] or [release]. */
    @Synchronized
    fun begin(identity: String): Boolean {
        if (identity in seen || identity in attempting) return false
        if (unsaved) save()
        attempting += identity
        return true
    }

    /** [identity] was shown or knowingly suppressed: spend it and save the set. False when the file refused the write (spent for this process, written again with a later change). */
    @Synchronized
    fun commit(identity: String): Boolean {
        attempting -= identity
        seen += identity
        while (seen.size > max) seen.remove(seen.first())
        return save()
    }

    /** The attempt for [identity] did not end in a shown or suppressed alert: it can be attempted again. */
    @Synchronized
    fun release(identity: String) {
        attempting -= identity
    }

    private fun save(): Boolean {
        unsaved = !store.commitString(KEY, seen.joinToString(" "))
        return !unsaved
    }

    companion object {
        const val KEY = "reply_alert_ledger_v1"
        const val MAX_ENTRIES = 256
    }
}

/**
 * Decides and routes the arrival alert for an unspoken final answer. Exactly one alert per identity, even across restarts:
 * a heard or cancelled answer never alerts; a Watch-target answer is alerted by the Watch alone (Phone sends it over the
 * link and only shows its own if the Watch could not be told); a Phone-target or text answer is shown by the Phone.
 */
class ReplyAlerts(
    private val ledger: ReplyAlertLedger,
    private val here: ReplyAlertPort,
    private val scope: CoroutineScope,
    private val presence: ConversationPresence? = null,
    private val everyFinal: Boolean = false,
) {
    /**
     * The screen of this device, when it can say what it shows. [confirmDisplay]: the visible conversation was refreshed and now holds
     * this very answer. [confirmOpened]: the visible conversation's history was read again. Without them a visible conversation
     * counts as showing its answers (and as read) as soon as it is visible.
     */
    @Volatile var confirmDisplay: (suspend (FinalReply) -> Boolean)? = null
    @Volatile var confirmOpened: (suspend (String) -> Boolean)? = null
    @Volatile var currentNamespace: (() -> String?)? = null
    @Volatile var currentGeneration: (() -> Long)? = null
    @Volatile var canonicalFinal: (suspend (FinalReply) -> Boolean)? = null

    /** [storedSessionId] was just opened (really in front of the user): its stacked alerts on this device are removed. Identities stay claimed. */
    fun onConversationOpened(storedSessionId: String) {
        runCatching { here.dismissConversation(storedSessionId) }
    }

    /** [storedSessionId] became visible: its alerts are removed once its history was read again (at once when the screen can't say). */
    fun onConversationVisible(storedSessionId: String) {
        val confirm = confirmOpened ?: return onConversationOpened(storedSessionId)
        scope.launch {
            val read = try {
                confirm(storedSessionId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (read) onConversationOpened(storedSessionId)
        }
    }

    private suspend fun displayed(reply: FinalReply): Boolean {
        val confirm = confirmDisplay ?: return true
        return try {
            confirm(reply)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    fun onFinalReply(reply: FinalReply) {
        if ((!everyFinal && reply.heard) || reply.cancelled) return
        scope.launch { dispatch(reply) }
    }

    /** True when this answer's alert is taken care of (shown, sent to the Watch, knowingly suppressed or already handled), false when it still has to be shown. */
    suspend fun deliver(reply: FinalReply): Boolean = dispatch(reply) != ReplyAlertResult.NOT_SHOWN_HERE

    /** As [deliver] without waiting: [done] is told the outcome when it is known; a cancelled or crashed attempt tells nothing. */
    fun deliverAsync(reply: FinalReply, done: (Boolean) -> Unit) {
        scope.launch {
            val handled = try {
                deliver(reply)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            runCatching { done(handled) }
        }
    }

    suspend fun dispatch(reply: FinalReply): ReplyAlertResult {
        if (reply.namespace != null && currentNamespace != null && currentNamespace?.invoke() != reply.namespace) return ReplyAlertResult.NOT_SHOWN_HERE
        if (reply.sourceGeneration != null && currentGeneration != null && currentGeneration?.invoke() != reply.sourceGeneration) return ReplyAlertResult.NOT_SHOWN_HERE
        val canonical = canonicalFinal
        if (reply.cancelled) {
            if (reply.canonicalRowId != null && canonical != null && !canonical(reply)) return ReplyAlertResult.NOT_SHOWN_HERE
            return ReplyAlertResult.SUPPRESSED_CANCELLED
        }
        if (reply.canonicalRowId != null && reply.noticeTitle == null && canonical != null) {
            if(!canonical(reply)) return ReplyAlertResult.NOT_SHOWN_HERE
            if(presence?.isVisible(reply.storedSessionId)==true) {
                runCatching { presence?.onArrival?.invoke(reply.storedSessionId) }
                displayed(reply) // Refresh/actual display remains useful; it cannot spend the durable alert obligation.
            }
            return ReplyAlertResult.DURABLE_INTAKE
        }
        if (!everyFinal && reply.heard) return ReplyAlertResult.SUPPRESSED_HEARD
        val alert = runCatching { ReplyAlert(reply.identity, reply.storedSessionId) }.getOrNull() ?: return ReplyAlertResult.INVALID
        if (ledger.isHandled(alert.identity)) return ReplyAlertResult.DUPLICATE
        if (!ledger.begin(alert.identity)) return ReplyAlertResult.NOT_SHOWN_HERE
        var spent = false
        try {
            val sink = reply.sink
            if (!reply.quiet && reply.target == VoiceOrigin.WATCH && sink != null && runCatching { sink.deliverReplyAlert(alert) }.getOrDefault(false)) {
                spent = true
                return ReplyAlertResult.SENT_TO_WATCH
            }
            if (presence?.isVisible(alert.storedSessionId) == true) {
                runCatching { presence?.onArrival?.invoke(alert.storedSessionId) }
                // Suppressed only if the screen really shows this answer now and the conversation is still in front of the user.
                val shown=displayed(reply)
                if (!everyFinal && shown && presence.isVisible(alert.storedSessionId)) {
                    spent = true
                    return ReplyAlertResult.SUPPRESSED_VISIBLE
                }
            }
            val title = reply.noticeTitle
            val preview = runCatching { if (title != null) ReplyPreview.notice(title, reply.text) else ReplyPreview.of(reply.text) }.getOrNull()
            if (!runCatching { here.show(alert, preview, reply.quiet) }.getOrDefault(false)) return ReplyAlertResult.NOT_SHOWN_HERE
            spent = true
            // It became visible while the alert was being shown: the alert goes only if the screen really shows this very answer.
            if (!everyFinal && presence?.isVisible(alert.storedSessionId) == true && displayed(reply)) onConversationOpened(alert.storedSessionId)
            return ReplyAlertResult.SHOWN_HERE
        } finally {
            if (spent) ledger.commit(alert.identity) else ledger.release(alert.identity)
        }
    }
}

/** What both devices carry for an arrival alert: generic text (the Phone adds a preview of the answer itself) and the conversation to open. */
object ReplyAlertContent {
    const val CHANNEL_ID = "reply_arrival"
    const val CHANNEL_NAME = "New replies"

    /** The channel of an alert that must stay silent (headset-only output): low importance, no sound. */
    const val QUIET_CHANNEL_ID = "reply_arrival_quiet"
    const val QUIET_CHANNEL_NAME = "New replies (headset only)"
    const val TITLE = "New reply"
    const val BODY = "A new reply is ready"
    const val PUBLIC_TITLE = "Hermes Voice"
    const val PUBLIC_BODY = "New reply"
    const val NOTICE_LIMIT_TITLE = "Reply may be lost"
    const val NOTICE_LIMIT_TEXT = "Your message reached Hermes, but this phone could not save a note of it. If you close the app before the reply arrives, the reply may not be shown. The message is not sent again."
    const val ACTION_OPEN_REPLY = "com.rumi.hermesvoice.action.OPEN_REPLY"
    const val EXTRA_SESSION_ID = "hv_reply_session"
    const val EXTRA_IDENTITY = "hv_reply_identity"

    /** The conversation an open-reply intent names, or null when it carries none that is safe to act on. */
    fun sessionOf(action: String?, sessionId: String?): String? =
        sessionId?.takeIf { action == ACTION_OPEN_REPLY && DestinationAllowlist.isValidSessionId(it) }
}
