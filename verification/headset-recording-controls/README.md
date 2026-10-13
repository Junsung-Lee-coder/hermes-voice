# Headset recording controls: source and test replay

This snapshot contains the Phone and Wear OS source for development version `0.1.19-dev` (version code `20`). All test classes are included in the source tree. The app build files remain unchanged by the host-test overlay.

## Main changes

- Share one Phone recording controller between visible and background headset controls, with microphone admission, cancellation and cleanup safeguards.
- Treat received PLAY and PAUSE as recording-state toggles. Retain duplicate/repeat suppression and explicit START/STOP idempotence for other callers.
- Select the Bluetooth communication output handle separately from the recorder input handle.
- Keep recording cues private and preserve independent Watch capture and submission.

## Files

- `source-manifest.json`: SHA-256 and size of every product-source member.
- `test-overlay.patch`: host-test changes to the Phone and Watch Gradle files only.
- `test-overlay-manifest.json`: expected complete file set after applying that overlay.

The patch adds test dependencies and host-test settings; it is not part of the app build configuration.

## Test replay

Use a disposable copy of the checkout. With JDK 17, Gradle 8.13 and the Android SDK configured:

```sh
git apply --check verification/headset-recording-controls/test-overlay.patch
git apply verification/headset-recording-controls/test-overlay.patch
mkdir -p ../robo-home
gradle :core:test :phone:testDebugUnitTest :watch:testDebugUnitTest
```

Compare the resulting source members against `test-overlay-manifest.json`. Build app APKs from a clean checkout without the overlay.

## Verified checks and limits

The Windows Gradle/JVM/Robolectric run completed 2,090 tests: core 1,510, Phone 389 and Watch 191, with zero failures, errors or skips. Phone and Watch APK assembly, Android lint and signing checks passed for the same source.

Host tests do not prove physical Buds touch delivery, microphone PCM, private cue audibility or Android screen-off behavior. Bluetooth output and Hermes media-button registration have been observed on a Phone; a complete physical single-touch start/stop check remains separate. The TTS provider's HTTP 403 problem is unresolved and is not repaired by these changes.

This publication contains source and test-replay metadata, not APKs, signing keys or device diagnostics.
