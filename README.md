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

1. **Record a WAV.** On the Phone, the Talk button toggles recording; the Phone recorder keeps at
   most the first 2 minutes of a recording (a Phone-only limit). On the Watch, use push-to-talk,
   which records until you tap Send with no time limit, or the optional
   [wake phrase](#watch-wake-phrase). The Watch sends the recording to the Phone over the Data
   Layer (`/hv/v1/turn/<id>`). One Data Layer frame holds about 13 minutes of audio; a longer Watch
   recording is not sent, and the Watch says so.
2. **Transcribe.** The Phone calls `POST /api/audio/transcribe`. If no speech is detected, the turn
   ends without routing or delivering anything. A wake-phrase request the Watch's recognizer
   already heard arrives as text instead and skips this step; it is treated exactly like a
   transcript.
3. **Route.** The transcript goes to a **persistent routing session**. It is created hidden
   (`source = "recorder-phone-router"`) on first use, then reused, and never appears in lists. The
   prompt restates the routing contract, lists the allowlisted aliases, and marks the transcript as
   untrusted data.
4. **Fail closed.** The router must reply with exactly one JSON object
   `{"destination":"<alias>","ack":"<text>"}`.
   - The allowlist is built only from your **unarchived** conversations (at most 32). Archiving a
     conversation removes it as a destination. The routing session can never be a destination.
   - The alias must match an allowlist entry exactly, and the ack must be 1–240 characters.
   - Anything else fails closed: nothing is spoken and nothing is delivered. Extra keys, including
     a session id the model invents, are ignored. Session ids never come from the model.
5. **Acknowledge.** The ack is synthesized with `POST /api/audio/speak` and played. If it can't be
   played, the transcript isn't delivered. For a Watch turn, only a `/hv/v1/played` ACK from that
   Watch's node counts. The Watch sends `ok=true` only when its player completes normally.
6. **Deliver.** Only then is the original transcript submitted to the chosen conversation.
7. **Speak replies.** The final reply (`message.complete`) is always spoken. The first and middle
   interim replies are spoken only if enabled in Settings (both are off by default). Text you
   already heard isn't repeated, and the app never makes up interim events.

Turns are serialized from capture to delivery. Replayed turn ids, such as Data Layer retries, are
ignored.

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
  feedback, so turning off touch vibration doesn't hide them; none of them overrides Do Not Disturb
  or the watch's vibration settings. The Phone's Watch **Haptics** setting turns them all off.

## Watch wake phrase

Off by default and edited only on the Phone (Settings → Watch). The Phone sends all Watch settings
as one snapshot with an increasing revision; the Watch ignores older or equal-revision snapshots.
Patterns are space-separated whole words; `*` matches any letters inside one word, and nothing
else is special. A blank field restores the defaults.

It works only while the Watch app is visible (foreground). Each time the app is shown, or the
screen turns back on with the app shown, the platform `SpeechRecognizer` listens for 5 seconds
(on-device recognition when available). It never runs while recording, sending, waiting on the
Phone, playing audio, or for 4 seconds after playback. The recognizer and the app's recorder never
use the microphone at the same time.

The wake phrase must come first: at the start of what you say, or right after one short greeting
("hey", "hi", "hello", "ok", "okay", "안녕", "저기"). A mention in the middle of a sentence is
ignored. What happens next depends on how you say it:

- **Wake phrase, then pause:** the recognizer is released, the Watch's own recorder starts and
  measures the room, and a buzz means "speak now". The request ends after about 2 seconds of
  silence (up to about 4 seconds if background noise got louder after you spoke); short pauses
  between words don't end it, and there is no time limit while you keep talking. If you don't
  start within 8 seconds, nothing is sent. This is the way to make a long request.
- **Wake phrase and request in one breath:** only the recognizer's **final** result is used, and
  it's sent whole as the request. While partial results keep changing, the Watch keeps waiting
  (up to 8 seconds after the last new partial). Nothing is sent, and the Watch shows "Didn't catch
  that. Tap or say it again", when the recognizer stops or fails before a final result, or when
  its final result is empty or no longer starts with the wake phrase it heard. A request longer
  than 4,000 characters is refused, not cut.
- **The system recognizer has its own limits.** It decides when you've finished (its own pause
  detection) and may end a session on its own, so a pause in a one-breath request can end it
  early, and what you say after that isn't captured. The Watch asks it to wait for 2 seconds of
  silence, but that is only a hint many recognizers ignore. For a long request, say the wake
  phrase, wait for the buzz, then speak.

The recognized text is never logged; only counts and outcomes are.

## Text chat and history

