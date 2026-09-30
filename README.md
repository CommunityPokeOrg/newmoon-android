# Pale Moon for Android — port revival staging

**Status: NOT a working browser.** This repository is the staging ground for
reviving Pale Moon ("pm4a") on Android. It contains the recovered historical
Android source, the archaeology of where it lived upstream, the first
verified build-system slices of the port, and an honest assessment of the
remaining work. See [`docs/STATUS.md`](docs/STATUS.md).

## What's here

- [`vendor/uxp-android/`](vendor/README.md) — the complete historical
  Android implementation recovered from UXP @ `63295d00` (Apr 2019, last
  tree before the Fennec/Android removal, upstream Issue #1053):
  `mobile/android` (the Pale-Moon-branded Fennec-derived app incl. early
  GeckoView), `widget/android`, `mozglue/android`, `hal/android`,
  `dom/system/android`, `gradle/`, `build/mobile`,
  `build/annotationProcessors`, and the other `*/android` backend dirs.
- [`patches/`](patches/) — port groundwork for current UXP master:
  - `0001` — NSPR modern-NDK (r19+/clang unified toolchain) support.
    **Verified:** cross-compiles to `aarch64-linux-android` .so libraries.
  - `0002` — Android build-system plumbing: `*-linux-android*` target
    triples, `cairo-android` toolkit choice, NDK configure includes, and
    moz.build wiring for the restored dirs.
- [`docs/ARCHAEOLOGY.md`](docs/ARCHAEOLOGY.md) — where the Android code
  lived, the removal-commit timeline, and what survives upstream today.
- [`docs/PORTING.md`](docs/PORTING.md) — architecture, gap analysis, and a
  realistic scope ladder (L0 → L8).
- [`scripts/`](scripts/) — pinned upstream fetch + patch/overlay + the
  verified NSPR build.

## Truthful scope statement

- The historical Android browser existed and its full source is recovered —
  it is MPL-2.0 upstream code, not a fork of a lost artifact.
- That code is ~8 years of platform drift behind current UXP. Re-landing it
  is a phased port, not a flip of a build flag.
- **Verified working today:** Android target recognition + NSPR
  cross-compile for aarch64 with a modern NDK.
- **Not working / not yet attempted:** the C++ engine (libxul) for Android,
  the widget/compositor layer, and the Java app itself. There is no APK and
  nothing to install.

## Upstream references

- UXP: https://repo.palemoon.org/MoonchildProductions/UXP
- Pale-Moon: https://repo.palemoon.org/MoonchildProductions/Pale-Moon

This is a downstream-only effort; upstream removed Android support
deliberately (Issue #1053).
