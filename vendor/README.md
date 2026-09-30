# vendor/uxp-android — historical Android source (provenance)

This directory contains the Android-specific source code recovered from the
upstream Unified XUL Platform (UXP) repository
(https://repo.palemoon.org/MoonchildProductions/UXP), extracted at commit
`63295d0087eb58a6eb34cad324c4c53d1b220491` — the last tree state before the
Fennec/Android removal series (upstream Issue #1053) began
(2019-04-23, commit `abe80cc31d5a`).

Contents:

| Path | What it is |
|---|---|
| `mobile/android/` | The Pale Moon for Android ("pm4a") application — a Fennec-derived native Android frontend: Java UI (`base/`), JNI bridge (`base/jni`), GeckoView, branding, l10n, packaging |
| `mobile/locales/` | Mobile localization filters |
| `widget/android/` | Android widget toolkit backend (`MOZ_WIDGET_TOOLKIT=android`) |
| `mozglue/android/` | Native Android glue: `APKOpen` (APK/ZIP library loader), JNI bridges (NSS, SQLite, NativeCrypto) |
| `gradle/` | Gradle wrapper for the Java build |
| `build/mobile/` | Mobile build helpers (szip, javacc, etc.) |
| `build/annotationProcessors/` | Java annotation processors generating JNI glue |
| `hal/android/` | Hardware Abstraction Layer for Android (sensors, alarms, wakelocks) |
| `dom/system/android/` | Android system integration (GeckoAppShell ties, network, location) |
| `dom/gamepad/android/` | Android gamepad backend |
| `dom/media/platforms/android/` | Android media platform decoders (MediaCodec) |
| `dom/plugins/base/android/` | Plugin layer Android bits |
| `dom/xbl/builtin/android/` | Android-specific XBL bindings |
| `image/decoders/icon/android/` | Android icon decoder |
| `media/libyuv/util/android/` | libyuv Android utility |
| `media/webrtc/trunk/build/android/` | WebRTC Android build scripts |

Regenerate with `../scripts/fetch-upstream.sh` or manually:

```sh
git clone https://repo.palemoon.org/MoonchildProductions/UXP.git uxp
cd uxp && git fetch origin 63295d0087eb58a6eb34cad324c4c53d1b220491
git archive FETCH_HEAD <paths above> | tar -x -C vendor/uxp-android
```

NOTE: This code is from 2019 (m-esr52 lineage). It is a porting *reference and
starting point*, not drop-in compatible with current UXP master — see
`../docs/PORTING.md` for the gap analysis.
