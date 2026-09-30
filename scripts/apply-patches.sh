#!/bin/sh
# Apply the port patches and overlay the recovered Android sources.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UXP="$ROOT/upstream/uxp"

[ -d "$UXP" ] || { echo "run scripts/fetch-upstream.sh first" >&2; exit 1; }

for p in "$ROOT"/patches/*.patch; do
    echo "Applying $(basename "$p")"
    git -C "$UXP" apply --whitespace=fix "$p" || patch -d "$UXP" -p1 --forward < "$p"
done

echo "Overlaying recovered Android sources (vendor/uxp-android -> upstream/uxp)"
cp -a "$ROOT/vendor/uxp-android/." "$UXP/"

echo "Done."
