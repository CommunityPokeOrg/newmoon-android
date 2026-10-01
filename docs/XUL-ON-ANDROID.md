# XUL/XBL on Android — candid assessment

Status of what is verified on-device vs. what is planned, and an honest
appraisal of how well UXP's XUL frontend maps to Android. Verified claims
below were checked on an Android 34 x86_64 emulator running the arm64 APK
under ndk_translation, on the current `main` tree.

## What "XUL on Android" actually means here

Two different things are often conflated:

1. **The platform executes XUL/XBL.** The Goanna/UXP platform — the XUL
   parser, prototype cache, XBL binding engine, chrome registry, XPConnect
   script layer — runs inside libxul regardless of platform. On Android this
   is the same code as desktop Pale Moon.
2. **The visible browser UI is XUL.** On desktop Pale Moon the entire chrome
   (toolbox, tab strip, menus, status bar) is a XUL document
   (`browser/chrome/content/browser.xul` + ~700 files of XUL/XBL/JS).
   On Fennec (Firefox for Android, which this port derives from) the visible
   UI is **Java widgets**; the XUL window is a 20-line shell
   (`mobile/android/chrome/content/browser.xul`) containing only a
   `<deck id="browsers">` that hosts `<browser>` elements for tab content.

This port currently ships the Fennec chrome: Java toolbar/tabs/menus + the
minimal XUL deck. It is **not** the desktop Pale Moon UI.

## Verified working (on-device)

- `XRE_mainRun` completes: component/manifest/XPT registration, profile
  init, prefs (goanna.js), hidden window, `appstartup-run`, event loop
  steady state.
- The XUL document pipeline works: `browser.xul` is parsed and cached in
  the XUL prototype cache (`xulcache/` entries in the profile's
  startupCache), and XBL bindings are compiled and cached (`xblcache/`
  entries for toolkit bindings — scrollbar/popup/general).
- Chrome chrome.manifests + chrome:// resolution work from omni.ja
  (Omnijar was previously broken on Android — see STATUS.md).
- The Java frontend fully works: BrowserApp, GeckoView pipeline, tab
  management, VIEW intents create real tabs (URL bar updates, throbber).
- NSS initializes; TLS code paths load.

## Verified since first writing this doc

- **Content pages complete loads, HTTP and HTTPS** (2026-10-01): a VIEW
  intent to `http://neverssl.com` ran the full tab lifecycle
  (`START → TITLE → LOCATION_CHANGE → SECURITY_CHANGE → PAGE_SHOW →
  STOP → FAVICON → THUMBNAIL`) including a followed redirect chain;
  `https://example.com` completed the same cycle over TLS. The earlier
  "docshell never starts a navigation" finding was an observability
  artifact: libxul's bundled liblog silently discarded all native
  logging (see STATUS.md root cause 3), so the working pipeline was
  invisible.
- Chrome JS now starts without functional errors: every console error
  surfaced after `browser.xul` load was root-caused and fixed
  (unpreprocessed `browser.js`, missing `Services.androidBridge` /
  `Services.telemetry`/`UITelemetry`, parental-controls, missing
  tracking prefs, packaging drift, unguarded imports of UXP-deleted
  modules).

## Still not verified

- User interaction: the emulator wedges its input dispatch under
  ndk_translation load (system_server ANRs), so taps/keys cannot be
  tested. Interactive verification needs a real arm64 device.
- First paint of rendered web content in the LayerView surface
  (navigation events and THUMBNAIL captures fire; pixels behind the
  persistent system notice dialog are unconfirmed).

## How well does XUL map to Android?

**Platform layer — maps well.** XUL parsing, XBL, chrome:// URIs,
omni.ja packaging, startup caches, and real content navigations all
work; every root-cause bug fixed to date was packaging/gating/observability,
not an architecture mismatch. Rendering goes through the same layer
pipeline as desktop; Android supplies an nsWindow + Compositor via
LayerView/OpenGL instead of a native window. There is no fundamental
reason a full XUL chrome cannot render inside that surface.

**Desktop Pale Moon chrome on Android — feasible but not free.**
`palemoon/app` (the desktop `browser/` tree) is largely platform-agnostic
XUL/JS, but assumes:

- a native menu bar / app menus (Android has none — would need a XUL
  overflow menu or mapping to the Android menu button),
- keyboard shortcuts and hover (Android: software keyboard, no hover —
  hover-dependent XBL `:hover` behaviors degrade),
- resizable top-level windows and multiple windows (Android: one
  activity = one surface),
- desktop widget theme glue (native theme drawing expects a desktop
  widget backend; needs a `LookAndFeel`/native-theme stub or Android
  theme — Fennec solved this with `-moz-appearance` overrides),
- window size/layout assumptions — desktop chrome is not responsive;
  at phone widths the toolbox is unusable without adaptation. On a
  tablet/foldable it is considerably more reasonable.
- `window.open` / dialog machinery → would need Android activity or
  in-chrome substitution.

The pragmatic path to a "real Pale Moon UI" on Android is: keep the
Fennec Java shell for Android OS integration (intents, lifecycle,
downloads, notifications) and load the desktop `browser.xul` chrome into
the LayerView surface as the app's XUL window — i.e., what Fennec does
with its minimal XUL doc, but pointing at the Pale Moon chrome and adding
the missing widget/theme/menu glue. Session estimate: roughly 1–3
sessions of focused work on top of a working content-load pipeline, with
menu/input/theme adaptation being the hard part.

## Current stance

- Shipping now: full Goanna/UXP platform + Fennec mobile chrome
  (Java UI + minimal XUL document) + New Moon branding.
- Target: desktop Pale Moon chrome rendered in-chrome, gated on (a)
  content loads verified working and (b) widget/theme glue.
- Honest bottom line: XUL itself runs fine on Android — the Fennec XUL
  document, its bindings, its chrome JS, and real page loads all work.
  The open work is compositor pixel-verification, interactive input,
  and desktop-chrome substitution — none of which are architectural
  blockers.
