# Status

_Last updated: 2026-09-30 (APK installs and launches on an Android 34 emulator; mozglue's custom linker loads libxul + all deps and runs static initializers; currently crashes inside libxul's static-init phase — see below)._

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
- **`./mach build` completes end-to-end.** All C++ (incl. `libxul.so`,
  ~19 MB), `libmozglue.so` (shared, with `BionicGlue.cpp`), and all Java
  jars (gecko-browser, gecko-util, geckoview, sync/etc. restored from
  mozilla/gecko-dev esr52, stumbler, bouncer, constants, thirdparty)
  compile for aarch64-android. Fennec JNI wrappers were regenerated via
  `make -C obj-android-aarch64/mobile/android/base FennecJNIWrappers.cpp`
  then `make update-fennec-wrappers`.
- **`./mach package` produces a signed, installable APK:**
  `upstream/obj-android-aarch64/dist/fennec-52.6.0.linux-android-aarch64.apk`
  (~33 MB, 1536 entries). Verified by inspection:
  - `lib/arm64-v8a/`: `libmozglue.so`, `libplugin-container.so` (Fennec
    layout: only the custom-linker loader libs live under lib/).
  - `assets/arm64-v8a/`: `libxul.so` + all NSS/NSPR/sqlite/etc. —
    extracted and loaded at runtime by mozglue's custom linker.
  - `classes.dex` (~7.5 MB, produced by d8), `assets/omni.ja` (~6.4 MB,
    includes `chrome/chrome/content/browser.xul` + 38 XUL/XBL files —
    the full Fennec XUL frontend), 1183 `res/` drawables.
  - `apksigner verify --verbose --print-certs`: **Verifies** with
    v1+v2+v3 schemes (CN=Android Debug cert from `~/.android/debug.keystore`).
  - aapt badging: package `org.mozilla.fennec_ubuntu`, versionName
    `52.6.0`, minSdk 15, targetSdk 23.

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

## What patch 0004 changes (build + packaging bring-up)

- `mozglue/build/moz.build`: builds `libmozglue.so` as a shared library on
  Android (was WINNT/Darwin only) and adds `BionicGlue.cpp`.
- `upload-files.mk`: `MOZ_PKG_FORMAT = APK` for the android widget toolkit;
  `upload-files-APK.mk` restored to vendor — drives
  `mozbuild.action.package_fennec_apk`.
- `old-configure.in`: `OMNIJAR_NAME = assets/omni.ja` when
  `MOZ_BUILD_APP=mobile/android` (required by the APK packager).
- `config/makefiles/java-build.mk` + `mobile/android/base/Makefile.in`
  (vendor): dexing switched from dx to **d8** (build-tools 34 has no dx;
  d8 needs an existing output dir and jar/class-file inputs).
- `config/android-common.mk` (vendor): `RELEASE_SIGN_ANDROID_APK` now does
  zipalign **then** `apksigner sign` with the standard debug keystore
  (v1+v2+v3); the old jarsigner path produced APKs that fail `apksigner
  verify` on API 15–20.
- `mobile/android` proguard cfgs (vendor): `-dontwarn com.google.android.gms.**`
  (play-services-ads 8.4.0 references WebSettings AppCache APIs removed in
  API 28).
- `python/mozbuild/mozpack/files.py`, `recursivemake.py`, `emitter.py`,
  `generate_browsersearch.py`: py3 str/bytes fixes for the packaging path.
- `config/config.mk`: `-static-libstdc++` for `OS_TARGET=Android` — the
  custom linker loads packaged .so files itself, so nothing may need
  `libc++_shared.so` (same approach as the 2019 port).
- `mobile/android/installer/package-manifest.in` (vendor):
  `libhunspell.so` added to `assets/` — it is a `NEEDED` dep of libxul
  and its absence aborted the libxul load.
- `mozglue/linker/XZStream.cpp`: `ParseUncompressedSize()` now sums **all**
  index records instead of reading only the first. Modern `xz -T` writes
  multi-block streams (4 blocks for libxul); only the first block's size
  was used, producing a 25 MB cache file for an 80 MB libxul → SIGBUS on
  segment mapping. Fixed file now decompresses fully (verified in logcat:
  `XZStream decoded 80352792`).
