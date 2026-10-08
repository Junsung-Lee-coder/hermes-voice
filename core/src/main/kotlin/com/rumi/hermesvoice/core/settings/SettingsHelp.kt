package com.rumi.hermesvoice.core.settings

/**
 * The explanations behind the Phone settings screen's ⓘ buttons. The screen shows compact labels and live status; everything
 * that says exactly how a setting behaves (defaults, which device owns it, what it depends on, what it protects, its limits)
 * is here, one dialog per topic. Plain data so it is tested without a device; the screen only renders it.
 */
data class HelpTopic(val id: String, val title: String, val paragraphs: List<String>) {
    /** Stable UI-test tag of the ⓘ button. */
    val buttonTag: String get() = "help_$id"

    /** Stable UI-test tag of the dialog body. */
    val dialogTag: String get() = "help_dialog_$id"

    /** Spoken name of the ⓘ button: tied to the option it explains. */
    val contentDescription: String get() = "About $title"

    val text: String get() = paragraphs.joinToString("\n\n")
}

object SettingsHelp {
    const val SPOKEN_REPLIES = "spoken_replies"
    const val LATER_REPLIES = "later_replies"
    const val USE_HEADSET = "use_headset"
    const val ROUTING = "routing"
    const val PHONE_NAVIGATION = "phone_navigation"
    const val WATCH_NAVIGATION = "watch_navigation"
    const val WATCH = "watch"
    const val BACKGROUND_RELAY = "background_relay"
    const val WAKE_PHRASE = "wake_phrase"
    const val WAKE_PATTERNS = "wake_patterns"
    const val STANDBY = "standby"
    const val VAD = "vad"
    const val WAITING = "waiting"
    const val DIAGNOSTICS = "diagnostics"

    val topicIds: List<String> = listOf(SPOKEN_REPLIES, LATER_REPLIES, USE_HEADSET, ROUTING, PHONE_NAVIGATION, WATCH_NAVIGATION, WATCH, BACKGROUND_RELAY,
        WAKE_PHRASE, WAKE_PATTERNS, STANDBY, VAD, WAITING, DIAGNOSTICS)

    fun spokenReplies() = HelpTopic(SPOKEN_REPLIES, "Spoken replies", listOf(
        "The routing acknowledgement and the final reply always play. They play on whichever device, this phone or the Watch, " +
            "sent the most recent voice request; text messages don't change that.",
        "\"Play first response\" and \"Play middle responses\" choose whether those earlier replies are spoken as well. " +
            "Both are saved on this phone.",
        "With \"Use headset\" on and a headset connected, this phone's answers are spoken through the headset whatever " +
            "these choices are, including answers to messages you type.",
    ))

    fun useHeadset() = HelpTopic(USE_HEADSET, "Use headset", listOf(
        "Off by default, and set on this phone. Off leaves audio exactly as it is: Android's own headset routing still applies, " +
            "this app neither forces nor blocks it.",
        "On, while a headset is connected to this phone (wired headphones or headset, a USB headset, or a Bluetooth headset), every " +
            "answer that is spoken is spoken only through that headset: answers to a request you spoke on this phone or on the " +
            "Watch, later replies in that conversation (even with \"Speak later replies\" off), and answers to messages you type. " +
            "Nothing is played on the Watch's speaker or on this phone's speaker then, and the Watch is asked to stay silent, " +
            "stopping any answer it was about to play or was playing. It doesn't lengthen how long a conversation is followed. " +
            "With no headset connected nothing changes. Android can't always tell earbuds from a Bluetooth speaker: an audio device " +
            "Android calls a headset or headphones counts, a car, a speaker or the phone's own speaker does not.",
        "The Watch is asked, not assumed: this phone counts the Watch as silent only after the Watch confirms it. A Watch that is " +
            "out of reach, an old Watch app, or one that hasn't reported yet is not counted, and answers still never play on it " +
            "while this phone is private; the Watch's confirmation can lag behind. An answer taken off the Watch's speaker may have " +
            "been partly heard there, and is spoken again from where it was not yet confirmed.",
        "If the headset is unplugged or disconnects before or while an answer plays, that answer stops and is not moved to any " +
            "speaker and does not start again when the headset returns; you get the text and a quiet new-reply notification instead. " +
            "While this is on and a headset is connected the new-reply notification makes no sound of its own on this phone or the " +
            "Watch; a paired watch or the system may still mirror it. Turning this off while an answer plays lets that answer " +
            "finish on the same headset. It never changes the volume, Do Not Disturb or another app's audio, and Stop still stops.",
        "Microphone: on, when the connected headset has a microphone, recordings made on this phone (the mic button and after the " +
            "wake phrase) use it, and the screen says which microphone is used. A headset without a microphone, a missing " +
            "Bluetooth permission, or a headset that can't be reached falls back to this phone's microphone and says so. If the " +
            "headset disconnects while you record, the recording ends there and what was recorded is sent. A Bluetooth headset " +
            "may sound worse while its microphone is in use.",
        "The wake phrase itself is heard by Android's speech recognizer, which this app can't point at the headset: only the " +
            "recording that follows it uses the headset microphone.",
        "Headset play/pause: on, with a headset connected, its play/pause button starts and stops a recording on this phone, " +
            "only while this app is open. One short tone in the headset means a recording started, two mean it stopped; the " +
            "tone is played only to the headset, never on a speaker. If it can't be played to the headset there is no tone, and " +
            "the screen says so. Android decides which app receives the headset buttons, and this app cannot confirm it, so the " +
            "screen only says the control is ready, not that your headset's button reaches it. It never starts or stops a " +
            "recording on the Watch: the Watch microphone is used as before, with or without a headset, and sending a request " +
            "from the Watch works the same. The tone is not part of what is sent.",
    ))

