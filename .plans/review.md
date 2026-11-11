# Code Review: External Video Player Playback History Tracking (Issue #67)

## Summary
The implementation resolves GitHub Issue #67 ("Videos opened from the app do not appear in VLC's history (they only appear in XPlayer's history)") by providing system `MediaStore` content URIs for local media, enhancing `UfmFileProvider` column projections (`_id`, `_display_name`, `_data`, `title`, `mime_type`, `date_modified`), adding on-demand `MediaScannerConnection.scanFile` (250ms timeout) for unindexed local media, and binding the HTTP streaming proxy to a fixed port (28842) with persistent session metadata and dynamic on-demand revival.

All findings identified during review (Critical, Minor, and Suggestions) have been fully fixed and verified by automated tests.

---

## Findings & Resolutions

### 🔴 Critical
- **[`OpenWithLauncher.kt:24-65`](file:///F:/Share/UFM/app/src/main/java/za/kilowatch/ultimatefilemanager/viewer/openwith/OpenWithLauncher.kt#L24-L65)**: *(Fixed)* Android platform restriction on `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`.
  - **Issue**: Under Android's `UriGrantsManagerService`, calling `startActivity(intent)` with `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` on a URI authority not owned by the calling application (such as system `MediaStore`'s `content://media/...`) throws a `java.lang.SecurityException`.
  - **Fix Applied**: Added `isOwnedAuthority(context, uri)` guard. `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` is only attached when launching UFM-owned providers (`.fileprovider` and `.documents`). For system `MediaStore` and external URIs, only `FLAG_GRANT_READ_URI_PERMISSION` (and write) is granted. Added nested `SecurityException` retry fallback in both `launch()` and `launchSystemChooser()` to strip the persistable flag and retry cleanly if any platform rejects it.

---

### 🟠 Major
*None.*

---

### 🟡 Minor
- **[`UfmFileProvider.kt:118-124`](file:///F:/Share/UFM/app/src/main/java/za/kilowatch/ultimatefilemanager/storage/UfmFileProvider.kt#L118-L124)**: *(Fixed)* Missing `_ID` in null projection.
  - **Issue**: Default projection on `query()` with `projection == null` lacked `MediaStore.MediaColumns._ID`.
  - **Fix Applied**: Added `MediaStore.MediaColumns._ID` to `defaultCols` in `UfmFileProvider.kt`.
- **[`OpenWithManager.kt:62-75`](file:///F:/Share/UFM/app/src/main/java/za/kilowatch/ultimatefilemanager/viewer/openwith/OpenWithManager.kt#L62-L75)**: *(Fixed)* Branch duplication in URI resolution.
  - **Issue**: Duplicated fallback branches for `SafTreeManager` and `FileProvider`.
  - **Fix Applied**: Consolidated into a cleaner null-coalescing chain with proper imports.
- **[`MediaStoreUriResolver.kt:75-155`](file:///F:/Share/UFM/app/src/main/java/za/kilowatch/ultimatefilemanager/viewer/openwith/MediaStoreUriResolver.kt#L75-L155)**: *(Fixed)* Symlink / canonical path mismatch on `/sdcard` vs `/storage/emulated/0`.
  - **Issue**: MediaStore stores canonical paths (`/storage/emulated/0/...`); querying solely by `file.absolutePath` missed entries when browsing via `/sdcard/`.
  - **Fix Applied**: Extended `queryMediaStoreForId()` to query both `file.absolutePath` and `file.canonicalPath` (when different) with `DATA = ? OR DATA = ?`. Added secondary collection fallback query (`MediaStore.Files.getContentUri("external")`) after scanner completion.

---

### 🔵 Suggestions
- **[`NetworkHttpProxyServer.kt:225-227`](file:///F:/Share/UFM/app/src/main/java/za/kilowatch/ultimatefilemanager/network/NetworkHttpProxyServer.kt#L225-L227)**: *(Fixed)* Cross-platform atomic file replacement.
  - **Fix Applied**: Added `if (file.exists()) file.delete()` prior to `tempFile.renameTo(file)` when saving `proxy_streaming_sessions.json`.

---

## Checklist Coverage
- **Kotlin Correctness**: Clean. Idiomatic null-safety, safe coroutine dispatch via executor, atomic primitives, and thread-safe collections (`ConcurrentHashMap`).
- **Android Architecture & Lifecycle**: Clean. No Activity context leaks (`context.applicationContext` used in `MediaScannerConnection`), proper handling of `ContextWrapper` and non-Activity contexts (`FLAG_ACTIVITY_NEW_TASK`).
- **URI & Permission Security**: Clean. Persistable URI permissions scoped strictly to UFM-owned authorities. System `MediaStore` queries execute via ContentResolver without leaking private directories. App-private paths (`/data/data/`, app cache, root staging) are explicitly excluded from MediaStore queries.
- **Network & Streaming Reliability**: Clean. Fixed port 28842 with fallback to ephemeral port `0`. Dynamic session revival via `NetworkShareRepository` restores playback from external player history without user intervention.
- **Project Conventions**: Clean. Version bumped to `2.2.6` (code `314`), `CHANGELOG.md` updated with approved entry, no hardcoded UI strings.
- **Tests**: Clean. 100% pass across `MediaStoreUriResolverTest`, `NetworkHttpProxyServerTest`, and `MediaIntentHelperTest` via `./gradlew testMobileGoogleDebugUnitTest`.

---

## Verdict
**Approve**. All findings (Critical, Minor, and Suggestions) are completely fixed and verified with automated unit tests. Ready to proceed to `/securitycheck`.
