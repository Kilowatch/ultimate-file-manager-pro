# Task List: Folder File List Stability & Background Sync Optimization (Fix Ghost Scrolling & Lag)

## Phase 1: Foundation & Data Sync Optimization
- [x] [T001] [P] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/FileBrowserActivity.kt] Implement smart pre-flight diff in `syncFolderWithIndex(directory)` to check existing database rows before writing, preventing redundant SQLite writes and table invalidation loops when files are unchanged.
- [x] [T002] [P] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/FileBrowserActivity.kt] Apply `.distinctUntilChanged` on `dao.getFilesInFolderFlow(targetDir.absolutePath)` to filter out redundant Room flow emissions caused by background indexing on other directories.
- [x] [T003] [P] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/FileBrowserFragment.kt] Synchronize smart pre-flight diff in `syncFolderWithIndex` and `.distinctUntilChanged` on `dao.getFilesInFolderFlow` in `FileBrowserFragment` for Tabbed Browser and Twin Window mode parity.

## Phase 2: FileAdapter DiffUtil & Granular Metadata Payloads
- [x] [T004] [P] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/FileAdapter.kt] Implement `FileDiffCallback` (`DiffUtil.Callback`) comparing `ListItem.FileEntry`, `ListItem.Header`, and `ListItem.EmptyBuffer` by identity, content timestamps, sizes, and selection/focus states.
- [x] [T005] [P] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/FileAdapter.kt] Refactor `submitList` to early-return if list contents are identical (`if (isContentIdentical(oldItems, newItems)) return`) and dispatch updates via `DiffUtil.calculateDiff` instead of `notifyDataSetChanged()`.
- [x] [T006] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/FileAdapter.kt] Refactor `childCountJob` to dispatch granular `notifyItemChanged(pos, PAYLOAD_METADATA)` only for directory positions that changed, completely removing the disruptive full `notifyDataSetChanged()`.
- [x] [T007] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/FileAdapter.kt] Implement payload-aware `onBindViewHolder(holder, position, payloads)` in `FileAdapter` to update `txtSubtitle` in-place when `PAYLOAD_METADATA` is received without rebinding or re-animating views.

## Phase 3: Viewport & Scroll Stability
- [x] [T008] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/FileBrowserActivity.kt] Refactor `submitAdapterList` in `FileBrowserActivity` to remove redundant same-folder `scrollToPositionWithOffset` post calls, allowing `DiffUtil` to maintain viewport offset naturally.
- [x] [T009] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/FileBrowserFragment.kt] Refactor `submitAdapterList` in `FileBrowserFragment` to remove redundant same-folder scroll posts and align with the `DiffUtil` lifecycle.

## Phase 4: Automated Testing & Verification
- [x] [T010] [P] [app/src/test/java/za/kilowatch/ultimatefilemanager/storage/FileDiffCallbackTest.kt] Create unit test verifying `FileDiffCallback` item identity, content equality, and metadata payload generation.
- [x] [T011] Run unit tests (`./gradlew testMobileGoogleDebugUnitTest`) and verify clean debug compilation (`./gradlew assembleMobileGoogleDebug`).
