# New Moon — UXP (Pale Moon platform) browser for Android

New Moon is a community-built browser for Android on the **Unified XUL
Platform (UXP)** — the Goanna engine forked from Firefox ESR-52-era
Gecko that powers Pale Moon — with the historical Fennec (Firefox for
Android) frontend recovered from the last UXP tree that shipped it.

- **Engine:** Goanna/UXP (XUL, XBL, XPCOM, classic XUL-overlay extensions)
- **Frontend:** Fennec-derived native Android UI (`mobile/android`)
- **Package:** `org.palemoon.community` · arm64-v8a · minSdk 15 · targetSdk 24+
- **Status:** builds, installs, launches; the XUL/XBL pipeline runs and real
  HTTP/HTTPS page loads are verified. This is a retro/hobby-class browser on
  an ESR-52-era engine — do not treat it as a secure daily driver.

## Download

Signed APKs are published on the
[Releases](https://github.com/CommunityPokeOrg/newmoon-android/releases)
page. See [`docs/STATUS.md`](docs/STATUS.md) and
[`docs/XUL-ON-ANDROID.md`](docs/XUL-ON-ANDROID.md) for exactly what is and
isn't verified.

### Crash diagnostics

Two apps ship on the release:

- **`newmoon-android-arm64.apk`** — the browser. It writes crash reports
  (`crash-`, `startup-`, `watch-`, `exit-*.txt`) and a live
  `newmoon-session-started.txt` marker into `Download/NewMoon/` —
  including reports produced by a `:reporter` watchdog process that
  survives a main-process native crash.
- **`newmoon-crash-helper.apk`** (`org.palemoon.crashhelper`) — a
  standalone companion that reads that shared folder via a one-tap folder
  grant (SAF), shows an "unclean exit" banner when the session marker is
  stale, lists reports with Copy/Share, can launch New Moon, and offers a
  launch-time logcat monitor after
  `adb shell pm grant org.palemoon.crashhelper android.permission.READ_LOGS`
  (no API lets a normal app read another app's logs or ApplicationExitInfo —
  this is the honest limit; a death before the app's own code runs can only
  be seen via the stale marker or adb logcat).

## Building

Reproducible end-to-end (also encoded in
[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)):

```sh
# Prereqs: JDK 17, Android SDK 34 + build-tools 34.0.0/36.x,
# Android NDK 27.2.12479018, m4, autoconf2.13
export ANDROID_SDK_ROOT=~/Android/Sdk
export ANDROID_NDK=$ANDROID_SDK_ROOT/ndk/27.2.12479018

./scripts/fetch-android-deps.sh   # support/GMS AARs + ProGuard jar into SDK
./scripts/fetch-upstream.sh       # pinned UXP + Pale-Moon sources (~2 GB)
./scripts/apply-patches.sh        # port patches + overlay recovered sources
./scripts/build-apk.sh            # mach configure && mach build && mach package
./scripts/sign-apk.sh upstream/obj-android-aarch64/dist/newmoon-*.apk out.apk
./scripts/verify-apk.sh out.apk   # ABI, ELF 16KB alignment, signature, SDK levels
```

`keystore/community.jks` is a **public community signing key** (store/key
password `newmoon-community`). It exists so builds produce a consistent
signature for update continuity — it is not a security credential and is
intentionally committed.

## Repository layout

- [`vendor/uxp-android/`](vendor/README.md) — historical Android sources
  recovered from UXP @ `63295d00` (Apr 2019, last tree before upstream's
  Fennec/Android removal).
- [`patches/`](patches/) — port patches applied on top of pinned UXP master:
  NSPR modern-NDK, Android build plumbing, configure machinery, APK
  packaging fixes.
- [`mozconfig/mozconfig.android-aarch64`](mozconfig/mozconfig.android-aarch64) —
  build configuration.
- [`scripts/`](scripts/) — fetch/patch/build/sign/verify pipeline;
  `build-helper.sh` builds the standalone Crash Helper APK.
- [`helper/`](helper/) — standalone Crash Helper app sources
  (`org.palemoon.crashhelper`).
- [`docs/`](docs/) — archaeology, porting plan, and honest status.

## Licensing & attribution

- UXP/Goanna and the recovered Android sources are **MPL-2.0** code
  Copyright © Moonchild Productions / Mozilla contributors.
- "New Moon" is an unofficial community branding used for continuity with
  the community Android build lineage; this project is **not affiliated
  with or endorsed by Moonchild Productions**.
- Upstream removed Android support deliberately
  ([UXP issue #1053](https://repo.palemoon.org/MoonchildProductions/UXP/issues/1053));
  this is a downstream-only effort. Staging history lives at
  [CommunityPokeOrg/pale-moon-android](https://github.com/CommunityPokeOrg/pale-moon-android).