    /** [minutes] is the configured window; the text names it exactly, so it never claims a fixed length. */
    fun laterReplies(minutes: Int): HelpTopic {
        val window = LaterReplyWindow.describe(minutes)
        return HelpTopic(LATER_REPLIES, "Speak later replies", listOf(
            "Off by default, and this phone's own choice: it isn't restored from a backup or moved to a new phone. The duration " +
                "below is a separate setting and never turns this on.",
            "When on, after a voice request has been answered, assistant replies that arrive later in that same conversation within " +
                "$window are spoken too, for example when a task Hermes started finishes. Hermes doesn't mark which request a later " +
                "reply belongs to, so this can also speak a reply to something you or someone else sent to that conversation from " +
                "another Hermes app or the dashboard in that time. Nothing else is spoken: other conversations stay silent.",
            "How long: the window is chosen on this phone (1 minute to 3 days, 30 minutes by default). It is read when a voice " +
                "request has been delivered and applies to that request's follow only: changing it later doesn't shorten or " +
                "extend a request that is already being followed, only the next ones.",
            "A later reply plays on the device of your latest voice request. It waits while a voice request is being answered and " +
                "while the device it will play on records; a recording started on this phone stops it first (it plays again " +
                "afterwards). It doesn't wait for the other device: a reply may play on the other device while one records, and " +
                "nothing keeps the two apart acoustically.",
            "A long window is not a delivery guarantee. Following happens only while this app keeps running and stays connected to " +
                "Hermes: replies arriving after $window, after the connection to Hermes drops, or after Android closes the app " +
                "(or the process ends) are lost, and a reply that arrives after a restart can't be tied to the original request. " +
                "One that arrived must get a free speaker within 10 minutes of arriving (plus up to 3 seconds for a listening window " +
                "to close). Once it plays, no total time cuts a long reply. Preparing its speech has no time limit: it ends with the " +
                "audio, an error, a disconnect or Stop. On the Watch, playback that doesn't advance for about 30 seconds is given up on.",
            "Turning this off, the background relay's Stop, or background listening's Stop ends it and every waiting reply.",
        ))
    }

    fun routing() = HelpTopic(ROUTING, "Voice routing", listOf(
        "On (the default): Hermes picks the conversation for each voice request, or creates one when none fits.",
        "Off: a voice request goes to the conversation open on the device you spoke to (this phone's Chat, or the conversation " +
            "open on the Watch). With none open, nothing is sent and you're asked to open one.",
        "This is one setting on this phone for both devices.",
    ))

    fun phoneNavigation() = HelpTopic(PHONE_NAVIGATION, "Open the routed conversation on this phone", listOf(
        "Off by default. When on, once a voice request has been delivered, this phone shows the conversation it went to " +
            "(only while this app is open; the Watch keeps its own conversation unless the Watch option below is on).",
        "Only used while routing is on. Your choice is kept while routing is off.",
    ))

