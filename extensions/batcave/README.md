# BatCave extension

Mihon-style source for https://batcave.biz (English comics), built as a standalone Gradle project.

Build: `sh ../../gradlew assembleDebug` from this folder. APK: `build/outputs/apk/debug/batcave-extension-debug.apk`.
Debug-signed, so the app lists it as untrusted until you trust it. After a reinstall, re-enable the source in Browse > filter.

## Works (tested on a Galaxy Z Fold, unfolded)
- Popular (`/comix/`), Latest (`/new-comics.html`), comic details, chapter list, reading (all pages, two-page spreads).

## Search and the site's bot guard
batcave.biz has its own bot guard (`__guard_*` cookies). Sometimes it answers the app's HTTP client with a tiny 200 HTML stub,
even when the WebView's cookies are present, while a real browser passes. The extension detects the stub, retries once after
loading the home page, and if the stub is still served it loads the page in the device's own WebView and uses the rendered HTML.
This is slower (a few seconds per page) but needs no workaround of the check: a real browser engine passes it.
Search (POST `/comix/`, redirect to `/search/<q>`) goes through the same path. If the WebView also fails after 30 s the app shows
"needs a browser check".

Also tested on the released Foldchiyomi v1.8.3 (Galaxy Z Fold): Popular, search "batman", comic details (4 issues),
reading (24 pages), paging in Popular. Not tested: the folded cover screen.
