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
   the Watch there is no Talk button: hold a finger still anywhere on the main screen for one
   second to start recording, and hold again (a new press) to stop and send; there is no time
   limit (see [Watch conversation reader](#watch-conversation-reader)). On either device the
   optional [wake phrase](#wake-phrase-phone-and-watch) starts a hands-free recording that is sent
   when you stop talking. The Watch sends its recording to the Phone over the Data Layer
   (`/hv/v1/turn/<id>`). One Data Layer frame holds about 13 minutes of audio; a longer Watch
   recording is not sent, and the Watch says so (a Phone hands-free recording has the same bound).
2. **Check, then transcribe.** A recording with no usable audio (unreadable, digital silence or a
   dead microphone, or only steady background noise) ends right away as "No speech detected": it
   isn't sent, transcribed, routed or delivered, and it doesn't change where replies play. The Watch
   checks before uploading and the Phone checks again before accepting. The check runs the same
   voice-activity detector as the hands-free ending (see [Hands-free ending](#hands-free-ending-shared-vad)),
   with a lower bar. It refuses a recording in which nothing stood out from its own background for
   140 ms, or whose loud part (half a second or more) stays at one steady level, i.e. silence or
   steady noise, also when the noise started and stopped during the recording. How far "stood
   out" is depends on the background: half again above a steady one (speech 3 dB above loud
   steady noise passes), and more when the background's own level moves: several times its usual
   swing, up to four times the background. So a room whose noise merely wanders is refused
   too, and speech there has to be clearly above the noise. It is a loudness check, not speech
   recognition: it lets soft and short speech through, so changing non-speech sound (dishes, a
   door, typing next to the microphone) still reaches speech-to-text, which
   can mistake it for words. Otherwise the Phone calls `POST /api/audio/transcribe`. If that finds no speech,
   the turn ends without routing or delivering anything. A wake-phrase request the recognizer
   already heard in full arrives as text instead and skips this step; it is treated exactly like a
   transcript.
3. **Route.** The transcript goes to a **persistent routing session**. It is created hidden
   (`source = "recorder-phone-router"`) on first use, then reused, and never appears in lists. The
   prompt restates the routing contract (version 2), lists the allowlisted destinations (possibly
   none) as one JSON object per line, and marks both their descriptions and the transcript as
   untrusted data. A routing session created by an older version of the app is replaced once (see
   [New conversations from the router](#new-conversations-from-the-router)).
4. **Fail closed.** The router must reply with exactly one JSON object, one of:
   - `{"action":"route","destination":"<alias>","ack":"<text>"}` to use an existing conversation.
     The older `{"destination","ack"}` form without `action` still means this.
   - `{"action":"create","title":"<title>","alias":"<alias>","description":"<text>"}` to ask for a
     **new conversation**, only when none of the listed ones fits (see
     [New conversations from the router](#new-conversations-from-the-router)). It carries no ack:
     the Phone words that one itself, and ignores any the model adds.
   - The allowlist is built only from your **unarchived** conversations (at most 32). Archiving a
     conversation removes it as a destination. The routing session can never be a destination.
   - A `destination` must match an allowlist entry exactly, and the ack must be 1–240 characters.
   - The two forms can't be mixed: a route (with or without `action`) that also has a title, alias
     or description, a create with a destination, an unknown `action`, or anything else fails
     closed: nothing is spoken, nothing is created and nothing is delivered. Extra keys, including a session id, source or role the model invents,
     are ignored. Session ids never come from the model.
5. **Acknowledge.** The ack is synthesized with `POST /api/audio/speak` and played. For an
   existing conversation it is the router's sentence (its wording is the model's, so it can be
   imprecise). If it can't be played, the transcript isn't delivered. For a Watch turn, only a `/hv/v1/played` ACK from that
   Watch's node counts. The Watch sends `ok=true` only when its player completes normally.
6. **Deliver.** Only then is the original transcript submitted to the chosen conversation, after
   one more check that it is still the app's and not archived, on the Phone or on the dashboard.
   If it was archived while the ack played, nothing is sent, to it or anywhere else.
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
  its place. Titles don't have to be unique, so two conversations can share one; the alias is what
  tells them apart, and the ack names it. Creating by hand, renaming, unarchiving and the router's
  creations all go through one lock, so an alias can't be taken twice and the limit of 32 active
  conversations holds; a manual alias that is already used is refused, never changed for you.
- **The Phone words the ack.** For a new conversation the spoken ack is composed by the Phone
  from what it actually created: "Creating a new conversation called *title*, alias *alias*, and
  sending this there." (in Korean when your request contains Hangul). Whatever sentence the
  model wrote for it is not used, so the ack can't name another conversation or claim nothing was
  created.
- **Order.** Save "about to create" → create → save the new id → read the new row back from the
  dashboard and check it is the app's and not archived → save it in the registry → speak the ack
  and wait for playback to finish → check again that it can receive → save "sent" → deliver your
  words unchanged → speak the reply. Every "save" is written to the Phone's storage and
  confirmed before the next step. If creating, checking or saving fails, the turn ends with a
  message saying so: no ack is spoken and nothing is sent to any other conversation.
- **Afterwards** the new conversation is a normal one: it is in the Phone list and the Watch
  browser, and the router can pick its alias for later requests.
- **At most once per request.** The Phone keeps a small journal (turn id, title, alias, the
  created session id; never your words) next to its registry. A repeated or replayed turn, even
  after the app restarts, reuses the conversation it created and never sends your words twice: a
  turn already marked as sent is reported as a duplicate.
- **If the Phone's storage fails.** A save that isn't confirmed stops the turn, and the app then
  refuses to create, acknowledge or send anything from that data until it is restarted and has
  read what really is on storage. Saved data that can't be read (damaged) is never replaced or
  emptied: voice and lists fail with a message until it is dealt with.
- **What can't be guaranteed.** Hermes's create call has no idempotency key or lookup. If the app
  stops or the connection drops after the dashboard created the conversation but before the Phone
  saved its id, the Phone can't tell whether one exists. That turn then fails with "a new
  conversation may already have been created" and is never retried automatically, so there is no
  duplicate, but an unused conversation may be left on the dashboard that the app doesn't list.
  Likewise, if the app stops in the instant between saving "sent" and sending, a replay is treated
  as already sent, so that request can be lost. It is at most once, not exactly once.
- **Text from the model is cleaned.** Titles, descriptions and acks lose control characters,
  invisible and direction-changing characters and line separators; ordinary letters of any
  language stay. Descriptions reach later routing prompts only as quoted data.
- **Upgrading.** An install whose hidden routing session was seeded for the old contract gets a
  new hidden one, once. The old one is kept on the dashboard and stays the app's; it is just no
  longer used. If that replacement is interrupted or can't be verified, it is not tried again
  and the old routing session keeps being used (every request restates the current contract).

### The routing session's model (Luna, low reasoning)

The hidden routing session, and only it, runs on a fast, inexpensive model: `gpt-5.6-luna` from
the `openai-codex` provider at **low** reasoning. That is the Luna name the installed Hermes
catalog lists (its Codex model list and price table); no `gpt-6-luna` exists there. Your
conversations, the ones the router creates and the ones you make by hand, keep the profile's
model; the profile's default and its settings files are never changed.

- **A new routing session** is created with that model, provider and effort as its own
  per-session overrides (`session.create`'s `model`, `provider` and `reasoning_effort`). That
  create is one call with its own 30-second timeout, plus connecting; it comes before, and is not
  part of, the 90-second model setup below. If it fails, the request ends with "the routing session
  could not be created" and the gateway's error code or the error's type, never the gateway's text;
  when no answer came, whether the session was created is reported as unknown. Nothing is
  registered, retried or sent, and the next request tries again.
- **An existing routing session** (made by an earlier build) is switched once, for that session
  only. Resuming a session that isn't loaded only starts building its model in the background, and
  a model switch that names its provider doesn't wait for that build: sent too early, it is
  dropped when the build finishes. So the app first resumes the session again every 0.75 seconds
  until the gateway reports it built (its info no longer marked lazy and carrying a reasoning
  effort; a model name alone doesn't count). Then it sends `config.set` key `model` with
  `gpt-5.6-luna --provider openai-codex --reasoning low --session` to its live runtime id on the same
  connection, resumes it once more and checks that it reports that model, provider and effort. If
  it already does, nothing is sent. Waiting, switching and checking together take at most
  90 seconds (each call's own timeout included); if the session isn't built by then the request
  fails with "router model … could not be set … (not_ready)" and nothing was switched or sent.
  The switch is sent only with at least 5 seconds of that time left. Cancelling the request stops
  it at once. It never sends a prompt to make the session build, never uses the `reasoning` key,
  which writes the profile's settings when its session isn't live, and never confirms a guarded
  switch on your behalf.
- **A routing session that isn't built and isn't being built.** The gateway reports such a session
  as "idle" both when its build failed and when its build hasn't started yet (a session only just
  resumed, or one another client holds without building it), and nothing it answers tells the two
  apart. So the app never sends anything to such a session: after two "idle" reads in a row (after
  the first one) the request ends at once with "initialization_unconfirmed" and "nothing was
  switched … try again shortly". When the gateway's answer doesn't say whether the session is
  being built at all, the request ends the same way with "build_state_unknown". Rebuilding a
  session whose build failed is not supported by this app: such a session fails every routed
  request quickly until the gateway builds it again (for example after it reloads it); routing
  off is not affected.
- **Once proven**, the Phone records it for that routing session and does not switch again.
- **If it can't be set or proven** (the session isn't built within the 90 seconds, the provider
  isn't signed in on that profile, the server asks for a confirmation, the session is busy, or it
  reads back something else), the voice request fails
  before anything is routed or sent, with "router model … could not be set … your request was not
  sent". For the routing session's creation and model setup the gateway's own error text is never
  shown, only its error code (other failures elsewhere in the app are not covered by this). Once a switch was sent,
  a lost answer or readback is reported as "outcome_unknown": it may have been applied, and the
  next request reads the session back and records it without switching again if it was. Nothing falls back to another model silently, and the next request tries again
  once. If a new routing session's model turns out to be unusable when its first request runs,
  that request fails at once with the server's reason.
- Nothing here measures cost or speed; it only selects the model.

### Routing off, and opening the routed conversation

Two Phone settings (Settings → Voice routing), saved on the Phone and kept across restarts:

- **Route voice requests automatically** (on by default): on, every voice request goes through the
  router as described above. Off, the router (and its session) isn't used at all: a request goes,
  with its original transcript, to the conversation selected on the device you spoke to, the open
  conversation on the Phone or the conversation selected on the Watch (the Watch sends its
  selection with each request). The Phone speaks its own acknowledgement ("Sending to work.") and
  the reply plays as usual. With nothing selected the request is refused before it's accepted
  ("Open a conversation first"): nothing is transcribed or sent and where replies play doesn't
  change. A selection that isn't one of this app's active conversations (archived, deleted, the
  routing session or someone else's) is refused before anything is sent; there is never a fallback
  to another conversation.
- **Open the routed conversation on this phone** (off by default; only used while routing is on,
  and kept while it's off): once a routed request (from either device) has been delivered, the
  Phone shows that conversation's chat, if the app is on screen. It happens once per request, only
  for the newest one, and not if you opened something yourself after the request started. It
  never brings the app forward, and it changes neither where replies play nor what the Watch shows.

Both settings apply to requests started after the change; a request already on its way keeps
the setting it started with. Text messages always go to the conversation they're typed in.

### Where audio plays: the latest accepted voice sender

Every spoken utterance plays on the device, Phone or Watch, that **most recently submitted an
accepted voice request**. That covers the ack and the first, middle and final replies of any turn.

- A request counts as accepted as soon as the Phone admits its turn id, before transcription. An
  accepted request still moves the route when speech-to-text then finds no words in it, when the
  router rejects it, or when the routing session can't be created or set up. A recording refused
  by the silence check (see "Check, then transcribe"), a repeated turn id, or a routing-off request
  with no conversation selected is never accepted and never moves it.
- Text chat and replayed turn ids never move it.
- The target is chosen when each utterance is handed off, after its audio is fetched. An utterance
  already handed to a device finishes (or is stopped) there, and the next one goes to the newest
  target.
- For example, if you talk on the Watch and then on the Phone before the Watch turn's reply
  arrives, that reply plays on the Phone.
- The Phone's Talk bar shows the current target. The route is held only in memory on the Phone.
- Creating a conversation for a request doesn't change any of this: the request's device was
  already the target when the request was accepted.

### Later replies (opt-in, off by default)

Settings → Spoken replies → **Speak later replies (30 minutes)**, off by default and never switched
on by an update or any other setting. It is this phone's own consent: it is kept in the
device-local preferences file that backup and device transfer exclude, so a restored or
transferred install starts with it off and shows the warning again (a copy an earlier, undelivered
build kept in the backed-up settings is switched off at start and never read). The acknowledgement
and the final reply of a request play as before, whether this is on or off.

**Why it exists, and what is known.** Reading the installed Hermes gateway's source shows that it
delivers some output for a conversation as a new turn after the request's own reply has
completed: a background process or delegated task finishing, or a follow-up. The app listened only
for the reply to the request it submitted, so such later output was never spoken. That gateway
behaviour was read from source and is modelled in the app's test double of the gateway; it was
not observed on a device. It is a **candidate** cause of the report "nothing played when a
background task finished". For the report "nothing played on the Watch after a request went to a
new conversation", no defect was found in the new-conversation path (in the test double its
reply plays on the Watch); later output from that conversation is only a candidate explanation,
not reproduced, and physical verification is still pending.

- **What it speaks.** While on, after a request has been delivered and answered, the app follows
  that conversation for **30 minutes** and speaks each later assistant reply that **arrives** in
  that time as a final reply (the first/middle switches don't apply), once. The 30 minutes bound
  arrivals only: a reply that arrived in time still gets its own wait and playback after them, and
  is never cut off by the window. A replayed frame, a failed or cancelled turn and any other
  conversation's output are not spoken. Every reply that arrived ends as exactly one of: played
  (on the Phone or the Watch) or not played with the reason, on the Phone's status line.
- **Warning shown beside the switch.** It also says where it plays, that it waits only for the
  device it will play on, the real bounds, and the Stops. Hermes doesn't mark which request a later reply belongs to.
  Within those 30 minutes this can also speak a reply to something you or someone else sent to the
  same conversation from another Hermes app or the dashboard. The app doesn't call that a result of
  your request; it can't tell the difference.
- **Where and when it plays.** On the device of the **latest accepted voice request** at that
  moment, like every reply. One later reply is prepared or played at a time, in arrival order: per
  followed request one being prepared or played plus 4 waiting, and at most 8 in all (counting the
  ones being prepared or played); any more are reported as not played at once. It waits while a
  voice request is in flight (from the moment the app starts it, through transcription, routing,
  the acknowledgement and its replies), and while something records on **the device it will play
  on** (the target). It does **not** wait for the other device: a reply may play on the Watch
  while this phone records, or on this phone while the Watch records, and nothing keeps the two
  apart acoustically (a reply on one device can be picked up by the other one's microphone).
  - *Phone:* every recording (push-to-talk, the hands-free recording after the wake phrase, the
    background recording) first claims the Phone's microphone and only then opens it. A later reply
    is admitted to the Phone's speaker only while no claim exists, decided under the same lock, so
    the two can't both start. A later reply already playing is stopped by the claim, and the
    microphone opens only after it has stopped (or the recording gives up after 2 seconds); the
    reply plays again afterwards. A wake phrase that was heard holds the claim from before its
    accepted pulse, through the pause before recording and a claim waiting for an answer in Both,
    until its recording or request takes it over. The recording's claim then passes to its
    request without a gap, so a reply that waited for that recording also waits for that request's
    answer instead of starting in between.
  - *Watch:* a Watch that is recording, or still sending its request to the Phone, refuses it
    ("busy"), and a recording started while one plays stops it; the Phone tries again with a
    back-off of 1 to 8 seconds. That is neither "played" nor "failed".
  - A reply that its device reported as played to the end (the Phone player's own completion, the
    Watch's accepted "played" confirmation) is recorded as played at that moment, under the same
    lock, before the app's waiting code resumes: a recording or a newer request that comes after
    that can no longer stop it, play it again or report it as not played, and a recording still
    waits until its player has been released. One that is stopped before that signal plays again
    after the recording. A Phone wake window closes only once a later reply has been admitted to the Phone's
    speaker, and the reply waits (up to 3 seconds) until it has; a later reply on its way to the
    Watch never touches the Phone's windows or an accepted wake request.
  - These rules were checked through the core with the other side injected at each race point, and
    the Android call order by source checks; none of it was run on a phone or a Watch.
- **Bounds.** An attempt to speak a reply may only **begin within 10 minutes of its arrival**
  (once the speaker is free and the target's microphone isn't claimed); one that can't is reported
  as not played ("the speaker or microphone stayed busy"). After an attempt begins, its speech
  synthesis may take up to 2 minutes (the first attempt only) and an open wake window on the target
  up to 3 seconds to close before the audio starts, so the audio can start up to about 12 minutes
  after the arrival; one playback attempt may then take up to 5 minutes. A busy retry is a new
  attempt and must also begin within the 10 minutes. A new request takes the speaker from a playing
  reply (then it is reported as not played). If the Watch can't be reached it is reported as not
  played, never played on the Phone instead.
- **When it stops.** Any new message the app sends to that conversation (voice or text) ends the
  follow (no more arrivals), so its own reply is never spoken twice; replies that already arrived
  still play. These end every follow **and** every reply that arrived and waits or plays (each
  reported as not played): turning the option off, the **background relay's Stop** (its switch or
  its notification, app open or not), and **background listening's Stop** (its switch or its
  notification). These are not a Stop for later replies: stopping or sending a recording (the
  reply waits, or plays again afterwards), and the Watch's own playback Stop (it ends that
  playback only).
- **When it is lost.** Nothing that arrives after the 30 minutes is spoken. When the connection to
  the Hermes gateway drops, nothing re-subscribes and a reconnect does not replay: later output is
  missed from then on, while replies that had already arrived are still spoken or reported. When
  Android ends the Phone app's process, everything waiting is lost unreported. Nothing is stored to
  deliver it later. Why following ended is reported to the core only; the app doesn't show it.
- **Power.** Nothing extra runs while a reply waits. Each attempt to speak one (its synthesis, the
  first time, the wake window's close, and its handoff and playback) asks Android to keep the CPU
  awake for at most 7 minutes 5 seconds (the 2 min + 3 s + 5 min bounds and a small margin), and
  lets go as soon as the attempt ends; a busy retry is a new attempt. This is a bound on what the
  app asks for, not a measured power figure, and Android's own scheduling still applies. With the Phone app closed it works only as long as Android keeps the app
  running (the relay or background listening keep it running). The waits are timed on the
  process's monotonic clock; whether that keeps pace with the wall clock while the phone sleeps
  deeply was not checked on a device.

## Watch conversation reader

The Watch shows the app's conversations without ever calling Hermes itself:

- **Swipe left** (right to left) switches between the session browser and the open conversation.
  **Swipe right** (left to right), or Back, always sends the app to the background, from the
  home screen, the browser or a conversation; the task and what you were reading stay alive. It
  never closes the app or stops its background operation, and it doesn't wait for anything (a
  permission, the background service). Vertical touch and the bezel/crown scroll whichever list is on screen. A
  gesture a list already scrolled with never counts as a swipe.
- The browser lists the app-owned, unarchived conversations. The conversation shows your own
  messages right-aligned in blue and replies left-aligned in grey, newest at the bottom, with
  "Load older" at the top.
- New messages don't move the view while you're reading older ones. The view follows new
  messages only if the newest one was already on screen.
- **Talk: hold one second, anywhere.** A still press of one second anywhere on the main screen
  (the browser, the conversation, the home screen, over text or blank space) starts recording; a
  new one-second press stops it and sends. It fires once per press while the finger is still down,
  and lifting the finger afterwards isn't also a tap. Moving more than a tap's slop (even back
  again), scrolling, a swipe, a second finger, a bezel turn, changing screens, leaving the app or a
  shorter press does nothing. A one-line status at the bottom says what a hold does now ("Hold 1 s
  to talk", "● Recording · hold 1 s to send") or how the request is doing. Accessibility services
  get the same start/stop as an action on the screen. The recording buzzes are the recorder's, as
  before; the gesture adds none.
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
- **Listening pulse:** one 20 ms pulse (hardware feedback) when the wake-phrase recognizer is really
  ready to listen (its ready callback, not when it is merely asked to start), once per armed
  session: each time the app is shown, or listening comes back after it was turned off, paused or
  the screen went off. The next windows of a background session, a duplicate or late callback, a
  window that failed before it was ready and the debug fixture recognizer give none. It says
  listening started, not that the phrase was recognized.

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

By default it works only while that device's app is open on screen (foreground) and needs no
permission beyond the microphone. Each time the app is shown, or the screen turns back on with the
app shown, the platform `SpeechRecognizer` listens for 5 seconds. On the Watch it can also keep
listening with the app closed and the screen off, through its background operation (on by
default, see [Background operation](#background-operation)); the Phone's own wake phrase is always
foreground-only. On-device
recognition is used when available; if it reports that it lacks the wake phrases' language, the
app falls back once to the system's default recognition service, which may send audio over the
network. It never runs while recording, sending, waiting for a reply, playing
audio, or for 4 seconds after playback. A device the setting excludes doesn't listen or respond;
turning it off, leaving the app or the screen going off closes the recognizer and stops a
hands-free recording without sending it (unless the Watch's background session is listening). The recognizer and the app's recorder never use the
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
  the recording). The Watch also keeps renewing it after the recording ended, while the recording
  travels to the Phone, until the Phone has answered that request; a slow transfer doesn't lose
  it. The other device is refused: it stops listening, shows that the other device answered, and
  sends nothing.
- The Phone accepts a wake-phrase request in Both only with the claim it gave that device, once.
  A request without it, with another device's, with one that ran out, or made before the Phone
  app restarted is refused before it is accepted: nothing is transcribed or delivered and where
  replies play doesn't change.
- If the answer doesn't arrive within 2.5 seconds, the claim was made under settings the Phone has
  since changed, or a renewal is refused, the device stops without sending and asks you to say it
  again. A claim that is no longer renewed (the app closed, the link dropped) runs out after 10
  seconds. A recording on its way to the Phone gives its claim back when the transfer fails or the
  Phone refuses it, and the Watch says so; a transfer that neither arrives nor fails is given up
  after a minute plus the time its size needs at 2 kB/s. The Watch's claim messages go out one at
  a time in order, so a late message can't undo or outrun a newer one.
- The Phone counts the wake requests it has accepted, and each device notes that count when its
  recognizer starts listening. A recognizer that was already listening when a request was accepted
  can't claim that phrase any more, however late it finishes hearing it: the Phone refuses it, and
  tells the other device so it stops listening. Listening that starts afterwards is a new request,
  with no waiting time. If the first device gives its claim back without a request (its final
  result didn't start with the wake phrase after all), the other device may still answer. Requests
  are never compared or merged by their words: saying the same thing twice on purpose is two
  requests.
- Changing **Listen on** while a wake request is being recorded cancels that recording with a
  notice; nothing is sent. Other setting changes don't interrupt it.
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
  below); short pauses don't end it, and there is no time limit while you keep talking. Hold one
  second (Watch) or tap "Send now" (Phone) to send it sooner. If you don't start within 8 seconds, nothing is
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
  that stretch voiced. A fading syllable stays "speech" for at most 200 ms. To start a request,
  100 ms of it must also be unbroken, as a syllable is and a string of knocks is not. The same
  140 ms continues a request after a pause, so words said one at a time keep it open. The
  timing is the same on the Phone and the Watch and doesn't depend on how the microphone delivers
  audio.
- **The background estimate** comes from the last second of sound:
  - *Before you speak* it is the usual level of that second (its median), as long as the second is
    one level (the middle half of it within about 6 dB). It follows that level up within a third
    of a second and down slowly, so a room whose noise wanders isn't taken for speech at its
    louder moments.
  - *Once you have spoken* it holds. Speech is quiet for seconds at a time without being
    background (soft words, the end of a sentence), so nothing heard in one second may raise it.
    It rises only slowly, towards the quietest tenth of the last three seconds (the pauses and
    the gaps between words), and only to levels clearly below your voice (under about 45% of its
    typical level): a background that rose doesn't hold the request open for ever, and soft
    speech isn't absorbed.
  - Sound a little above the background (two to three times it) neither counts as your silence
    nor as speech: it pauses the count. Only background counts towards the trailing silence.
  - If it started far too high (you spoke before the buzz, or a loud moment fell into the 0.4 s),
    it drops at once to the quiet between your words.
  - A steady sound about as loud as your speech is never taken for background once you have
    spoken: a drawn-out "uhh", a held note, or a vacuum cleaner while you talk does not end your
    request, however long it lasts.
  - A steady sound that starts before you have said anything, and doesn't even waver the way a
    held voice does (under about 1 dB), is noise: it becomes the background after a second or so
    and the request counts as not started. A held "uhh" first, then speech, is kept whole.
- **What it can't do:** it measures loudness, not speech.
  - Loud changing sound (music, TV, other people talking, dishes) can start a request and keeps
    one open until it stops or you send it yourself.
  - After you have spoken, steady noise that is about as loud as your speech was (in tests, from
    about half of its level) can't be told from a held voice, so the request stays open for a tap
    rather than being cut. Quieter steady noise ends it 3–4 s after the speech.
  - Your voice has to stand clearly above the room. In tests with recorded speech mixed into
    recordings of five real rooms, no request was cut short with the speech 15 dB or more above
    the room noise, and a pause of one second between two sentences never ended a request. At
    10 dB about one in twenty-five was sent without its last words; at 6 dB about one in six
    was, and one in five wasn't heard at all. A pause of 1.5 s between sentences (with the 2 s
    setting) ended 7 of 130 requests at 15 dB and none at 20 dB. In a noisy place use
    push-to-talk, or a longer trailing silence.
  - A room whose noise swings widely (by about 5 dB or more from moment to moment), or that has
    loud events of its own (dishes, a door, someone else talking), can still be taken for a
    request now and then, and a request there can take several seconds longer to end. The
    recording check refuses most recordings that hold nothing but wandering noise, but not loud
    events. With nobody speaking, 30-second recordings of a living room, a cafeteria and a
    laundry room were never sent in these tests; of a kitchen and an office, 4 of 36 were.
  - A word has to be voiced for 140 ms: very short words with long pauses between them, spoken
    softly, may not count.
  - A single click or knock of 100 ms or less doesn't count, and neither does sparse clicking
    such as slow typing, nor a quick string of short knocks. Sounds that ring on for longer
    (dishes, a door), and a burst of noise longer than 140 ms, like a cough, can start a request,
    which speech-to-text may then find empty or mishear.

### Phone: listening with the app closed or the screen off (opt-in)

Settings → Wake phrase → **Keep listening with this app closed or the screen off**, off by
default, switched on only in the open app. While on, a notification with **Stop** stays visible,
and whenever the app is closed or the screen goes off, a background flow listens for the wake
phrase in windows of 30 seconds, one after another, with the same rules as in the app (a pause
after the phrase starts the recorder, a request in one breath is sent as text, Both is arbitrated,
routing preferences apply, and the reply plays on the Phone). It never opens the app.

- **On-device recognition only.** With the app closed the room is never streamed to a recognition
  server: it needs an on-device recognizer (Android 12+) with the wake phrase's language. Without
  one, or when the model lacks the language, it doesn't listen and says so; nothing falls back to
  the system's network recognizer. The app on screen keeps its own recognizer as before.
- **One owner of the microphone.** Opening the app hands listening back to the app at once: a
  background window closes and an unfinished background recording is dropped unsent. The relay is
  separate and keeps working whether this is on or off.
- **Started only from the open app** (Android allows the microphone in the background only then):
  a microphone foreground service, typed for the microphone only when the wake setting includes
  the Phone, the microphone is allowed, an on-device recognizer exists and the notification can be
  seen. Anything that blocks it later stops listening at once; nothing turns it back on from the
  background. Stop (switch or notification) ends listening, a recording in progress (unsent), the
  next window and every wake lock. After Android ends it, or a restart, it shows as paused until
  you switch it on again.
- **Best effort, and it costs battery.** Android's recognizer isn't made for continuous listening:
  there are short gaps between windows, a failing recognizer backs off (doubling up to a minute),
  and the phone's maker may still stop it. While it listens the CPU is kept awake window by
  window (each hold bounded by the window), so with the screen off it uses noticeably more
  battery; this wasn't measured.
- **Pulses on the Phone:** one 30 ms pulse when a wake phrase is accepted (a final match that
  passed every check and, in Both, only on the device the Phone admitted), in the app and in the
  background, through the system vibrator (Do Not Disturb and vibration settings apply). With the
  app closed the recording start and end cues are vibrations too.

## Background operation

The Phone's relay is off by default and switched on only by you, in the Phone app. The Watch's
background operation is on by default: opening the Watch app starts it, and it has no switch in
the app. Neither is a voice setting: where the wake phrase listens, the phrases and the trailing
silence stay Phone settings (background operation never turns the Watch's wake phrase on), and
the Phone can't start anything on the Watch.

- **Phone: Settings → Background → "Keep relaying for the Watch when this app is closed".** While
  on, Watch requests are transcribed, routed, delivered and answered with the Phone app closed and
  its screen off, and a reply due on the Phone (it sent the latest voice request) still plays. It
  runs as a foreground service of the *connected device* and *media playback* types, with an
  ongoing "Relaying …" notification that has **Stop**. The relay itself never listens or records:
  the Phone's Talk button works only with the app open, and its wake phrase too unless
  [background listening](#phone-listening-with-the-app-closed-or-the-screen-off-opt-in) is on. The
  relay is the same one the open app uses (one dashboard client, one orchestrator, the same saved data).
- **Watch: on whenever you open the app.** Each time you open the Watch app (launch it, or bring
  it back to the front), it starts its background session from the open screen, once; a session
  that is already running is kept as it is (never restarted, doubled or stopped by opening the
  app, a swipe or Back). There is no background status or start/stop control in the app; its
  ongoing notification says what it really does, and has **Stop**. The session always lets replies play with the app closed. It also keeps listening for the
  wake phrase with the app closed and the screen off, but only when all of these hold: the Phone's
  wake setting includes the Watch, the microphone is allowed, the Watch has a speech recognizer,
  and the session's notification can be shown (notifications allowed, for the app and for its
  channel). Then it runs one recognizer window after another (30 seconds each, a third of a second
  apart, backing off up to a minute when the recognizer fails), and records, sends and plays
  exactly as on screen, including the Both claim. It runs as a foreground service of the
  *microphone* and *media playback* types with an ongoing notification and **Stop**. Otherwise it
  runs for *media playback* only and says why ("replies only", with the reason). The Watch's
  screen isn't kept on for it; only a recording keeps it on, as before.
- **The microphone is armed only from the open app.** Android doesn't let an app start using the
  microphone from the background, and the Watch doesn't try. It arms only while its app is on
  screen, after that show's settings check has finished, and only once the service has really
  entered the foreground with the microphone type (asking Android to start it is not enough; until
  then the notification says "Starting the wake phrase"). A check that finishes after you left is
  ignored. Opening the app starts the session at once for replies only ("Not listening: Phone
  settings check not complete" in its notification), and listening starts when the check and the
  service are both done. If you leave first (a swipe right right after opening), it keeps
  playing replies only ("Open the app to listen for the wake phrase") and listens after your
  next visit to the app. A
  session that was already listening keeps listening while a new visit's check runs. Anything that stops it from listening (the Phone's setting no longer including the
  Watch, the microphone or notification permission taken away, notifications switched off, no
  recognizer found) disarms it at once and the session keeps only playback. When that changes
  back while the app is closed, the Watch shows why it replies only and listens again only once
  you open it. A missing recognizer is looked for again only when you open the app.
- **Stop** (the notification's) holds until you open the app again, and can be repeated: nothing
  listens or records any more, and nothing in the app (a resume, a swipe, a permission answer)
  starts it again before your next open. Closing the app from the system's app management (force
  stop) ends it too. With the app closed it also ends what was under way: a recording is dropped
  unsent (a Both claim is given back), a recording not yet handed to the Phone is withdrawn, the
  reply playing stops and the rest of that turn isn't played; on the Phone, turns in flight are
  stopped and the Watch shows "Stopped on the phone". With the app open, Stop only ends the
  background part. Withdrawing a recording is best effort once sending has begun: the Phone may
  already have received it and started on it (transcription, routing, possibly creating a new
  conversation). Even then, while the Watch remains the latest accepted voice sender, its
  transcript isn't delivered after a Stop and nothing is confirmed as played: the stopped Watch
  refuses to play the acknowledgement, and a request is delivered only after its acknowledgement
  has played. If a newer request from the Phone is accepted first, replies move to the Phone, the
  older request's acknowledgement can play there, and that request can then be delivered.
- **Nothing restarts by itself.** A session the system ended (force stop, the system's own Stop,
  a revoked permission, a reboot, an app update) shows as paused. The Phone's relay starts again
  when you next open the Phone app, the Watch's session when you next open the Watch app (not
  again within the same visit). There is no boot
  start, battery-optimization exemption, assistant role, accessibility service, screen wake or
  full-screen notification.
- **Notifications and the microphone.** The Watch asks for the microphone and then (Android 13+)
  for notifications at most once each time you open it, never because of a swipe; a refusal stands
  until your next open (or until you hold to talk, which asks for the microphone). The Phone asks
  for notifications when you switch its relay on (only the first time). Either way the session
  runs, but a hidden notification means no Stop outside the app. So the Watch then never listens in
  the background; it plays replies only, and the system's app management (force stop) ends it.
  The Phone says when its notification is hidden (a line under the switch), and its switch stops
  the relay. The check is the system's
  current answer each time (the permission, the app's notifications and the session's channel),
  not a remembered one. The opt-ins and the "asked" flag belong to this install: backup and
  device transfer leave them out, so a restored or transferred install starts with the relay off. An
  update from a build that kept the relay switch in the backed-up settings also starts with the
  Phone's relay off.
- **Power.** While the Watch listens with its app closed it keeps the CPU awake with partial wake
  locks: one for each open window (until its deadline), one from the wake phrase to the recorder
  (the microphone handoff and, in Both, the Phone's answer), and one for each gap between windows
  (the pause, a back-off or retry of up to a minute, and a reachability check of at most
  5 seconds). A new lock is taken before the one it replaces is let go. So the CPU and the speech
  recognizer keep running: expect a clearly shorter battery life while it is on. While it waits
  to retry (recognizer failing, Phone unreachable, microphone muted) it says "retrying", not
  "listening". Each retry's lock is short and time-limited, but retries have no overall limit. A
  Phone that stays unreachable, a muted microphone or a recognizer that keeps failing can
  therefore keep the Watch's CPU awake indefinitely, until you tap Stop or the cause goes away.
  That can drain the battery, and the impact hasn't been measured. Waiting
  for the Phone's answer after a request is sent holds nothing; the Phone's messages wake the
  Watch. Every wake lock has a reason and a time limit (a window, a handoff, a gap, a recording, a
  transfer, a turn, an utterance) and is released when that ends. No battery figure has been
  measured, and whether a real watch in deep sleep keeps up hasn't been tried.
- **Audio.** Replies take transient audio focus (other audio ducks) and respect the volume and Do
  Not Disturb. If focus isn't granted, that utterance isn't played. On Android 15 this happens to
  Phone replies that become due after you close the Phone app while the relay is off. What
  follows depends on which utterance it is:
  - If it's the **acknowledgement**, the request is **not delivered**: a transcript goes to its
    conversation only after its acknowledgement has played. A conversation the router asked for
    may already have been created.
  - If it's the **final reply**, the request was already delivered and shows as delivered with
    its reply not played.

  Keep the relay on to have both play with the app closed.

Without these switches, what worked before still works while the apps are open. Android may also
keep a closed app running for a while and let it relay or play, but nothing promises that.

## Text chat and history

History shows user and assistant messages (hidden and tool rows are dropped), newest page first,
with "load older". Text messages use the same ownership checks as voice. A reply appears after the
turn completes. Replies to text chat aren't spoken.

On the Phone a conversation opens at its newest message, right above the composer. Sending,
or tapping the composer, goes back to the newest message; a reply (or a reply that grows) is
followed while you're at the newest message and leaves your place alone while you read older
ones, as does loading older messages. The keyboard lifts the composer by exactly what the Talk
bar and tabs don't already cover, and the newest message stays above it while the keyboard opens
or closes. Closing the keyboard keeps the draft; switching conversations keeps each one's draft.

## Settings and UI

- **Phone settings:** dashboard URL and profile, sign in/out (with confirmation), **Appearance**
  (Dark by default, Light, or System), **Play first response** (off by default), **Play middle
  responses** (off by default), **Speak later replies** (off by default; see
  [Later replies](#later-replies-opt-in-off-by-default)), and **Voice routing** (automatic routing on by default; opening
  the routed conversation off by default; see [Routing off](#routing-off-and-opening-the-routed-conversation)).
  There's deliberately no setting for the ack or the final reply.
  Settings also shows whether the Watch app is reachable.
- **Voice settings** (edited on the Phone, synced to the Watch as the `/hv/v1/settings` data
  item): where the wake phrase listens (Off by default), the shared wake phrases, the hands-free
  trailing silence (2 s by default), and Watch haptics. There is no Watch recording time limit.
- **Background** (each device's own, not synced): the Phone's relay switch and background-listening
  switch (off by default, see
  [Phone: listening with the app closed](#phone-listening-with-the-app-closed-or-the-screen-off-opt-in));
  the Watch's background operation has no switch: it is on whenever its app is opened (see
  [Background operation](#background-operation)).
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
builds ignore them. For the background session, which must not be brought to the screen, debuggable
builds also register broadcast receivers (never registered otherwise): on the Watch
`-a com.rumi.hermesvoice.QA_WATCH --es hv_qa_wake_heard <text> [--es hv_qa_wake_delay_ms <ms>]`
is a simulated recognizer result for the window open at that moment (again never recognition), and
`--es hv_qa_background stop` runs the notification's Stop; on the Phone
`-a com.rumi.hermesvoice.QA_PHONE --es hv_qa_relay stop` does the same for the relay.

## Validation

Only the following has been run:

- **Core unit tests:** `scripts/core-jvm-check.sh` compiles `:core` and runs **532 JUnit tests**, all
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
  accepts the same audio), its behaviour in unsteady backgrounds (synthetic, 40 seeds per cell:
  a background drifting by 1, 2, 3 and 4.5 dB with nobody speaking is recorded as a request in 0,
  0, 0 and 10 of 40 half-minutes and uploaded in 0, 0, 0 and 9–10; one whose level jumps every
  100 ms by 2–6 dB in 0, 0, 8 and 33; a request in a background drifting by up to 4.5 dB is never cut and
  ends on average 2.0–3.1 s after the speech, at worst 10 s; six seconds of drifting noise alone pass the
  recording check in 0, 2, 4 and 24 of 40; strings of short knocks never start a request; steady noise of 2.5–30 times the background that starts
  with nobody speaking ends as "no request"; a held voice first, then speech, is kept whole; noise
  after speech ends the request only when clearly quieter than the speech; words of 100–300 ms
  said one at a time keep a request open at both the 0.5 s and the 2 s setting; soft speech 3.2–5
  times the background is never cut in a minute), wake arbitration in Both (the claim lease, its renewal, expiry and
  stale messages; two simulated recognizers hearing the same phrase in either order, by partial or
  final result, phrase-only and in one breath; unanswered, refused and lost claims; push-to-talk
  and deliberate repeats; the Watch link; a Watch recording that reaches the Phone 3 to 90 s after
  it was recorded is accepted, and one whose transfer fails, is refused, outlasts its limit or
  meets a restarted Phone gives its claim back with a notice; a recognizer that reports the phrase
  0.5 s to an hour after the other device's request was accepted never delivers it again, told or
  not told by the Phone, and the next wake works at once; claim messages arrive in order behind a
  slow first send; changing the wake location during a wake recording cancels it unsent), the voice settings (0.5–10 s validation, the
  wake-location modes, durable migration from the old Watch switch, strict Watch-side validation,
  stale snapshots), the per-device wake flow both apps delegate to (which device listens for each
  mode, turning a device off mid-window or mid-recording, the silence snapshot, same-breath
  requests), routing to a new conversation (contract v2 parsing and rejections, an empty list,
  reuse on the next request, alias collisions, refused and unverifiable creations, the same turn
  repeated or run concurrently, and restarts after delivery, before delivery and with an
  unresolved creation; the Phone-worded create ack; storage whose writes fail at each step, with
  restarts that see only what was confirmed saved; unreadable saved data; alias and limit races
  between manual and router creation, rename and unarchive; invisible characters and quoted
  prompt data; a destination archived while the ack plays; the one-time routing-session
  replacement and its interruption), the recording loop of both recorders (Phone and Watch read sizes), the recording input
  check, haptic timing, gesture arbitration, bezel scrolling, the background session (off on fresh
  and upgraded installs, started and its microphone armed only from the visible app, a refused
  microphone, a Stop that is final and can be repeated, "paused" after the system ended it and no
  restart by itself, callbacks of an older session ignored), listening with the app hidden (200
  consecutive windows on a simulated clock, back-off, no recognizer, cooldown and an unreachable
  Phone, a late result of an earlier window, the Both claim, Stop while listening or recording,
  settings that exclude and include the device, showing and hiding the screen without a second
  window), the Watch's session composed as its runtime composes it (a settings check that
  finishes after the app was hidden never asks for the microphone, whether the platform would
  grant or refuse it; normal reopen and visible Stop then Start listen again; a session never says
  it listens when it can't; no microphone without a visible notification, with notifications
  allowed later arming only at a visible show; a Start before the show's settings check, or
  leaving before it finishes, plays replies only and never says it listens, while a session
  already listening keeps its loop; no recognizer narrows it to playback; a hold covers
  every step from window to handoff to recorder and every gap, taken before the previous one is
  let go and released once), the device-local opt-ins (a restored or updated install never
  starts the relay from a backed-up copy; the backup rules exclude the files the apps use),
  time-limited wake locks, a Watch turn stopped on the Phone, and source checks of the Android
  wiring (permissions and service types, where sessions may start, Stop paths, audio focus).
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
  - *Held sounds and noise bursts:* a 75-second request with a 2.5-second held "uhh" at speech
    level in the middle (Watch and Phone), and one with a 4-second steady noise burst at speech
    level (Watch), were recorded whole and ended 2.0 s after the speech; an early build cut
    such requests and sent them truncated. Both were repeated on the Watch with a later build, before
    background operation (synthesized voice), with the same result; the rest of this item is from an earlier build. Speech that was already under way when the
    recorder started was detected and sent complete. Push-to-talk speech about 4 dB above steady
    noise was accepted and answered on both devices; the same noise alone was refused before
    speech-to-text on both. Tapping Talk while the Phone's real recognizer was listening released
    it first and the recording had normal audio.
  - *Both, with simulated recognizers:* the recognizers were replaced by a debug fixture that
    reports a result on each device (this tests arbitration, not recognition; the Watch emulator
    has no recognizer). With the same one-breath request on both devices, in either order, one
    device's request was accepted and answered there, and the other showed that the other device
    answered and sent nothing. With the phrase alone, only the winner recorded, on the real
    microphone, renewing its claim throughout (25 renewals over a 77-second request), and the
    other never started its recorder. Watch push-to-talk in Both needed no claim. One earlier
    build let the Watch give its claim back as its recorder started; the Phone then accepted only
    one of the two recordings, and that defect is fixed and covered by a test.
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
  - *Saved data from an earlier build, and the create ack (an earlier build):* the build was installed over a Phone whose saved data for
    a test dashboard had been created by an earlier build (one conversation and a routing session
    seeded with the first routing instructions). The first spoken request replaced the routing
    session once: the saved data then held the old one marked retired and a new one, and the
    dashboard still held the old session with its history. In that same request the real routing
    model asked for a new conversation; the ack the Phone composed and played named the title and
    alias that were actually registered ("Creating a new conversation called Car Maintenance,
    alias car-maintenance, and sending this there."), the conversation's first message on the
    dashboard was the transcript, and the next request on that topic reused it. A Watch
    push-to-talk request created another conversation with the composed ack played and
    acknowledged on the Watch, a hands-free Watch follow-up (recorder started by the simulated
    recognizer, ending 2.0 s after the speech) reused it, and the Watch browser listed and opened
    the new conversations. Archiving the destination on the Phone while its ack was playing, and
    in another run while the request was still being routed, ended both requests as not
    delivered with nothing sent to the dashboard. One Both run with simulated recognizers again
    gave one winner. The Korean ack wording wasn't heard: the test dashboard's speech-to-text
    returned Latin text for the Korean recordings, so the English wording was used. Failed and
    unreadable storage, restarts between steps, alias races and the interrupted or unverifiable
    replacement of the routing session were exercised only in the unit tests.
  - *Room recordings and recorded human speech (a build before background operation):* recordings of real rooms
    (DEMAND, CC BY 4.0) and of people reading aloud (Mini LibriSpeech, CC BY 4.0), mixed offline,
    were played into the microphones. With nobody speaking after the buzz (recorder started by
    the simulated recognizer), 40 seconds of a living room, a cafeteria and an office each ended
    as "Didn't hear a request" on the Watch with nothing sent, as did twelve further 20-second
    stretches (three per room, kitchen included) and the office on the Phone. **The 40-second
    kitchen recording, which has dishes clattering, was still recorded as a request for about
    37 seconds on both devices, sent, transcribed into a few words and answered**, as on the
    build before; loud, irregular household sounds are not told apart from speech. About a
    minute of read speech 15 dB above the kitchen (Phone), with pauses between sentences, and a
    ten-second sentence above the living room (Watch), were recorded whole, ended 2.0 and 1.9 s
    after the last word while the room kept playing, and were transcribed completely. With
    push-to-talk, a small voice 6 and 10 dB above the cafeteria was accepted, transcribed and
    answered on both devices. Eight seconds of the cafeteria alone **passed the recording check
    on both devices**; speech-to-text then returned nothing and the request ended as "no
    speech" with nothing delivered.
  - *Words said one at a time (a build before background operation):* eight synthesized words with 450 ms between
    them, after a wake on the Watch, were recorded whole and ended 1.96 s after the last word.
    Ten seconds of synthetic 160 ms syllables 240 ms apart ended 2.0 s after the last one; the
    build before ended that recording 0.84 s after it.
  - *Both and the wake location (a build before background operation, simulated recognizers, so this tests the flow
    and not recognition):* with a debug delay holding a finished Watch recording back for 25
    seconds, the Watch kept renewing its claim every 3 seconds, a wake on the Phone during the
    wait was refused as "the other device is answering", and the recording was then accepted and
    answered on the Watch. With both recognizers reporting one wake and the Watch's result
    arriving 4.5 s late, only the Phone recorded and the Watch showed that the other device
    answered. With the Watch's result arriving after the Phone's short request had been
    accepted, the Watch's window had already closed and the late result started nothing. Each
    run produced one request. Changing the wake location on the Phone while the Watch was
    recording a wake request cancelled it unsent, and the Watch showed "Wake settings changed.
    Say it again".
  - *Routing (a build before background operation):* a spoken Phone request created a conversation with the composed
    ack naming the registered title and alias, and the next request on that topic reused it.
  - *Background operation, current build (installed on both emulators).* Notifications were
    allowed through the system's own screens (the Watch's prompt; on the Phone, which had already
    asked once, its notification settings page), and the test dashboard was signed in by hand on
    the Phone. The Watch emulator has no speech recognizer, so every Watch wake below came from
    the simulated recognizer (it tests the flow after a wake, never recognition); the microphone
    audio, speech-to-text, routing, the model's replies and playback were real. The speech was
    synthesized or a published recording played through the audio cable. Screen, app and service
    states below were read from the emulators while the requests ran (the system's display and
    activity state, its record of app activity and screen changes, its foreground-service list,
    this app's notifications and its wake locks); the reply-target label and the chat were read
    from the app's screen.
    - Notifications: each app's session showed an ongoing "Hermes Voice" notification with a
      **Stop** action ("Relaying voice requests and replies for your Watch" on the Phone,
      "Listening for the wake phrase. Replies play here" on the Watch). Tapping Stop in the
      notification ended the session and removed the notification on each device, and starting
      again from the open app worked. With the real recognizer selected, the Watch's session
      showed "replies only (no recognizer)" and ran as *media playback* only, without the
      microphone type.
    - Phone push-to-talk: requests were transcribed, routed (to existing conversations, and to
      one the router created, with the composed ack), and the ack and the final reply played to
      completion. For one request the conversation was read back in full afterwards: its new
      message was the transcript, word for word.
    - Watch with the Phone relay on (*connected device + media playback*) and the Phone app in
      the background with its screen off: Watch push-to-talk requests were relayed, and the ack
      and the final reply played on the Watch, each confirmed as played. With the Watch's
      session on (*microphone + media playback*) and the Watch app in the background with its
      screen off, four wake requests in two runs, each a recording of read speech with
      room sound, were recorded through the end of the speech, sent, answered and played on the
      Watch in the same way. In each run the Watch stayed in the background for more than three
      minutes, opening one 30-second window after another; the device's wake-lock records show
      the listen lock taken for each window and the recording and playback locks during each
      request. These are emulator samples and records, not a measure of deep sleep or battery.
      In an earlier run on this build, the app's logs showed listening carry on after the Watch
      app was reopened or recreated.
    - Where replies play (the Phone's label): after Watch requests it said the Watch; browsing
      conversations on the Watch, a typed Phone message and a silent Phone recording (refused
      before speech-to-text) left it there; a spoken Phone request moved it to the Phone, and a
      Watch wake with nothing said (digital silence on the cable, discarded as "Didn't hear a
      request") didn't move it back.
    - Stop: the Watch notification's Stop during a recording discarded it unsent; during the ack
      of a pending request it stopped the ack, and the request wasn't passed to its conversation
      (it had already been transcribed and routed); during the final reply it stopped playback
      (the request had been delivered after its ack). The Phone notification's Stop during a
      Watch request, with the Phone app in the background, stopped that request before it was
      passed to its conversation, stopped the ack on the Watch and told the Watch the request
      was stopped. Afterwards no service, notification or wake lock of the app remained, and no
      crash or ANR was recorded.
    - **Not run:** recognition of any kind on the Watch or in Korean; live speech or a physical
      microphone; showing the Watch's "checking Phone settings" state at runtime (the settings
      check finished before a tap could land; unit tests cover it); a Watch that can't be
      reached (it needs the link cut); rotation; and anything on physical devices, including
      deep sleep, battery, Doze and manufacturer power rules.
  - *Background operation, on the previous build, before the Watch waited for its settings
    check (bounded).* The emulators'
    notification permission is undecided (the prompt was dismissed, never answered), so the
    Watch's new rule applied throughout. Its Start ran the session for *media playback* only,
    showing "replies only. Allow notifications, then open this app to listen" and "Notification
    hidden. Tap here to stop". With the app closed nothing listened: no window opened, no listen
    wake lock was held, and a simulated wake result started nothing. With the real recognizer
    selected it showed "replies only (no recognizer)", still without the microphone type. On
    screen, push-to-talk recorded and sent a request, a simulated wake started a hands-free
    recording, and leaving the app cancelled that recording unsent. The in-app Stop ended the
    session. On the Phone, the relay was off after the update (its old copy in the backed-up
    settings was dropped), and switching it on ran it as before, saying its notification is
    hidden. The Phone's dashboard session had expired and wasn't signed in again, so no request
    was transcribed or answered and no reply played. **Not run on that build:** listening,
    recording and sending with the Watch app closed (they need notifications allowed), the
    notification and its Stop, hidden playback, anything needing the dashboard, recognition, and
    physical devices.
  - *Background operation, on its first build, two builds before the current one (these runs
    don't cover the current build, whose session code changed; the Watch emulator has no speech recognizer, so
    every Watch wake below came from the simulated recognizer: it tests the flow after a wake,
    never recognition).* Both switches started only from the open apps: the services ran as
    *microphone + media playback* (Watch) and *connected device + media playback* (Phone). With
    both apps closed and both screens off (the Watch dozing or asleep) for 3 minutes on that build,
    and for 5 and 20 minutes on two development builds before it with the same session code, the Watch opened one
    30-second window after another (41 in the 20 minutes, each ending on its own) and then recorded
    a request, sent it, and played the ack and the reply, each acknowledged as played; the Phone
    relayed it with its app closed. A recording carried on through Home, the
    activity being recreated and the screen going off, and was sent once. Reopening the Watch app,
    and recreating the Phone's, during a turn left one upload and one delivery. In Both with both
    apps closed one request was delivered; with the Phone open and the Watch closed hearing the
    same phrase, the Phone answered and the Watch showed that it had; a Watch result reported
    6 seconds late was ignored. Stop in the open Watch app ended listening; Stop with the Watch
    closed (through the debug trigger that runs the notification's Stop, see below) dropped a
    recording in progress unsent, withdrew one waiting to be sent, and stopped the reply being
    played (the Phone reported the turn not delivered); the Phone relay's Stop during a turn
    ended it and the Watch showed that it had stopped. The Phone's setting excluding the Watch
    dropped the microphone from the Watch's service at once; including it again while the Watch
    app was closed left it without, and opening the app restored it. A Phone push-to-talk request
    followed by Home and screen off was answered on the Phone speaker, with audio focus taken and
    given back; with the relay off (on the development build before it), the final reply was not played (focus
    refused) and the turn said so; its acknowledgement had played before the app was left. Without a session, leaving the Watch app still cancelled a hands-free recording, a
    word-paced request ended 1.96 s after the last word, and 20 seconds of kitchen and office room
    recordings after a wake sent nothing; with a session and the app closed, cafeteria and living
    room recordings sent nothing either. With the real recognizer selected, the Watch said the
    wake phrase is unavailable and its session showed "replies only (no recognizer)". After each
    reinstall both switches showed "paused"; the Phone's relay resumed when its app was opened,
    the Watch's only after "Tap to start". **Not run:** the notification itself and its Stop
    button (the notification permission prompt was left unanswered; the service's notification
    and its one action were checked through the system's service list), recognition with the
    screen off, rotation (auto-rotate is off on the Phone emulator), Doze, battery drain, and any
    physical device.
  - *Earlier builds:* Watch reader, gestures, bezel scrolling and haptics, Watch playback with
    `played` ACKs, and playback switching between devices.

- **UI instrumentation tests (build 0.1.2-dev, emulators only).** `phone/src/androidTest` and
  `watch/src/androidTest` render the production composables (the Phone's chat and frame with its
  Talk bar and tabs, its Settings screen and view model; the Watch's main screen with its swipe
  handling, browser and conversation) in a plain test activity of a separately installed fixture
  build (`-Phv.uiFixture=true`: application id `com.rumi.hermesvoice.uifixture`; the app itself is
  never built with it). Callbacks are counters: no Hermes, audio, recognizer or network. All passed:
  - *Phone chat (11):* opens at the newest message above the composer; follows replies, a reply
    growing past the screen height and the user's own send; keeps the place being read through
    replies and "Load older" (also after an accessibility scroll); a new conversation opens at its
    newest message; an empty one keeps its composer. Keyboard: the emulator's **real** keyboard
    (with a hardware keyboard attached it is only a 63-pixel strip, lower than the Talk bar, so
    nothing needs lifting) and a **simulated** full keyboard (600 and 1007 pixels, delivered as
    window insets through the platform's insets path): in each case the composer sat 8 dp (its own
    padding) above the keyboard or the Talk bar, whichever was higher, the newest message right
    above it, and closing the keyboard kept the draft. Tapping the composer while reading older
    messages returned to the newest one. On the code before this change, 10 of these 11 failed.
  - *Phone routing settings:* switched in the real Settings screen, then read back by a second
    instrumentation run in a new process (defaults on/off, the second switch disabled but kept while
    routing is off, then restored).
  - *Watch hold (16, on the test clock):* 999 ms does nothing and is still the chip's tap; at
    1000 ms it fires once with the finger down, never again in that press, and the release selects
    nothing; a new press stops; jitter within the touch slop still holds; moving past the slop and
    back (both directions), a list scroll, a swipe, a second finger, a cancelled pointer, a bezel
    turn, the screen changing, the activity pausing and resuming within the second, or the gesture
    being disabled does nothing; holds over a message and over blank space work in the
    conversation; the accessibility action starts and stops. On the code before this change, the
    5 tests that expect a toggle failed.
  - **Not run:** a full-height soft keyboard (the emulator shows only its compact strip), rotation,
    TalkBack itself, the hold on a physical watch or with a real bezel, and the routing switches
    or the routed-conversation opening with a real dashboard (those are covered by the core tests
    through the real orchestrator and Watch intake with fakes).

- **0.1.3 additions (host tests and a compile check only; nothing was run on an emulator or a
  device for this build).** The gateway's later turns are modelled in the fake from the installed
  gateway's source, not observed. Through the production wiring against the fake dashboard (real
  WebSocket, gateway connection, orchestrator, Watch intake, Watch playback sink and its
  confirmations): a later reply arriving after a Watch request ended is spoken on the Watch,
  also for a newly created conversation and for a Phone request on the Phone; each distinct later
  reply once, never a replayed frame, a failed turn, another conversation's output or one after
  the window; the app's next message to the conversation ends the follow; a later reply plays on
  the latest sender at its handoff, waits for a request that is speaking and is stopped by a newer
  one; an unreachable Watch is reported, never replaced by the Phone. Before the change 7 of those
  failed (the positive control and the guards passed). The wake cues through the shared wake flow:
  the Watch's listening pulse once per armed session and never for duplicates, stale or failed
  windows or background rollovers; the Phone's accepted-phrase pulse once, never for partials,
  ambient mentions, stale windows, an excluded or busy phone or a refused claim in Both. The Phone's
  background listening through the real wake flow, presence, session and wake locks with the
  platform faked: off by default, started only on screen, never listening on screen, taking over
  when hidden in long windows with bounded holds only, handing back (unsent) when shown, Stop
  ending everything, each blocker keeping it off, a blocker while hidden disarming with no re-arm
  from the background, back-off up to a minute, no recognizer stopping the loop, a match pulsing
  once. **Not run:** any of it on a device or emulator: heard later replies, real Watch playback of
  them, the Phone's background or screen-off recognition (on-device recognizer, Korean model,
  battery, gaps, vendor limits), the haptic pulses' feel, and the new Settings switch.

- **0.1.4 corrections (host tests and a remote compile only; nothing run on an emulator or a
  device).** Later replies became an opt-in, off by default: with nothing switched on, a later reply
  is neither followed, synthesized nor played, and migration or saving any other setting never
  turns it on (on the 0.1.3 code these failed). A Watch that is recording refuses a later reply as
  busy, and it is delivered after the recording, once, on the latest sender at that moment (on 0.1.3
  it was reported as failed and dropped). It waits while the Phone records, stops and comes back
  when a recording starts during it, is never spoken over an open Phone wake window, gives up
  after its bound and reports it, and a busy answer from another Watch node doesn't count.
  Switching it off, or a Stop, ends following; a dropped gateway connection is reported. The
  Android side (which recordings and windows are reported, the Watch's refusal, the Stops) is
  checked by source gates and by compiling, not run. (An independent review of 0.1.4 then found
  that the Phone opened its microphone before stopping a later reply and could admit one just as a
  recording started, that the 30-minute window also cut off replies that had already arrived, that
  a reply could play twice, that a later reply could cancel an accepted wake request or play in its
  own request's transcription gap, and that the opt-in was restored from a backup: see 0.1.5.)

- **0.1.5 corrections (host tests and a remote compile only; nothing run on an emulator or a
  device).** One ownership seam for the Phone's microphone and the later-reply speaker: each
  recording path claims the microphone before opening it, and opens it only after a later reply it
  stopped has stopped; a later reply is admitted only while no claim exists, under the same lock;
  an accepted wake phrase holds the claim from before its pulse to its recording or request, and
  the claim passes to the request without a gap. With a recording injected at each race point
  (during synthesis, right after admission, as playback starts, mid-utterance, as it finishes),
  nothing is audible while the microphone is open and the reply plays once, afterwards; on the
  0.1.4 code the same tests failed (overlap, a reply played twice). The 30 minutes bound arrivals
  only: replies that arrived are queued (bounded), each reported exactly once, never cut by the
  window (on 0.1.4 they were dropped or cut unreported). A later reply to the Watch never signals
  the Phone's speaker, and a reply that waited for a recording waits through that request's
  transcription and answer. The opt-in is device-local and a restored copy is never consent. The
  Android call order is checked by source gates that failed on the 0.1.4 sources; nothing was run
  on a phone or Watch. (An independent review of 0.1.5 then found that a reply heard to the end
  could still be played again, or reported as not played, when a recording, a newer request or a
  Stop landed between the player's completion and the app's waiting code resuming; that a waiter
  started on an already cancelled scope kept the microphone claimed; that the text beside the
  switch promised waiting for the other device too; and that some stated bounds were tighter than
  the code: see 0.1.6.)

- **0.1.6 corrections (host tests and a remote compile only; nothing run on an emulator or a
  device).** The end of a later reply is now recorded from the device's own signal (the Phone
  player's completion callback, the Watch's accepted "played" confirmation), under the ownership
  lock, before the waiting code resumes. With the waiting code's thread held busy at that moment
  and a recording, a newer request or a Stop arriving then, a reply confirmed by the Watch sink is
  reported played once (on the 0.1.5 code: "not played"), and one confirmed by a sink shaped like
  the Phone's player is heard once (on 0.1.5: twice); a stale or failed confirmation never counts,
  and a recording still waits for the player's release. A microphone claim is given back when its
  waiter's scope was already cancelled or the opening throws. The text beside the switch now says
  that a reply waits only for the device it will play on, and the bounds and the CPU hold
  (7 minutes 5 seconds per attempt) are stated as the code has them.

- **0.1.7 additions (host tests and a remote compile only; nothing run on an emulator or a
  device).** The routing session's own model (see above), set and read back through the gateway
  calls in a test double that follows the gateway's source: a new routing session is created with
  it, an existing one is switched once for that session only, recipients and hand-made
  conversations keep the profile's model, nothing writes the profile's settings, and a switch that
  fails, needs confirmation or is deferred stops the request visibly (on the 0.1.6 code none of this
  happened). For the unexplained new-conversation report: a turn the gateway ends before it starts
  now ends at once with the reason (on 0.1.6 it waited 15 minutes); new and existing
  conversations, Phone and Watch, every turn-start order, automatic opening on and off, another
  connection after the create, and an active-session limit were all tried and played (or reported)
  correctly on 0.1.6 as well, so they don't explain it. Metadata-only stage diagnostics were added.

- **0.1.8 additions (host tests and a remote compile only; nothing run on an emulator or a
  device).** On 0.1.7 the switch of an existing routing session failed on its first use whenever
  the session wasn't loaded yet, even one already set to Luna, because the switch was sent before
  the session's model was built (an independent review found it). The test double now
  follows the gateway's cold start: the lazy first answer, the default-model answers while the
  model builds, a build from the session's stored settings, and a switch that is only noted while
  nothing is built. With it, an old router or one already on Luna is switched (or left) and proven
  on its first request, including a full voice request that is then routed on Luna with the
  transcript sent once; a session that never builds fails as not ready within the time allowed,
  having sent no switch; a cancelled request sends none either; and refusals, confirmations,
  deferrals and a wrong model or effort read back still stop the request (on the 0.1.7 code the
  first-use cases failed). A dropped connection after a conversation is created or during the
  switch now comes from the test double closing the connection on purpose, and ends the request
  within seconds.

- **0.1.9 additions (host tests and a remote compile only; nothing run on an emulator or a
  device; the rebuild described here was removed again in 0.1.10).** On 0.1.8 a routing session whose model build had failed was waited for the whole
  90 seconds on every request and never rebuilt (an independent review found it from the gateway's
  source). The test double now also follows a failed build, as read from the gateway's source and
  not seen on a live server: a later answer is "idle" and not built, only a switch rebuilds it, and
  the rebuild keeps the stored effort. With it, a session whose build failed, whether already loaded
  or failing after starting, is rebuilt by one switch and proven on Luna low, including a full first
  voice request that creates a new conversation and plays its acknowledgement and reply (later
  replies stay off); a rebuild that fails again ends the request as soon as the gateway says so and
  frees the app's conversation actions; a gateway answer without a build status, or with any other, gets no
  switch; and a switch without enough time left is not sent. A switch whose answer or readback is
  lost is reported as possibly applied, and the next request records it without switching again.
  A cancelled request records nothing at any stage. On the 0.1.8 code these cases waited out the
  time or were reported as "nothing was switched".

- **0.1.10 changes (host tests and a remote compile only; nothing run on an emulator or a
  device).** The 0.1.9 rebuild sent a switch to a session after two "idle" reads, but by the
  gateway's source an "idle" session may simply not have started its build yet (a delayed build
  timer, or a session another client holds); a test of exactly that showed a healthy session
  getting a switch, and one that was never built being reported as "build failed again". 0.1.10
  sends nothing to such a session: in the same test cases (a late timer, a never-started session, a
  failed build, a gateway without a status) no switch is sent and the request ends within about
  two seconds as "initialization_unconfirmed" or "build_state_unknown"; the next request after the
  build has run elsewhere switches it normally and plays the new conversation's acknowledgement
  and reply. Router model-setup failures now show only the gateway's error code, never its error
  text (a planted path does not appear in the message or the logs; the first creation of the
  routing session still showed the gateway's text until 0.1.11), a connection refused while
  connecting is reported as such, and invalid test limits are rejected.

- **0.1.11 changes (host tests and a remote compile only; nothing run on an emulator or a
  device).** When the very first routing session couldn't be created, the request's failure
  message carried the gateway's own error text (an independent review showed a planted path
  reaching it). It now names router creation with only the gateway's error code (refused), says
  the result is unknown when no answer came, or names the error's type otherwise; a planted path
  appears neither in the message nor in any log line, nothing is transcribed, registered or sent,
  a sign-in that expired is still reported as such, a cancelled request is still cancelled, and
  an older routing session is still kept when its replacement can't be created.
- **0.1.12 changes (host tests and a remote compile only; nothing run on an emulator or a
  device).** The Watch's background operation is on by default: opening the Watch app starts it
  once per open (a running session is reused), with no background status or start/stop control in
  the app; Stop is the notification's (or the system's force stop) and holds until the next open.
  Swipe right and Back always send the app to the background from every screen, never close it and
  never wait for the service or a permission. The microphone counts as armed only once the
  service really entered the foreground with the microphone type. Permissions are asked at most
  once per open. The only wake-phrase haptic stays the 20 ms listening pulse (no pulse for a
  recognized phrase). These change nothing about the earlier, unreproduced report of a missing
  first reply on the Watch.

**Never run:** wake-phrase recognition on the Watch (the Watch emulator has no speech recognition
service, so its recorder was started by the debug handoff), Korean wake-phrase recognition, and
anything on physical devices, including audio routing, haptic strength, real room acoustics and
a physical bezel. Live human speech and live room sound were never used: the human
speech and rooms above are published recordings played through the audio cable, and the other
spoken stimuli are synthesized voices. The emulator microphone occasionally delivers digital silence; those runs ended
as "no speech" and were repeated.

## Known limitations

- **"A new conversation's reply is not spoken" (reported on the installed 0.1.6 build) is still
  unexplained.** Through the app's real wiring against a test double built from the gateway's
  source, a newly created conversation's acknowledgement and reply play, from the Phone or the
  Watch, with automatic opening on or off, with every turn-start order the gateway produces and
  over another connection than the one it was created on. One real gap was found and fixed: a turn
  the gateway ends BEFORE it starts (refused, cancelled before the model was ready, or the model
  failed to start) used to leave the app waiting 15 minutes in silence; it now ends at once with the
  reason. Whether that is what happens on the phone is not known. Each voice turn now logs, under
  the `HermesVoiceTurn` tag, metadata only (event counts, timings, lengths, sizes, device kind,
  failure class, the reason a reply wasn't spoken; never words, audio, addresses or ids), so the
  failing stage can be read from the phone's log the next time it happens.
- **The routing session's model is checked on a computer only.** Whether the `openai-codex`
  provider will actually answer with `gpt-5.6-luna` on your profile hasn't been tried. Once the
  model has been proven for the routing session, the Phone doesn't check it again, so a change
  made later from another client isn't noticed. The first request to a routing session that isn't
  loaded can take up to 90 seconds longer while its model builds; creating, archiving or editing a
  conversation in the app waits for it meanwhile. The model setup takes at most 90 seconds (a slow
  switch included); on first use, or when an outdated routing session is replaced, creating the
  session comes first under the same wait with its own 30-second call timeout plus connecting, so
  that request can hold it longer than 90 seconds; no single limit covers both. A routing session whose build failed is not rebuilt by the app: routed requests fail
  within about two seconds until the gateway builds it again. A new routing session's low
  reasoning is taken from how the gateway stores its create settings, not read back.
- **An error left over from an earlier turn can fail a new one.** The gateway's "ended before it
  started" messages carry no turn id. If such a message from an earlier turn of the same
  conversation arrives just as a new request is sent, the new request is reported as failed even
  though it may still run; it never plays another turn's reply.

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
- **Wake phrase depends on each device's speech recognition service.** The Phone listens with its
  app open, and with it closed only once background listening is switched on (on-device
  recognizer only); the Watch also with its app closed once Background is started. Both run the
  platform recognizer window after window, not a dedicated low-power hotword. Where no recognizer
  is installed (as on the Watch emulator used here), the device says the wake phrase is
  unavailable, and a background session then only plays replies. The Watch also listens in the
  background only while its notification can be shown. Whether a watch's recognizer
  keeps working with the screen off for hours, and what that costs in battery, hasn't been
  measured.
- **Background sessions don't survive** a force stop, the system's Stop, a revoked permission, a
  restart of either device, the microphone privacy switch, an OEM's own power rules or a Phone that
  stays out of reach; they are shown as paused and wait for you (the Watch's starts again when you
  next open its app). The Phone relay's
  foreground-service type is *connected device*; Android requires one of a few permissions for it,
  and the app declares `CHANGE_NETWORK_STATE` for that reason only (it never changes network
  state).
- **Later replies (opt-in) are followed for 30 minutes** after a request was answered, and only while
  the Phone app process runs and its gateway connection holds; an attempt to speak one that arrived
  must begin within 10 minutes (its audio may start up to about 2 minutes later). A reply to
  something sent to the same conversation from another Hermes client in that time is spoken too
  (the gateway marks no reply as caused by a request). It waits only for a recording on the device
  it will play on, not on the other device. Why following ended is not shown in the app. The
  microphone and speaker rules were checked on a computer only, not on a phone or Watch.
- **Hands-free ending is loudness-based**, not speech understanding (see
  [Hands-free ending](#hands-free-ending-shared-vad)).
- **Watch reader history is a window.** The Watch keeps at most 200 messages per conversation;
  older ones are shown on the Phone.
- **Unverified:** whether the agent reads an attached `@file:` reference during a turn.

## License

This repository doesn't include a license file yet.
