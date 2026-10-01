#!/bin/sh
# Configure, build, and package the New Moon Android APK.
# Expects: fetch-upstream.sh + apply-patches.sh already run, Android SDK/NDK
# env set (ANDROID_SDK_ROOT or ANDROID_HOME, ANDROID_NDK), JDK 17 on PATH.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UXP="$ROOT/upstream/uxp"
OBJ="$ROOT/upstream/obj-android-aarch64"

[ -d "$UXP" ] || { echo "run scripts/fetch-upstream.sh first" >&2; exit 1; }

cp "$ROOT/mozconfig/mozconfig.android-aarch64" "$UXP/mozconfig"

cd "$UXP"
./mach configure
if ! ./mach build; then
    # Known race: widget/android/bindings export can run before
    # build/annotationProcessors produces its jar on a cold build.
    echo "mach build failed; running bindings export workaround and retrying"
    make -C "$OBJ/widget/android/bindings" export || \
        make -C "$OBJ/build/annotationProcessors"
    ./mach build
fi
./mach package

echo "== APK(s) =="
ls -l "$OBJ"/dist/*.apk