    fun watchNavigation() = HelpTopic(WATCH_NAVIGATION, "Open the routed conversation on Watch", listOf(
        "Off by default. Saved on this phone and sent to the Watch with the other Watch settings; the Watch only follows it and " +
            "has no switch of its own.",
        "When on, after a voice request spoken on the Watch has been DELIVERED to a conversation (a routing decision, an " +
            "acknowledgement or a failed send doesn't count), the Watch selects that conversation, a newly created one included. " +
            "Requests spoken on this phone never move the Watch.",
        "Nothing is opened and the screen doesn't wake: the Watch's conversation list and chat show it the next time you look. " +
            "If you pick a conversation or change screens on the Watch after speaking, your choice stays and the late move is " +
            "ignored; so is one from an older request, a repeated one, or one after Stop.",
        "Only used while routing is on. Your choice is kept while routing is off. The Watch remembers the selection only while " +
            "its app process runs: after the Watch app is restarted, no conversation is selected.",
    ))

    fun watch() = HelpTopic(WATCH, "Watch", listOf(
        "Push-to-talk is always available on the Watch and this phone and records until you tap Send (no time limit).",
        "Watch haptics is saved on this phone and applied by the Watch.",
        "\"Check\" only asks whether the Watch app can be reached now.",
    ))

    fun backgroundRelay() = HelpTopic(BACKGROUND_RELAY, "Background relay", listOf(
        "Off by default. While on, Watch requests are transcribed, routed, delivered and answered with this app closed and the " +
            "screen off, and a notification with Stop stays visible.",
        "The relay itself never listens or records: this phone's Talk button works only while the app is open, and its wake " +
            "phrase too unless you switch on background listening under Wake phrase.",
        "It doesn't survive a force stop, a restart of the phone or the system's own Stop; it starts again when you open the " +
            "app. It uses more battery.",
    ))

    fun wakePhrase() = HelpTopic(WAKE_PHRASE, "Wake phrase", listOf(
        "\"Listen on\" chooses which device waits for the wake phrase, with its app open or closed. A device it does not select " +
            "never starts listening. A device's own standby switch is an extra condition for listening with its app closed: it " +
            "never makes a device listen that \"Listen on\" leaves out.",
        "When both devices may hear you, only one of them takes each phrase, so a request is never sent twice.",
        "Say the wake phrase, pause for the buzz, then speak: the request is sent when you stop talking (no time limit). Or say the " +
            "request right after the phrase: it's sent only once the speech recognizer has finished hearing it; if it can't, nothing " +
            "is sent and you're asked to repeat.",
        "The microphone: the phone needs its microphone permission (tap Talk once to grant it) and a speech recognizer; the Watch " +
            "shows if it has none.",
    ))

    fun wakePatterns() = HelpTopic(WAKE_PATTERNS, "Wake phrases", listOf(
        "The same list is used by both devices. Separate phrases with spaces; at most ${WakePhrasePatterns.MAX_PATTERN_COUNT} " +
            "phrases of ${WakePhrasePatterns.MAX_PATTERN_LENGTH} characters, ${WakePhrasePatterns.MAX_PATTERN_TEXT_LENGTH} in all " +
            "(more is cut off). An empty list restores the default.",
        "* stands for any characters inside one word, for example \"hermes*\" matches \"hermes\" and \"hermesbot\". Matching ignores " +
            "capital letters and compares whole words, so a phrase never spans two words.",
        "Tap \"Save wake phrases\" to apply them; they reach the Watch with the next sync.",
    ))

