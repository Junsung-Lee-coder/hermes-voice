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
    const val PHONE_CHARGING = "phone_wake_while_charging"
    const val WATCH_CHARGING = "watch_wake_while_charging"
    const val SESSION_MARKER = "session_marker"

    val topicIds: List<String> = listOf(SPOKEN_REPLIES, LATER_REPLIES, USE_HEADSET, ROUTING, PHONE_NAVIGATION, WATCH_NAVIGATION, WATCH, BACKGROUND_RELAY,
        WAKE_PHRASE, WAKE_PATTERNS, STANDBY, VAD, WAITING, DIAGNOSTICS, PHONE_CHARGING, WATCH_CHARGING, SESSION_MARKER)

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
            "headset microphone alone disconnects while you record, the recording ends there and what was recorded is sent. A Bluetooth headset " +
            "may sound worse while its microphone is in use.",
        "The wake phrase itself is heard by Android's speech recognizer, which this app can't point at the headset: only the " +
            "recording that follows it uses the headset microphone.",
        "Headset play/pause: on, with a personal headset output connected and this process alive, a delivered button can start or " +
            "stop Phone recording even with HOME or the screen locked. Android must admit this capture's microphone foreground service " +
            "first; if it refuses, nothing records. Open a signed-in conversation first. The ongoing recording notification's Stop " +
            "ends only that capture (pending starts are cancelled; captured speech is sent). On setting OFF or personal-output/control loss, " +
            "a headset-started capture is discarded, not sent; the on-screen mic's recording stays independent. It never cancels Watch recording or other requests. " +
            "Manual captures end and send after 13 minutes to release microphone and CPU ownership; screen removal alone does not end them.",
        "One short tone in the headset marks a real recording start, two a real stop after microphone release. These tones are not in your request. " +
            "A newly observed disconnected-to-connected headset output can also make one brief connection sound while this switch is on. " +
            "Startup, enabling the switch on an existing headset, and repeated reports do not replay it. A connection during recording is skipped. " +
            "Every tone requires the exact headset route; without it there is no tone. Missing route, disconnect, noisy-output signal or lost focus ends it, never a speaker fallback.",
        "Android decides which app receives media buttons. The connection sound is genuine playback, not a guarantee of button ownership; " +
            "another music app can become the receiver later. There is no silent loop or persistent focus seizure. Volume and Do Not Disturb apply. " +
            "Process death, force-stop and manufacturer power policy can end these controls; reopen the app to arm them again. Interrupted PCM is " +
            "never automatically resumed or sent after restart. Watch microphone input stays independent. Sending a request from the Watch works the same. Use the on-screen mic if buttons go elsewhere.",
        "Background answers (Android 15 and newer): with this app closed Android may not let the Phone play an answer. The button " +
            "below turns on the existing background relay for that, and only when you tap it on this screen; it turns on no other " +
            "option. If Android allows the relay, the Phone can try to play answers on your headset while the app is closed. " +
            "The status reports whether the relay is running, not a guarantee of audible playback. Android admission, " +
            "audio focus, headset routing, volume and Do Not Disturb still apply.",
        "An answer that could not be heard is kept as text on this phone only (up to 8, for 3 days) so Play again still works " +
            "after a restart. That copy is stored unencrypted, stays out of backups and device transfers, and is removed when you " +
            "sign out or change the dashboard or profile. A copy past 3 days is removed the next time the app runs or looks at it, " +
            "not at the exact moment: while the app is not running it stays on the phone. If the phone cannot remove the copy when you sign out " +
            "or change the connection, the app says so, keeps it hidden and unplayable while it runs, and tries again; if the app is closed " +
            "before that works, the copy can be listed again when the app next runs, until it is removed or 3 days have passed.",
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
            "(Android and the maker may still stop it) and may increase battery use. The independent charging option can lift that screen restriction " +
            "only while this device is locally charging; it never turns standby on.",
    ))

    fun chargingWake(device: String): HelpTopic {
        require(device == "Phone" || device == "Watch")
        return HelpTopic(if (device == "Phone") PHONE_CHARGING else WATCH_CHARGING,
            "$device: Always listen while charging", listOf(
                "충전 중 항상 듣기. Off by default. Saved independently for Phone and Watch in this phone's settings; the Watch mirrors its own choice.",
                "On, while this device is connected to external power and Android reports charging or full, its selected, armed standby may keep listening " +
                    "with its own screen off or in ambient display, even with screen-off recognition off or after the ordinary five-second screen window. " +
                    "Phone charging never authorizes the Watch, or the other way around.",
                "Listen on must select this device, and its background standby must be on. Open its app first so Android can admit the microphone. " +
                    "This is a request, not proof that a microphone is running: permissions, sign-in or Phone reachability, busy audio, arbitration, Stop " +
                    "and Android or the maker's restrictions still apply. It never turns another switch on or forces the display to stay on.",
                "Listening uses bounded recognition windows with gaps and normal retries, not guaranteed gapless recognition or a power-policy bypass. " +
                    "Unplugging, a not-charging state or turning this off restores ordinary screen rules immediately, cancels ineligible idle retries, " +
                    "and does not restart the screen's five-second window or cancel an accepted recording, upload, transfer or reply. " +
                    "Existing screen-off recognition remains independent. Stop still stops. Listening may increase power use.",
            ))
    }

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
        "Each device keeps its own technical events for 24 hours (at most 256) in private app storage, so they survive a restart. " +
            "Only when you tap Share is a small text file prepared and Android's share sheet opened; the app sends nothing itself. " +
            "\"Clear this phone's log\" and \"Clear this watch's log\" clear only that device's events.",
        "It lists the step of a request that ran or failed (preparing, uploading, sending, waiting, receiving, speech, transfer), " +
            "fixed failure categories (such as timeout, connection dropped, sign-in), counts, durations, the app version and a hash " +
            "of the installed app file, the network type (Wi-Fi, mobile, VPN) with whether Android validated it, and which switches are on.",
        "It never contains audio, what you said, replies, conversation or project names, file names, addresses, Wi-Fi names, " +
            "accounts, passwords, tokens, device names or ids, or error messages. Requests appear as one-time labels that mean " +
            "something only inside that file. An event not yet written when the app is killed can be missing.",
    ))

    fun sessionMarker() = HelpTopic(SESSION_MARKER, "Sessions made elsewhere", listOf(
        "Every Hermes session has two labels. \"source\" says which client it is routed through now (discord, tui and so on), and other " +
            "clients can change it. \"created_source\" is written once, when the session is created, and is kept. This app lists and " +
            "uses the sessions whose created_source is \"recorder-phone\" (the marker); conversations made in this app carry it.",
        "To use a session that was started elsewhere, such as in Discord, someone with access to the Hermes database sets that session's " +
            "created_source to \"recorder-phone\". After Refresh or the next launch it appears here with its existing history. " +
            "The app creates, copies, renames and sends nothing for that, and the session's source and Discord routing are not touched.",
        "The marker makes a session eligible; it does not mean this app created it. Setting it by hand replaces the record of where " +
            "the session began, so do it only for sessions you mean to use here and keep a backup. Taking the marker off hides the " +
            "session again: the next send or voice request to it is refused and it leaves the voice destinations. A turn the app already " +
            "accepted is not cancelled. A change of \"source\" neither hides a marked session nor makes an unmarked one usable.",
        "Only created_source decides. A session whose created_source is empty or different is not listed or usable, even if its source is " +
            "\"recorder-phone\" or it was made earlier in this app, and an older server without that field shows no conversation. Such " +
            "sessions need the marker set by hand once. For a session that Hermes compressed, set it on the original (root) session. " +
            "At most 100 conversations are active; marked sessions beyond that are counted but not added until you archive one, and a " +
            "session whose marker was taken off keeps its place and voice name until the marker comes back. The phone reads the " +
            "dashboard's sessions page by page to the end of its list and shows each conversation as soon as its page has been read; " +
            "the list says when reading stopped early. The Watch shows a session only after the phone has added it.",
        "Only one Hermes process can write to a session at a time. If the terminal or another Hermes runtime is using it, a send is " +
            "refused with \"currently in use\": release or close it there and retry. Your text stays for retry and nothing was sent; " +
            "the app does not take the session over or resend on its own. A send from this app does not guarantee a Discord " +
            "broadcast or physical delivery. A timeout while opening a session is reported as a timeout, not as in use.",
    ))

    fun all(laterReplyWindowMinutes: Int): List<HelpTopic> = listOf(spokenReplies(), laterReplies(laterReplyWindowMinutes), useHeadset(), routing(),
        phoneNavigation(), watchNavigation(), watch(), backgroundRelay(), wakePhrase(), wakePatterns(), standby(), vad(), waiting(), diagnostics(), chargingWake("Phone"), chargingWake("Watch"), sessionMarker())
}
