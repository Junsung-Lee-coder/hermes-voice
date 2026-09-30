#!/usr/bin/env bash
# Compile and run the Hermes Voice core + its tests WITHOUT Gradle or the Android SDK.
#
#   HERMES_VOICE_TOOLS=~/.cache/recorder-direction-tools hermes-voice/scripts/core-jvm-check.sh
#
# HERMES_VOICE_TOOLS must contain kotlinc/ (kotlin-compiler-1.9.22.zip) and lib/ with the pinned
# jars below; pass FETCH=1 to download them from Maven Central and GitHub (SHA-256 verified).
# Only :core is compiled here; the :phone and :watch Android modules need a Gradle build.
set -euo pipefail

TOOLS="${HERMES_VOICE_TOOLS:?set HERMES_VOICE_TOOLS}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export TMPDIR="${TMPDIR:-/tmp}"
WORK="${HERMES_VOICE_WORK:-$(mktemp -d)}"
M=https://repo1.maven.org/maven2
JARS="
66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9 org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed org/json/json/20240303/json-20240303.jar
8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3 junit/junit/4.13.2/junit-4.13.2.jar
f3d4f5de1c391bbcc20f3b3435ccbac013521e76b6902d7d59635ec15c1f797e org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.jar
6784673687f4ac8f21679b9d4bc7cdb46e1a1ce1be9d3133b36bede59a741561 com/squareup/okhttp3/mockwebserver/4.12.0/mockwebserver-4.12.0.jar
b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0 com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar
67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412 com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar
"

mkdir -p "$TOOLS/lib"
if [ "${FETCH:-0}" = 1 ] && [ ! -x "$TOOLS/kotlinc/bin/kotlinc" ]; then
  curl -sSfL -o "$TOOLS/kc.zip" https://github.com/JetBrains/kotlin/releases/download/v1.9.22/kotlin-compiler-1.9.22.zip
  python3 -c "import zipfile,sys;zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$TOOLS/kc.zip" "$TOOLS"
  chmod +x "$TOOLS"/kotlinc/bin/*; rm "$TOOLS/kc.zip"
fi
CP=""
while read -r sum path; do
  [ -n "$sum" ] || continue
  jar="$TOOLS/lib/$(basename "$path")"
  [ -f "$jar" ] || { [ "${FETCH:-0}" = 1 ] && curl -sSfL -o "$jar" "$M/$path"; }
  echo "$sum  $jar" | sha256sum -c --quiet -
  CP="$CP:$jar"
done <<< "$JARS"

export JAVA_OPTS="-Djava.io.tmpdir=$TMPDIR ${JAVA_OPTS:-}"
rm -rf "$WORK/main" "$WORK/test"
"$TOOLS/kotlinc/bin/kotlinc" -jvm-target 17 -nowarn -cp "${CP#:}" -d "$WORK/main" \
  $(find "$ROOT/core/src/main/kotlin" -name '*.kt' | sort)
if [ "${COMPILE_ONLY:-0}" = 1 ]; then echo "core compiled: $WORK/main"; exit 0; fi
"$TOOLS/kotlinc/bin/kotlinc" -jvm-target 17 -nowarn -cp "${CP#:}:$WORK/main" -d "$WORK/test" \
  $(find "$ROOT/core/src/test/kotlin" -name '*.kt' | sort)
TESTS=$(cd "$WORK/test" && find . -name '*Test.class' | sed 's#^\./##; s/\.class$//; s#/#.#g' | sort)
java -Djava.io.tmpdir="$TMPDIR" -cp "${CP#:}:$WORK/main:$WORK/test:$TOOLS/kotlinc/lib/kotlin-stdlib.jar" \
  org.junit.runner.JUnitCore $TESTS
