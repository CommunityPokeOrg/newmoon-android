# Status

_Last updated: 2026-09-30 (initial revival commit)._

## Verified

- **Historical source recovered.** `vendor/uxp-android/` was extracted from
  UXP @ `63295d0087eb58a6eb34cad324c4c53d1b220491` — confirmed to contain the
  full pm4a surface: `mobile/android` (incl. `geckoview/`, branding
  `official`/`unofficial`), `widget/android`, `mozglue/android`, `gradle/`,
  `build/mobile`, `build/annotationProcessors`, `hal/android`,
  `dom/system/android`, `dom/gamepad/android`,
  `dom/media/platforms/android`, `dom/plugins/base/android`,
  `dom/xbl/builtin/android`, `image/decoders/icon/android`,
  `media/libyuv/util/android`, `media/webrtc/trunk/build/android`.
- **Android triples canonicalize.** `build/autoconf/config.sub` now maps
  `aarch64-linux-android`, `arm-linux-androideabi`, etc. (patch 0002).
- **NSPR cross-builds.** With `patches/0001-nspr-modern-android-ndk.patch`
  applied and Android NDK r27, `nsprpub` configures and compiles for
  `aarch64-linux-android` (API 21). Output: `libnspr4.so`, `libplc4.so`,
  `libplds4.so` — ELF 64-bit ARM aarch64 shared objects.
  Reproduce: `scripts/build-nspr-android.sh`.

## Unverified / partial (honest caveats)

- Patch 0002 (moz.configure plumbing, toolkit choice, moz.build wiring) is
  reviewed but **not** exercised through a full `./mach configure` run —
  the UXP configure needs a python2-era mach environment; the python-level
  canonicalization and config.sub shell paths were verified directly.
- `build/moz.configure/android-ndk.configure` detects the modern unified
  LLVM NDK, but whole-tree compiler/binutils selection for it is **not**
  wired (modern NDKs have no single GNU-style tool prefix). Old GCC-era
  paths are kept as fallback.
- The vendored 2019 code will not compile as-is against current UXP
  interfaces; it is the porting base, not working code.
- No APK exists. Nothing renders. No device/emulator testing has been done.

## Known missing pieces (next work)

1. mozglue/android + custom linker cross-build (L3).
2. Standalone `js` (SpiderMonkey) aarch64-android build (L4).
3. widget/android re-land + reconcile with current nsIWidget/compositor (L5).
4. Top-level `--with-android-*` option parity with old-configure
   (`MOZ_ANDROID_*` defines, packaging vars).
5. Java frontend SDK modernization (targetSdk, Gradle 8, API 34).
