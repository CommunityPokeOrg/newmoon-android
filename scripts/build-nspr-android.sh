#!/bin/sh
# Verified slice: cross-compile UXP's bundled NSPR for Android.
# Produces libnspr4.so / libplc4.so / libplds4.so (ELF aarch64) under
# upstream/uxp/nsprpub/../build output dir.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NSPR="$ROOT/upstream/uxp/nsprpub"
OUT="${NSPR_OUT:-$ROOT/out/nspr-aarch64}"
NDK="${ANDROID_NDK:-$ANDROID_HOME/ndk/current}"

[ -d "$NSPR" ] || { echo "run scripts/fetch-upstream.sh + apply-patches.sh first" >&2; exit 1; }
[ -d "$NDK" ] || { echo "set ANDROID_NDK to an NDK r19+ path" >&2; exit 1; }

TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API="${ANDROID_API:-21}"

mkdir -p "$OUT"
cd "$OUT"
"$NSPR/configure" \
    --target=aarch64-linux-android \
    --with-android-ndk="$NDK" \
    --with-android-toolchain="$TC" \
    --with-android-platform="$TC/sysroot" \
    --with-android-version="$API" \
    --disable-debug --enable-optimize

make -j"$(nproc)"
echo "Artifacts:"
find "$OUT" -name "lib*.so" | xargs file