- `mozglue/linker/Elfxx.h`: aarch64 `R_AARCH64_ABS64/GLOB_DAT/JUMP_SLOT/
  RELATIVE` constants for the custom linker.
- Remaining C++ interface fixes across `dom/plugins/ipc`, `ipc/chromium`,
  `hal`, `widget`, `gfx`, `netwerk`, `security`, `toolkit`, `xpcom`,
  `memory/jemalloc`, `mozglue/linker` to reconcile the 2019 Android code
  with current UXP.
- API-34 javac collisions renamed in vendor:
  `RemotePresentationService.getDeviceId()` → `getPresentationDeviceId()`
  (clashes with `ContextWrapper.getDeviceId():int`), and
  `BouncerService.getDataDir()` → `getAppDataDir()` (clashes with
  `Context.getDataDir():File`).

## Environment dependencies (build machine)

- Android SDK 34 + NDK `27.2.12479018`, JDK 17.
- **ProGuard is no longer in the SDK.** A 6.2.0 `proguard.jar` must be at
  `$ANDROID_HOME/tools/proguard/lib/proguard.jar` (obtained here from the
  Ubuntu `libproguard-java` deb).
- `~/.android/debug.keystore` (alias `androiddebugkey`, pass `android`)
  is created automatically by the signing rule if absent.
- Vendored AARs under `$ANDROID_HOME/extras/{android,google}/m2repository/`
  (incl. play-services-*-8.4.0) — see `scripts/` for install steps.

## Runtime status (verified on emulator, Android 34 / x86_64 + ndk_translation)

AVD `nocturne-emu` (google_apis x86_64, abi list includes arm64-v8a via
ndk_translation). No /dev/kvm → TCG software CPU, cold boot ~8 min.

- `adb install -r dist/fennec-52.6.0.linux-android-aarch64.apk`: succeeds.
- `am start -n org.mozilla.fennec_ubuntu/.App`: **the app launches.**
  Java frontend verified working end-to-end: LauncherActivity → BrowserApp,
  profile migration, preferences, network listener, search engine manager,
  and the home screen UI renders (GLES/EGL).
- mozglue's custom linker on aarch64 works: decompresses every xz'd
  library (incl. 80 MB libxul), resolves all relocations
  (`ABS64/GLOB_DAT/JUMP_SLOT/RELATIVE`), loads NSS/NSPR/sqlite/hunspell/etc.,
  and begins running libxul's C++ static initializers.
- `MOZ_LINKER_ONDEMAND=0` (eager page mapping) is required on this
  emulator — without it the run dies earlier with `SEGV_ACCERR` on the
  main thread; the fault-handler-based lazy-page path is untested on
  real arm64 hardware.
- **The app reaches steady state.** `XRE_mainRun` completes end to end:
  omni.ja component/xpt registration, directory-provider startup, chrome
  manifest registration, profile prefs, `profile-after-change`, chrome
  window creation via `nsWindowWatcher::OpenWindow` (`browser.xul`
  loads — `nsWebShellWindow::JustCreateWebShell` → docshell →
  `CreateAboutBlankContentViewer` → XPConnect globals wrapped →
  `loadURI` rv=0), hidden window, `final-ui-startup`,
  `appstartup-run`, and the Gecko event loop then idles in
  `epoll_wait`. Verified on cold (`pm clear`) and warm launches; the
  warm launch renders Top Sites with real bookmark data (profile DB
  works). Screenshot-verified, not just logcat.
- The earlier `SIGSEGV@0` in libxul static-init was actually two
  packaging/config bugs, both now fixed (see below); the stagefright
  frame turned out to be a red herring (first `.init_array` entry to
  trip the pref service, not the culprit).
- Remaining emulator issue (environmental): `system_server` and the app
  both ANR under ndk_translation load during startup
  ("Timed out while trying to bind" / broadcast timeouts on
  `MY_PACKAGE_REPLACED`). The ANR dialogs block input dispatch, so
  interactive verification (typing a URL, clicking links) is not
  possible on this emulator. system_server keeps making progress
  (not deadlocked); a real arm64 device is unlikely to exhibit this.
- Emulation caveat: everything above runs under ndk_translation
  (arm64→x86_64). `lldb-server`/gdbserver cannot run inside it, so
  native debugging is limited to logcat instrumentation.

