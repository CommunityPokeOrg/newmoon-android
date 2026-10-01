#!/bin/bash
# Fetch the legacy Android SDK "extras" dependencies the UXP-era build needs:
# com.android.support 23.4.0 AARs, play-services 8.4.0 AARs, and ProGuard 6.2.0.
# Idempotent — safe to re-run.
set -e

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
M2="$SDK/extras"
BASE="https://dl.google.com/dl/android/maven2"

dl() { # group_path artifact version dest
    local gp="$1" a="$2" v="$3" dest="$4"
    local dir="$dest/$gp/$a/$v"
    mkdir -p "$dir"
    for ext in aar jar pom; do
        [ -f "$dir/$a-$v.$ext" ] || curl -sfL "$BASE/$gp/$a/$v/$a-$v.$ext" -o "$dir/$a-$v.$ext" || true
    done
    if [ ! -f "$dir/$a-$v.aar" ] && [ ! -f "$dir/$a-$v.jar" ]; then
        echo "MISSING $gp/$a/$v" >&2
        return 1
    fi
}

AS=com/android/support
for a in customtabs appcompat-v7 support-vector-drawable animated-vector-drawable \
         cardview-v7 design recyclerview-v7 support-v4 palette-v7 mediarouter-v7 \
         support-annotations; do
    dl "$AS" "$a" 23.4.0 "$M2/android/m2repository"
done

G=com/google/android/gms
for a in play-services-base play-services-basement play-services-cast \
         play-services-gcm play-services-measurement play-services-ads; do
    dl "$G" "$a" 8.4.0 "$M2/google/m2repository"
done

# ProGuard 6.2.0 jar where the build expects it (SDK tools/proguard/lib).
PG="$SDK/tools/proguard/lib"
mkdir -p "$PG"
if [ ! -f "$PG/proguard.jar" ]; then
    tmp=$(mktemp -d)
    curl -sfL "https://repo1.maven.org/maven2/net/sf/proguard/proguard-base/6.2.0/proguard-base-6.2.0.jar" -o "$tmp/proguard.jar"
    cp "$tmp/proguard.jar" "$PG/proguard.jar"
    cp "$tmp/proguard.jar" "$PG/proguard-6.2.0.jar"
    rm -rf "$tmp"
fi
echo "Android deps staged under $M2 and $PG"
