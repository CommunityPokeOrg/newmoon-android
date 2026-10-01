#!/bin/bash
# Build the standalone New Moon Crash Helper APK with plain SDK tools
# (aapt2 + javac + d8 + zipalign/apksigner via scripts/sign-apk.sh).
# Usage: scripts/build-helper.sh [output.apk]
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HELPER="$ROOT/helper"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
OUT="${1:-$ROOT/newmoon-crash-helper.apk}"
WORK="$ROOT/build/helper"

BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
AAPT2="$BT/aapt2"
D8="$BT/d8"

[ -d "$HELPER" ] || { echo "missing helper sources: $HELPER" >&2; exit 1; }
[ -f "$PLATFORM/android.jar" ] || { echo "missing android.jar in $PLATFORM" >&2; exit 1; }

rm -rf "$WORK"
mkdir -p "$WORK/compiled" "$WORK/gen" "$WORK/classes" "$WORK/dex"

"$AAPT2" compile --dir "$HELPER/res" -o "$WORK/compiled/res.zip"

"$AAPT2" link \
    -o "$WORK/helper-unsigned.apk" \
    -I "$PLATFORM/android.jar" \
    --manifest "$HELPER/AndroidManifest.xml" \
    --java "$WORK/gen" \
    "$WORK/compiled/res.zip"

find "$HELPER/src" "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
javac -source 7 -target 7 \
    -bootclasspath "$PLATFORM/android.jar" \
    -d "$WORK/classes" \
    @"$WORK/sources.txt"

"$D8" --min-api 21 --lib "$PLATFORM/android.jar" --output "$WORK/dex" \
    $(find "$WORK/classes" -name '*.class')

cd "$WORK/dex" && zip -q "$WORK/helper-unsigned.apk" classes.dex && cd "$ROOT"

"$ROOT/scripts/sign-apk.sh" "$WORK/helper-unsigned.apk" "$OUT"
echo "helper APK: $OUT"
