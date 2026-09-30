# Hermes Voice (Phone + Watch)

Hermes Voice is an experimental Android Phone app and a companion Wear OS Watch app for talking
to an existing [Hermes](#hermes-dashboard-apis-used) dashboard by voice or text.

- The **Phone** signs in to a Hermes dashboard and calls its authenticated APIs directly. It
  manages its own conversations, text chat with attachments, and push-to-talk voice turns.
- The **Watch** never talks to Hermes and holds no credentials. It records audio, plays replies,
  and reads the app's conversations, talking only to the paired Phone over the Wear OS Data Layer.
- A voice turn is transcribed on the dashboard and then sent to a hidden **routing session**,
  which picks one of your conversations by alias. The transcript is delivered there only after a
  short spoken acknowledgement has played.

> **Status: pre-release, debug builds only.** There is no release signing, R8/minification is
> off, and the version is `0.1.0-dev`. Voice has run end to end on a Phone emulator, including
> speech fed to the emulator microphone. The Watch has run only on an unpaired emulator. See
> [Validation](#validation) and [Known limitations](#known-limitations) before relying on it.

## Repository layout

```
core/     Pure Kotlin/JVM library: dashboard sign-in, dashboard + gateway clients, app-owned
          sessions, chat and attachments, voice orchestration and routing contract, settings,
          and the Phone↔Watch link contract. Holds all unit tests.
phone/    Android app (Jetpack Compose): sign-in, conversation list, history, text chat with
          attachments, push-to-talk, Phone and Watch settings, Data Layer bridge to the Watch.
watch/    Wear OS app (Compose for Wear OS): conversation reader (session browser and history),
          push-to-talk, optional wake phrase with silence endpointing, playback on the Watch.
scripts/  core-jvm-check.sh: compiles :core and runs its JUnit tests without Gradle or the
          Android SDK.
```

The Phone and Watch APKs share one `applicationId` (see `phone/build.gradle.kts` and
`watch/build.gradle.kts`). Wear Data Layer delivery requires both apps to have the same
application id **and** the same signing key, so build and install both from the same checkout
and keystore.

## Prerequisites

These versions come from the Gradle files in this repository:

| Component | Version |
| --- | --- |
| JDK | 17 (`jvmToolchain(17)` in `:core`, Java/Kotlin target 17 in `:phone` and `:watch`) |
| Android Gradle Plugin | 8.13.2 |
| Kotlin (Android and JVM plugins) | 1.9.22 |
| Compose compiler extension | 1.5.10 |
| Android `compileSdk` / `targetSdk` | 35 (Android SDK Platform 35 must be installed) |
| `minSdk` | 29 on the Phone, 30 on the Watch |
| Gradle | Not pinned: there's **no Gradle wrapper**. The build below was verified with Gradle 8.13; use that version to reproduce it |

Main libraries: kotlinx-coroutines 1.8.1, OkHttp 4.12.0, org.json 20240303 (JVM only; Android
provides its own), Compose BOM 2024.06.00, Wear Compose 1.3.1, and
`play-services-wearable` 18.2.0. Dependencies resolve from Google Maven and Maven Central.

For the Watch's Data Layer features, both devices need Google Play services and the phone needs a
Wear OS companion app to pair them.

## Building

There is no wrapper, so use an installed Gradle 8.13 running on JDK 17. Point Gradle at your
Android SDK with `ANDROID_HOME` or a git-ignored `local.properties` (`sdk.dir=...`). Then run:

```sh
gradle --no-daemon :phone:assembleDebug :watch:assembleDebug
```

The debug APKs are written to `phone/build/outputs/apk/debug/` and `watch/build/outputs/apk/debug/`.
Both are signed with your local debug keystore. They must be signed by the same keystore for the
Watch link to work.

### Core checks without Gradle or the Android SDK

`scripts/core-jvm-check.sh` compiles `:core` and its tests with a standalone `kotlinc` 1.9.22 and
runs them with JUnit 4. It needs `java` (17+), `curl`, `python3` and `sha256sum`. Every jar it uses
is pinned by SHA-256 in the script.

```sh
# First run: FETCH=1 downloads kotlinc 1.9.22 and the pinned jars into the tools directory.
HERMES_VOICE_TOOLS="$HOME/.cache/hermes-voice-tools" FETCH=1 scripts/core-jvm-check.sh

# Later runs reuse the tools directory. Optional environment variables:
#   HERMES_VOICE_WORK=<dir>  build output directory (default: a new mktemp -d)
#   TMPDIR=<dir>             temp directory for the compiler and JVM
#   COMPILE_ONLY=1           compile the core without running the tests
HERMES_VOICE_TOOLS="$HOME/.cache/hermes-voice-tools" scripts/core-jvm-check.sh
```

This script covers only `:core`. The `:phone` and `:watch` modules need the Gradle build.

## Setup and sign-in

1. Open **Settings** on the Phone and enter your dashboard URL, for example
   `https://hermes.example.com/`. Optionally enter a Hermes **profile**. Every REST and RPC call
   carries that profile.
   - The URL must use `https`. Plain `http` is accepted only for loopback (`127.0.0.1`) or
     Tailscale (`100.64.0.0/10`) hosts. URLs with embedded credentials are rejected.
2. Tap **Sign in**. The system browser opens the dashboard's own login page (RFC 8252 native flow
   with S256 PKCE and a `127.0.0.1` loopback redirect). The app never sees your password or
   identity provider login.
3. The resulting bearer pair is sealed with an Android Keystore key and excluded from cloud backup
   and device transfer. It's bound to one dashboard URL and profile, so changing either signs you
   out locally.
4. Create one or more conversations. Each conversation has a **voice alias** (lowercase letters,
   digits, `-` or `_`, up to 32 characters) and an optional description. Voice turns can be
   routed only to these aliases.

## Hermes dashboard APIs used

| Need | Dashboard surface |
| --- | --- |
| Sign-in | `/auth/native/authorize` in the system browser → loopback redirect → `POST /auth/native/token`; `POST /auth/native/refresh` on 401 |
| WebSocket | `POST /api/auth/ws-ticket` → `/api/ws` with subprotocol `hermes-gateway-ticket.<ticket>` |
| Speech | `POST /api/audio/transcribe`, `POST /api/audio/speak` |
| Create session | `session.create {source, title, hidden, messages:[hidden system seed]}` |
| Send | `session.resume`, then `prompt.submit {queued: true}` |
| Events | `message.start`, `message.interim`, `message.complete`. `message.delta` is never treated as a reply |
| List | `GET /api/sessions?source=recorder-phone&archived=exclude\|only&order=recent&limit&offset` |
| Detail / history | `GET /api/sessions/{id}`, `GET /api/sessions/{id}/messages?order=latest&limit&offset` |
| Archive | `PATCH /api/sessions/{id} {archived}` |
| Attach image | `image.attach_bytes {content_base64, filename}` for the next `prompt.submit` |
| Attach file | `file.attach {data_url, name}`. The returned `@file:` reference is prepended to the prompt |

The app never sends `file.attach {path}`, because the gateway host would resolve that path. It
doesn't use `POST /api/files/upload` either. Attachments are limited to 6 per message and 10 MiB
each, which keeps base64 frames under the 16 MiB WebSocket frame limit of older gateways.

## Security model and session ownership

- **App-owned sessions only.** Conversations are created with `source = "recorder-phone"`. The
  Phone keeps a local registry of the exact session ids it created, per dashboard URL and profile.
  A session is listed only if the server's source filter returns it **and** its id is in the
  registry.
- **Checked before every action.** Before reading history, archiving, or submitting text or voice,
  the app checks that the session is in the registry and that the server row's `source` matches.
  Anything else fails with `SessionNotOwnedException`.
- **This isn't an access-control boundary.** `source` is only a categorization filter. The same
  dashboard identity can still reach its other sessions through other clients. The checks only
  keep this app confined to its own sessions.
- **The registry is local.** After a reinstall or on a second phone, earlier sessions aren't listed,
  even though they still carry the app's source on the server.
- **The Watch holds no credentials.** It never calls Hermes and only exchanges `/hv/v1/*` messages
  with the Phone. Its conversation reader goes through the Phone with the same checks (see
  [Watch conversation reader](#watch-conversation-reader)).

## Voice turns

The flow is the same whether a turn starts on the Phone or the Watch:

1. **Record a WAV.** On the Phone, the Talk button toggles push-to-talk recording; the Phone
   push-to-talk recorder keeps at most the first 2 minutes of a recording (a Phone-only limit). On
   the Watch, push-to-talk records until you tap Send with no time limit. On either device the
   optional [wake phrase](#wake-phrase-phone-and-watch) starts a hands-free recording that is sent
   when you stop talking. The Watch sends its recording to the Phone over the Data Layer
   (`/hv/v1/turn/<id>`). One Data Layer frame holds about 13 minutes of audio; a longer Watch
   recording is not sent, and the Watch says so (a Phone hands-free recording has the same bound).
2. **Check, then transcribe.** A recording with no usable audio (unreadable, digital silence or a
   dead microphone, or only steady background noise) ends right away as "No speech detected": it
   isn't sent, transcribed, routed or delivered, and it doesn't change where replies play. The Watch
   checks before uploading and the Phone checks again before accepting. The check runs the same
   voice-activity detector as the hands-free ending (see [Hands-free ending](#hands-free-ending-shared-vad)),
   with a lower bar: it only refuses a recording in which nothing rose at least half again above
   its own background for 140 ms, or whose loud part (half a second or more) stays at one steady
   level, i.e. silence or steady noise, also when the noise started and stopped during the
   recording. Speech in loud steady noise
   (tested down to 3 dB above it) passes. It is a loudness check, not speech recognition: it lets
   soft and short speech through, so changing non-speech sound still reaches speech-to-text, which
   can mistake it for words. Otherwise the Phone calls `POST /api/audio/transcribe`. If that finds no speech,
   the turn ends without routing or delivering anything. A wake-phrase request the recognizer
   already heard in full arrives as text instead and skips this step; it is treated exactly like a
   transcript.
3. **Route.** The transcript goes to a **persistent routing session**. It is created hidden
   (`source = "recorder-phone-router"`) on first use, then reused, and never appears in lists. The
   prompt restates the routing contract (version 2), lists the allowlisted aliases (possibly none),
   and marks the transcript as untrusted data.
4. **Fail closed.** The router must reply with exactly one JSON object, one of:
   - `{"action":"route","destination":"<alias>","ack":"<text>"}` to use an existing conversation.
     The older `{"destination","ack"}` form without `action` still means this.
   - `{"action":"create","title":"<title>","alias":"<alias>","description":"<text>","ack":"<text>"}`
     to ask for a **new conversation**, only when none of the listed ones fits (see
     [New conversations from the router](#new-conversations-from-the-router)).
   - The allowlist is built only from your **unarchived** conversations (at most 32). Archiving a
     conversation removes it as a destination. The routing session can never be a destination.
   - A `destination` must match an allowlist entry exactly, and the ack must be 1–240 characters.
   - The two forms can't be mixed: a route with a title or alias, a create with a destination, an
     unknown `action`, or anything else fails closed: nothing is spoken, nothing is created and
     nothing is delivered. Extra keys, including a session id, source or role the model invents,
     are ignored. Session ids never come from the model.
5. **Acknowledge.** The ack is synthesized with `POST /api/audio/speak` and played. If it can't be
   played, the transcript isn't delivered. For a Watch turn, only a `/hv/v1/played` ACK from that
   Watch's node counts. The Watch sends `ok=true` only when its player completes normally.
6. **Deliver.** Only then is the original transcript submitted to the chosen conversation.
7. **Speak replies.** The final reply (`message.complete`) is always spoken. The first and middle
   interim replies are spoken only if enabled in Settings (both are off by default). Text you
   already heard isn't repeated, and the app never makes up interim events.

Turns are serialized from capture to delivery. Replayed turn ids, such as Data Layer retries, are
ignored.

### New conversations from the router

When nothing you have fits what you said, including when you have no conversations yet, the router
may ask for a new one. You are not asked to confirm; the request itself is the authorization.

- **The router only proposes.** It suggests a title (up to 80 characters), an alias and a short
  description, with the same limits as conversations you make by hand. The Phone creates the
  conversation through the same dashboard call and the same ownership rules as the Create button:
  visible, tagged with the app's source, recorded in the local registry. The Watch never creates
  anything; its turns go through the Phone.
- **The alias is assigned by the Phone.** If the suggested alias is already in use, the Phone adds
  a short suffix derived from the turn (`garden-3fa2`). An existing conversation is never used in
  its place.
- **Order.** Create → read the new row back from the dashboard and check it is the app's and not
  archived → register it → speak the ack and wait for playback to finish → deliver your words
  unchanged → speak the reply. If creating or checking fails, the turn ends with a message saying
  so: no ack is spoken and nothing is sent to any other conversation.
- **Afterwards** the new conversation is a normal one: it is in the Phone list and the Watch
  browser, and the router can pick its alias for later requests.
- **At most once per request.** The Phone keeps a small journal (turn id, title, alias, the ack,
  the created session id; never your words) next to its registry. A repeated or replayed turn,
  even after the app restarts, reuses the conversation it created and never sends your words
  twice: a turn already handed to the conversation is reported as a duplicate.
- **What can't be guaranteed.** Hermes's create call has no idempotency key or lookup. If the app
  stops or the connection drops after the dashboard created the conversation but before the Phone
  recorded its id, the Phone can't tell whether one exists. That turn then fails with "a new
  conversation may already have been created" and is never retried automatically, so there is no
  duplicate, but an unused conversation may be left on the dashboard that the app doesn't list.
  Likewise, if the app stops in the instant between marking the turn as sent and sending it, a
  replay is treated as already sent.
- **The ack is the router's sentence.** The prompt asks it to say a new conversation with that
  title is being created; the title is used as given, but the wording is the model's.

### Where audio plays: the latest accepted voice sender

Every spoken utterance plays on the device, Phone or Watch, that **most recently submitted an
accepted voice request**. That covers the ack and the first, middle and final replies of any turn.

- A request counts as accepted as soon as the Phone admits its turn id, before transcription. A
  request that turns out to be silence, or that the router rejects, still moves the route.
- Text chat and replayed turn ids never move it.
- The target is chosen when each utterance is handed off, after its audio is fetched. An utterance
  already handed to a device finishes (or is stopped) there, and the next one goes to the newest
  target.
- For example, if you talk on the Watch and then on the Phone before the Watch turn's reply
  arrives, that reply plays on the Phone.
- The Phone's Talk bar shows the current target. The route is held only in memory on the Phone.
- Creating a conversation for a request doesn't change any of this: the request's device was
  already the target when the request was accepted.

## Watch conversation reader

The Watch shows the app's conversations without ever calling Hermes itself:

- **Swipe left** (right to left) switches between the session browser and the open conversation.
  **Swipe right** (left to right) sends the app to the background; the task and what you were
  reading stay alive. Vertical touch and the bezel/crown scroll whichever list is on screen. A
  gesture a list already scrolled with never counts as a swipe.
- The browser lists the app-owned, unarchived conversations. The conversation shows your own
  messages right-aligned in blue and replies left-aligned in grey, newest at the bottom, with
  "Load older" at the top.
- New messages don't move the view while you're reading older ones. The view follows new
  messages only if the newest one was already on screen.
- Loading, "Phone not reachable", sign-in and timeout states are shown explicitly with a Retry.
- **Wire:** `/hv/v1/reader/request` (Watch → Phone) and `/hv/v1/reader/response` (Phone → the
  asking node only). Every request has an id; a response is applied only if it answers the
  request still pending for that list or conversation and comes from the node it was sent to, so
  late or duplicate answers are dropped.
- **Ownership:** the Phone answers from the same registry and server `source` checks as its own
  history. Router, archived, foreign or unknown ids fail closed. Reading never submits anything and
  never changes where voice replies play or which conversation a voice turn goes to.
- **Bounds:** at most 24 conversations, 20 messages per page and 60 KB per response (long texts are
  shortened, marked "more on phone"). The Watch caches 4 conversations and 200 messages each.
- **Haptics:** a 10 ms touch-feedback tick per bezel step that actually scrolled (nothing at the
  ends), a 50 ms pulse when the microphone really
  starts delivering audio, and a 2 × 30 ms pulse once when recording ends for any reason (also when
  a wake-phrase request heard by the recognizer is sent). Recording pulses are marked as hardware
  feedback rather than touch feedback (how each watch treats that is up to the watch); none of them
  overrides Do Not Disturb or the watch's vibration settings. The Phone's Watch **Haptics** setting turns them all off.

## Wake phrase (Phone and Watch)

Off by default and edited only on the Phone (Settings → Wake phrase). **Listen on** chooses where
it works: **Off**, **Watch**, **Phone** or **Both**. Settings saved before this selector existed
migrate once: a Watch wake phrase that was on becomes **Watch**, anything else **Off**. The wake
phrases themselves are one list shared by both devices: space-separated whole words, where `*`
matches any letters inside one word and nothing else is special. A blank field restores the
defaults. The Phone sends all voice settings to the Watch as one snapshot with an increasing
revision (the `/hv/v1/settings` data item). The Watch refuses a snapshot with any invalid value
(an unknown mode, a silence that isn't a number from 0.5 to 10 on the 0.5 grid, a wrong type) and
ignores older or equal-revision ones, so a stale snapshot can't switch a listener back on; after
it is opened, the Watch reads the synced snapshot before it listens. Settings shows each device's
status: off, listening, or why it can't (no speech recognizer, no microphone permission, not
signed in, Watch not reachable).

It works only while that device's app is open on screen (foreground), never in the background,
and needs no permission beyond the microphone. Each time the app is shown, or the screen turns
back on with the app shown, the platform `SpeechRecognizer` listens for 5 seconds. On-device
recognition is used when available; if it reports that it lacks the wake phrases' language, the
app falls back once to the system's default recognition service, which may send audio over the
network. It never runs while recording, sending, waiting for a reply, playing
audio, or for 4 seconds after playback. A device the setting excludes doesn't listen or respond;
turning it off, leaving the app or the screen going off closes the recognizer and stops a
hands-free recording without sending it. The recognizer and the app's recorder never use the
microphone at the same time: tapping Talk while the recognizer is listening releases it first and
starts recording after the same short pause. Leaving the screen, which includes rotating the
Phone, cancels a hands-free recording with "Cancelled"; nothing is sent. The Watch never holds
credentials: its requests always go through the Phone.

**Both** means both devices may hear the wake phrase, but one spoken wake phrase is answered by
one device:

- When a device's recognizer hears the wake phrase at the start of what you say, in a partial or
  final result, it asks the Phone for the claim on that wake phrase before it records or sends
  anything. The Phone is the only referee. The Watch asks over the paired link and the Phone
  identifies it by the link, not by anything the Watch says.
- The first to ask wins and keeps the claim while it listens, hands over to its recorder and
  records, renewing it every few seconds for as long as that takes (this is not a time limit on
  the recording). The other device is refused: it stops listening, shows that the other device
  answered, and sends nothing.
- The Phone accepts a wake-phrase request in Both only with the claim it gave that device, once.
  A request without it, with another device's, with one that ran out, or made before the Phone
  app restarted is refused before it is accepted: nothing is transcribed or delivered and where
  replies play doesn't change.
- If the answer doesn't arrive within 2.5 seconds, the claim was made under settings the Phone has
  since changed, or a renewal is refused, the device stops without sending and asks you to say it
  again. A claim that is no longer renewed (the app closed, the link dropped) runs out after 10
  seconds. Late messages about an old claim can't affect a newer one.
- For 3 seconds after a request is accepted, the other device still can't claim, because its
  recognizer may only just be finishing the same spoken phrase. The same device can start its
  next request at once. Requests are never compared or merged by their words: saying the same
  thing twice on purpose is two requests.
- Push-to-talk and typed messages are never arbitrated, and with Watch or Phone alone there is
  nothing to arbitrate.
- Where a recognizer gives no partial results, the claim is made at its final result, so both
  devices may listen to the whole phrase before one is refused; still only one request is
  accepted.

The wake phrase must come first: at the start of what you say, or right after one short greeting
("hey", "hi", "hello", "ok", "okay", "안녕", "저기"). A mention in the middle of a sentence is
ignored. What happens next depends on how you say it:

- **Wake phrase, then pause:** the recognizer is released, the app's own recorder starts and
  measures the room, and a buzz (a haptic on the Watch and on the Phone; no sound, which would be
  recorded) means "speak now". The request is sent after the **trailing silence** you set (see
  below); short pauses don't end it, and there is no time limit while you keep talking. Tap Send
  (Watch) or "Send now" (Phone) to send it sooner. If you don't start within 8 seconds, nothing is
  sent. This is the way to make a long request.
- **Wake phrase and request in one breath:** only the recognizer's **final** result is used, and
  it's sent whole as the request. While partial results keep changing, the device keeps waiting
  (up to 8 seconds after the last new partial). Nothing is sent, and the device shows "Didn't
  catch that", when the recognizer stops or fails before a final result, or when its final result
  is empty or no longer starts with the wake phrase it heard. A request longer than 4,000
  characters is refused, not cut.
- **The system recognizer has its own limits.** It decides when you've finished (its own pause
  detection, separate from the app's trailing-silence setting) and may end a session on its own,
  so a pause in a one-breath request can end it early, and what you say after that isn't captured.
  For a long request, say the wake phrase, wait for the buzz, then speak.

A request spoken on the Phone plays its replies on the Phone, one spoken on the Watch on the Watch
(the latest accepted voice sender, as for push-to-talk). The recognized text is never logged; only
counts and outcomes are.

### Hands-free ending (shared VAD)

Both devices end a hands-free request with the same voice-activity detector (VAD), in `:core`:

- **Trailing silence** (Settings → Wake phrase): 0.5 to 10 seconds in 0.5-second steps, 2 seconds
  by default. The request is sent that long after you stop talking. A recording keeps the value it
  started with; a change applies from the next request. Short values end at natural pauses between
  phrases (0.5 s ends in a 0.7 s pause); long values wait through them.
- **How it listens:** in 20 ms frames, it compares loudness with the room's background, which it
  measures for 0.4 s before the "speak now" buzz. Speech is three times the background or louder
  for at least 140 ms, with gaps between syllables of up to 200 ms tolerated and at least 45% of
  that stretch voiced. A fading syllable stays "speech" for at most 200 ms. The timing is the same
  on the Phone and the Watch and doesn't depend on how the microphone delivers audio.
- **The background estimate** comes from the last second of sound:
  - It drops quickly to the quietest moments of that second. So if it started too high, because
    you spoke before the buzz or a steady sound was taken for background, it recovers at the next
    natural dips of your speech.
  - It rises only when the whole last second is steady (no louder moment more than twice the
    quietest), and only if that steady sound is not voiced, or is barely above the speech
    threshold, or is clearly quieter than your speech so far (under about 45% of its typical
    level). Speech changes loudness all the time, so it never raises the estimate. A fan that
    starts after you stop is adopted within a second or two and the request ends; in tests,
    background that rose to 2.5–10 times its level ended the request 2.7–3.6 s after the speech.
  - A steady sound about as loud as your speech is never taken for background: a drawn-out "uhh",
    a held note, or a vacuum cleaner while you talk does not end your request, however long it
    lasts.
- **What it can't do:** it measures loudness, not speech.
  - Loud changing sound (music, TV, other people talking) keeps a request open until you tap Send.
  - So does steady noise that is about as loud as your speech (in tests, from about 40% of its
    level): it can't be told from a voice, so the request stays open rather than being cut.
  - Speech that stays very soft, under about three times the background, may count as silence
    and end the request early, or never start it.
  - A single click or knock of 100 ms or less doesn't count, and neither does sparse clicking
    such as slow typing. Dense tapping or knocking (voiced nearly half the time) can count, and a
    burst of noise longer than 140 ms, like a cough, can start a request, which speech-to-text
    may then find empty.

## Text chat and history

History shows user and assistant messages (hidden and tool rows are dropped), newest page first,
with "load older". Text messages use the same ownership checks as voice. A reply appears after the
turn completes. Replies to text chat aren't spoken.

## Settings and UI

- **Phone settings:** dashboard URL and profile, sign in/out (with confirmation), **Appearance**
  (Dark by default, Light, or System), **Play first response** (off by default) and **Play middle
  responses** (off by default). There's deliberately no setting for the ack or the final reply.
  Settings also shows whether the Watch app is reachable.
- **Voice settings** (edited on the Phone, synced to the Watch as the `/hv/v1/settings` data
  item): where the wake phrase listens (Off by default), the shared wake phrases, the hands-free
  trailing silence (2 s by default), and Watch haptics. There is no Watch recording time limit.
- **Dark by default.** On the Phone, sign-in, lists, chat, Settings and dialogs follow the
  Appearance setting. The Watch is always dark and shows whether the Phone is reachable.
- **Full-width Talk bar.** The Phone's Talk button is a full-width bar above the navigation bar,
  inset for the safe area. It never covers the chat composer, which moves above the keyboard.

**Emulator QA input (debuggable builds only).** A debuggable build accepts an `hv_qa_wav` string
extra naming a `<name>.wav` file in the app's private `files/qa/` directory, and submits that file
through the normal voice path in place of a recording. Both apps accept
`hv_qa_wake_handoff=second_utterance` (runs the wake handoff as a match would, to test the
recorder on the real microphone, not recognition; a device the wake setting excludes ignores it).
Both also accept `hv_qa_recognizer=fixture` with `hv_qa_wake_heard=<text>` (and
`hv_qa_wake_final=false` for a partial): a simulated recognizer result for the window that launch
opens. It tests the wake flow and the Both arbitration, never recognition.
The Phone accepts `hv_qa_wake_location=<OFF|WATCH|PHONE|BOTH>` and `hv_qa_vad_silence=<seconds>`
(saved and sent to the Watch exactly as from Settings; invalid values are refused). The Watch
accepts `hv_qa_seed_reader=<n>` (shows synthetic reader rows, to test the reader UI without a
paired Phone). Each extra is honoured once, for a fresh launch intent only, and non-debuggable
builds ignore them.

## Validation

Only the following has been run:

- **Core unit tests:** `scripts/core-jvm-check.sh` compiles `:core` and runs **257 JUnit tests**, all
  passing. They use an in-process fake dashboard and cover sign-in, session ownership, chat and
  attachments, routing, playback routing, the Watch link and reader contracts, the wake contract
  (final-only, leading wake phrase, contradicted or empty finals, 30/60/120-second recognizer
  streams), the shared VAD (every trailing-silence choice ends exactly that long after the last
  speech; identical timing at 8/16/32/48 kHz and 20–160 ms reads; all-zero, peak-2, clicks at any
  alignment and four background levels give no speech; 30, 60 and 120 seconds of modulated speech
  are never cut and never raise the background estimate; background rises of 2.5× to 10× after
  speech still end a request; a held sound or a steady noise burst in the middle of a request, a
  shallow-modulated voice, and speaking before the buzz (40 seeds at four levels) never end it
  early; isolated and sparse clicks don't count; short words and soft speech do; push-to-talk
  speech 3, 5 and 7 dB above steady noise is accepted for 30 seeds at 4, 20 and 60 s while silence
  and steady noise alone (throughout, or between quiet margins) are refused; whenever the ending finds speech the recording check
  accepts the same audio), wake arbitration in Both (the claim lease, its renewal, expiry and
  stale messages; two simulated recognizers hearing the same phrase in either order, by partial or
  final result, phrase-only and in one breath; unanswered, refused and lost claims; push-to-talk
  and deliberate repeats; the Watch link), the voice settings (0.5–10 s validation, the
  wake-location modes, durable migration from the old Watch switch, strict Watch-side validation,
  stale snapshots), the per-device wake flow both apps delegate to (which device listens for each
  mode, turning a device off mid-window or mid-recording, the silence snapshot, same-breath
  requests), routing to a new conversation (contract v2 parsing and rejections, an empty list,
  reuse on the next request, alias collisions, refused and unverifiable creations, the same turn
  repeated or run concurrently, and restarts after delivery, before delivery and with an
  unresolved creation), the recording loop of both recorders (Phone and Watch read sizes), the recording input
  check, haptic timing, gesture arbitration, bezel scrolling, and source checks of the Android wiring.
- **Paired debug build:** `gradle :phone:assembleDebug :watch:assembleDebug` succeeded with
  Gradle 8.13 and JDK 17. Both APKs have the same package and are v2-signed by the same debug
  certificate.
- **Paired emulators (Phone Android 15, Watch Wear OS 5), real microphone input.** Speech was
  played into both emulators' microphones through a virtual audio cable, against a local test
  dashboard with a real model, speech-to-text and text-to-speech:
  - *Settings:* changing the wake location and trailing silence on the Phone (Settings screen
    and debug extras) reached the Watch within about 2 seconds each time; equal-revision snapshots
    read again on resume were ignored; invalid values (0.7, 12, NaN, an unknown mode) were refused
    and not sent. A Phone that had the old Watch wake switch on started with **Watch** selected.
  - *Hands-free ending:* on the Phone, requests ended 500, 2000, 5000 and 10000 ms after the last
    speech for the 0.5, 2, 5 and 10 s settings; on the Watch 500, 1980 and 4960 ms for 0.5, 2 and
    5 s (a short sound in the tail pauses the count without restarting it). A 73-second request
    with pauses was recorded whole and ended once on each device. Soft speech (10 % level) and a
    single short word ended correctly. A request followed by steady noise ended 2.3 s after the
    speech while the noise kept playing. With no speech, the recording stopped 8 s after the buzz
    and nothing was sent; where replies play did not change.
  - *Phone wake phrase with the platform recognizer (English):* a request in one breath was sent
    as text once the recognizer's final result arrived; the phrase alone started the recorder
    0.3 s after the recognizer was released and the request ended at the set silence. Both ran
    through routing, the ack, delivery and the final reply on the Phone. Speech without the wake
    phrase sent nothing. With the default Korean phrases, this emulator's on-device recognizer had
    no Korean model and its fallback recognizer returned no match, so Korean recognition was not
    verified.
  - *Wake location:* with **Phone**, the Watch ignored a wake handoff; with **Watch**, the Phone
    never listened; with **Both**, the Phone answered. Turning the Watch off from the Phone while
    the Watch was recording a hands-free request, and leaving the Phone app while it was
    recording one, both stopped the recording without sending it.
  - *Push-to-talk:* on both devices a recording with 6 seconds of silence after the speech was
    sent only on the tap; a silent Phone recording was refused before speech-to-text.
  - *New conversations from the router:* starting with no conversations at all, a spoken request
    on the Phone led the real routing model to ask for a new conversation; the Phone created it,
    played the ack, delivered the transcript and played the reply, and the conversation appeared
    in the Phone list. The next request on that topic was routed to it without creating another.
    A hands-free Watch request on an unrelated topic created a second conversation, with the ack
    and the reply played and acknowledged on the Watch; the Watch browser listed it on its own and
    showed its history, and a follow-up from the Watch reused it. The dashboard held exactly those
    two app conversations plus the hidden routing session. Whether to create was the model's own
    choice in these runs; forced replies (refusals, malformed replies, failures, restarts) were
    exercised only in the unit tests against the fake dashboard.
  - *Earlier builds:* Watch reader, gestures, bezel scrolling and haptics, Watch playback with
    `played` ACKs, and playback switching between devices.

**Never run:** wake-phrase recognition on the Watch (the Watch emulator has no speech recognition
service, so its recorder was started by the debug handoff), Korean wake-phrase recognition, and
anything on physical devices, including audio routing, haptic strength, real room acoustics and
a physical bezel. The emulator microphone occasionally delivers digital silence; those runs ended
as "no speech" and were repeated.

## Known limitations

- **Not release-ready.** Debug builds only: no release signing configuration and no R8. Tested
  on emulators only (see [Validation](#validation)).
- **A reset dashboard at the same address.** The app's saved sessions, including the hidden
  routing session, belong to the dashboard data that created them. If that data is reset while the
  address stays the same, voice turns stop with "session no longer exists on the dashboard".
- **Playback route is in memory.** After the Phone process restarts, nothing plays until the next
  accepted voice request. If the route points to a Watch that has become unreachable, that
  utterance fails and the Phone doesn't play it instead. If the failed utterance is an ack, the turn
  isn't delivered.
- **One live transport per Hermes session.** `session.resume` moves the session's event stream to
  this client. Another client streaming the same session stops getting live events until it
  re-attaches.
- **Replies are matched by order.** Gateway events carry no per-prompt id. If another prompt is
  already queued, the message is still delivered, but its reply isn't spoken or shown inline.
- **Server-to-client requests aren't answered.** A turn that needs a tool approval or a
  clarification ends at the 15-minute timeout.
- **Wake phrase is foreground-only** and depends on each device's speech recognition service.
  Where none is installed, the device says the wake phrase is unavailable. There's no always-on
  hotword.
- **Hands-free ending is loudness-based**, not speech understanding (see
  [Hands-free ending](#hands-free-ending-shared-vad)).
- **Watch reader history is a window.** The Watch keeps at most 200 messages per conversation;
  older ones are shown on the Phone.
- **Unverified:** whether the agent reads an attached `@file:` reference during a turn.

## License

This repository doesn't include a license file yet.
