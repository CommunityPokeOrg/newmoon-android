# Status

_Last updated: 2026-09-30 (full-tree configure now working)._

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
- **Full-tree `./mach configure` completes** for
  `--enable-application=mobile/android --target=aarch64-linux-android`
  with `--enable-noncomm-build --enable-default-toolkit=cairo-android`,
  NDK r27.2 (unified LLVM toolchain, API 21), Android SDK 34
  (build-tools 34.0.0: aapt/aapt2/d8/aidl/zipalign/apksigner), JDK 17.
  Reproduce: `cp mozconfig/mozconfig.android-aarch64 upstream/uxp/mozconfig`,
  `export ANDROID_HOME=<sdk> ANDROID_NDK=<sdk>/ndk/27.2.12479018`,
  then `cd upstream/uxp && ./mach configure`. 917 moz.build files read,
  6206 descriptors, RecursiveMake + FasterMake backends generated.

## What patch 0003 changes

- Restores `build/autoconf/android.m4` (modernized): `MOZ_ANDROID_NDK`,
  `MOZ_ANDROID_CPU_ARCH`, `MOZ_ANDROID_SDK` rewritten for unified LLVM NDK
  + modern SDK layout (d8/aapt2, `emulator/` dir); legacy support-AAR
  checks are gated off (those deps will come from androidx/Gradle later).
- Restores ~20 Android hunks in `old-configure.in` (toolchain flags,
  MOZ_LINKER, hash-style sysv, ANDROID_PACKAGE_NAME, gamepad, mozglue,
  TK_CFLAGS for `android` widget toolkit, `MOZ_ANDROID_SDK(34)` for
  `mobile/android`).
- Restores moz.build Android frontend machinery:
  `ANDROID_RES_DIRS`, `ANDROID_EXTRA_RES_DIRS`, `ANDROID_ASSETS_DIRS`,
  `ANDROID_EXTRA_PACKAGES`, `ANDROID_GENERATED_RESFILES`,
  `ANDROID_APK_NAME`, `ANDROID_APK_PACKAGE`,
  `ANDROID_INSTRUMENTATION_MANIFESTS` in `context.py`/`data.py`/
  `emitter.py`/`recursivemake.py`.
- `constants.py`: `Android` added to `OS` enum (required for
  `--enable-default-toolkit=cairo-android`).
- `java.configure`: `javah` optional (removed in JDK 10+);
  `javac_version` decoded as text.
- `icu.m4`: accepts clang as the ICU assembler (unified NDK has no GNU as
  and no yasm target flags on aarch64).
- `config/external/moz.build`: `modules/xz-embedded` builds when
  `MOZ_LINKER` (mozglue/linker needs it for szip APK decompression).
- `dom/base/moz.build`, `toolkit/modules/moz.build`: Android provides its
  own `SiteSpecificUserAgent.js` / `LightweightThemeConsumer.jsm` —
  gated like 2019's `MOZ_FENNEC` guards.
- `mobile/android`: GCM default off (no Play Services yet);
  `MOZ_NATIVE_DEVICES` unset; dead `imply_option`s removed; dead
  `mozilla.dtd` locale entries removed.
- New file `build/autoconf/android.m4` and `gradlew` shim live under
  `vendor/uxp-android/` (applied by overlay).

## Unverified / partial (honest caveats)

- `mach build` has **not** been run end-to-end; the vendored 2019 code
  will not compile as-is against current UXP interfaces. Configure
  completing means the build graph is coherent, not that C++/Java compiles.
- `ANDROID_TOOLS` maps to the SDK `emulator/` dir (no `tools/` dir in
  modern SDKs); `build/annotationProcessors` javac lint-jar references
  are inert until Gradle integration.
- Support-library AAR `extra_jars` that resolve to `None` are filtered
  in the backend as an interim measure; the real fix is the androidx/
  Gradle frontend rework.
- No APK exists. Nothing renders. No device/emulator testing has been done.

## Known missing pieces (next work)

1. `mach build` bring-up, subtree by subtree:
   mozglue/android + custom linker cross-build (L3), then js/ (L4).
2. widget/android re-land + reconcile with current nsIWidget/compositor (L5).
3. Java frontend SDK modernization (targetSdk, Gradle 8, API 34) —
   replace make-driven javac/aapt + support libs with androidx + Gradle.
4. APK packaging path (`ANDROID_APK_*` vars are declared; the packaging
   Makefile rules still reference dx/aapt-era flows).
5. l10n/crashreporter overrides audited as they surface.
