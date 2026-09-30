# Archaeology: Pale Moon for Android (pm4a) in upstream history

## Verdict

**Yes — a real Android browser existed upstream.** "Pale Moon for Android"
was a Fennec-derived app maintained inside the monolithic Pale-Moon tree
(roughly 2014–2017 releases), and its code remained in the tree — no longer
built or shipped — until it was deleted piecemeal between 2018 and 2021.

This is open source (MPL 2.0) and fully recoverable; it is *not* a usable
modern port — it predates ~8 years of UXP platform changes.

## Where the code lived

The Android surface was distributed across the tree, not in one module:

- `mobile/android/` — the entire app frontend (Java + JNI + chrome + branding)
- `widget/android/` — platform widget layer
- `mozglue/android/` — APKOpen loader, JNI glue
- `gradle/`, `build/mobile/`, `build/annotationProcessors/` — Java/JNI build
- `hal/android/`, `dom/system/android/`, `dom/gamepad/android/`,
  `dom/media/platforms/android/`, `dom/plugins/base/android/`,
  `dom/xbl/builtin/android/`, `image/decoders/icon/android/`
- scattered `MOZ_WIDGET_ANDROID` / `ANDROID` conditionals throughout the tree

## Timeline (upstream commits, verified against repo.palemoon.org)

| Date | Repo | Commit | What happened |
|---|---|---|---|
| 2015-05-27 | Pale-Moon | `1539e283` | "Switch Fennec to Goanna" — Android frontend rides along on the Goanna engine |
| 2015–2017 | Pale-Moon | many | pm4a maintenance commits (about: pages, sync server → palemoon.org, J-PAKE, toolbar UX) |
| 2016-09-01 | Pale-Moon | `3d8ce1a1` | Tycho (27.x) base import — `mobile/` carried forward |
| 2018-02-03 | UXP | `8b8c6507` | "Purge b2g/" (Firefox OS, related but separate) |
| 2018-05-12 | UXP | `cfe5ef4a` | Remove Gonk: `widget/gonk`, `hal/gonk`, `dom/system` gonk parts (355 files) |
| 2019-04-23 | UXP | `abe80cc3` | **Issue #1053 part 1a: Remove `mobile/android` + `mobile/locales` (3,891 files)** |
| 2019-12-14 | Pale-Moon | `846fcb45` | "Remove Platform Code" — app/platform repo split; Pale-Moon repo keeps only `palemoon/` |
| 2020-02-20 | UXP | `18e74277` | Issue #1053: Remove `widget/android` (73 files) |
| 2020-02-22 | UXP | `5496e4f3` | Issue #1053: Remove Android support from mozglue |
| 2020-02-23 | UXP | `81089ff0` | Issue #1053: Remove Android support from hal |
| 2021-03-11 | UXP | `cc8a7e3f` | Issue #1053: Remove `MOZ_WIDGET_ANDROID` and `IDB_MOBILE` |
| 2021-10-14 | UXP | `39f9ab37` | Issue #1053: Remove `/dom/system/android` and dependent modules, robocop |
| 2021-10-14 | UXP | `28a3cd10` | Issue #1053: "First pass" — Android defines, annotation processors |
| 2021-10-16 | UXP | `426a9755` | Issue #1053: Android systrace, more build-system removals |

## What survived in current UXP master (verified)

- **NSPR** (`nsprpub/`): full Android cross-compile support — but written for
  GCC-era standalone toolchains (NDK ≤ r18). Works again after
  `patches/0001-nspr-modern-android-ndk.patch` (verified: builds aarch64 .so).
- **Vendored libraries**: cubeb (`cubeb_opensl.c`, `cubeb_audiotrack.c`),
  Skia (`SkFontMgr_android`), libyuv, dav1d, ANGLE EGL, libevent, nrappkit,
  NSS coreconf `Linux.mk` android section.
- `mozglue/linker/` — the custom ELF linker originally written for Android —
  survived (its Android-specific bits were trimmed in `5496e4f3`).
- The `uikit` widget backend — an incomplete iOS port proving the "mobile
  backend" pattern exists in-tree.

## What was removed (the porting surface)

Everything in `vendor/uxp-android/` **plus** the build plumbing that wired
it up (`--with-android-ndk` and friends at top level, `MOZ_ANDROID_*`
configure options, `mobile/android` as `--enable-application` target,
`MOZ_FENNEC` handling, packaging/installer rules). Plus ~8 years of
platform drift on every interface it touches (widget, gfx/layers,
APZ/e10s, media, JS engine).

## Baseline used for vendoring

`63295d0087eb58a6eb34cad324c4c53d1b220491` (UXP, 2019-04-23) — parent of the
`mobile/android` removal; the most modern tree state containing the complete
Android app code.
