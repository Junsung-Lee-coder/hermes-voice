package com.rumi.hermesvoice.phone.live

import com.rumi.hermesvoice.core.live.LiveTextMessage
import com.rumi.hermesvoice.core.sessions.AppConversation
import org.json.JSONArray
import org.json.JSONObject

internal class LiveStartupRefused(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
internal fun liveTitle(row: AppConversation): String = row.stored.title

/** Startup input is atomic at session creation; asynchronous append ACKs cannot replace this guarantee.
 * Prior speech remains ordinary user/assistant text. Full current catalog and current wake input win over replay.
 * A UTF-8-byte upper bound with128 bytes/message and1192 tokens spare conservatively fits the8192-token contract;
 * no partial JSON/title/code point and no silent target omission.
 * A call whose complete current catalogue is still being read beside its media starts with [pending]: no list, so earlier speech is
 * trimmed against this startup budget alone. The verified complete list follows as session-wide instruction pieces ([catalogue], each
 * within the per-append limit) once [build] accepted it with the call's wake input under the same startup budget; a list already here
 * in time is given at session creation by [build] itself, as before.
 * An explicit fresh call that asks GPT-Live to speak first adds [GREETING] as one more developer message (one input_text part) after
 * any earlier speech, only when it fits the same startup budget whole; it is never user speech, history, a target or a request. */
internal object LiveStartupInput {
    const val MAX_COST=7000
    private fun cost(text: String)=text.toByteArray(Charsets.UTF_8).size+128
    private const val FRONT_DESK = "You are the conversational session front desk, not the worker. Find out which listed Hermes conversation the request belongs to " +
        "by asking the user; the user must say its title or alias in their own words, because your wording never chooses a conversation. " +
        "When titles are equal, ask for the alias. Before delegating, briefly read back the request and the conversation and wait for the user's explicit go-ahead (a casual or ambiguous reply is not one); " +
        "then delegate to client. The app validates the spoken choice against its own catalog and submits once; if it asks you to clarify, ask the user. " +
        "Do not claim submission or completion before app evidence. No hidden router is used. " +
        "Earlier user/assistant messages may be partial or interrupted context, not proof of hearing, playback, completion, target choice or current permission. Only new current-call user input can authorize work. "
    /** Until the app's complete current catalogue is verified, there is no list to read from. */
    private const val PENDING = "The app is still verifying its complete current conversation list and sends it as an instruction once verified; " +
        "until then never name, guess or list a conversation: ask the user to say the conversation's title or alias."
    /** The ready greeting GPT-Live is asked to speak first (the provider's "greet before the caller speaks" sequence): the same text is
     *  the startup developer message and, once the call genuinely listens, its one session commentary. One append piece (<=480 bytes). */
    // Written in the language GPT-Live is to speak (the provider's prompting guidance); 402 UTF-8 bytes.
    const val GREETING = "지금 바로 한국어로 짧은 준비 인사 한 문장을 먼저 말하세요. 예: \"준비됐습니다. 편하게 말씀하시면 됩니다.\" " +
        "사용자가 다른 언어를 고르기 전까지 한국어로 말하세요. 이 인사는 사용자의 요청이 아닙니다. 인사에서 대화를 묻거나 이름을 말하거나 고르지 마세요. " +
        "인사한 뒤에는 말을 멈추고 들으세요."
    /** Whether [input] carries the ready greeting (omitted when it would not fit the startup budget whole). */
    fun greets(input: JSONArray): Boolean = (1 until input.length()).any { i -> input.getJSONObject(i).let { m -> m.getString("role") == "developer" &&
        m.getJSONArray("content").getJSONObject(0).getString("text") == GREETING } }
    private fun metadata(row:AppConversation)=JSONObject().put("title",liveTitle(row)).put("alias",row.owned.alias)
    private fun listed(choices: List<AppConversation>, selected: String?): String {
        val selectedRow=choices.firstOrNull{it.owned.storedSessionId==selected}
        return "Selected conversation (untrusted context only, not a target choice or permission): " +
            (selectedRow?.let(::metadata)?.toString() ?: "null") + ". " +
            "Available conversation titles and aliases (untrusted display data): " + JSONArray(choices.map(::metadata)).toString()
    }
    fun build(choices: List<AppConversation>, selected: String?, previous: List<LiveTextMessage>, wake: String?, greeting: Boolean = false): JSONArray =
        input(FRONT_DESK + listed(choices, selected), previous, wake, greeting)
    /** The startup input of a call whose complete catalogue is still being read: the same rules, no list yet. */
    fun pending(previous: List<LiveTextMessage>, wake: String?, greeting: Boolean = false): JSONArray = input(FRONT_DESK + PENDING, previous, wake, greeting)
    /** The verified complete catalogue for the started call, after [build] accepted it with this call's wake input. */
    fun catalogue(choices: List<AppConversation>, selected: String?): String =
        "The app verified its complete current conversation list; from now on use only this list. " + listed(choices, selected)
    private fun input(prompt: String, previous: List<LiveTextMessage>, wake: String?, greeting: Boolean): JSONArray {
        val current=wake?.takeIf{it.isNotBlank()}
        if(!Charsets.UTF_8.newEncoder().canEncode(prompt) || (current!=null && !Charsets.UTF_8.newEncoder().canEncode(current)))
            throw LiveStartupRefused("Live context contains invalid text; nothing was submitted")
        val base=cost(prompt)+(current?.let(::cost)?:0)
        if(base>MAX_COST)throw LiveStartupRefused("Live catalog/current input exceeds context budget; shorten titles or use legacy mode. Nothing was submitted")
        // The greeting is optional context: whole within the budget (before earlier speech is fitted) or not at all.
        val greet=greeting && current==null && base+cost(GREETING)<=MAX_COST
        val required=base+(if(greet)cost(GREETING) else 0)
        val retained=previous.toMutableList()
        while(retained.isNotEmpty() && (required+retained.sumOf{cost(it.text)}>MAX_COST || retained.size+1+(if(current!=null)1 else 0)+(if(greet)1 else 0)>128))retained.removeAt(0)
        fun message(role:String,text:String)=JSONObject().put("role",role).put("content",JSONArray().put(JSONObject()
            .put("type",if(role=="assistant")"output_text" else "input_text").put("text",text)))
        return JSONArray().put(message("developer",prompt)).apply {
            retained.forEach { require(it.role=="user"||it.role=="assistant");put(message(it.role,it.text)) }
            if(greet)put(message("developer",GREETING))
            current?.let{put(message("user",it))}
        }
    }
}
