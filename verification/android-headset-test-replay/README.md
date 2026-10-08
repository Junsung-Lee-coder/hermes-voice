# Android test replay: composer, attachments and private headset replies

This directory holds the test-only material needed to run the Phone and Watch Robolectric tests
for the composer, attachments, message selection, reply preview and private-headset behavior
against the production tree. It is **not** part of any shipped build: the production Gradle files
in `core/`, `phone/` and `watch/` are unchanged, and nothing here is compiled unless you apply it
to a disposable copy as described below.

## Contents

| Path | What it is |
| --- | --- |
| `overlay/` | 35 test-source files, laid out as repository-relative paths (`phone/src/test/...`, `watch/src/test/...`). |
| `gradle/core.build.gradle.kts` | Test-configuration variant of `core/build.gradle.kts` (adds a per-test timeout). |
| `gradle/phone.build.gradle.kts` | Test-configuration variant of `phone/build.gradle.kts` (adds Robolectric, MockK, Compose UI-test dependencies, the Android-resource unit-test option and a per-test timeout). |
| `gradle/watch.build.gradle.kts` | Test-configuration variant of `watch/build.gradle.kts` (same additions for the Watch module). |
| `SHA256SUMS` | SHA-256 of every file in `overlay/` and `gradle/`. |

The `gradle/` files are full replacements of the three module build files, not patches. The Phone
and Watch variants point the Robolectric runtime cache at `../robo-home`, a directory next to the
copy you test, so jars are not written into your home directory.

## Replay

Requirements: JDK 17, Gradle 8.13, an Android SDK (set `sdk.dir` in `local.properties`), and
network access the first time so Robolectric and the test dependencies can be fetched.

```sh
# 1. Work on a disposable copy of the repository, never the checkout you intend to commit.
cp -r <repository> /tmp/replay/src && cd /tmp/replay/src

# 2. Add the test sources and swap in the test build files.
cp -r verification/android-headset-test-replay/overlay/. .
cp verification/android-headset-test-replay/gradle/core.build.gradle.kts  core/build.gradle.kts
cp verification/android-headset-test-replay/gradle/phone.build.gradle.kts phone/build.gradle.kts
cp verification/android-headset-test-replay/gradle/watch.build.gradle.kts watch/build.gradle.kts
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 3. Run the three test suites.
gradle --no-daemon --max-workers=1 --continue \
  :core:test :watch:testDebugUnitTest :phone:testDebugUnitTest
```

The recorded run used `-Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m"` and
`-Pkotlin.compiler.execution.strategy=in-process` on a Windows host with `--max-workers=1`; use
the same settings if the suites are memory-constrained.

After step 2 the tree is the production source plus exactly the files in `overlay/` and the three
replaced build files. Production source files are not modified. To return to the shipped
configuration, discard the disposable copy; the committed `core/`, `phone/` and `watch/` Gradle
files are the shipped configuration.

## Expected result

A Windows Gradle/JVM/Robolectric run of this configuration recorded 1,148 Core, 136 Watch and
202 Phone tests (1,486 total) with no failures, errors or skipped tests. Phone and Watch debug
assembly and lint (zero errors) were run separately on the unmodified production tree.

## What this does not cover

These are JVM and Robolectric tests with fake audio devices. They do not exercise a physical
microphone, a real Bluetooth or wired headset, headset media buttons, absence of speaker leakage,
Wear OS Data Layer delivery between real devices, or battery impact. No hardware validation is
claimed, and the repository has no continuous-integration workflow that runs these tests.
