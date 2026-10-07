# Version 19 source verification

This directory contains the manifests and a test-only replay patch for the Phone and Watch
source snapshot, versionCode 19 / `0.1.18-dev`.

## Source and tests

- The source manifest describes 216 repository files. These are the exact reviewed production
  source and Core tests; no host-specific Watch test harness is applied to the product tree.
- `test-overlay.patch` adds the Watch Robolectric test harness and nine test-only files.
  Applying it to the source files produces the 225-file set in `test-overlay-manifest.json`.
- To run the native test suite on a disposable checkout with Android SDK, JDK 17 and compatible
  Gradle installed, apply the patch with `git apply verification/v19/test-overlay.patch`, then run
  `gradle :core:test :watch:testDebugUnitTest --no-daemon --max-workers=1`.
  Do not commit the overlay into the production modules.

## Recorded results

- Full native tests: **884 Core + 119 Watch = 1,003**, zero failures, errors or skipped tests.
- Focused marker/history tests: **75 passing**.
- Predecessor regression: **24 tests, 18 assertion failures, six passing controls** against unchanged
  preceding production code. Earlier compile-failed or timeout-contaminated attempts are not counted.
- Phone and Watch debug assembly and lint passed. Lint: zero errors, 30 Phone and 27 Watch warnings.
- Both APKs have package `com.rumi.hermesvoice`, versionCode 19 / `0.1.18-dev`, and the same Android
  Debug signer. APKs, signing material, private diagnostics and build-host receipts are not included.

The README was subsequently corrected without changing any compiled source, tests, build
configuration, resources, manifests or APK bytes. Existing native evidence therefore remains
applicable by exact file equivalence; no new native build or test run is claimed for the README-only
archive. The tests that read README have unchanged assertion outcomes.

## Coverage limits

No UI, emulator or physical-device behavior, real microphone/speaker behavior, Wear synchronization,
share-sheet/FileProvider runtime grants, battery effects or actual model compliance was validated
by this source publication. The leading `🎙` convention is visible request text, not authenticated
voice-origin metadata. No Hermes server/core/API changes are included.

This source update is not an APK release, update-channel rollout or device installation.

## Listen on selector successor delta

`test-overlay-listen-selector.patch` is applied after `test-overlay.patch` (`patch -p1`). It migrates
only the four Watch Robolectric tests whose subject was the old rule that a background standby bypassed
the "Listen on" selector (an unselected Watch now never listens, hidden or on screen) and the implicit
selection their fixtures relied on. `test-overlay.patch` itself is unchanged.

## New-reply alert and Phone verification overlays

These overlays are test-only and are applied in this order to a disposable copy (`patch -p1`):
`test-overlay.patch`, `test-overlay-listen-selector.patch`, `test-overlay-reply-alert.patch` (Watch alert
Robolectric tests), `test-overlay-phone.patch` (Phone Robolectric harness: a fake in-memory key store and the
P01-P08 alert/tap tests; baseline-compilable) and `test-overlay-phone-green.patch` (S01-S05, which use the new
alert types and therefore only apply to this source). The Phone tests also need the Core test doubles
`FakeHermesDashboard.kt` and `VoiceMarkerTestSupport.kt` copied into `phone/src/test/kotlin/com/rumi/hermesvoice/core/`.
`red/AlertRig.kt` is the baseline-compatible variant of the Core alert rig used only for the red run.

The test bundles additionally append a per-test timeout (`tasks.withType<Test>` with a 30-minute bound) to the
three module build scripts. It is bundle-only: it is not in `test-overlay*.patch`, the product tree, or the APKs.
`test-overlay-manifest.json` describes the exact test bundle (timeout included); `source-manifest.json` the
product tree.

Run: `gradle :core:test :watch:testDebugUnitTest :phone:testDebugUnitTest --no-daemon --max-workers=1`.
Recorded: 959 Core + 129 Watch + 13 Phone passing; red on the baseline by assertion; Phone and Watch debug
assembly and lint passing. UI, devices, real notification delivery/vibration/sound and Wear bridging are not covered.
