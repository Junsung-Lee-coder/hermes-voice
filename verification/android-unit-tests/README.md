# Android unit-test replay

All Kotlin test sources are included in `core/src/test`, `phone/src/test`, and
`watch/src/test`. The application Gradle files remain unchanged for APK builds.
This directory supplies an exact test-only overlay for the Phone and Watch
Robolectric/Compose dependencies and test-runner options.

Use a separate disposable checkout for the overlay:

```sh
git apply --check verification/android-unit-tests/test-overlay.patch
git apply verification/android-unit-tests/test-overlay.patch
gradle --no-daemon --max-workers=1 :core:test :phone:testDebugUnitTest :watch:testDebugUnitTest
```

Requirements: JDK 17, Gradle 8.13, Android SDK platform 35 and build-tools 35.0.0,
a local `sdk.dir` configuration, and access to the declared dependency
repositories. Robolectric uses a `robo-home` directory beside the checkout.
The source and test-overlay manifests bind all 524 source files before and after
applying the patch. The four files in this verification directory are not
application inputs.

The retained Windows Gradle/JVM/Robolectric run passed 2,875 tests:
1,874 core, 721 Phone, and 280 Watch; no failures, errors or skips.
This publication reuses that exact source evidence; it is not a fresh build or
a GitHub Actions run. Physical radios, notification delivery, Doze, force-stop
and real-device rendering are not established by this unit-test result.

Build distributable APKs from an unmodified checkout, without this overlay.
No APK, private signing material, runtime data or device-specific configuration
is included here.
