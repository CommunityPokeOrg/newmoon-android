# Porting assessment: Pale Moon on Android

## Bottom line

A full Pale Moon port to Android is a **large, multi-phase engineering
program**, not a build flag. The historical pm4a implementation is real and
recovered (see `vendor/` and `ARCHAEOLOGY.md`), but it is an m-esr52-era
(≈Firefox 52/Goanna-2017) codebase. Current UXP has had ~8 years of widget,
compositor, IPC and build-system change since it was deleted. This
repository holds the recovered source plus the first *verified* slices of
revival work; it is not a browser and cannot render a page today.

## Architecture the historical port used

```
mobile/android (Java app, Fennec-derived)
   └─ JNI: GeneratedJNIWrappers / GeckoAppShell / EventDispatcher
        └─ mozglue/android: APKOpen → loads libxul.so/libmozglue.so from APK
             └─ widget/android (nsWindow, nsAppShell over ALooper/ANativeWindow)
                  └─ UXP core (Gecko/Goanna), single-process chrome,
                     compositor via AndroidCompositorWidget + GL/EGL
```

Key integration surfaces a revival must restore or rewrite:

1. **Target recognition** — `config.sub`/`config.guess` and
   `moz.configure` canonicalization must accept `*-linux-android*`.
   (Restored — `patches/0002`.)
2. **NDK toolchain wiring** — `--with-android-ndk` plumbing. The
   2019 version assumed GCC standalone toolchains + `platforms/android-N`
   layout (removed in NDK r19); revived `android-ndk.configure` now prefers
   the modern unified LLVM toolchain (`toolchains/llvm/prebuilt`).
   (Partially restored — `patches/0002`; compiler/binutils selection for the
   *whole* tree is still TODO — there is no single GNU-style tool prefix in
   modern NDKs.)
3. **mozglue/android** — APKOpen + JNI glue. Restorable with modest edits;
   the `mozglue/linker` custom loader survives in master.
4. **widget/android** — the real port. ~70 files: `nsWindow`, `nsAppShell`
   on `ALooper`, `AndroidCompositorWidget`, input → `WidgetTouchEvent`,
   LookAndFeel, GfxInfo, clipboard, IM/event handling. The 2019 sources are
   the base; they must be re-landed and reconciled with the current
   `nsIWidget`/compositor interfaces.
5. **Compositing/display** — EGL on `ANativeWindow`/`Surface`, GL context
   sharing, SurfaceTexture for video. ANGLE-Android bits survive in tree.
6. **hal/dom system glue** — sensors, power/wakelock, network-info,
   vibration, gamepad, MediaCodec decoders.
7. **App frontend** — `mobile/android` is a complete Fennec-era UI
   (Java `base/`, `geckoview/`, branding `official`, services). It targets
   old Android SDK (build-tools ~23-era) and needs a Gradle/SDK
   modernization pass even after the engine works.
8. **JS engine** — SpiderMonkey has aarch64/arm64 JIT in tree; needs the
   Android target wired through `js/moz.configure` (shares
   `init.configure` — patch 0002 helps).
9. **NSS/security** — NSS coreconf still knows Android; Java-side
   `NativeCrypto`/`NSSBridge` exists in mozglue/android.
10. **Packaging** — APK assembly (omni.ja in assets, `.so` packaging,
    manifest generation, signing) lived under `mobile/android/installer`
    + `build/mobile` tools.

## Realistic scope ladder

| Level | Deliverable | Status |
|---|---|---|
| L0 | Repo: recovered source + archaeology + plan | **done** |
| L1 | Configure plumbing: `*-linux-android` target recognized | **done** (patch 0002) |
| L2 | NSPR cross-build for aarch64 with NDK r19+ | **verified** (patch 0001 → `libnspr4.so` etc.) |
| L3 | mozglue (+custom linker) cross-build | not started |
| L4 | `js` (SpiderMonkey) cross-build | not started |
| L5 | `widget/android` re-landed + `hal`/`dom` glue compiles | not started |
| L6 | Headless `libxul.so` for aarch64 | blocked on L3–L5 |
| L7 | Minimal activity: EGL surface + URL bar | far |
| L8 | Feature-parity pm4a app | far |

## Biggest risks / unknowns

- **e10s/process model**: 2019-era code predates UXP's current remote
  compositing assumptions; widget/gfx reconciliation is the deepest work.
- **Bionic vs glibc**: xpcom, NSS, JIT, sandboxing carry glibc assumptions;
  residual `#ifdef __ANDROID__` coverage is thin (36 files, mostly vendored).
- **SDK/API drift**: the Java frontend targeted ~API 23-era SDK; modern
  Play requirements (targetSdk, 64-bit, permission model, Scoped Storage)
  all need work.
- **Upstream stance**: UXP dropped Android deliberately; this port lives
  downstream-only. Expect zero upstream support.

## Reproduce the verified slice

```sh
scripts/fetch-upstream.sh        # clones pinned UXP + Pale-Moon into upstream/
scripts/apply-patches.sh         # applies patches/ to upstream/uxp
scripts/build-nspr-android.sh    # configures+builds nsprpub for aarch64
```

Requires: Android NDK r19+ (`ANDROID_NDK` env var), autoconf-era build tools.
