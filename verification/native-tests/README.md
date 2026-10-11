# Native verification

The product source is the canonical Phone/Watch source snapshot. The two Android
build scripts in the product tree have not been replaced by native-test adapters.

## Recorded execution

- Original Windows AMD64 build host, Temurin JDK 17.0.20.1 and Gradle 8.13.
- Focused JVM/Robolectric verification: 152 tests, 0 failures, 0 errors, 0 skipped.
- Full native JVM/Robolectric suite: 2,026 tests, 0 failures, 0 errors, 0 skipped.
- All 1,971 predecessor test identities retained; 55 additional tests; no duplicates.
- Canonical Phone/Watch debug assembly and lint succeeded. Lint retained 63 warnings.

The recorded checks are native build/test evidence, not GitHub CI results,
independent code review, physical-device audio acceptance, or an installed update.
No APK, signing material, host logs or deployment configuration is included here.

## Test-only replay

`test-overlay.patch` changes only `phone/build.gradle.kts` and
`watch/build.gradle.kts` for the JVM/Robolectric harness. Apply it only to a
throwaway copy, never to the product source used for APK assembly:

```sh
git apply --check verification/native-tests/test-overlay.patch
git apply verification/native-tests/test-overlay.patch
```

`source-manifest.json` lists the 399 canonical members.
`test-overlay-manifest.json` lists the same 399 members after the test-only patch.
The overlay was replayed and all resulting member hashes were compared before upload.
The original ZIP archive hashes identify the archived inputs, not a GitHub-generated ZIP.
