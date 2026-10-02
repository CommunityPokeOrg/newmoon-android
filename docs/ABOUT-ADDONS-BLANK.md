# Diagnosis: blank `about:addons`

_Reported by @wolfybl: about:addons opens to a blank page. Investigated
against release `newmoon-52.6.1` on an Android emulator (API 37, 16 KB
page kernel, arm64-v8a via translation)._

## Verdict

**The add-ons manager UI exists, loads, and executes correctly.** The
blank screen is not a missing or broken add-ons page — it is a
presentation-layer failure: Gecko composites the page (real pixels are
produced), but the live `LayerView` SurfaceView never shows them.
Every tab page — not only `about:addons` — displays the same white
area. `about:addons` just happened to be the reported symptom.

## Evidence chain

### 1. The routing chain is intact (static)

- `docshell/base/nsAboutRedirector.cpp` `kRedirMap` maps
  `about:addons` → `chrome://mozapps/content/extensions/extensions.xul`
  (verified identical to upstream UXP and mozilla-esr52).
- `mobile/android/chrome/jar.mn` overrides that URI →
  `chrome://browser/content/aboutAddons.xhtml`.
- `aboutAddons.xhtml`, `aboutAddons.js`, `aboutAddons.dtd`,
  `aboutAddons.properties`, `aboutBase.css`, `aboutAddons.css` are all
  packaged inside `assets/omni.ja` in the shipped APK.
- `AddonManager.jsm`, `XPIProvider.jsm`, `PluginProvider.jsm` compile
  and execute at runtime (visible via the PMJCL jar-loader logs).

### 2. The page executes end-to-end at runtime (instrumented)

The app probes `file:///data/user/0/<pkg>/chrome.manifest` at startup
(visible in logcat), so an instrumentation override was installed
without rebuilding:

```
override chrome://browser/content/aboutAddons.js \
  file:///data/user/0/org.palemoon.community/files/aboutAddons.js
```

The instrumented copy logs via `Cu.reportError` → `GeckoConsole`.
Observed on load of `about:addons`:

- script executes (`aboutAddons.js executing`),
- `document.title = "Add-ons"` — the XHTML **and** all three DTDs
  (brand/global/aboutAddons) parsed, entities resolved,
- `init()` completes, `ContextMenus.init` OK,
- `AddonManager.getAllAddons` returns successfully with **0 addons**
  (fresh profile; only system add-ons exist, which the page filters),
- zero JavaScript errors from the page.

The tabs-tray thumbnail for the Add-ons tab shows the rendered header
**"Your Add-ons"** with its styled separator — i.e. layout, CSS and DOM
population all produced real pixels.

### 3. What is actually blank

The live `LayerView` SurfaceView shows a white area for **every** tab
(`about:addons`, `about:config`, `about:downloads`, `example.com`),
while the same tabs' thumbnails in the tabs tray show correctly
rendered content. The compositor produces frames; they never reach the
screen. `dumpsys SurfaceFlinger` shows the SurfaceView BLAST layer
exists but has no presented buffer. No EGL/GL attach logging appears.

So the failure sits in the **compositor → SurfaceView presentation
handoff** (`LayerView`/`Compositor` → ANativeWindow path in
`widget/android`), not in the about:addons code path.

### 4. Caveat on device-vs-emulator

On the emulator, *all* tab content fails to present. If on a real
device ordinary web pages do display but `about:addons` alone stays
blank, that would contradict this model — but no content-side failure
exists for it to be: the page's document, script and data all
verified healthy. If a device shows the same white content area on
`example.com`, it is the same presentation bug.

Quick device check for anyone reproducing: open the **tabs tray** —
if the thumbnail shows the page rendered while the live view is
white, it is this presentation bug, not the page.

## Latent bug found during instrumentation

`aboutAddons.js` references `AddonManager.SIGNEDSTATE_MISSING`, which
**does not exist on UXP** (Pale Moon dropped add-on signing). It
evaluates to `undefined`, so `aAddon.signedState <= SIGNEDSTATE_MISSING`
is always `false` — the unsigned-add-on warning can never show. Guarded
in `vendor/uxp-android/mobile/android/chrome/content/aboutAddons.js`
in the accompanying commit; behavior is unchanged (no signing ⇒ never
warn), but the dead dependency is now explicit and safe.

## How to instrument page JS without a rebuild

Reusable recipe (used for this diagnosis):

1. `adb push` an instrumented copy of the page script into
   `/data/user/0/org.palemoon.community/files/`.
2. `adb push` a `chrome.manifest` into `/data/user/0/<pkg>/` containing
   `override chrome://browser/content/<file> file:///data/user/0/<pkg>/files/<file>`.
3. `chown` both to the app uid, `am force-stop`, relaunch, read
   `Cu.reportError` output via logcat tag `GeckoConsole`.

Remove `chrome.manifest` afterwards to restore packaged files.

## Follow-up (not in this commit)

Root-causing why the `LayerView` SurfaceView never presents
composited frames is the real fix for the blank screen. Suspects: the
`LayerView`/`Compositor` JNI handoff (`createCompositor`,
`syncResumeResizeCompositor`) and SurfaceView/BLAST behavior on modern
Android — `targetSdk` is 24 while the platform now runs SurfaceView
through BLAST, and this code predates BLAST. Investigation should start
at `widget/android/AndroidCompositorWidget.cpp` and the
`LayerView.updateCompositor()` path.
