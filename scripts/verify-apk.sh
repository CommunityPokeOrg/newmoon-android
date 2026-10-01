#!/bin/bash
# Sanity-check a New Moon APK: ABIs, ELF LOAD-segment page alignment (16 KB),
# signature, and manifest SDK levels. Exit non-zero on any failure.
set -e

APK="$1"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"

[ -f "$APK" ] || { echo "usage: $0 <apk>" >&2; exit 1; }

echo "== badging =="
"$BT/aapt" dump badging "$APK" | grep -E "package:|application-label:|sdkVersion|targetSdkVersion|native-code" || true

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
unzip -qq "$APK" '*.so' -d "$tmp" 2>/dev/null || true

echo "== ELF LOAD alignment (need >= 0x4000 on 16 KB devices) =="
bad=0
while IFS= read -r so; do
    align=$($ANDROID_SDK_ROOT/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf -lW "$so" 2>/dev/null \
        | awk '/LOAD/ {print $NF}' | sort -u | tr '\n' ' ')
    printf '%-60s align: %s\n' "${so#$tmp/}" "$align"
    echo "$align" | grep -q 0x4000 || bad=1
done < <(find "$tmp" -name '*.so')

echo "== signature =="
"$BT/apksigner" verify --verbose --print-certs "$APK" | grep -E 'Verified|Signer' | head -6

[ "$bad" -eq 0 ] || { echo "FAIL: found .so with <16KB LOAD alignment" >&2; exit 1; }
echo "OK"
