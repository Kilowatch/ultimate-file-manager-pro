# Task List: Immediate Other Storage Export Validation & WebDAV Folder Performance Optimization

## Phase 1: Remote Subfolder Navigation & Child-Count Storm Suppression
- [x] [T001] [app/src/main/java/za/kilowatch/ultimatefilemanager/network/NetworkFileAdapter.kt] Remove sequential background `childCountJob` issuing `listFiles` queries over WebDAV, SMB, NFS, and Cloud storage for subdirectories, eliminating the 15–30+ second folder opening delay. Ensure previous job cancellation is preserved and directory views cleanly display modification dates without network stalling.

## Phase 2: Document Provider Export Helper Modularization
- [x] [T002] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/DocumentProviderExportHelper.kt] Extract `handleExportChoice(activity, itemCount, hasDirectory, firstName, provider, onExportSingle, onExportZip, onExportShareSheet)` allowing upfront dialog interaction without requiring pre-downloaded local files.
- [x] [T003] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/DocumentProviderExportHelper.kt] Refactor existing `handleExportToProvider(activity, files, provider, ...)` to delegate to `handleExportChoice` for backward compatibility with local file exports.

## Phase 3: Standalone & Tabbed Network Browser Pre-Flight & Deferred Staging
- [x] [T004] [app/src/main/java/za/kilowatch/ultimatefilemanager/network/NetworkBrowserActivity.kt] Refactor `uploadNetworkFilesToOtherStorage(files: List<NetworkFile>)` to synchronously inspect `DocumentProviderRepository.getMountedProviders(this)` upfront. If empty, instantly display `no_mounted_doc_providers_toast` and launch `OtherStorageActivity` in <100ms with zero bytes downloaded.
- [x] [T005] [app/src/main/java/za/kilowatch/ultimatefilemanager/network/NetworkBrowserActivity.kt] Implement upfront provider & format selection flow and deferred staging pipeline using `ArchiveProgressDialog`: prompt for provider and export mode first, display `ArchiveProgressDialog` with real-time file counters and cancellation during remote staging, and clean up temp cache immediately on cancel or error.
- [x] [T006] [app/src/main/java/za/kilowatch/ultimatefilemanager/network/NetworkBrowserFragment.kt] Synchronize the identical upfront validation, provider selection, and deferred staging pipeline with `ArchiveProgressDialog` in `NetworkBrowserFragment`, strictly adhering to Fragment safety rules (no concrete activity casts).

## Phase 4: Local & SAF Browser Consistency Check
- [x] [T007] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/FileBrowserActivity.kt] Verify and ensure instant validation in `showSelectMountedProviderForExportDialog` when `mountedProviders.isEmpty()`.
- [x] [T008] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/FileBrowserFragment.kt] Verify and ensure instant validation in `FileBrowserFragment`.

## Phase 5: Automated Testing & Verification
- [x] [T009] [app/src/test/java/za/kilowatch/ultimatefilemanager/network/OtherStorageExportTest.kt] Create unit test verifying `handleExportChoice` routing logic (single file direct vs multi-item/folder zip options) and `NetworkFile` export parameter handling.
- [x] [T010] Run automated unit tests (`./gradlew testMobileGoogleDebugUnitTest`) to verify zero regressions and confirm clean build.
