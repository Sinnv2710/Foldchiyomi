# Internet Archive (Tiếng Việt) extension

Mihon-style source for Vietnamese books and old comic scans on https://archive.org (items with `language:vie` that have page scans), built as a standalone Gradle project like `../batcave`.

Build: `sh ../../gradlew assembleDebug` from this folder. APK: `build/outputs/apk/debug/archiveorg-vi-extension-debug.apk`.
Debug-signed, so the app lists it as untrusted until you trust it, then enable Vietnamese in Browse > filter.

## How it maps
- An entry is an archive.org item. Popular = most downloaded; Latest = newest uploads; search matches title, creator and text.
- A chapter is one volume: one `*_jp2.zip` in the item. A series stored as one item with many volumes (Dũng sĩ Hesman: 50) shows 50 chapters; a single book shows one.
- Pages are JPEGs from archive.org's BookReader endpoint (it converts the stored JP2 scans); the page count comes from the volume's `_scandata.xml`.

## Works (tested on a Galaxy Z Fold 7)
- Popular list (14,000+ Vietnamese texts), details, Dũng sĩ Hesman's 50 volumes, and reading volume 50 (68 pages).

## Limits
- Only items with page scans. Items that are text-only (PDF/EPUB with no JP2 pages) are filtered out of the list.
- Scans are as good as the uploader's; some are large, so pages load slower on mobile data.
- Copyright varies by item; archive.org lends or hosts what it hosts.
