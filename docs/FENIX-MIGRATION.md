# "Migrate to Fenix" — feasibility assessment

_Requested by Wolfy after the blank `about:addons` page. Since
diagnosed: the add-ons manager UI exists and executes end-to-end — the
blank screen is a compositor→SurfaceView presentation failure that
affects every tab page, not an add-ons-UI gap (see
`docs/ABOUT-ADDONS-BLANK.md`). A frontend migration would not fix that
bug by itself; it lives below the UI layer. This document explains what
Fenix actually is, what "migrating" would mean for a UXP/Goanna
product, and which parts are feasible._

## What Fenix is

Fenix (Firefox for Android) is not a "frontend you bolt onto any
engine". It is a Kotlin application built on **Mozilla Android
Components** (`mozilla.components.*`) which drive the engine through
the **`org.mozilla.geckoview` API**:

- `GeckoRuntime` — the engine process singleton,
- `GeckoSession` — per-tab session object (`loadUri`, navigation /
  content / permission / progress / prompt / media delegates,
  `WebExtensionController`, `GeckoSessionSettings` incl. content
  blocking and cookie policy),
- `GeckoView` — the `org.mozilla.geckoview` display widget,
- `GeckoResult` — async result plumbing throughout.

Fenix's own feature work (add-ons management, tracking protection,
reader mode, downloads, logins, autofill, sessions store) is
implemented in Android Components **on top of those delegates**. The
components are engine-abstracted at `concept-engine`
(`Engine`/`EngineSession`/`EngineView`/`Settings`), which is the seam
a non-Gecko engine could in principle plug into.

## What this tree has instead

New Moon runs **Goanna/UXP** (ESR-52-era Gecko lineage) with the
recovered **Fennec** frontend:

- The engine-embedding surface is the old Fennec-internal
  `org.mozilla.gecko.GeckoView` widget (`vendor/uxp-android/mobile/android/geckoview/`):
  `GeckoView.addBrowser()`, `ChromeDelegate`/`ContentDelegate`,
  `EventDispatcher` + `GeckoAppShell` JNI messaging, `browser.js`
  XUL chrome doing tab management. The in-tree `geckoview_example/`
  app demonstrates exactly this model.
- There is **no** `org.mozilla.geckoview` package: no `GeckoRuntime`,
  no `GeckoSession`, no navigation/permission/prompt delegates, no
  `WebExtensionController`, no content-blocking settings, no
  `GeckoResult`. The "session" lives inside XUL chrome JS
  (`browser.js`/`Tabs`), not in a Java API object.

## The engine incompatibility

A real Fenix port requires the engine to implement the modern
GeckoView contract. That contract was built alongside Firefox's
Quantum-era embedding stack (e10s/PContent message managers, GPU
process, OOP compositor/APZ plumbing, WebExtensions `browser.*` on
content scripts, session-store format). Goanna/UXP diverged from
Gecko at ESR-52 and UXP deliberately does not track those layers —
porting them is not a frontend task, it is effectively "port Pale
Moon to modern Gecko", i.e. replacing the engine. That is
out-of-scope for this product: a UXP browser whose engine is replaced
by Gecko is, by definition, no longer a UXP browser — it is Firefox.

**So: "Fenix on UXP" cannot mean "Fenix UI driving Goanna via real
GeckoView" — that API does not exist in the engine and cannot be
backported without an engine-scale rewrite.**

## What is actually feasible

Ordered by increasing cost:

1. **Fix/complete Fennec frontend features** (the SurfaceView
   presentation bug from `docs/ABOUT-ADDONS-BLANK.md`, others) —
   keep the working engine + frontend, patch the frontend where
   features are stubbed. Hours-to-days each. Highest value per effort.

2. **Fenix-style UI inside the Fennec frontend** — refresh the Java
   UI layer (`base/java/org/mozilla/gecko/BrowserApp` etc.) with
   Material-style components, tabs tray, modern toolbar, while keeping
   XUL `browser.js` underneath. The Java↔chrome messaging
   (`EventDispatcher`, `Tabs`, `GeckoAppShell`) already exists, so
   this is a reskin, not an engine port. Feasible, bounded, and does
   not claim to be Fenix.

3. **`engine-uxp` Android-Components adapter (PoC)** — implement
   `concept-engine`'s `Engine`/`EngineSession`/`EngineView` over the
   Fennec `org.mozilla.gecko.GeckoView` + chrome JS messaging (precedent:
   Mozilla shipped `engine-webview` and `engine-servo` behind the same
   interfaces). With a *basic* subset (loadUri, navigation progress,
   title/url, goBack/Forward, stop/reload), Android Components UI
   pieces (feature-toolbar, browser-menu, feature-tabs, ui-* widgets)
   could render a Fenix-*looking* shell over Goanna.
   - **What's stubbed forever without engine work:** WebExtensions
     (`WebExtensionController` — i.e. Fenix's real add-ons flow),
     content-blocking categories, tracking-protection engine bits,
     GeckoView settings surface, prompt/permission delegate parity,
     media/fullscreen delegates, GeckoResult-based session state.
   - Cost estimate (Devin throughput): a compiling adapter + a demo
     Activity that loads a page ≈ 1 session; enough of Fenix's
     `BrowserFragment` graph to feel like Fenix ≈ several sessions;
     never reaches real Fenix feature parity because the engine API
     doesn't exist. This is a *proof of concept* — honest about being
     a shell over Fennec chrome, not Fenix.

4. **Real Fenix = engine replacement.** Not a migration of this
   codebase. Excluded.

## Recommendation

Treat "migrate to Fenix" as **frontend modernization**, not an
engine-port project:

- Fix the concrete UX breakages in the Fennec frontend (starting with
  the SurfaceView presentation bug behind the blank `about:addons` —
  see `docs/ABOUT-ADDONS-BLANK.md`). Migrating the frontend would not
  address this bug, so it should be fixed regardless of any migration
  decision.
- If a Fenix-class UI is still wanted, build the `engine-uxp`
  PoC adapter as a separate module (mirroring `helper/`'s
  standalone-app approach) so the main product is untouched.
- Keep user-facing language honest: it would be a Fenix-*style* shell
  over Goanna, not Fenix.