## Runtime root causes found and fixed (this bring-up)

1. **Omnijar never initialized on Android** — `XRE_InitCommandLine` in
   `toolkit/xre/nsAppRunner.cpp` only calls `mozilla::Omnijar::Init`
   when `UXP_CUSTOM_OMNI` is set; esr52 processed `-greomni`
   unconditionally. Without it the gre `omni.ja`'s
   `chrome.manifest`/`components.manifest`/xpt never register → every
   XPConnect wrap fails → the first content window cannot be created.
   Fixed with a `MOZ_WIDGET_ANDROID` conditional (patch 0004).
2. **`goanna.js` missing from packaged omni.ja** —
   `mobile/android/installer/package-manifest.in` still listed esr52's
   `@BINPATH@/greprefs.js`; UXP renamed it to `goanna.js`. The packager
   only warns about missing manifest entries, so this was silent in the
   log. Missing goanna.js → `pref_ReadPrefFromJar` fails →
   `Preferences::Init` fails → `gCacheData`/`gObserverTable` never
   allocated → `SIGSEGV@0` in `AddBoolVarCache` during
   `nsIOService::Init`. Fixed by renaming the manifest entry (in
   `vendor/uxp-android/mobile/android/installer/package-manifest.in`).

## Unverified / partial (honest caveats)

- **No user interaction verified.** The app reaches steady state and
  renders, but taps/typing are untested because the emulator's
  `system_server` ANR dialogs block input dispatch (see above).
  Actual web page loading from user input is unverified.
- **Compositor depth is partially verified.** `nsWindow`,
  `nsAppShell`, and the docshell/viewer path all execute (window
  creation + `loadURI` succeed), but pixel-level compositing of XUL
  chrome beyond the first-run/home screens is not yet confirmed.
- Branding is still Fennec: package `org.mozilla.fennec_ubuntu`,
  label "Fennec", APK filename `fennec-52.6.0.linux-android-aarch64.apk`.
  No Pale Moon branding/product-name pass has been done.
- Signed with the auto-generated **debug** key only.
- The UI is the **Fennec-derived pm4a mobile frontend** (XUL/XBL chrome:
  `browser.xul` + bindings inside omni.ja) on the full Goanna/UXP
  platform — not the desktop Pale Moon browser chrome. XUL does map to
  Android here: the XUL/XBL frontend ships, its window instantiates at
  runtime, and the chrome JS runs — the gap to "full Pale Moon UI" is
  the chrome content itself (mobile chrome vs `browser/` desktop
  chrome), not the XUL platform.
- Support-library AAR `extra_jars` that resolve to `None` are still
  filtered in the backend; androidx/Gradle frontend rework not done.
- `ANDROID_TOOLS` maps to the SDK `emulator/` dir (no `tools/` dir in
  modern SDKs).
- Two benign packaging warnings remain: "nothing matches overlay file
  `sync_avatar_default.png`/`sync_promo.png`" — the drawables still land
  in the APK.
- Crash-path diagnostics remain in the tree (Android-gated):
  `mozglue/linker/ElfLoader.cpp` (`moz_pmlog` export),
  `mfbt/Assertions.cpp` (assert → logcat), `memory/mozalloc` abort →
  logcat. They log only on crashes/fatals and are worth keeping until
  first interactive use is stable; all temporary instrumentation has
  been removed.

## Known missing pieces (next work)

1. **Interactive verification on a real arm64 device** (or a faster
   emulator host): drive URL loading, link navigation, and page
   rendering past the home screen; watch for ndk_translation-specific
   behavior that won't reproduce on hardware.
2. Pale Moon branding/product pass (app name, package id, APK filename,
   `MOZ_APP_*` branding) — currently Fennec.
3. Java frontend SDK modernization (targetSdk, Gradle 8, API 34) —
   replace make-driven javac/aapt + support libs with androidx + Gradle.
4. Desktop Pale Moon browser chrome (`browser/` XUL) on Android, if the
   mobile Fennec chrome is deemed insufficient for the "full Pale Moon
   UI" goal — large effort; the Fennec chrome is already XUL/XBL and
   fully executing.
5. Release signing path + l10n/crashreporter overrides audit.