    fun standby() = HelpTopic(STANDBY, "Background wake standby", listOf(
        "Both standby switches are off by default (also after an update, and on a new install). They are standby switches, not a " +
            "Stop: turning one off ends that device's waiting for the wake phrase at once, but never cuts a recording, an upload, " +
            "a reply or the button's talk, and the \"Listen on\" choice stays as you set it. Listening with the app closed needs both: " +
            "the device selected in \"Listen on\" and its standby switch on.",
        "While on, a notification with Stop stays visible, and when the app is closed the device listens for the phrase with its " +
            "ON-DEVICE speech recognizer only (nothing is streamed to a server; a device without one, or without the phrase's " +
            "language, doesn't listen and says so).",
        "It is delayed on purpose: short listening windows with longer gaps in between, scheduled with Android's non-exact " +
            "alarms (in Doze a window can come minutes apart), so it uses less battery than listening nonstop, but it is not " +
            "instant and Android or the maker may still stop it.",
        "The Phone's standby is re-started when you open this app if it was ended by the system; the notification's Stop turns it off. " +
            "Android lets a microphone start only from a visible app, so the Watch's standby starts when the Watch app is next opened.",
        "\"Recognition with screen off\" (one per device) is subordinate to that device's standby switch and off by default. " +
            "While the standby is off it has no effect and your choice is kept. When off, the device listens for the wake phrase for " +
            "only 5 seconds after its own screen turns on, then waits for the screen to turn on again; when on, recognition " +
            "continues, also with the screen off or dimmed to the always-on display. It is requested only: screen-off listening remains subject to Android restrictions " +
            "(Android and the maker may still stop it) and may increase battery use.",
    ))

    fun vad() = HelpTopic(VAD, "Send after silence", listOf(
        "Applies to hands-free requests on both devices, from the next request on. Saved on this phone; default " +
            "${VadSilence.label(VadSilence.DEFAULT_SECONDS)}.",
        "Silence is judged by loudness against the room's background, not by understanding speech: steady noise such as a fan fades " +
            "into the background, but loud changing sound (music, TV, other voices) can keep a request open until you tap, and " +
            "very soft speech may count as silence.",
    ))

    fun waiting() = HelpTopic(WAITING, "Waiting for replies", listOf(
        "A request that is waiting for its reply doesn't hold the microphone, the speaker or the screen: you can speak the next " +
            "request, on the phone or the Watch, while earlier ones are still being answered. Each request keeps its own " +
            "conversation, status and reply.",
        "Requests to the SAME conversation are sent one after the other: a later one shows \"Queued\" until the earlier one is " +
            "answered, and can be cancelled there. A few requests may wait at once; past that limit a new request is refused " +
            "with a message instead of being dropped silently.",
        "A reply that arrives while you record or while another reply plays waits its turn: it never interrupts a recording " +
            "and is never spoken over another audio, and it isn't dropped.",
        "Stop on a request (the Stop beside it, or cancelling a queued one) ends only that request. The Stop on the Watch, the " +
            "notification's Stop and turning \"Speak later replies\" off keep ending everything of that device or feature at once.",
        "A long reply is not cut by a total time limit. Waiting for Hermes to answer ends after 15 minutes without any new " +
            "response from Hermes: each new response restarts that time, a keep-alive signal doesn't. Preparing the speech has no " +
            "time limit: it ends with the audio, an error, a disconnect or Stop. On the Watch, playback that doesn't advance for " +
            "about 30 seconds is given up on (a Watch that reports no playback progress gets a limit based on the clip's size " +
            "instead). While speech is prepared the screen says it is waiting; it shows no progress it doesn't have.",
    ))

    fun diagnostics() = HelpTopic(DIAGNOSTICS, "Share diagnostics", listOf(
        "Only when you tap it: the app prepares a small text file of recent technical events and opens Android's share sheet. " +
            "Nothing is sent by the app itself, and you choose where it goes, or cancel there.",
        "It lists kinds of events (request stages, waiting and queue states, speech and playback progress, Stop results), fixed " +
            "failure words, counts, durations and which switches are on, from this phone and, if it can be reached right then, " +
            "the Watch. Without the Watch the phone part is still shared and says the Watch was offline.",
        "It never contains audio, what you said, replies, conversation or project names, addresses, accounts, passwords, tokens, " +
            "device names or ids, or error messages. Requests appear as one-time labels that mean something only inside that file.",
        "Preparing it doesn't interrupt a recording, a reply or any setting. The app keeps only a few recent files in its own " +
            "private cache for at most a day, and shares one file read-only.",
    ))

    fun all(laterReplyWindowMinutes: Int): List<HelpTopic> = listOf(spokenReplies(), laterReplies(laterReplyWindowMinutes), useHeadset(), routing(),
        phoneNavigation(), watchNavigation(), watch(), backgroundRelay(), wakePhrase(), wakePatterns(), standby(), vad(), waiting(), diagnostics())
}
