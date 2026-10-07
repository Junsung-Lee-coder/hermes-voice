# b43 source snapshot — owner review pending

This branch publishes the latest self-verified Phone + Watch source snapshot, not an accepted release.
The 163 production-source archive members match `source-manifest.json` byte-for-byte. Existing root
README prose contains historical version/runtime descriptions; the b43 attribution below governs this snapshot.

- Candidate: b43; status: SELF_VERIFIED_OWNER_REVIEW_PENDING.
- Package: com.rumi.hermesvoice (Phone and Watch).
- versionCode: 16; versionName: 0.1.15-dev; existing debug signer only.
- Frozen source archive SHA256: ef3a6e41ccf825d84ddd18858d6bf68923fa9db1c22706e7b5d02a1cdab053e0 (163 members).
- Frozen test overlay SHA256: 656f7511e6f55865b1c4bcf2e18c37bd7d32700e4d5568b4d4e279c14974b1b5 (165 members).

## Changes since the predecessor

WatchApp retains admitted Phone response identities after playback completion, moving the known
pre-Stop identities into process-lifetime stopped-turn denial. Each audio-focus acquisition gets its own
actual request/listener; old ownership is invalidated before abandonment and checked again in queued work.
Focus denial or loss before request return admits no MediaPlayer and sends a negative ACK.
The branch also contains the cumulative source changes that had not yet been pushed on the earlier feature branch.

## Actual self-verification

- Original and corrected native RED: 10 Watch cases, 7 assertion failures + 3 positive controls; native exit1.
- Focused native GREEN: core23 + Watch17; failures/errors/skips0, native exit0.
- Full native GREEN: core544 + Watch73; failures/errors/skips0, native exit0.
- Phone + Watch assembleDebug and lintDebug: actual native exits0.
- Resolved Watch androidx.fragment:fragment:1.3.0: actual dependencyInsight native exit0.
- Lint Error/Fatal0; Phone Warning30/Hint2, Watch Warning27/Hint1 remain.
- Initial new-fixture failures were preserved and corrected without weakening assertions.
- Original product wrapper exit1 was a dependency CAPACITY_NOT_STARTED result under the3GiB free-commit floor.
  A new isolated recovery run copied the original APK/lint bytes and executed only that previously unstarted
  dependency task: native0/wrapper0, no assemble/lint replay.
- Both debug APKs passed package/version/signer/v2/CRC/all-DEX harness-exclusion and remote/local hash checks.
  APKs, signing material, private logs and internal runner/receipt paths are deliberately NOT uploaded here.

## Optional test-only host overlay

`test-overlay.patch` adds the existing Robolectric/MockK host harness and appends test-only Gradle options.
Apply it ONLY to a disposable copy of the source, never to a shipping artifact build. Its resulting source
members must match `test-overlay-manifest.json`. The R64-R73 tests exercise actual WatchApp admission,
completion, notification Stop and acquired focus listeners. The original63 tests are retained; R62 has only
its permitted private reflection getter migration. Four new-only setup calls isolate unrelated LISTEN/REARM
holds without changing assertions or adversarial callback/payload inputs.

Windows qualification used JDK17, Gradle8.13, SDK35; serial max-workers1, Gradle Xmx1024MiB / metaspace512MiB,
in-process Kotlin, Watch test heap1024MiB and >=3GiB live free-commit admission before each Gradle invocation.
The host overlay does not belong in production DEX.

## Limits

SDK34 Robolectric, mocked decoder/Data Layer and synthetic PCM/callback schedules are host evidence,
not physical Android timing, recording, audio, recognition, FGS, gesture or haptic proof.
Stop denial is process-lifetime only; no tombstone cap/TTL/LRU or cross-process persistence claim.
Physical/runtime NOT_RUN; missing-audio NOT_FIXED. No acceptance, review dispatch, installation,
APK release, updater publication, main merge or runtime apply is authorized by this source snapshot.
