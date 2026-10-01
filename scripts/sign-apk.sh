#!/bin/bash
# 16 KB-page-align and release-sign an APK with the committed community key.
# Usage: scripts/sign-apk.sh <input.apk> [output.apk]
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IN="$1"
OUT="${2:-${IN%.apk}-signed.apk}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
KS="$ROOT/keystore/community.jks"
PASS="newmoon-community"

# Prefer the newest build-tools (zipalign -P needs build-tools >= 35).
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

[ -f "$IN" ] || { echo "no such APK: $IN" >&2; exit 1; }
[ -f "$KS" ] || { echo "missing keystore: $KS" >&2; exit 1; }

# Align uncompressed .so entries on 16 KB boundaries (required by Android 15+
# 16 KB page-size devices for direct APK mmap loading).
"$ZIPALIGN" -f -P 16 -v 4 "$IN" "$OUT.aligned" >/dev/null
mv "$OUT.aligned" "$OUT"

"$APKSIGNER" sign \
    --ks "$KS" --ks-key-alias newmoon \
    --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    "$OUT"

"$APKSIGNER" verify --verbose "$OUT" | tail -5
echo "signed: $OUT"
