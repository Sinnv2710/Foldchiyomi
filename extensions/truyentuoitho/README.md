# TruyenTuoiTho (WebView) extension

Mihon-style source for https://truyentuoitho.com (old Vietnamese comics: Thần đồng Đất Việt, 7 Viên Ngọc Rồng, Dũng sĩ Hesman, Tý Quậy, Shin...), built as a standalone Gradle project like `../batcave`.

Build: `sh ../../gradlew assembleDebug` from this folder. APK: `build/outputs/apk/debug/truyentuoitho-wv-extension-debug.apk`.
Debug-signed, so the app lists it as untrusted until you trust it, then enable Vietnamese in Browse > filter.

## Why it exists
Keiyoushi's `TruyenTuoiTho` extension lists a single page per chapter since the site changed how it builds a chapter's images
(Thần đồng Đất Việt tập 110 has 159 pages; that extension returns 1). This one reads the chapter in the device's own WebView
after the site's script has built the images, then collects them. It uses a different package, so both can be installed.

## Works (tested on a Galaxy Z Fold 7)
- Popular, Latest, search (Madara `?s=&post_type=wp-manga`), details with status (Hoàn thành = Completed), the full chapter list (110 for TDDV), and reading all 159 pages of a chapter.
- Pages cost a few seconds per chapter, while the WebView loads (up to 40 s before it gives up).
