#!/bin/sh
# Fetch pinned upstream sources for the Pale Moon Android port.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UP="$ROOT/upstream"
mkdir -p "$UP"

# UXP master tip at time of port-base creation (see docs/ARCHAEOLOGY.md).
UXP_REPO="https://repo.palemoon.org/MoonchildProductions/UXP.git"
UXP_SHA="1920188d85313e83a4ddeab03fc734ba2c02c3c3"
# Pre-removal tree containing the complete Android surface (2019-04-23).
UXP_ANDROID_SHA="63295d0087eb58a6eb34cad324c4c53d1b220491"

PM_REPO="https://repo.palemoon.org/MoonchildProductions/Pale-Moon.git"
PM_SHA="3b31d1c1df457fcb39f3536cb1dc1b8ca2a369d5"

fetch_retry() {
    dir="$1" sha="$2"
    attempt=1
    while [ "$attempt" -le 8 ]; do
        if git -C "$dir" fetch --depth 1 origin "$sha"; then
            return 0
        fi
        echo "fetch of $sha failed (attempt $attempt/8); retrying in 15s" >&2
        sleep 15
        attempt=$((attempt + 1))
    done
    return 1
}

fetch_repo() {
    dir="$1" repo="$2" sha="$3"
    if [ ! -d "$dir/.git" ]; then
        git init "$dir"
        git -C "$dir" remote add origin "$repo"
    fi
    fetch_retry "$dir" "$sha"
    git -C "$dir" checkout -f FETCH_HEAD
}

echo "== UXP (master, pinned) =="
fetch_repo "$UP/uxp" "$UXP_REPO" "$UXP_SHA"

echo "== UXP (pre-removal android tree) =="
fetch_retry "$UP/uxp" "$UXP_ANDROID_SHA" || true

echo "== Pale-Moon (app, pinned) =="
fetch_repo "$UP/pale-moon" "$PM_REPO" "$PM_SHA"

echo "Done. Apply patches with scripts/apply-patches.sh"
