# Task List: External Video Player Playback History Tracking (Issue #67)

## Foundation (MediaStore Resolution & Fallback Projections)
- [x] [T001] [P] Create `MediaStoreUriResolver.kt` in `za.kilowatch.ultimatefilemanager.viewer.openwith` resolving local video and audio files to system `MediaStore` content URIs, with fast on-demand `MediaScannerConnection` scan (250ms timeout).
- [x] [T002] Create unit tests in `MediaStoreUriResolverTest.kt` verifying extension filtering, root/cache exclusions, and collection resolution.
- [x] [T003] Enhance `UfmFileProvider.kt` `query()` cursor projections to map `_id` (`MediaStore.MediaColumns._ID`), `_display_name`, `_data`, and verify storage volume path resolution.

## Feature Implementation (Intent Dispatching & Permissions)
- [x] [T004] [P] Update `OpenWithManager.kt` `show()` to resolve MediaStore URIs for local video and audio files before falling back to `UfmFileProvider`.
- [x] [T005] Update `FileViewerRouter.kt` `openWithExternalApp()` to pass resolved MediaStore URIs into `OpenWithManager.show()`.
- [x] [T006] Update `OpenWithLauncher.kt` (`launch()` and `launchSystemChooser()`) to add `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` alongside read permissions with nested fallback.

## Network Streaming Session Revival
- [x] [T007] [P] Update `NetworkHttpProxyServer.kt` to bind to preferred port `28842` (with fallback to `0`), persist active session metadata in `proxy_streaming_sessions.json`, and dynamically revive expired/past sessions on demand via `NetworkShareRepository`.
- [x] [T008] Add unit tests in `NetworkHttpProxyServerTest.kt` verifying on-demand session revival from stored metadata.

## Version Bump & Repository Documentation
- [x] [T009] Bump `appVersionName` to `"2.2.6"` and `appVersionCode` to `314` in `app/build.gradle.kts`.
- [x] [T010] Update `CHANGELOG.md` with approved version header `[2.2.6] — 2026-11-11` and Issue #67 release notes.
- [x] [T011] Run automated test suite (`./gradlew testDebugUnitTest`) to verify all unit tests pass.
