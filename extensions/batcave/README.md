# BatCave extension

Mihon-style source for https://batcave.biz (English comics), built as a standalone Gradle project.

Build: `sh ../../gradlew assembleDebug` from this folder. APK: `build/outputs/apk/debug/batcave-extension-debug.apk`.
Debug-signed, so the app lists it as untrusted until you trust it. After a reinstall, re-enable the source in Browse > filter.

## Works (tested on a Galaxy Z Fold, unfolded)
- Popular (`/comix/`), Latest (`/new-comics.html`), comic details, chapter list, reading (all pages, two-page spreads).

## Known limit
batcave.biz has its own bot guard (`__guard_*` cookies). When it decides the app's HTTP client is not a browser it answers
200 with a tiny HTML stub, even with the WebView's cookies present. The extension detects this, retries once after
loading the home page, then reports "needs a browser check". Search (POST `/comix/` then `/search/<q>`) is built but
was never seen returning results because of this. The extension does not try to get around the guard.
