# Android test harness (not part of the shipped product)

Everything under `core/src/test`, `phone/src/test` and `watch/src/test` is delivered in this checkout. Nothing is hidden in an overlay.

## What runs without this directory

`:core:test` is **self-sufficient**. `core/build.gradle.kts` already declares its test dependencies, so on a machine with JDK 17,
Gradle 8.13 and network access to `google()`/`mavenCentral()`:

    gradle :core:test

runs the whole core test tree (pure JVM: orchestrator, clients over MockWebServer, Markdown, diagnostics, ...). This is the
"standalone delivered core source" check. It uses the delivered `core/build.gradle.kts` byte for byte.

## What needs this directory (Phone and Watch Robolectric tests)

`phone/src/test` and `watch/src/test` render the production Compose/Android classes under Robolectric. The product
`phone/build.gradle.kts` and `watch/build.gradle.kts` deliberately do not carry Robolectric/Compose-test/mockk dependencies (they would
not belong in the app). The harness is **appended** to those two files in a scratch copy only:

    cat android-test-harness/phone.append.gradle.kts >> phone/build.gradle.kts
    cat android-test-harness/watch.append.gradle.kts >> watch/build.gradle.kts
    mkdir ../robo-home            # Robolectric's android-all jars are fetched here (user.home is redirected to it)
    gradle :phone:testDebugUnitTest :watch:testDebugUnitTest

* A scratch `local.properties` with `sdk.dir=` is needed (Android SDK platform 35, build-tools 35.0.0).
* The appended blocks only add test dependencies, `unitTests` options (resources, a heap, the redirected `user.home`), a
  `ui-test-manifest` debug dependency and a 30-minute per-test timeout (the module script gets `import java.time.Duration` as its first line). `core` needs no append. They change no assertion, filter or product code.
* The delivered product `build.gradle.kts` files are never edited in the checkout; build the APKs from the checkout without the appends.

## Not covered by any of this

Physical-device gestures (long-press selection, link tap versus long-press on a real touch screen), TalkBack, Doze, real radios and
hardware are **NOT_RUN**; the Robolectric tests approximate touch input and say so.
