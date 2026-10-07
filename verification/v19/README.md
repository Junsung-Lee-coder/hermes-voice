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