History shows user and assistant messages (hidden and tool rows are dropped), newest page first,
with "load older". Text messages use the same ownership checks as voice. A reply appears after the
turn completes. Replies to text chat aren't spoken.

## Settings and UI

- **Phone settings:** dashboard URL and profile, sign in/out (with confirmation), **Appearance**
  (Dark by default, Light, or System), **Play first response** (off by default) and **Play middle
  responses** (off by default). There's deliberately no setting for the ack or the final reply.
  Settings also shows whether the Watch app is reachable.
- **Watch settings** (edited on the Phone, synced as the `/hv/v1/settings` data item): wake phrase
  on/off (off by default), the wake phrases and haptics. There is no Watch recording time limit.
- **Dark by default.** On the Phone, sign-in, lists, chat, Settings and dialogs follow the
  Appearance setting. The Watch is always dark and shows whether the Phone is reachable.
- **Full-width Talk bar.** The Phone's Talk button is a full-width bar above the navigation bar,
  inset for the safe area. It never covers the chat composer, which moves above the keyboard.

**Emulator QA input (debuggable builds only).** A debuggable build accepts an `hv_qa_wav` string
extra naming a `<name>.wav` file in the app's private `files/qa/` directory, and submits that file
through the normal voice path in place of a recording. The Watch also accepts
`hv_qa_wake_handoff=second_utterance` (runs the wake handoff as a match would, to test the
recorder, not recognition) and `hv_qa_seed_reader=<n>` (shows synthetic reader rows, to test the
reader UI without a paired Phone). Each extra is honoured once, for a fresh launch intent only, and
non-debuggable builds ignore them.

## Validation

Only the following has been run:

- **Core unit tests:** `scripts/core-jvm-check.sh` compiles `:core` and runs **170 JUnit tests**, all
  passing. They use an in-process fake dashboard and cover sign-in, session ownership, chat and
  attachments, routing, playback routing, the Watch link and reader contracts, the wake contract
  (final-only, leading wake phrase, contradicted or empty finals, 30/60/120-second recognizer
  streams), silence endpointing (30, 60 and 120 seconds of continuous and of speech-like audio with
  short pauses are not cut; background noise rising after speech still ends a request),
  haptic timing, gesture arbitration, the Watch capture, wake-window, microphone-handoff and bezel
  scrolling logic the Android adapters delegate to, and source checks of the Android wiring.
- **Paired debug build:** `gradle :phone:assembleDebug :watch:assembleDebug` succeeded with
  Gradle 8.13 and JDK 17. Both APKs have the same package and are v2-signed by the same debug
  certificate.
- **Phone emulator (Android 15), real microphone input.** Speech played into the emulator
  microphone went through transcription, routing, the ack played on the Phone, delivery, and the
  final reply played on the Phone. The Phone was signed in to a local test dashboard.
- **Watch emulator (Wear OS 5), not paired.** Push-to-talk captured real (non-silent) audio from the
  emulator microphone for 75 seconds and stopped only when Send was tapped, with one start and one
  end haptic. The wake-phrase recorder path ran through the debug handoff: 72 seconds of speech with
  pauses of up to 0.8 seconds were recorded whole and ended once on trailing silence; with no speech
  it gave up after the no-speech timeout without sending. Swipes, bezel scrolling with scroll
  haptics, follow-latest and scroll preservation ran with synthetic reader rows. Leaving the app
  while recording cancelled it without sending.

- **Paired emulators, partial.** After pairing, with an older Phone build and no dashboard reachable,
  Watch reader requests reached the Phone and its answers came back, and Watch recordings were
  uploaded to the Phone, which reported its stages back. Nothing was transcribed or delivered.
  Background noise that rose after speech ended the Watch's hands-free recording within seconds.

**Never run:** a complete paired flow with matching Phone and Watch builds, playback on the Watch,
the Watch `played` ACK, switching playback between devices, and wake-phrase recognition (the Watch
emulator has no speech recognition service). These are covered only by core unit tests. Nothing has
been tested on physical devices, including audio routing, haptic strength and a physical bezel.

## Known limitations

- **Not release-ready.** Debug builds only: no release signing configuration and no R8. The Watch
  has never been paired with a Phone (see [Validation](#validation)).
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
- **Wake phrase is foreground-only** and depends on the Watch's speech recognition service. Where
  none is installed, the Watch says the wake phrase is unavailable. There's no always-on hotword.
- **Watch reader history is a window.** The Watch keeps at most 200 messages per conversation;
  older ones are shown on the Phone.
- **Unverified:** whether the agent reads an attached `@file:` reference during a turn.

## License

This repository doesn't include a license file yet.
